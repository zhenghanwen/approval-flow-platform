package com.ruoyi.workflow.controller;

import java.util.List;

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
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.service.ApprovalDefinitionService;

/**
 * 审批类型管理（PRD 13.1）。
 */
@RestController
@RequestMapping("/workflow/approval-definitions")
public class WfApprovalDefinitionController extends BaseController {

    private final ApprovalDefinitionService approvalDefinitionService;

    public WfApprovalDefinitionController(ApprovalDefinitionService approvalDefinitionService) {
        this.approvalDefinitionService = approvalDefinitionService;
    }

    /**
     * 查询审批类型列表。
     */
    @GetMapping("/list")
    public AjaxResult list(WfApprovalDefinition query) {
        List<WfApprovalDefinition> list = approvalDefinitionService.list(query);
        return success(list);
    }

    /**
     * 创建审批类型。
     */
    @PostMapping
    public AjaxResult create(@RequestBody WfApprovalDefinition definition) {
        WfApprovalDefinition created = approvalDefinitionService.create(
            definition.getApprovalCode(), definition.getApprovalName(), definition.getCategoryId(),
            definition.getDescription(), definition.getIcon(), SecurityUtils.getUsername());
        return success(created);
    }

    /**
     * 查询审批类型详情。
     */
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable String id) {
        return success(approvalDefinitionService.get(id));
    }

    /**
     * 修改草稿配置（绑定表单版本 / 流程版本等）。
     */
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable String id, @RequestBody WfApprovalDefinition definition) {
        definition.setId(id);
        return success(approvalDefinitionService.update(definition));
    }

    /**
     * 发布审批类型（表单 + 流程综合校验）。
     */
    @PostMapping("/{id}/publish")
    public AjaxResult publish(@PathVariable String id) {
        return success(approvalDefinitionService.publish(id, SecurityUtils.getUsername()));
    }

    /**
     * 停用审批类型。
     */
    @PostMapping("/{id}/disable")
    public AjaxResult disable(@PathVariable String id) {
        return success(approvalDefinitionService.disable(id));
    }
}
