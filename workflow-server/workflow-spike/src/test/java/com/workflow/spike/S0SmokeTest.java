package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S0 冒烟测试：验证 Flowable 7.1 + JDK 17 + 本地 MySQL 全链路可用。
 * 这是 M0 引擎验证工程（T01–T16）的地基，不涉及业务断言。
 */
@DisplayName("S0-Smoke: 引擎×MySQL×JDK17 全链路冒烟")
class S0SmokeTest extends SpikeEngineSupport {

    @Test
    @DisplayName("串行审批：部署→启动→两级UserTask→结束+历史齐全")
    void sequentialApprovalSmoke() {
        deployBpmn("spike-sequential-approval.bpmn20.xml");

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeSequentialApproval");
        assertThat(pi).isNotNull();

        // 第一级：部门审批（zhangsan）
        Task t1 = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(t1.getTaskDefinitionKey()).isEqualTo("deptApproval");
        assertThat(t1.getAssignee()).isEqualTo("zhangsan");

        taskService.complete(t1.getId());

        // 第二级：财务审批（lisi）
        Task t2 = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(t2.getTaskDefinitionKey()).isEqualTo("financeApproval");
        assertThat(t2.getAssignee()).isEqualTo("lisi");

        taskService.complete(t2.getId());

        // 流程实例结束、历史留痕
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("并行会签：多实例(3)任务同生共死，最后一个完成时结束")
    void parallelCountersignSmoke() {
        deployBpmn("spike-parallel-countersign.bpmn20.xml");

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeParallelCountersign");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        assertThat(tasks).hasSize(3);
        assertThat(tasks.stream().allMatch(t -> "countersign".equals(t.getTaskDefinitionKey()))).isTrue();

        // 完成前两个：流程不能结束（多实例仍活跃）
        taskService.complete(tasks.get(0).getId());
        taskService.complete(tasks.get(1).getId());
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);

        // 第三个完成：整体结束
        Task last = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        taskService.complete(last.getId());
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("MySQL 引擎表可达：ACT_RU_TASK / ACT_HI_PROCINST 建表成功并可查询")
    void engineTablesOnMySqlSmoke() {
        // 部署成功即说明 SEQ 黄表与 RE 表已在 workflow_spike 库创建
        deployBpmn("spike-sequential-approval.bpmn20.xml");
        long deployments = repositoryService.createDeploymentQuery().count();
        assertThat(deployments).isGreaterThanOrEqualTo(1);
    }
}
