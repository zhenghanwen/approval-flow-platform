package com.ruoyi.workflow.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ruoyi.workflow.document.ApprovalFormDataDoc;

/**
 * 审批单表单数据仓储（PRD 6.2）。
 */
public interface ApprovalFormDataRepository extends MongoRepository<ApprovalFormDataDoc, String> {

    Optional<ApprovalFormDataDoc> findByApprovalInstanceId(String approvalInstanceId);
}
