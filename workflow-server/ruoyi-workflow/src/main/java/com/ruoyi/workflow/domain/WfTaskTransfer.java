package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 任务转交记录（PRD 11.1：wf_task_transfer）。
 *
 * <p>转交把当前任务的办理责任交给另一个用户；记录原办理人、新办理人及转交原因，
 * 供审计与轨迹查询。</p>
 */
public class WfTaskTransfer {

    /** 转交记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** Flowable 任务ID */
    private String flowableTaskId;

    /** 节点Key */
    private String taskDefinitionKey;

    /** 原办理人 */
    private String fromUserId;

    /** 新办理人 */
    private String toUserId;

    /** 转交原因 */
    private String reason;

    /** 操作人 */
    private String operatorUserId;

    /** 创建时间 */
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

    public String getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(String fromUserId) {
        this.fromUserId = fromUserId;
    }

    public String getToUserId() {
        return toUserId;
    }

    public void setToUserId(String toUserId) {
        this.toUserId = toUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(String operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
