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
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.service.ApprovalTaskService;

/**
 * 审批任务（PRD 14.2/14.3：同意 / 驳回 + 待办 / 已办）。
 */
@RestController
@RequestMapping("/workflow/tasks")
public class WfTaskController extends BaseController {

    private final ApprovalTaskService approvalTaskService;

    public WfTaskController(ApprovalTaskService approvalTaskService) {
        this.approvalTaskService = approvalTaskService;
    }

    /**
     * 请求体：{ comment, idempotencyKey }
     */
    public static class ActionRequest {
        public String comment;
        public String idempotencyKey;
    }

    /**
     * 我的待办。
     */
    @GetMapping("/todo")
    public AjaxResult todo() {
        List<WfApprovalTask> tasks = approvalTaskService.todo(SecurityUtils.getUserId().toString());
        return success(tasks);
    }

    /**
     * 我的已办。
     */
    @GetMapping("/done")
    public AjaxResult done() {
        List<WfApprovalTask> tasks = approvalTaskService.done(SecurityUtils.getUserId().toString());
        return success(tasks);
    }

    /**
     * 同意审批。
     */
    @PostMapping("/{taskId}/approve")
    public AjaxResult approve(@PathVariable String taskId, @RequestBody ActionRequest request) {
        WfApprovalInstance instance = approvalTaskService.approve(
            taskId, request.comment, SecurityUtils.getUserId().toString(), request.idempotencyKey);
        return success(instance);
    }

    /**
     * 驳回审批（退回申请人节点，白名单 v1）。
     */
    @PostMapping("/{taskId}/reject")
    public AjaxResult reject(@PathVariable String taskId, @RequestBody ActionRequest request) {
        WfApprovalInstance instance = approvalTaskService.reject(
            taskId, request.comment, SecurityUtils.getUserId().toString(), request.idempotencyKey);
        return success(instance);
    }
}
