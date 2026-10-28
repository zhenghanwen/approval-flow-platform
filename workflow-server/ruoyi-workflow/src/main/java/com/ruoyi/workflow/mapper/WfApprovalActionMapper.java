package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalAction;

/**
 * 审批操作记录 Mapper（PRD 7.2）。
 */
public interface WfApprovalActionMapper {

    int insert(WfApprovalAction action);

    List<WfApprovalAction> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
