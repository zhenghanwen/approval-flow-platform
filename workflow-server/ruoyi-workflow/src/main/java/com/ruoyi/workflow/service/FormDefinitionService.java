package com.ruoyi.workflow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.document.FormSchemaVersionDoc;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.mapper.WfFormDefinitionMapper;
import com.ruoyi.workflow.mapper.WfFormVersionMapper;
import com.ruoyi.workflow.repository.FormSchemaVersionRepository;

/**
 * 表单定义服务（PRD 5.2–5.4）。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>创建表单定义（form_code 唯一）；</li>
 *     <li>保存/发布表单版本：schema 落 MongoDB form_schema_versions，schema_hash 校验，发布后快照不可变；</li>
 *     <li>版本递增：当前发布版本号写入 wf_form_definition.current_version。</li>
 * </ol>
 */
@Service
public class FormDefinitionService {

    /** 状态 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String VERSION_STATUS_PUBLISHED = "PUBLISHED";
    public static final String VERSION_STATUS_ARCHIVED = "ARCHIVED";

    private final WfFormDefinitionMapper definitionMapper;
    private final WfFormVersionMapper versionMapper;
    private final FormSchemaVersionRepository schemaRepository;
    private final ObjectMapper objectMapper;

    public FormDefinitionService(WfFormDefinitionMapper definitionMapper,
                                 WfFormVersionMapper versionMapper,
                                 FormSchemaVersionRepository schemaRepository,
                                 ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.schemaRepository = schemaRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建表单定义（form_code 唯一）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfFormDefinition createDefinition(String formCode, String formName, String createdBy) {
        if (formCode == null || formCode.isBlank()) {
            throw new ServiceException("表单编码不能为空");
        }
        if (definitionMapper.selectByCode(formCode) != null) {
            throw new ServiceException("表单编码已存在: " + formCode);
        }
        WfFormDefinition definition = new WfFormDefinition();
        definition.setId(UUID.randomUUID().toString());
        definition.setFormCode(formCode);
        definition.setFormName(formName);
        definition.setStatus(STATUS_DRAFT);
        definition.setCurrentVersion(0);
        definition.setCreatedBy(createdBy);
        definition.setCreatedAt(new Date());
        definitionMapper.insert(definition);
        return definition;
    }

    /**
     * 保存一个 DRAFT 版本草稿到 MongoDB（不发布）。返回版本实体。
     *
     * <p>草稿可反复覆盖（SOP P02）：若表单已存在未发布的 DRAFT 版本，则复用该版本号，
     * 覆盖其 Mongo schema 快照并同步 schema_hash；否则新建一个版本。</p>
     *
     * @param formId   表单ID
     * @param schema   schema JSON 对象
     * @param operator 操作人
     */
    @Transactional(rollbackFor = Exception.class)
    public WfFormVersion saveDraft(String formId, Object schema, String operator) {
        WfFormDefinition definition = requireDefinition(formId);
        if (!STATUS_DRAFT.equals(definition.getStatus()) && !STATUS_PUBLISHED.equals(definition.getStatus())) {
            throw new ServiceException("表单状态不允许编辑: " + definition.getStatus());
        }
        String schemaHash = hashSchema(schema);

        // 已有未发布草稿则覆盖（草稿可反复覆盖），否则新建版本
        WfFormVersion existingDraft = versionMapper.selectByFormId(formId).stream()
            .filter(v -> STATUS_DRAFT.equals(v.getStatus()))
            .max(Comparator.comparing(WfFormVersion::getVersionNo))
            .orElse(null);

        if (existingDraft != null) {
            FormSchemaVersionDoc doc = schemaRepository.findById(existingDraft.getMongoSchemaId())
                .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失: " + existingDraft.getMongoSchemaId()));
            doc.setSchema(schema);
            doc.setSchemaHash(schemaHash);
            doc.setCreatedAt(new Date());
            schemaRepository.save(doc);
            existingDraft.setSchemaHash(schemaHash);
            versionMapper.update(existingDraft);
            return existingDraft;
        }

        int nextVersion = nextVersionNo(formId);
        String versionId = UUID.randomUUID().toString();

        FormSchemaVersionDoc doc = new FormSchemaVersionDoc();
        doc.setId(versionId);
        doc.setFormId(formId);
        doc.setVersion(nextVersion);
        doc.setSchema(schema);
        doc.setSchemaHash(schemaHash);
        doc.setCreatedAt(new Date());
        schemaRepository.save(doc);

        WfFormVersion version = new WfFormVersion();
        version.setId(versionId);
        version.setFormId(formId);
        version.setVersionNo(nextVersion);
        version.setMongoSchemaId(versionId);
        version.setSchemaHash(schemaHash);
        version.setStatus(STATUS_DRAFT);
        version.setCreatedAt(new Date());
        versionMapper.insert(version);
        return version;
    }

    /**
     * 发布指定版本。发布后：
     * <ul>
     *     <li>版本状态置 PUBLISHED 并记录发布时间/人；</li>
     *     <li>表单定义 status 置 PUBLISHED、current_version 置该版本号。</li>
     * </ul>
     * 已发布版本不可再修改（快照不可变）；发布前用 schema_hash 校验 Mongo 快照一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfFormVersion publish(String formId, String versionId, String operator) {
        WfFormVersion version = versionMapper.selectById(versionId);
        if (version == null || !formId.equals(version.getFormId())) {
            throw new ServiceException("表单版本不存在或不属于该表单");
        }
        if (VERSION_STATUS_PUBLISHED.equals(version.getStatus())) {
            throw new ServiceException("该版本已发布，不可重复发布");
        }
        // schema_hash 一致性校验：Mongo 快照与 MySQL 记录必须一致
        FormSchemaVersionDoc doc = schemaRepository.findById(version.getMongoSchemaId())
            .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失: " + version.getMongoSchemaId()));
        if (!version.getSchemaHash().equals(doc.getSchemaHash())) {
            throw new ServiceException("版本快照摘要不一致，拒绝发布");
        }

        version.setStatus(VERSION_STATUS_PUBLISHED);
        version.setPublishedAt(new Date());
        version.setPublishedBy(operator);
        versionMapper.update(version);

        WfFormDefinition definition = definitionMapper.selectById(formId);
        definition.setStatus(STATUS_PUBLISHED);
        definition.setCurrentVersion(version.getVersionNo());
        definitionMapper.update(definition);
        return version;
    }

    /**
     * 查询表单定义详情。
     */
    public WfFormDefinition getDefinition(String formId) {
        return requireDefinition(formId);
    }

    /**
     * 表单定义列表。
     */
    public List<WfFormDefinition> listDefinitions(WfFormDefinition query) {
        return definitionMapper.selectList(query);
    }

    /**
     * 表单版本列表（含 Mongo schema 快照）。
     */
    public List<WfFormVersion> listVersions(String formId) {
        requireDefinition(formId);
        return versionMapper.selectByFormId(formId);
    }

    /**
     * 取指定表单当前已发布版本对应的 Mongo schema（供渲染/填报）。
     */
    public Object getPublishedSchema(String formId) {
        Integer publishedVersionNo = versionMapper.selectMaxPublishedVersion(formId);
        if (publishedVersionNo == null) {
            throw new ServiceException("表单尚未发布任何版本");
        }
        WfFormVersion version = versionMapper.selectByFormAndVersion(formId, publishedVersionNo);
        FormSchemaVersionDoc doc = schemaRepository.findById(version.getMongoSchemaId())
            .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失: " + version.getMongoSchemaId()));
        return doc.getSchema();
    }

    private WfFormDefinition requireDefinition(String formId) {
        WfFormDefinition definition = definitionMapper.selectById(formId);
        if (definition == null) {
            throw new ServiceException("表单不存在: " + formId);
        }
        return definition;
    }

    /**
     * 计算下一个版本号：表单已存在的最大版本号 + 1，无版本则 1。
     * <p>与草稿覆盖逻辑配合：仅当表单没有任何 DRAFT 版本（无草稿可覆盖）时才新建版本。</p>
     */
    private int nextVersionNo(String formId) {
        return versionMapper.selectByFormId(formId).stream()
            .map(WfFormVersion::getVersionNo)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    /**
     * 对 schema JSON 计算 SHA-256 摘要（规范化：按 JSON 序列化结果哈希）。
     */
    private String hashSchema(Object schema) {
        try {
            String canonical = objectMapper.writeValueAsString(schema);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 不可用");
        } catch (Exception e) {
            throw new ServiceException("schema JSON 序列化失败");
        }
    }
}
