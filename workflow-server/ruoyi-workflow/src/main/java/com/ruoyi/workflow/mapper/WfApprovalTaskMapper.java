package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalTask;

/**
 * 审批任务投影 Mapper（PRD 7.1）。
 */
public interface WfApprovalTaskMapper {

    int insert(WfApprovalTask task);

    int update(WfApprovalTask task);

    WfApprovalTask selectById(@Param("id") String id);

    WfApprovalTask selectByFlowableTaskId(@Param("flowableTaskId") String flowableTaskId);

    List<WfApprovalTask> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);

    List<WfApprovalTask> selectTodoByAssignee(@Param("assigneeUserId") String assigneeUserId);

    List<WfApprovalTask> selectDoneByAssignee(@Param("assigneeUserId") String assigneeUserId);
}
