package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfFormDefinition;

/**
 * 表单定义 Mapper（PRD 5.2）。
 */
public interface WfFormDefinitionMapper {

    int insert(WfFormDefinition definition);

    int update(WfFormDefinition definition);

    WfFormDefinition selectById(@Param("id") String id);

    WfFormDefinition selectByCode(@Param("formCode") String formCode);

    List<WfFormDefinition> selectList(WfFormDefinition query);
}
