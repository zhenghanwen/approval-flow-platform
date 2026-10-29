package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfApprovalCc;
import com.ruoyi.workflow.domain.WfApprovalCcVo;

/**
 * 抄送记录 Mapper（V5：wf_approval_cc）。
 */
public interface WfApprovalCcMapper {

    int insert(WfApprovalCc cc);

    /** 同一审批单同一被抄送人的记录数（幂等去重） */
    int countByInstanceAndUser(@Param("approvalInstanceId") String approvalInstanceId,
                               @Param("ccUserId") String ccUserId);

    /** 某用户收到的抄送列表（关联审批单 / 审批类型） */
    List<WfApprovalCcVo> selectListByUser(@Param("ccUserId") String ccUserId);

    /** 标记已读（仅本人记录） */
    int markRead(@Param("id") String id, @Param("ccUserId") String ccUserId);
}
