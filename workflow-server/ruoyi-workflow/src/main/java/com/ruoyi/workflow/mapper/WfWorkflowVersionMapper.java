package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfWorkflowVersion;

/**
 * 流程版本 Mapper（PRD 5.6）。
 */
public interface WfWorkflowVersionMapper {

    int insert(WfWorkflowVersion version);

    int update(WfWorkflowVersion version);

    WfWorkflowVersion selectById(@Param("id") String id);

    List<WfWorkflowVersion> selectByWorkflowId(@Param("workflowId") String workflowId);

    WfWorkflowVersion selectByWorkflowAndVersion(@Param("workflowId") String workflowId,
                                                 @Param("versionNo") Integer versionNo);

    /**
     * 取某流程定义下已发布的最大版本号（无则 null）。
     */
    Integer selectMaxPublishedVersion(@Param("workflowId") String workflowId);
}
