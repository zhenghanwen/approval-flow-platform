package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S4 撤回 + 投影一致性实测（T07/T12）。
 *
 * <p>T07：撤回 = deleteProcessInstance（申请人撤回场景），五项核对：
 * ①运行实例清零 ②运行任务清零 ③历史实例留痕（endTime+deleteReason）
 * ④历史任务全部留痕 ⑤旧任务句柄操作必抛（无僵尸）。</p>
 *
 * <p>T12：wf_approval_task 投影雏形 —— 引擎事件监听同步内存投影，
 * 验证"完成/撤回后投影与引擎一致"的事件同步方案先例（M3 平移到 MySQL 投影表）。</p>
 */
@DisplayName("S4-撤回+投影: T07 撤回五项核对 / T12 投影事件同步一致性")
class S4CancelAndProjectionTest extends SpikeEngineSupport {

    private static final String BPMN = "spike-sequential-approval.bpmn20.xml";

    /** T12 雏形：wf_approval_task 的内存镜像（ taskId → RUNNING/COMPLETED/REMOVED ）。 */
    static final InMemoryTaskProjection PROJECTION = new InMemoryTaskProjection();    @BeforeAll
    static void registerListener() {
        // JUnit5 语义：父类 @BeforeAll 先于子类执行 → 引擎与 runtimeService 此处必然已就绪
        // （引擎按测试类独立构建，注册只影响本类，无跨类串扰）
        runtimeService.addEventListener(PROJECTION);
    }

    @BeforeEach
    void setUp() {
        PROJECTION.reset();
        deployBpmn(BPMN);
    }

    private ProcessInstance start() {
        return runtimeService.startProcessInstanceByKey("spikeSequentialApproval");
    }

    private Task taskAt(String activityId, String piId) {
        return taskService.createTaskQuery()
            .processInstanceId(piId)
            .taskDefinitionKey(activityId)
            .singleResult();
    }

    // ============================ T07 撤回 ============================

    @Test
    @DisplayName("T07: 撤回(deleteProcessInstance) 五项核对")
    void withdrawFiveChecks() {
        ProcessInstance pi = start();
        Task dept = taskAt("deptApproval", pi.getId());
        taskService.complete(dept.getId()); // 走到财务
        Task finance = taskAt("financeApproval", pi.getId());
        assertThat(finance).isNotNull();

        String reason = "申请人自行撤回（T07 五项核对）";
        runtimeService.deleteProcessInstance(pi.getId(), reason);

        // ① 运行实例清零
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();

        // ② 运行任务清零（无残留待办）
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(runtimeService.createExecutionQuery().processInstanceId(pi.getId()).count()).isZero();

        // ③ 历史实例留痕：endTime + deleteReason
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
        assertThat(hpi.getDeleteReason()).contains("撤回");

        // ④ 历史任务全部留痕（已办+未办一律有结束时点）
        List<HistoricTaskInstance> hTasks = historyService.createHistoricTaskInstanceQuery()
            .processInstanceId(pi.getId())
            .list();
        assertThat(hTasks).extracting(HistoricTaskInstance::getEndTime).isNotEmpty();
        assertThat(hTasks).allSatisfy(t -> assertThat(t.getEndTime()).isNotNull());

        // ⑤ 旧任务句柄续操作必抛（无僵尸任务）
        Assertions.assertThrows(FlowableObjectNotFoundException.class, () -> taskService.complete(finance.getId()));
    }

    // ============================ T12 投影 ============================

    @Test
    @DisplayName("T12a: 正常流转过程中投影与引擎一致（创建/完成事件同步）")
    void projection_tracksNormalLifecycle() {
        ProcessInstance pi = start();

        Task dept = taskAt("deptApproval", pi.getId());
        assertThat(PROJECTION.stateOf(dept.getId())).isEqualTo("RUNNING");

        taskService.complete(dept.getId());
        assertThat(PROJECTION.stateOf(dept.getId())).isEqualTo("COMPLETED");
        Task finance = taskAt("financeApproval", pi.getId());
        assertThat(PROJECTION.stateOf(finance.getId())).isEqualTo("RUNNING");

        taskService.complete(finance.getId());

        // 对账函数：投影_RUNNING 集合 == 引擎 ACT_RU_TASK 活跃集合（此刻都应为空）
        assertThat(PROJECTION.runningIds())
            .containsExactlyInAnyOrderElementsOf(activeEngineTaskIds(pi.getId()));
        assertThat(PROJECTION.runningIds()).isEmpty();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
        assertThat(PROJECTION.lastProcessEndState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("T12b: 撤回瞬间投影同步（REMOVED），对账函数无差异")
    void projection_tracksWithdraw() {
        ProcessInstance pi = start();
        Task dept = taskAt("deptApproval", pi.getId());

        runtimeService.deleteProcessInstance(pi.getId(), "撤回后投影同步核对");

        // 引擎运行任务空
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isZero();
        // 投影：先前 RUNNING 的任务全部转为 REMOVED，无 RUNNING 残留
        assertThat(PROJECTION.stateOf(dept.getId())).isEqualTo("REMOVED");
        assertThat(PROJECTION.runningIds()).isEmpty();
        assertThat(PROJECTION.lastProcessEndState()).isEqualTo("CANCELLED");
    }

    private List<String> activeEngineTaskIds(String piId) {
        return taskService.createTaskQuery().processInstanceId(piId).list().stream()
            .map(Task::getId)
            .toList();
    }

    // （T12 投影实现已抽取为独立类 InMemoryTaskProjection.java，S4/S5 共用）
}