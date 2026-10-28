package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审批操作记录实体（PRD 7.2：wf_approval_action）。
 *
 * <p>只追加，不覆盖历史审批意见。action_type 示例：
 * SUBMIT / APPROVE / REJECT / RETURN / TRANSFER / ADD_SIGN / WITHDRAW / CANCEL / CC / ADMIN_TERMINATE。</p>
 */
public class WfApprovalAction {

    /** 操作ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 相关 Flowable 任务ID */
    private String flowableTaskId;

    /** 节点Key */
    private String taskDefinitionKey;

    /** 操作人 */
    private String operatorUserId;

    /** 操作类型 */
    private String actionType;

    /** 审批意见 */
    private String comment;

    /** 目标用户 */
    private String targetUserId;

    /** 目标节点 */
    private String targetTaskKey;

    /** 操作前状态 */
    private String beforeStatus;

    /** 操作后状态 */
    private String afterStatus;

    /** 幂等操作ID */
    private String operationId;

    /** 操作时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApprovalInstanceId() {
        return approvalInstanceId;
    }

    public void setApprovalInstanceId(String approvalInstanceId) {
        this.approvalInstanceId = approvalInstanceId;
    }

    public String getFlowableTaskId() {
        return flowableTaskId;
    }

    public void setFlowableTaskId(String flowableTaskId) {
        this.flowableTaskId = flowableTaskId;
    }

    public String getTaskDefinitionKey() {
        return taskDefinitionKey;
    }

    public void setTaskDefinitionKey(String taskDefinitionKey) {
        this.taskDefinitionKey = taskDefinitionKey;
    }

    public String getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(String operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(String targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getTargetTaskKey() {
        return targetTaskKey;
    }

    public void setTargetTaskKey(String targetTaskKey) {
        this.targetTaskKey = targetTaskKey;
    }

    public String getBeforeStatus() {
        return beforeStatus;
    }

    public void setBeforeStatus(String beforeStatus) {
        this.beforeStatus = beforeStatus;
    }

    public String getAfterStatus() {
        return afterStatus;
    }

    public void setAfterStatus(String afterStatus) {
        this.afterStatus = afterStatus;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
