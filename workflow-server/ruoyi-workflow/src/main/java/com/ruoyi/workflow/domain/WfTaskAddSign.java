package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 任务加签记录（PRD 11.2：wf_task_add_sign）。
 *
 * <p>加签 = 对活跃的多实例审批节点动态追加审批人实例（addMultiInstanceExecution，v1 并行 ALL 规则）。
 * 前加签（原审批人未完成时加人）与后加签（部分完成后加人）在引擎层面同一 API，业务层用 signType 区分记录。</p>
 */
public class WfTaskAddSign {

    /** 加签记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 加签操作所针对的任务ID */
    private String flowableTaskId;

    /** 节点Key */
    private String taskDefinitionKey;

    /** FIRST=前加签 / AFTER=后加签 */
    private String signType;

    /** 加签人 */
    private String addUserId;

    /** 加签原因 */
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

    public String getSignType() {
        return signType;
    }

    public void setSignType(String signType) {
        this.signType = signType;
    }

    public String getAddUserId() {
        return addUserId;
    }

    public void setAddUserId(String addUserId) {
        this.addUserId = addUserId;
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
