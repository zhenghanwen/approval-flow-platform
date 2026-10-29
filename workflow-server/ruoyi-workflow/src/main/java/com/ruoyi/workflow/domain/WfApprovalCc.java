package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审批抄送记录实体（V5：wf_approval_cc）。
 */
public class WfApprovalCc {

    /** 抄送记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 被抄送用户ID */
    private String ccUserId;

    /** 抄送发起人（提交人） */
    private String ccByUserId;

    /** 是否已读 */
    private Boolean readFlag;

    /** 抄送时间 */
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

    public String getCcUserId() {
        return ccUserId;
    }

    public void setCcUserId(String ccUserId) {
        this.ccUserId = ccUserId;
    }

    public String getCcByUserId() {
        return ccByUserId;
    }

    public void setCcByUserId(String ccByUserId) {
        this.ccByUserId = ccByUserId;
    }

    public Boolean getReadFlag() {
        return readFlag;
    }

    public void setReadFlag(Boolean readFlag) {
        this.readFlag = readFlag;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
