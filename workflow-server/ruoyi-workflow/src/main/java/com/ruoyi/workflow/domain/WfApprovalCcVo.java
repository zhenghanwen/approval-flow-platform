package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 抄送列表查询 VO（P7「抄送我的」）。
 * 关联审批单 + 审批类型，补齐标题 / 类型名 / 发起人 / 当前节点 / 状态。
 */
public class WfApprovalCcVo {

    /** 抄送记录ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 审批标题 */
    private String title;

    /** 审批类型名 */
    private String typeName;

    /** 发起人快照 */
    private String applicant;

    /** 当前节点名称（最近一条任务节点，终态为空） */
    private String currentNodeName;

    /** 抄送时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date ccTime;

    /** 审批单业务状态 */
    private String businessStatus;

    /** 是否已读 */
    private Boolean readFlag;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getApplicant() {
        return applicant;
    }

    public void setApplicant(String applicant) {
        this.applicant = applicant;
    }

    public String getCurrentNodeName() {
        return currentNodeName;
    }

    public void setCurrentNodeName(String currentNodeName) {
        this.currentNodeName = currentNodeName;
    }

    public Date getCcTime() {
        return ccTime;
    }

    public void setCcTime(Date ccTime) {
        this.ccTime = ccTime;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    public Boolean getReadFlag() {
        return readFlag;
    }

    public void setReadFlag(Boolean readFlag) {
        this.readFlag = readFlag;
    }
}
