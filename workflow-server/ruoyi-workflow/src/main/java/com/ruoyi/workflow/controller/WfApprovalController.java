package com.ruoyi.workflow.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.service.ApprovalSubmissionService;
import com.ruoyi.workflow.service.ApprovalTaskService;

/**
 * 审批单（PRD 14.1 提交 + 查询 + 时间线）。
 */
@RestController
@RequestMapping("/workflow/approvals")
public class WfApprovalController extends BaseController {

    private final ApprovalSubmissionService approvalSubmissionService;
    private final ApprovalTaskService approvalTaskService;

    public WfApprovalController(ApprovalSubmissionService approvalSubmissionService,
                                ApprovalTaskService approvalTaskService) {
        this.approvalSubmissionService = approvalSubmissionService;
        this.approvalTaskService = approvalTaskService;
    }

    /**
     * 请求体：{ approvalDefinitionId, formVersionId, title, formData, idempotencyKey }
     */
    public static class SubmitRequest {
        public String approvalDefinitionId;
        public String formVersionId;
        public String title;
        public Map<String, Object> formData;
        public String idempotencyKey;
    }

    /**
     * 提交审批（PRD 10.1 事务链）。
     */
    @PostMapping
    public AjaxResult submit(@RequestBody SubmitRequest request) {
        Long userId = SecurityUtils.getUserId();
        Long deptId = SecurityUtils.getLoginUser().getUser().getDeptId();
        WfApprovalInstance instance = approvalSubmissionService.submit(
            request.approvalDefinitionId, request.formVersionId, request.title, request.formData,
            String.valueOf(userId), SecurityUtils.getUsername(),
            deptId == null ? null : String.valueOf(deptId), request.idempotencyKey);
        return success(instance);
    }

    /**
     * 审批单详情。
     */
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable String id) {
        return success(approvalTaskService.instanceDetail(id));
    }

    /**
     * 时间线（审批操作记录聚合）。
     */
    @GetMapping("/{id}/timeline")
    public AjaxResult timeline(@PathVariable String id) {
        List<WfApprovalAction> actions = approvalTaskService.timeline(id);
        return success(actions);
    }

    /**
     * 我发起的审批单。
     */
    @GetMapping("/my")
    public AjaxResult my() {
        List<WfApprovalInstance> instances = approvalTaskService.myInstances(
            SecurityUtils.getUserId().toString());
        return success(instances);
    }
}
