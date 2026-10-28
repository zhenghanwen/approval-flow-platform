package com.ruoyi.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.workflow.domain.WfExternalApiLog;

/**
 * 外部接口调用日志 Mapper（PRD 8.2）。
 */
public interface WfExternalApiLogMapper {

    int insert(WfExternalApiLog log);

    List<WfExternalApiLog> selectByApiId(@Param("apiId") String apiId);

    List<WfExternalApiLog> selectByApprovalInstance(@Param("approvalInstanceId") String approvalInstanceId);
}
