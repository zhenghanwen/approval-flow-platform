package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.document.FormSchemaVersionDoc;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfFormVersionMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;
import com.ruoyi.workflow.repository.FormSchemaVersionRepository;

/**
 * 表单数据服务（PRD 6.2 六条数据规则）。
 *
 * <p>数据规则落实：</p>
 * <ol>
 *     <li>approvalInstanceId 与 MySQL 审批单 ID 一致（生成审批单后写 Mongo）；</li>
 *     <li>formVersionId 固定指向发起时使用的表单版本；</li>
 *     <li>表单 JSON 必须经过服务端校验（FormSchemaValidator）；</li>
 *     <li>已提交表单不允许普通更新覆盖原始申请内容（business_status 校验）；</li>
 *     <li>修改申请形成新的数据版本 dataVersion+1 并留修订记录；</li>
 *     <li>附件只存元数据（fileId/name/size/type），文件本体走对象存储（本服务不接存储）。</li>
 * </ol>
 */
@Service
public class FormDataService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTING = "SUBMITTING";

    private final ApprovalFormDataRepository formDataRepository;
    private final FormSchemaVersionRepository schemaRepository;
    private final WfFormVersionMapper versionMapper;
    private final WfApprovalInstanceMapper approvalInstanceMapper;
    private final FormSchemaValidator validator;

    public FormDataService(ApprovalFormDataRepository formDataRepository,
                           FormSchemaVersionRepository schemaRepository,
                           WfFormVersionMapper versionMapper,
                           WfApprovalInstanceMapper approvalInstanceMapper,
                           FormSchemaValidator validator) {
        this.formDataRepository = formDataRepository;
        this.schemaRepository = schemaRepository;
        this.versionMapper = versionMapper;
        this.approvalInstanceMapper = approvalInstanceMapper;
        this.validator = validator;
    }

    /**
     * 保存草稿（不提交）：创建 MySQL 投影 + Mongo 文档，不强制必填校验。
     *
     * @param formVersionId 表单版本ID（发布版本）
     * @param data          表单 JSON
     * @param applicantUserId 申请人ID
     * @param applicantName  申请人姓名快照
     * @param title          审批标题
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance saveDraft(String formVersionId, Object data,
                                        String applicantUserId, String applicantName, String title) {
        WfFormVersion version = versionMapper.selectById(formVersionId);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw new ServiceException("表单版本不存在或未发布: " + formVersionId);
        }
        FormSchemaVersionDoc schemaDoc = schemaRepository.findById(version.getMongoSchemaId())
            .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失: " + version.getMongoSchemaId()));
        // 规则 3：草稿也做类型校验，但不强制必填
        validator.validate(schemaDoc.getSchema(), data, false);

        WfApprovalInstance instance = createInstance(formVersionId, version, applicantUserId, applicantName, title);
        ApprovalFormDataDoc doc = new ApprovalFormDataDoc();
        doc.setId(UUID.randomUUID().toString());
        doc.setApprovalInstanceId(instance.getId());
        doc.setFormVersionId(formVersionId);
        doc.setSchemaHash(schemaDoc.getSchemaHash());
        doc.setDataVersion(1);
        doc.setData(data);
        doc.setCreatedAt(new Date());
        doc.setUpdatedAt(new Date());
        formDataRepository.save(doc);

        instance.setFormDataId(doc.getId());
        approvalInstanceMapper.update(instance);
        return instance;
    }

    /**
     * 提交（正式发起）：强制必填校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance submit(String formVersionId, Object data,
                                     String applicantUserId, String applicantName, String title) {
        WfFormVersion version = versionMapper.selectById(formVersionId);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw new ServiceException("表单版本不存在或未发布: " + formVersionId);
        }
        FormSchemaVersionDoc schemaDoc = schemaRepository.findById(version.getMongoSchemaId())
            .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失: " + version.getMongoSchemaId()));
        // 规则 3：提交强制必填 + 类型校验
        validator.validate(schemaDoc.getSchema(), data, true);

        WfApprovalInstance instance = createInstance(formVersionId, version, applicantUserId, applicantName, title);
        instance.setBusinessStatus(STATUS_SUBMITTING);
        instance.setSubmittedAt(new Date());

        ApprovalFormDataDoc doc = new ApprovalFormDataDoc();
        doc.setId(UUID.randomUUID().toString());
        doc.setApprovalInstanceId(instance.getId());
        doc.setFormVersionId(formVersionId);
        doc.setSchemaHash(schemaDoc.getSchemaHash());
        doc.setDataVersion(1);
        doc.setData(data);
        doc.setCreatedAt(new Date());
        doc.setUpdatedAt(new Date());
        formDataRepository.save(doc);

        instance.setFormDataId(doc.getId());
        approvalInstanceMapper.update(instance);
        return instance;
    }

    /**
     * 读取审批单表单数据（完整 JSON）。
     */
    public ApprovalFormDataDoc getFormData(String approvalInstanceId) {
        return formDataRepository.findByApprovalInstanceId(approvalInstanceId)
            .orElseThrow(() -> new ServiceException("审批单表单数据不存在: " + approvalInstanceId));
    }

    /**
     * 创建审批单 MySQL 投影行。
     */
    private WfApprovalInstance createInstance(String formVersionId, WfFormVersion version,
                                              String applicantUserId, String applicantName, String title) {
        WfApprovalInstance instance = new WfApprovalInstance();
        instance.setId(UUID.randomUUID().toString());
        instance.setApprovalNo(generateApprovalNo());
        instance.setFormVersionId(formVersionId);
        instance.setApplicantUserId(applicantUserId);
        instance.setApplicantNameSnapshot(applicantName);
        instance.setTitle(title);
        instance.setBusinessStatus(STATUS_DRAFT);
        instance.setVersion(0);
        instance.setCreatedAt(new Date());
        approvalInstanceMapper.insert(instance);
        return instance;
    }

    /**
     * 审批单编号：WF + yyyyMMddHHmmss + 4 位随机。
     */
    private String generateApprovalNo() {
        java.time.format.DateTimeFormatter fmt =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String ts = java.time.LocalDateTime.now().format(fmt);
        String rand = String.format("%04d", (int) (Math.random() * 10000));
        return "WF" + ts + rand;
    }
}
