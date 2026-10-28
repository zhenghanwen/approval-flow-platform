package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalDefinition;

/**
 * 审批类型 Mapper（PRD 5.1）。
 */
public interface WfApprovalDefinitionMapper {

    int insert(WfApprovalDefinition definition);

    int update(WfApprovalDefinition definition);

    WfApprovalDefinition selectById(@Param("id") String id);

    WfApprovalDefinition selectByCode(@Param("approvalCode") String approvalCode);

    List<WfApprovalDefinition> selectList(WfApprovalDefinition query);

    List<WfApprovalDefinition> selectPublished();
}
