package com.workflow.spike;

import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * M0 Spike 测试基座：独立 Flowable 引擎（非 Spring 装配），直连本地 MySQL 的 workflow_spike 库。
 *
 * <p>凭证来源（优先级）：环境变量 &gt; 本文件默认值。凭证默认值不提交真实密码到 git，
 * 本地用环境变量 WF_DB_USERNAME / WF_DB_PASSWORD 覆盖，或在 application-local.properties 配置。</p>
 *
 * <p>数据库 schema 由引擎自动创建（databaseSchemaUpdate=true），测试库可随意重建。</p>
 */
public abstract class SpikeEngineSupport {

    protected static ProcessEngine engine;
    protected static RuntimeService runtimeService;
    protected static TaskService taskService;
    protected static RepositoryService repositoryService;
    protected static HistoryService historyService;

    @BeforeAll
    static void startEngine() {
        ProcessEngineConfiguration cfg = ProcessEngineConfiguration
            .createStandaloneProcessEngineConfiguration()
            .setJdbcUrl(SpikeDbConfig.url())
            .setJdbcUsername(SpikeDbConfig.username())
            .setJdbcPassword(SpikeDbConfig.password())
            .setJdbcDriver("com.mysql.cj.jdbc.Driver")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setHistoryLevel(HistoryLevel.FULL)
            .setAsyncExecutorActivate(false);
        engine = cfg.buildProcessEngine();
        runtimeService = engine.getRuntimeService();
        taskService = engine.getTaskService();
        repositoryService = engine.getRepositoryService();
        historyService = engine.getHistoryService();
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    /** 部署 classpath:/bpmn/ 下的资源（按文件名）。 */
    protected static void deployBpmn(String resource) {
        repositoryService.createDeployment()
            .addClasspathResource("bpmn/" + resource)
            .deploy();
    }

    /** 部署内存中的 BPMN XML 内容（BpmnNormalizer 输出的规一化产物走这条通道，需与 classpath 资源同效）。 */
    protected static void deployBpmnContent(String resourceName, byte[] xmlContent) {
        repositoryService.createDeployment()
            .addBytes(resourceName, xmlContent)
            .deploy();
    }
}
