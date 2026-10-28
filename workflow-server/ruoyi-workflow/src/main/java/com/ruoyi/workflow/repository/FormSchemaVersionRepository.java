package com.ruoyi.workflow.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ruoyi.workflow.document.FormSchemaVersionDoc;

/**
 * 表单 schema 版本快照仓储（PRD 5.4）。
 */
public interface FormSchemaVersionRepository extends MongoRepository<FormSchemaVersionDoc, String> {
}
