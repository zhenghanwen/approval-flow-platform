package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfTaskReturn;

/**
 * 任务退回记录 Mapper（PRD 11.3）。
 */
public interface WfTaskReturnMapper {

    int insert(WfTaskReturn taskReturn);

    List<WfTaskReturn> selectByInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
