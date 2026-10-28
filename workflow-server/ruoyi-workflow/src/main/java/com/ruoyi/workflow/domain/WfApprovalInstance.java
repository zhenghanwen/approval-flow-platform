package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审批单主表实体（PRD 6.1：wf_approval_instance）。
 *
 * <p>M2 起作为表单数据 MySQL 投影（业务状态、申请人、标题等固定字段），
 * 完整表单 JSON 存 MongoDB approval_form_data；M3 审批闭环复用本表承接流程实例。</p>
 */
public class WfApprovalInstance {

    /** 审批单ID */
    private String id;

    /** 审批单编号 */
    private String approvalNo;

    /** 审批类型ID */
    private String approvalDefinitionId;

    /** 表单版本ID */
    private String formVersionId;

    /** 流程版本ID */
    private String workflowVersionId;

    /** Flowable 流程实例ID */
    private String processInstanceId;

    /** Flowable 流程定义ID */
    private String processDefinitionId;

    /** 申请人ID */
    private String applicantUserId;

    /** 申请人姓名快照 */
    private String applicantNameSnapshot;

    /** 申请人部门ID */
    private String applicantDeptId;

    /** 部门名称快照 */
    private String applicantDeptSnapshot;

    /** 审批标题 */
    private String title;

    /** MongoDB 表单文档ID */
    private String formDataId;

    /** 业务状态：DRAFT/SUBMITTING/PENDING/APPROVED/REJECTED/RETURNED/WITHDRAWN/CANCELLED/ERROR */
    private String businessStatus;

    /** 当前节点Key */
    private String currentNodeKey;

    /** 当前节点名称 */
    private String currentNodeName;

    /** 提交时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date submittedAt;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date completedAt;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApprovalNo() {
        return approvalNo;
    }

    public void setApprovalNo(String approvalNo) {
        this.approvalNo = approvalNo;
    }

    public String getApprovalDefinitionId() {
        return approvalDefinitionId;
    }

    public void setApprovalDefinitionId(String approvalDefinitionId) {
        this.approvalDefinitionId = approvalDefinitionId;
    }

    public String getFormVersionId() {
        return formVersionId;
    }

    public void setFormVersionId(String formVersionId) {
        this.formVersionId = formVersionId;
    }

    public String getWorkflowVersionId() {
        return workflowVersionId;
    }

    public void setWorkflowVersionId(String workflowVersionId) {
        this.workflowVersionId = workflowVersionId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public void setProcessDefinitionId(String processDefinitionId) {
        this.processDefinitionId = processDefinitionId;
    }

    public String getApplicantUserId() {
        return applicantUserId;
    }

    public void setApplicantUserId(String applicantUserId) {
        this.applicantUserId = applicantUserId;
    }

    public String getApplicantNameSnapshot() {
        return applicantNameSnapshot;
    }

    public void setApplicantNameSnapshot(String applicantNameSnapshot) {
        this.applicantNameSnapshot = applicantNameSnapshot;
    }

    public String getApplicantDeptId() {
        return applicantDeptId;
    }

    public void setApplicantDeptId(String applicantDeptId) {
        this.applicantDeptId = applicantDeptId;
    }

    public String getApplicantDeptSnapshot() {
        return applicantDeptSnapshot;
    }

    public void setApplicantDeptSnapshot(String applicantDeptSnapshot) {
        this.applicantDeptSnapshot = applicantDeptSnapshot;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFormDataId() {
        return formDataId;
    }

    public void setFormDataId(String formDataId) {
        this.formDataId = formDataId;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    public String getCurrentNodeKey() {
        return currentNodeKey;
    }

    public void setCurrentNodeKey(String currentNodeKey) {
        this.currentNodeKey = currentNodeKey;
    }

    public String getCurrentNodeName() {
        return currentNodeName;
    }

    public void setCurrentNodeName(String currentNodeName) {
        this.currentNodeName = currentNodeName;
    }

    public Date getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Date submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Date getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Date completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
