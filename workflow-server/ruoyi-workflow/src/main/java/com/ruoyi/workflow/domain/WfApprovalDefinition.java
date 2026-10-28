package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审批类型实体（PRD 5.1：wf_approval_definition）。
 */
public class WfApprovalDefinition {

    /** 审批类型ID */
    private String id;

    /** 唯一编码 */
    private String approvalCode;

    /** 审批名称 */
    private String approvalName;

    /** 分类ID */
    private String categoryId;

    /** 描述 */
    private String description;

    /** 图标标识 */
    private String icon;

    /** 当前表单版本 */
    private String currentFormVersionId;

    /** 当前流程版本 */
    private String currentFlowVersionId;

    /** 是否允许撤回 */
    private Boolean allowWithdraw;

    /** 是否允许取消 */
    private Boolean allowCancel;

    /** 状态：DRAFT / PUBLISHED / DISABLED */
    private String status;

    /** 创建人 */
    private String createdBy;

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

    public String getApprovalCode() {
        return approvalCode;
    }

    public void setApprovalCode(String approvalCode) {
        this.approvalCode = approvalCode;
    }

    public String getApprovalName() {
        return approvalName;
    }

    public void setApprovalName(String approvalName) {
        this.approvalName = approvalName;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getCurrentFormVersionId() {
        return currentFormVersionId;
    }

    public void setCurrentFormVersionId(String currentFormVersionId) {
        this.currentFormVersionId = currentFormVersionId;
    }

    public String getCurrentFlowVersionId() {
        return currentFlowVersionId;
    }

    public void setCurrentFlowVersionId(String currentFlowVersionId) {
        this.currentFlowVersionId = currentFlowVersionId;
    }

    public Boolean getAllowWithdraw() {
        return allowWithdraw;
    }

    public void setAllowWithdraw(Boolean allowWithdraw) {
        this.allowWithdraw = allowWithdraw;
    }

    public Boolean getAllowCancel() {
        return allowCancel;
    }

    public void setAllowCancel(Boolean allowCancel) {
        this.allowCancel = allowCancel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
