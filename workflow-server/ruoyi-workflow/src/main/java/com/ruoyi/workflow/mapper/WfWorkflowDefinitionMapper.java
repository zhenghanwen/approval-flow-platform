package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfWorkflowDefinition;

/**
 * 流程定义 Mapper（PRD 5.5）。
 */
public interface WfWorkflowDefinitionMapper {

    int insert(WfWorkflowDefinition definition);

    int update(WfWorkflowDefinition definition);

    WfWorkflowDefinition selectById(@Param("id") String id);

    WfWorkflowDefinition selectByCode(@Param("workflowCode") String workflowCode);

    List<WfWorkflowDefinition> selectByApprovalDefinition(@Param("approvalDefinitionId") String approvalDefinitionId);

    List<WfWorkflowDefinition> selectList(WfWorkflowDefinition query);
}
