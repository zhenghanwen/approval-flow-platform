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
import com.ruoyi.workflow.service.ApprovalTaskService.ReturnTargetVo;

/**
 * 审批任务（PRD 14.2/14.3：同意 / 驳回 + 待办 / 已办；PRD 11.1/11.2/11.3：转交 / 加签 / 退回）。
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
     * 转交请求体：{ toUserId, reason, idempotencyKey }
     */
    public static class TransferRequest {
        public String toUserId;
        public String reason;
        public String idempotencyKey;
    }

    /**
     * 加签请求体：{ signType(FIRST/AFTER), addUsers, reason, idempotencyKey }
     */
    public static class AddSignRequest {
        public String signType;
        public List<String> addUsers;
        public String reason;
        public String idempotencyKey;
    }

    /**
     * 退回请求体：{ targetNodeKey, reason, idempotencyKey }
     */
    public static class ReturnRequest {
        public String targetNodeKey;
        public String reason;
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

    /**
     * 转交审批（PRD 11.1）：把当前任务的办理责任交给另一个用户。
     */
    @PostMapping("/{taskId}/transfer")
    public AjaxResult transfer(@PathVariable String taskId, @RequestBody TransferRequest request) {
        WfApprovalInstance instance = approvalTaskService.transfer(
            taskId, request.toUserId, request.reason, SecurityUtils.getUserId().toString(), request.idempotencyKey);
        return success(instance);
    }

    /**
     * 加签审批（PRD 11.2）：对活跃的并行 ALL 多实例节点追加审批人实例。
     */
    @PostMapping("/{taskId}/add-sign")
    public AjaxResult addSign(@PathVariable String taskId, @RequestBody AddSignRequest request) {
        WfApprovalInstance instance = approvalTaskService.addSign(
            taskId, request.signType, request.addUsers, request.reason,
            SecurityUtils.getUserId().toString(), request.idempotencyKey);
        return success(instance);
    }

    /**
     * 退回任意节点（PRD 11.3）：白名单校验 + 受控状态变更；多实例目标走方式二。
     */
    @PostMapping("/{taskId}/return")
    public AjaxResult returnTask(@PathVariable String taskId, @RequestBody ReturnRequest request) {
        WfApprovalInstance instance = approvalTaskService.returnTask(
            taskId, request.targetNodeKey, request.reason,
            SecurityUtils.getUserId().toString(), request.idempotencyKey);
        return success(instance);
    }

    /**
     * 退回目标白名单（PRD 11.3 校验清单）：返回任务可退回的节点列表。
     */
    @GetMapping("/{taskId}/return-targets")
    public AjaxResult returnTargets(@PathVariable String taskId) {
        List<ReturnTargetVo> targets = approvalTaskService.returnTargets(taskId);
        return success(targets);
    }
}
