package com.ruoyi.workflow.service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.domain.WfExternalApi;
import com.ruoyi.workflow.domain.WfExternalApiLog;
import com.ruoyi.workflow.mapper.WfExternalApiLogMapper;
import com.ruoyi.workflow.mapper.WfExternalApiMapper;
import com.ruoyi.workflow.security.ExternalApiSecurityGuard;

/**
 * 外部接口配置服务（PRD 8.1–8.2）。
 *
 * <p>M2 仅"登记 + 白名单 + 测试调用"骨架；M5 增加 SSRF 防护（PRD 17.2）：</p>
 * <ul>
 *     <li>登记接口配置（api_code 唯一，凭证与配置分离）；</li>
 *     <li>白名单：allowed_hosts_json 声明允许访问的主机范围，调用前校验；</li>
 *     <li>SSRF：目标主机命中内网/保留/云元数据黑名单即拒绝，重定向关闭，响应有界读取；</li>
 *     <li>测试调用：仅允许调用 base_url 白名单内主机，结果与耗时落 wf_external_api_log。</li>
 * </ul>
 * <p>数据源绑定渲染（下拉/级联）为 M2 末期扩展点，本服务提供调用基元。</p>
 */
@Service
public class ExternalApiService {

    public static final String STATUS_ENABLED = "ENABLED";

    private final WfExternalApiMapper apiMapper;
    private final WfExternalApiLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final ExternalApiSecurityGuard securityGuard;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public ExternalApiService(WfExternalApiMapper apiMapper,
                              WfExternalApiLogMapper logMapper,
                              ObjectMapper objectMapper,
                              ExternalApiSecurityGuard securityGuard) {
        this.apiMapper = apiMapper;
        this.logMapper = logMapper;
        this.objectMapper = objectMapper;
        this.securityGuard = securityGuard;
    }

    /**
     * 登记接口配置。
     */
    public WfExternalApi register(WfExternalApi api, String operator) {
        if (api.getApiCode() == null || api.getApiCode().isBlank()) {
            throw new ServiceException("接口编码不能为空");
        }
        if (apiMapper.selectByCode(api.getApiCode()) != null) {
            throw new ServiceException("接口编码已存在: " + api.getApiCode());
        }
        api.setId(UUID.randomUUID().toString());
        api.setStatus(api.getStatus() == null ? STATUS_ENABLED : api.getStatus());
        api.setCreatedBy(operator);
        api.setCreatedAt(new Date());
        apiMapper.insert(api);
        return api;
    }

    /**
     * 更新接口配置（凭证引用可空更新，凭证本体不落库）。
     */
    public WfExternalApi update(WfExternalApi api) {
        WfExternalApi existing = apiMapper.selectById(api.getId());
        if (existing == null) {
            throw new ServiceException("接口配置不存在: " + api.getId());
        }
        apiMapper.update(api);
        return apiMapper.selectById(api.getId());
    }

    /**
     * 接口列表。
     */
    public List<WfExternalApi> list(WfExternalApi query) {
        return apiMapper.selectList(query);
    }

    /**
     * 测试调用接口（白名单内主机）。结果写入 wf_external_api_log。
     */
    public Map<String, Object> testCall(String apiId, Object requestParams, String operator) {
        WfExternalApi api = apiMapper.selectById(apiId);
        if (api == null) {
            throw new ServiceException("接口配置不存在: " + apiId);
        }
        if (!STATUS_ENABLED.equals(api.getStatus())) {
            throw new ServiceException("接口已禁用: " + api.getApiName());
        }
        checkWhitelistAndSsrf(api);

        long start = System.currentTimeMillis();
        WfExternalApiLog log = new WfExternalApiLog();
        log.setId(UUID.randomUUID().toString());
        log.setApiId(apiId);
        log.setOperatorUserId(operator);
        log.setRequestSummary(maskRequest(requestParams));
        try {
            String body = objectMapper.writeValueAsString(requestParams);
            String url = api.getBaseUrl() + (api.getPath() == null ? "" : api.getPath());
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(api.getTimeoutMs() == null ? 5000 : api.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .method(api.getHttpMethod() == null ? "GET" : api.getHttpMethod(),
                    api.getHttpMethod() == null || "GET".equalsIgnoreCase(api.getHttpMethod())
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long duration = System.currentTimeMillis() - start;

            // SSRF：有界读取响应，防超大响应拖垮内存（PRD 17.2）
            String responseText;
            try (InputStream in = response.body()) {
                responseText = new String(securityGuard.readBounded(in),
                    java.nio.charset.StandardCharsets.UTF_8);
            }

            log.setResponseStatus(response.statusCode());
            log.setDurationMs((int) duration);
            log.setSuccess(response.statusCode() >= 200 && response.statusCode() < 300);
            if (!log.getSuccess()) {
                log.setErrorCode("HTTP_" + response.statusCode());
                log.setErrorMessage(maskBody(responseText));
            }
            logMapper.insert(log);

            return Map.of("success", log.getSuccess(),
                "status", response.statusCode(),
                "durationMs", duration,
                "body", maskBody(responseText));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.setDurationMs((int) duration);
            log.setSuccess(false);
            log.setErrorCode("CALL_ERROR");
            log.setErrorMessage(e.getMessage());
            logMapper.insert(log);
            throw new ServiceException("接口调用失败: " + e.getMessage());
        }
    }

    /**
     * 白名单 + SSRF 联合校验：allowed_hosts_json 声明允许的主机范围，命中 SSRF 黑名单即拒绝（PRD 17.2）。
     */
    private void checkWhitelistAndSsrf(WfExternalApi api) {
        if (api.getBaseUrl() == null || api.getBaseUrl().isBlank()) {
            throw new ServiceException("接口未配置 base_url");
        }
        if (api.getAllowedHostsJson() == null || api.getAllowedHostsJson().isBlank()) {
            throw new ServiceException("接口未配置 allowed_hosts 白名单，拒绝调用");
        }
        try {
            List<String> allowedHosts = objectMapper.readValue(api.getAllowedHostsJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            securityGuard.assertSafeTarget(api.getBaseUrl(), allowedHosts);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("allowed_hosts_json 解析失败");
        }
    }

    private String maskRequest(Object params) {
        if (params == null) {
            return null;
        }
        String s = String.valueOf(params);
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    private String maskBody(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 2000 ? body.substring(0, 2000) + "..." : body;
    }
}
