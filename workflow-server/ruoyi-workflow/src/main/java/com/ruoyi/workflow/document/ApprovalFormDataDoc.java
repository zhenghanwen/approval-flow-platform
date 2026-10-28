package com.ruoyi.workflow.document;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 审批单表单数据文档（PRD 6.2：MongoDB approval_form_data）。
 *
 * <p>每份审批单保存完整表单 JSON；approvalInstanceId 与 MySQL wf_approval_instance.id 一致，</p>
 * <p>formVersionId 固定指向发起时使用的表单版本（版本不可变约束）。</p>
 */
@Document(collection = "approval_form_data")
public class ApprovalFormDataDoc {

    /** 文档ID */
    @Id
    private String id;

    /** 审批单ID（与 MySQL wf_approval_instance.id 一致） */
    private String approvalInstanceId;

    /** 表单版本ID（固定指向发起时使用的版本） */
    private String formVersionId;

    /** schema 摘要 */
    private String schemaHash;

    /** 数据版本（修订递增） */
    private Integer dataVersion;

    /** 表单 JSON 数据 */
    private Object data;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
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

    public String getFormVersionId() {
        return formVersionId;
    }

    public void setFormVersionId(String formVersionId) {
        this.formVersionId = formVersionId;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public void setSchemaHash(String schemaHash) {
        this.schemaHash = schemaHash;
    }

    public Integer getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(Integer dataVersion) {
        this.dataVersion = dataVersion;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
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
