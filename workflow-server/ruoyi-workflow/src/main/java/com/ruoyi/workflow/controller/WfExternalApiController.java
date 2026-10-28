package com.ruoyi.workflow.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.domain.WfExternalApi;
import com.ruoyi.workflow.service.ExternalApiService;

/**
 * 外部接口配置（PRD 8.1–8.2：登记 + 白名单 + 测试调用）。
 */
@RestController
@RequestMapping("/workflow/external-apis")
public class WfExternalApiController extends BaseController {

    private final ExternalApiService externalApiService;

    public WfExternalApiController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    /**
     * 登记接口配置。
     */
    @PostMapping
    public AjaxResult register(@RequestBody WfExternalApi api) {
        return success(externalApiService.register(api, SecurityUtils.getUsername()));
    }

    /**
     * 更新接口配置。
     */
    @PutMapping
    public AjaxResult update(@RequestBody WfExternalApi api) {
        return success(externalApiService.update(api));
    }

    /**
     * 接口列表。
     */
    @GetMapping("/list")
    public AjaxResult list(WfExternalApi query) {
        List<WfExternalApi> list = externalApiService.list(query);
        return success(list);
    }

    /**
     * 测试调用（白名单内主机，结果落日志表）。
     */
    @PostMapping("/{apiId}/test-call")
    public AjaxResult testCall(@PathVariable String apiId, @RequestBody Object requestParams) {
        Map<String, Object> result = externalApiService.testCall(apiId, requestParams, SecurityUtils.getUsername());
        return success(result);
    }
}
