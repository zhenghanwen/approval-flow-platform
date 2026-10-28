package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审批任务投影实体（PRD 7.1：wf_approval_task）。
 *
 * <p>Flowable 是任务执行事实来源，本表用于业务列表查询和展示；
 * 通过任务/活动事件监听器与 Flowable 保持同步。</p>
 */
public class WfApprovalTask {

    /** 业务任务记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** Flowable 流程实例ID */
    private String processInstanceId;

    /** Flowable 任务ID */
    private String flowableTaskId;

    /** 节点Key */
    private String taskDefinitionKey;

    /** 节点名称 */
    private String taskName;

    /** 当前办理人 */
    private String assigneeUserId;

    /** 初始办理人 */
    private String originalAssigneeUserId;

    /** 任务Owner，按需使用 */
    private String ownerUserId;

    /** 业务任务状态：CREATED/PENDING/COMPLETED/CANCELLED */
    private String taskStatus;

    /** 委托处理人 */
    private String delegatedToUserId;

    /** 关联父任务 */
    private String parentTaskId;

    /** 截止时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date dueAt;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    /** 完成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date completedAt;

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

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
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

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(String assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public String getOriginalAssigneeUserId() {
        return originalAssigneeUserId;
    }

    public void setOriginalAssigneeUserId(String originalAssigneeUserId) {
        this.originalAssigneeUserId = originalAssigneeUserId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getDelegatedToUserId() {
        return delegatedToUserId;
    }

    public void setDelegatedToUserId(String delegatedToUserId) {
        this.delegatedToUserId = delegatedToUserId;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(String parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public Date getDueAt() {
        return dueAt;
    }

    public void setDueAt(Date dueAt) {
        this.dueAt = dueAt;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Date completedAt) {
        this.completedAt = completedAt;
    }
}
