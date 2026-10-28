package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalDefinitionMapper;
import com.ruoyi.workflow.mapper.WfFormVersionMapper;
import com.ruoyi.workflow.mapper.WfWorkflowVersionMapper;

/**
 * 审批类型服务（PRD 5.1 + 13.1）。
 *
 * <p>审批类型 = 一种业务审批（请假/采购/报销…），绑定当前表单版本与当前流程版本。
 * 发布前必须完成表单 + 流程的综合校验（PRD 13.1：发布接口必须执行表单、流程和审批人配置的综合校验）。</p>
 */
@Service
public class ApprovalDefinitionService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final WfApprovalDefinitionMapper approvalDefinitionMapper;
    private final WfFormVersionMapper formVersionMapper;
    private final WfWorkflowVersionMapper workflowVersionMapper;

    public ApprovalDefinitionService(WfApprovalDefinitionMapper approvalDefinitionMapper,
                                     WfFormVersionMapper formVersionMapper,
                                     WfWorkflowVersionMapper workflowVersionMapper) {
        this.approvalDefinitionMapper = approvalDefinitionMapper;
        this.formVersionMapper = formVersionMapper;
        this.workflowVersionMapper = workflowVersionMapper;
    }

    /**
     * 创建审批类型（approval_code 唯一，初始 DRAFT）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalDefinition create(String approvalCode, String approvalName, String categoryId,
                                       String description, String icon, String operator) {
        if (approvalCode == null || approvalCode.isBlank()) {
            throw new ServiceException("审批类型编码不能为空");
        }
        if (approvalDefinitionMapper.selectByCode(approvalCode) != null) {
            throw new ServiceException("审批类型编码已存在: " + approvalCode);
        }
        WfApprovalDefinition definition = new WfApprovalDefinition();
        definition.setId(UUID.randomUUID().toString());
        definition.setApprovalCode(approvalCode);
        definition.setApprovalName(approvalName);
        definition.setCategoryId(categoryId);
        definition.setDescription(description);
        definition.setIcon(icon);
        definition.setAllowWithdraw(Boolean.TRUE);
        definition.setAllowCancel(Boolean.TRUE);
        definition.setStatus(STATUS_DRAFT);
        definition.setCreatedBy(operator);
        definition.setCreatedAt(new Date());
        approvalDefinitionMapper.insert(definition);
        return definition;
    }

    /**
     * 修改草稿配置（绑定表单版本 / 流程版本等）。仅 DRAFT 可改。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalDefinition update(WfApprovalDefinition definition) {
        WfApprovalDefinition existing = requireDefinition(definition.getId());
        if (!STATUS_DRAFT.equals(existing.getStatus())) {
            throw new ServiceException("仅 DRAFT 状态的审批类型可修改配置");
        }
        definition.setStatus(null); // 状态经 publish/disable 变更，不在此改
        definition.setCreatedAt(null);
        definition.setUpdatedAt(null);
        approvalDefinitionMapper.update(definition);
        return requireDefinition(definition.getId());
    }

    /**
     * 发布审批类型：校验已绑定已发布的表单版本与流程版本（综合校验）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalDefinition publish(String id, String operator) {
        WfApprovalDefinition definition = requireDefinition(id);
        if (definition.getCurrentFormVersionId() == null) {
            throw new ServiceException("审批类型未绑定表单版本，无法发布");
        }
        WfFormVersion formVersion = formVersionMapper.selectById(definition.getCurrentFormVersionId());
        if (formVersion == null || !"PUBLISHED".equals(formVersion.getStatus())) {
            throw new ServiceException("绑定的表单版本不存在或未发布");
        }
        if (definition.getCurrentFlowVersionId() == null) {
            throw new ServiceException("审批类型未绑定流程版本，无法发布");
        }
        WfWorkflowVersion flowVersion = workflowVersionMapper.selectById(definition.getCurrentFlowVersionId());
        if (flowVersion == null || !"PUBLISHED".equals(flowVersion.getStatus())) {
            throw new ServiceException("绑定的流程版本不存在或未发布");
        }
        definition.setStatus(STATUS_PUBLISHED);
        approvalDefinitionMapper.update(definition);
        return requireDefinition(id);
    }

    /**
     * 停用审批类型。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalDefinition disable(String id) {
        WfApprovalDefinition definition = requireDefinition(id);
        definition.setStatus(STATUS_DISABLED);
        approvalDefinitionMapper.update(definition);
        return requireDefinition(id);
    }

    public WfApprovalDefinition get(String id) {
        return requireDefinition(id);
    }

    public List<WfApprovalDefinition> list(WfApprovalDefinition query) {
        return approvalDefinitionMapper.selectList(query);
    }

    private WfApprovalDefinition requireDefinition(String id) {
        WfApprovalDefinition definition = approvalDefinitionMapper.selectById(id);
        if (definition == null) {
            throw new ServiceException("审批类型不存在: " + id);
        }
        return definition;
    }
}
