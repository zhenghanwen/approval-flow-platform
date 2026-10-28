package com.ruoyi.workflow.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfOutboxEvent;

/**
 * 可靠事件 Mapper（PRD 16.3）。
 */
public interface WfOutboxEventMapper {

    int insert(WfOutboxEvent event);

    WfOutboxEvent selectById(@Param("id") String id);

    /** 待投递事件：PENDING 且到下次重试时间（含首次未排期）。 */
    List<WfOutboxEvent> selectDuePending(@Param("now") Date now, @Param("limit") int limit);

    /** 按聚合ID查事件（测试/排查用）。 */
    List<WfOutboxEvent> selectByAggregate(@Param("aggregateId") String aggregateId);

    int update(WfOutboxEvent event);
}
