package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfTaskTransfer;

/**
 * 任务转交记录 Mapper（PRD 11.1）。
 */
public interface WfTaskTransferMapper {

    int insert(WfTaskTransfer transfer);

    List<WfTaskTransfer> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
