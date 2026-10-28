package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 外部接口配置实体（PRD 8.1：wf_external_api）。
 *
 * <p>M2 仅"登记 + 白名单 + 测试调用"骨架；认证凭证与接口配置分离，
 * credential_ref 只是凭证引用标识，凭证本体不落库。</p>
 */
public class WfExternalApi {

    /** 接口ID */
    private String id;

    /** 接口编码（唯一） */
    private String apiCode;

    /** 接口名称 */
    private String apiName;

    /** 基础地址 */
    private String baseUrl;

    /** 接口路径 */
    private String path;

    /** HTTP 方法：GET / POST 等 */
    private String httpMethod;

    /** 认证类型：NONE / BEARER / API_KEY */
    private String authType;

    /** 凭证引用（凭证本体不落库） */
    private String credentialRef;

    /** 超时时间（毫秒） */
    private Integer timeoutMs;

    /** 请求参数、响应映射等非敏感配置（JSON 字符串） */
    private String configJson;

    /** 允许访问的主机范围（JSON 字符串） */
    private String allowedHostsJson;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApiCode() {
        return apiCode;
    }

    public void setApiCode(String apiCode) {
        this.apiCode = apiCode;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getAllowedHostsJson() {
        return allowedHostsJson;
    }

    public void setAllowedHostsJson(String allowedHostsJson) {
        this.allowedHostsJson = allowedHostsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
