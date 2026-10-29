package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.domain.WfApprovalCc;
import com.ruoyi.workflow.domain.WfApprovalCcVo;
import com.ruoyi.workflow.mapper.WfApprovalCcMapper;

/**
 * 抄送服务（P7「抄送我的」）。
 *
 * <p>提交审批时由 {@link ApprovalSubmissionService} 调 {@link #addCc} 落抄送记录；
 * 被抄送人在「抄送我的」列表可见，打开详情后标记已读。</p>
 */
@Service
public class ApprovalCcService {

    private final WfApprovalCcMapper ccMapper;

    public ApprovalCcService(WfApprovalCcMapper ccMapper) {
        this.ccMapper = ccMapper;
    }

    /**
     * 为审批单落抄送记录（同一审批单同一用户去重，返回实际写入条数）。
     *
     * @param instanceId 审批单ID
     * @param ccByUserId 抄送发起人（提交人）
     * @param ccUserIds  被抄送用户ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public int addCc(String instanceId, String ccByUserId, List<String> ccUserIds) {
        if (ccUserIds == null || ccUserIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String ccUserId : ccUserIds) {
            if (ccUserId == null || ccUserId.isBlank()) {
                continue;
            }
            if (ccMapper.countByInstanceAndUser(instanceId, ccUserId) > 0) {
                continue;
            }
            WfApprovalCc cc = new WfApprovalCc();
            cc.setId(UUID.randomUUID().toString());
            cc.setApprovalInstanceId(instanceId);
            cc.setCcUserId(ccUserId);
            cc.setCcByUserId(ccByUserId);
            cc.setReadFlag(Boolean.FALSE);
            cc.setCreatedAt(new Date());
            ccMapper.insert(cc);
            count++;
        }
        return count;
    }

    /** 我的抄送列表 */
    public List<WfApprovalCcVo> listMine(String userId) {
        return ccMapper.selectListByUser(userId);
    }

    /** 标记已读（仅本人记录） */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String id, String userId) {
        int updated = ccMapper.markRead(id, userId);
        if (updated == 0) {
            throw new ServiceException("抄送记录不存在");
        }
    }
}
