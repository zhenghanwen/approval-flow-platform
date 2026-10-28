package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfTaskAddSign;

/**
 * 任务加签记录 Mapper（PRD 11.2）。
 */
public interface WfTaskAddSignMapper {

    int insert(WfTaskAddSign addSign);

    List<WfTaskAddSign> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
