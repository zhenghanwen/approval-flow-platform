package com.ruoyi.workflow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.bpmn.BpmnValidationService;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfWorkflowDefinitionMapper;
import com.ruoyi.workflow.mapper.WfWorkflowVersionMapper;

/**
 * 流程定义服务（PRD 5.5–5.6 + 12.1）。
 *
 * <p>流程发布链路：前端提交 BPMN XML → {@link BpmnValidationService} 校验 + 规一化
 * → 部署至 Flowable → 保存 deploymentId/processDefinitionId → 更新流程定义当前版本。</p>
 *
 * <p>版本快照不可变：发布后旧实例绑定旧版本，流程版本不再覆盖已部署内容（PRD 5.6）。</p>
 */
@Service
public class WorkflowDefinitionService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String VERSION_STATUS_PUBLISHED = "PUBLISHED";

    private final WfWorkflowDefinitionMapper definitionMapper;
    private final WfWorkflowVersionMapper versionMapper;
    private final BpmnValidationService validationService;
    private final RepositoryService repositoryService;

    public WorkflowDefinitionService(WfWorkflowDefinitionMapper definitionMapper,
                                     WfWorkflowVersionMapper versionMapper,
                                     BpmnValidationService validationService,
                                     RepositoryService repositoryService) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.validationService = validationService;
        this.repositoryService = repositoryService;
    }

    /**
     * 创建流程定义（workflow_code 唯一）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfWorkflowDefinition createDefinition(String approvalDefinitionId, String workflowCode,
                                                 String workflowName, String createdBy) {
        if (approvalDefinitionId == null || approvalDefinitionId.isBlank()) {
            throw new ServiceException("流程必须挂接所属审批类型(approvalDefinitionId)");
        }
        if (workflowCode == null || workflowCode.isBlank()) {
            throw new ServiceException("流程编码不能为空");
        }
        if (definitionMapper.selectByCode(workflowCode) != null) {
            throw new ServiceException("流程编码已存在: " + workflowCode);
        }
        WfWorkflowDefinition definition = new WfWorkflowDefinition();
        definition.setId(UUID.randomUUID().toString());
        definition.setApprovalDefinitionId(approvalDefinitionId);
        definition.setWorkflowCode(workflowCode);
        definition.setWorkflowName(workflowName);
        definition.setStatus(STATUS_DRAFT);
        definition.setCurrentVersion(0);
        definition.setCreatedBy(createdBy);
        definition.setCreatedAt(new Date());
        definitionMapper.insert(definition);
        return definition;
    }

    /**
     * 校验流程配置（不部署）。返回规一化后的 BPMN，供前端预览/校验。
     */
    public String validate(String workflowId, String bpmnXml) {
        requireDefinition(workflowId);
        return validationService.validateAndNormalize(bpmnXml);
    }

    /**
     * 发布流程版本：校验 + 规一化 → 部署 Flowable → 记录版本快照。
     *
     * @param workflowId 流程定义ID
     * @param bpmnXml    前端提交的 BPMN XML
     * @param configJson 节点配置和扩展元数据（可空）
     * @param operator   发布人
     */
    @Transactional(rollbackFor = Exception.class)
    public WfWorkflowVersion publish(String workflowId, String bpmnXml, String configJson, String operator) {
        WfWorkflowDefinition definition = requireDefinition(workflowId);

        // 1. 校验 + 规一化（门禁，completionRule 从节点配置读取）
        String normalizedXml = validationService.validateAndNormalize(bpmnXml, configJson);

        // 2. 部署至 Flowable
        Deployment deployment = repositoryService.createDeployment()
            .addString("workflow-" + definition.getWorkflowCode() + "-v"
                + (nextVersionNo(workflowId) + 1) + ".bpmn20.xml", normalizedXml)
            .name(definition.getWorkflowName())
            .deploy();
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
        if (pd == null) {
            throw new ServiceException("流程定义部署后未找到 ProcessDefinition");
        }

        // 3. 版本快照（不可变）
        int versionNo = nextVersionNo(workflowId) + 1;
        WfWorkflowVersion version = new WfWorkflowVersion();
        version.setId(UUID.randomUUID().toString());
        version.setWorkflowId(workflowId);
        version.setVersionNo(versionNo);
        version.setFlowableDeploymentId(deployment.getId());
        version.setProcessDefinitionId(pd.getId());
        version.setProcessDefinitionKey(pd.getKey());
        version.setBpmnHash(hashXml(normalizedXml));
        version.setConfigJson(configJson);
        version.setStatus(VERSION_STATUS_PUBLISHED);
        version.setPublishedAt(new Date());
        version.setPublishedBy(operator);
        version.setCreatedAt(new Date());
        versionMapper.insert(version);

        // 4. 更新流程定义
        definition.setStatus(STATUS_PUBLISHED);
        definition.setCurrentVersion(versionNo);
        definitionMapper.update(definition);
        return version;
    }

    /**
     * 流程定义详情。
     */
    public WfWorkflowDefinition getDefinition(String workflowId) {
        return requireDefinition(workflowId);
    }

    /**
     * 流程定义列表。
     */
    public List<WfWorkflowDefinition> listDefinitions(WfWorkflowDefinition query) {
        return definitionMapper.selectList(query);
    }

    /**
     * 流程版本列表。
     */
    public List<WfWorkflowVersion> listVersions(String workflowId) {
        requireDefinition(workflowId);
        return versionMapper.selectByWorkflowId(workflowId);
    }

    private WfWorkflowDefinition requireDefinition(String workflowId) {
        WfWorkflowDefinition definition = definitionMapper.selectById(workflowId);
        if (definition == null) {
            throw new ServiceException("流程定义不存在: " + workflowId);
        }
        return definition;
    }

    private int nextVersionNo(String workflowId) {
        Integer maxPublished = versionMapper.selectMaxPublishedVersion(workflowId);
        return maxPublished == null ? 0 : maxPublished;
    }

    private String hashXml(String xml) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(xml.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException("BPMN 摘要计算失败");
        }
    }
}
