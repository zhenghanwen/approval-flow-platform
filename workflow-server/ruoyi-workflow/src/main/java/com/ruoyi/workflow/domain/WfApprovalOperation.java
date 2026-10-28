package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 流程操作实体（PRD 7.3：wf_approval_operation）。
 *
 * <p>用于管理复杂流程操作的执行状态和幂等：同一用户同一幂等键重复提交时，
 * 返回已存在操作的结果，不重复执行流程动作。</p>
 */
public class WfApprovalOperation {

    /** 操作ID */
    private String id;

    /** 审批单ID */
    private String approvalInstanceId;

    /** 操作类型 */
    private String operationType;

    /** 幂等键 */
    private String idempotencyKey;

    /** 操作人 */
    private String operatorUserId;

    /** 必要的请求摘要（JSON 字符串） */
    private String requestJson;

    /** 操作状态：PENDING / PROCESSING / SUCCESS / FAILED */
    private String operationStatus;

    /** 执行结果摘要（JSON 字符串） */
    private String resultJson;

    /** 错误信息 */
    private String errorMessage;

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

    public String getApprovalInstanceId() {
        return approvalInstanceId;
    }

    public void setApprovalInstanceId(String approvalInstanceId) {
        this.approvalInstanceId = approvalInstanceId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(String operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getOperationStatus() {
        return operationStatus;
    }

    public void setOperationStatus(String operationStatus) {
        this.operationStatus = operationStatus;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
