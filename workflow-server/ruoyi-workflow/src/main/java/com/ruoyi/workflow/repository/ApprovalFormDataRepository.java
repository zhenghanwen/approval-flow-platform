package com.ruoyi.workflow.repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.ruoyi.workflow.document.ApprovalFormDataDoc;

/**
 * 审批单表单数据仓储（PRD 6.2）。
 */
public interface ApprovalFormDataRepository extends MongoRepository<ApprovalFormDataDoc, String> {

    Optional<ApprovalFormDataDoc> findByApprovalInstanceId(String approvalInstanceId);

    /**
     * 创建时间早于截止时间的文档（一致性恢复：清理无对应实例的孤立数据，PRD 16.2/16.4）。
     */
    List<ApprovalFormDataDoc> findByCreatedAtBefore(Date cutoff);
}
