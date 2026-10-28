package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 任务退回记录（PRD 11.3：wf_task_return）。
 *
 * <p>退回 = 受控的活动状态变更（change-state）到本流程版本内的白名单节点。
 * 目标节点类型：APPLICANT（申请人节点，普通任务直退）、MI（多实例审批节点，
 * 走 §3.3.2 方式二：退前置 + 集合变量重写，规避 Flowable 6.8 collection-MI 直退空壳）、
 * PLAIN（普通串行节点直退）。</p>
 */
public class WfTaskReturn {

    /** 退回记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 退回源任务ID */
    private String sourceFlowableTaskId;

    /** 退回源节点Key */
    private String sourceNodeKey;

    /** 退回目标节点Key */
    private String targetNodeKey;

    /** APPLICANT=申请人节点 / MI=多实例审批节点 / PLAIN=普通节点 */
    private String targetNodeType;

    /** 退回原因 */
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

    public String getSourceFlowableTaskId() {
        return sourceFlowableTaskId;
    }

    public void setSourceFlowableTaskId(String sourceFlowableTaskId) {
        this.sourceFlowableTaskId = sourceFlowableTaskId;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public void setSourceNodeKey(String sourceNodeKey) {
        this.sourceNodeKey = sourceNodeKey;
    }

    public String getTargetNodeKey() {
        return targetNodeKey;
    }

    public void setTargetNodeKey(String targetNodeKey) {
        this.targetNodeKey = targetNodeKey;
    }

    public String getTargetNodeType() {
        return targetNodeType;
    }

    public void setTargetNodeType(String targetNodeType) {
        this.targetNodeType = targetNodeType;
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
