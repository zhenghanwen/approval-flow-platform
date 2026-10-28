package com.ruoyi.workflow.config;

import javax.sql.DataSource;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flowable 引擎接线（M1）。
 *
 * <p>关键决策：引擎数据源显式绑定 {@code masterDataSource}（原生 Druid 主库），
 * 而不是注入路由用的 {@code dynamicDataSource}（@Primary）——</p>
 * <ul>
 *     <li>流程引擎线程不经过 @DataSource 路由切面，走 dynamic 数据源只会命中默认 target；
 *         显式绑定 master 消除该隐式依赖，并为将来 slave 大读量预留正确语义（引擎读写一律主库）；</li>
 *     <li>引擎自建 ACT_* 表随 master 数据源落 {@code workflow_dev} 库，与 sys_* 同库不同表前缀；</li>
 *     <li>事务仍走 Spring 全局 PlatformTransactionManager（starter 自动装配），
 *         M3 "Mongo 先写 → MySQL 事务（审批单+流程启动+投影）"依赖同一事务上下文。</li>
 * </ul>
 *
 * <p>引擎参数（database-schema-update / async-executor）在 application.yml {@code flowable:} 段，
 * 由 starter 的 FlowableProperties 读取，此处只做数据源/结构性定制。</p>
 */
@Configuration
public class WorkflowEngineConfig {

    /**
     * Flowable starter 会收集容器内所有 EngineConfigurationConfigurer 并作用到引擎构建上。
     *
     * <p>额外钉住 databaseCatalog：JDBC 元数据 isTablePresent 的 catalog 参数来自
     * connectionMetadataDefaultCatalog（starter 路径下为 null），MySQL Connector/J 8
     * 对 null catalog 表示"跨所有库"——同服务器其他库（如 spike 残留的 ACT 表）会造成
     * "表已存在"误判而走 upgrade 分支，撞空库直接崩。显式钉住本库即绕开。</p>
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> workflowEngineConfigurationConfigurer(
        @org.springframework.beans.factory.annotation.Qualifier("masterDataSource") DataSource masterDataSource,
        @org.springframework.beans.factory.annotation.Value("${WF_DB_NAME:workflow_dev}") String dbName) {
        return engineConfiguration -> {
            engineConfiguration.setDataSource(masterDataSource);
            engineConfiguration.setDatabaseCatalog(dbName);
        };
    }
}
