package com.ruoyi.workflow.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 接线（M2）。
 *
 * <p>与 Flowable 同原则：迁移数据源显式绑定 {@code masterDataSource}（原生 Druid 主库），
 * 不依赖 @Primary 的 {@code dynamicDataSource} 路由——迁移线程同样不经 @DataSource 切面，
 * 显式绑定消除"路由默认命中 master"的隐式依赖。</p>
 *
 * <p>职责边界（D8）：Flyway 只托管 {@code wf_*} / {@code approval_*} 新业务表；
 * ACT_* 由 Flowable 引擎自建（database-schema-update）、sys_* 走基线 SQL。</p>
 */
@Configuration
public class WorkflowFlywayConfig {

    /**
     * Spring Boot Flyway 自动配置会收集所有 FlywayConfigurationCustomizer 作用到 FluentConfiguration 上。
     */
    @Bean
    public FlywayConfigurationCustomizer workflowFlywayConfigurationCustomizer(
        @Qualifier("masterDataSource") DataSource masterDataSource) {
        return configuration -> configuration.dataSource(masterDataSource);
    }
}
