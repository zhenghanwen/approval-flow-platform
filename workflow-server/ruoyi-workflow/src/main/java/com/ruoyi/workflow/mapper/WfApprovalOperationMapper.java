package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalOperation;

/**
 * 流程操作 Mapper（PRD 7.3，幂等）。
 */
public interface WfApprovalOperationMapper {

    int insert(WfApprovalOperation operation);

    int update(WfApprovalOperation operation);

    WfApprovalOperation selectById(@Param("id") String id);

    WfApprovalOperation selectByOperatorAndKey(@Param("operatorUserId") String operatorUserId,
                                               @Param("idempotencyKey") String idempotencyKey);

    List<WfApprovalOperation> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
