package com.ruoyi.workflow.domain;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 流程版本实体（PRD 5.6：wf_workflow_version）。
 */
public class WfWorkflowVersion {

    /** 流程版本ID */
    private String id;

    /** 流程业务定义ID */
    private String workflowId;

    /** 版本号 */
    private Integer versionNo;

    /** Flowable 部署ID */
    private String flowableDeploymentId;

    /** Flowable 流程定义ID */
    private String processDefinitionId;

    /** BPMN 流程定义Key */
    private String processDefinitionKey;

    /** BPMN 内容摘要（SHA-256 hex） */
    private String bpmnHash;

    /** 节点配置和扩展元数据（JSON 字符串） */
    private String configJson;

    /** 状态：DRAFT / PUBLISHED / ARCHIVED */
    private String status;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date publishedAt;

    /** 发布人 */
    private String publishedBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getFlowableDeploymentId() {
        return flowableDeploymentId;
    }

    public void setFlowableDeploymentId(String flowableDeploymentId) {
        this.flowableDeploymentId = flowableDeploymentId;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public void setProcessDefinitionId(String processDefinitionId) {
        this.processDefinitionId = processDefinitionId;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String getBpmnHash() {
        return bpmnHash;
    }

    public void setBpmnHash(String bpmnHash) {
        this.bpmnHash = bpmnHash;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Date getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Date publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
