package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 表单版本实体（PRD 5.3：wf_form_version）。
 *
 * <p>版本快照不可变：发布后 schema 内容由 mongo_schema_id 指向 MongoDB
 * form_schema_versions 文档，schema_hash 用于一致性校验；DRAFT 状态可覆盖，PUBLISHED/ARCHIVED 冻结。</p>
 */
public class WfFormVersion {

    /** 表单版本ID */
    private String id;

    /** 表单ID */
    private String formId;

    /** 版本号 */
    private Integer versionNo;

    /** MongoDB 文档ID */
    private String mongoSchemaId;

    /** JSON 内容摘要（SHA-256 hex） */
    private String schemaHash;

    /** 状态：DRAFT / PUBLISHED / ARCHIVED */
    private String status;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date publishedAt;

    /** 发布人 */
    private String publishedBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFormId() {
        return formId;
    }

    public void setFormId(String formId) {
        this.formId = formId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getMongoSchemaId() {
        return mongoSchemaId;
    }

    public void setMongoSchemaId(String mongoSchemaId) {
        this.mongoSchemaId = mongoSchemaId;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public void setSchemaHash(String schemaHash) {
        this.schemaHash = schemaHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Date getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Date publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
