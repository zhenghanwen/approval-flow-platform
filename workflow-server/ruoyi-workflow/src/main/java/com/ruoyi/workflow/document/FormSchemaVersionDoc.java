package com.ruoyi.workflow.document;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 表单 schema 版本快照文档（PRD 5.4：MongoDB form_schema_versions）。
 *
 * <p>已发布版本的 schema 为不可变快照，由 MySQL wf_form_version.mongo_schema_id 引用；</p>
 * <p>schemaHash 为 schema JSON 的 SHA-256 摘要，用于一致性校验。</p>
 */
@Document(collection = "form_schema_versions")
public class FormSchemaVersionDoc {

    /** 文档ID（与 wf_form_version.id 一致） */
    @Id
    private String id;

    /** 表单ID */
    private String formId;

    /** 版本号 */
    private Integer version;

    /** 表单 schema（JSON） */
    private Object schema;

    /** schema JSON 摘要（SHA-256 hex） */
    private String schemaHash;

    /** 创建时间 */
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Object getSchema() {
        return schema;
    }

    public void setSchema(Object schema) {
        this.schema = schema;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public void setSchemaHash(String schemaHash) {
        this.schemaHash = schemaHash;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
