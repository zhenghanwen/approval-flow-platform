package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalInstance;

/**
 * 审批单 Mapper（PRD 6.1，M2 起作为表单数据 MySQL 投影）。
 */
public interface WfApprovalInstanceMapper {

    int insert(WfApprovalInstance instance);

    int update(WfApprovalInstance instance);

    WfApprovalInstance selectById(@Param("id") String id);

    WfApprovalInstance selectByApprovalNo(@Param("approvalNo") String approvalNo);

    WfApprovalInstance selectByProcessInstanceId(@Param("processInstanceId") String processInstanceId);

    List<WfApprovalInstance> selectByApplicant(@Param("applicantUserId") String applicantUserId);

    List<WfApprovalInstance> selectList(WfApprovalInstance query);
}
