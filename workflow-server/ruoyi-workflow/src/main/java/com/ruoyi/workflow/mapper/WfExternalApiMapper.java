package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfExternalApi;

/**
 * 外部接口配置 Mapper（PRD 8.1）。
 */
public interface WfExternalApiMapper {

    int insert(WfExternalApi api);

    int update(WfExternalApi api);

    WfExternalApi selectById(@Param("id") String id);

    WfExternalApi selectByCode(@Param("apiCode") String apiCode);

    List<WfExternalApi> selectList(WfExternalApi query);
}
