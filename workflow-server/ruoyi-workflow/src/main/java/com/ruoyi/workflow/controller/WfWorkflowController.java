package com.ruoyi.workflow.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.service.WorkflowDefinitionService;

/**
 * 流程定义与发布（PRD 5.5–5.6 + 12.1）。
 */
@RestController
@RequestMapping("/workflow/workflows")
public class WfWorkflowController extends BaseController {

    private final WorkflowDefinitionService workflowDefinitionService;

    public WfWorkflowController(WorkflowDefinitionService workflowDefinitionService) {
        this.workflowDefinitionService = workflowDefinitionService;
    }

    /**
     * 请求体：{ approvalDefinitionId?, workflowCode, workflowName, bpmnXml?, configJson? }
     * approvalDefinitionId 可选：流程管理侧新建流程不再要求挂接审批类型。
     */
    public static class WorkflowRequest {
        public String approvalDefinitionId;
        public String workflowCode;
        public String workflowName;
        public String bpmnXml;
        public String configJson;
    }

    /**
     * 创建流程定义。
     */
    @PostMapping
    public AjaxResult create(@RequestBody WorkflowRequest request) {
        WfWorkflowDefinition definition = workflowDefinitionService.createDefinition(
            request.approvalDefinitionId, request.workflowCode, request.workflowName,
            SecurityUtils.getUsername());
        return success(definition);
    }

    /**
     * 校验流程配置（门禁：解析 + 结构校验 + 规一化），返回规一化后的 BPMN。
     */
    @PostMapping("/{workflowId}/validate")
    public AjaxResult validate(@PathVariable String workflowId, @RequestBody WorkflowRequest request) {
        String normalized = workflowDefinitionService.validate(workflowId, request.bpmnXml);
        // 显式装箱为 Object，避免命中 success(String message) 重载（否则 XML 被放到 msg 而非 data）
        return success((Object) normalized);
    }

    /**
     * 发布流程版本（校验 + 规一化 → 部署 Flowable → 版本快照不可变）。
     */
    @PostMapping("/{workflowId}/publish")
    public AjaxResult publish(@PathVariable String workflowId, @RequestBody WorkflowRequest request) {
        WfWorkflowVersion version = workflowDefinitionService.publish(
            workflowId, request.bpmnXml, request.configJson, SecurityUtils.getUsername());
        return success(version);
    }

    /**
     * 流程定义详情。
     */
    @GetMapping("/{workflowId}")
    public AjaxResult detail(@PathVariable String workflowId) {
        return success(workflowDefinitionService.getDefinition(workflowId));
    }

    /**
     * 流程定义列表。
     */
    @GetMapping("/list")
    public AjaxResult list(WfWorkflowDefinition query) {
        return success(workflowDefinitionService.listDefinitions(query));
    }

    /**
     * 流程版本列表。
     */
    @GetMapping("/{workflowId}/versions")
    public AjaxResult versions(@PathVariable String workflowId) {
        List<WfWorkflowVersion> versions = workflowDefinitionService.listVersions(workflowId);
        return success(versions);
    }
}
