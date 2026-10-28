package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfFormVersion;

/**
 * 表单版本 Mapper（PRD 5.3）。
 */
public interface WfFormVersionMapper {

    int insert(WfFormVersion version);

    int update(WfFormVersion version);

    WfFormVersion selectById(@Param("id") String id);

    List<WfFormVersion> selectByFormId(@Param("formId") String formId);

    WfFormVersion selectByFormAndVersion(@Param("formId") String formId, @Param("versionNo") Integer versionNo);

    /**
     * 取某表单下已发布的最大版本号（无则 null）。
     */
    Integer selectMaxPublishedVersion(@Param("formId") String formId);
}
