package com.ruoyi.workflow.mapper;

import java.util.Date;
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

    /**
     * 判断某审批单下是否存在指派给指定用户的投影任务（资源级鉴权，PRD 17.3）。
     */
    boolean existsByInstanceAndAssignee(@Param("approvalInstanceId") String approvalInstanceId,
                                        @Param("assigneeUserId") String assigneeUserId);

    /**
     * 按流程实例 + 任务状态查询投影（一致性恢复兜底，PRD 16.4）。
     */
    List<WfApprovalTask> selectByProcessInstanceAndStatus(@Param("processInstanceId") String processInstanceId,
                                                          @Param("taskStatus") String taskStatus);

    /**
     * 查询某审批单下未完成（CREATED/PENDING）的投影任务（一致性恢复投影漂移，PRD 16.4）。
     */
    List<WfApprovalTask> selectPendingByInstance(@Param("approvalInstanceId") String approvalInstanceId);

    List<WfApprovalTask> selectTodoByAssignee(@Param("assigneeUserId") String assigneeUserId);

    List<WfApprovalTask> selectDoneByAssignee(@Param("assigneeUserId") String assigneeUserId);

    /**
     * 批量结束流程实例下未完成的任务投影（撤回/取消用，PRD 11.4/11.5）。
     */
    int updateStatusByProcessInstance(@Param("processInstanceId") String processInstanceId,
                                      @Param("taskStatus") String taskStatus,
                                      @Param("completedAt") Date completedAt);
}
