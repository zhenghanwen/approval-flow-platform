package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 退回能力实测（T05/T06）——对应《可行性分析》B0 结论：
 * change-state 可以进出多实例（MI）节点。
 *
 * <p>流程：申请人(apply) → 部门审批(MI×2) → 财务审批(MI×2) → 结束。
 * 审批节点一律按 D1 多实例建模，退回目标均为已完成的多实例节点。</p>
 *
 * <p>每条断言验证：新任务生成 / 旧任务无残留 / 历史留痕 / 流程可继续推进到结束。</p>
 */
@DisplayName("S1-退回: 串行多实例节点互退（T05）+ 退回申请人（T06）")
class S1ReturnTest extends SpikeEngineSupport {

    private static final String BPMN = "spike-return-process.bpmn20.xml";

    @BeforeEach
    void setUp() {
        deployBpmn(BPMN);
    }

    private ProcessInstance startToFinance() {
        ProcessInstance pi = runtimeService.createProcessInstanceBuilder()
            .processDefinitionKey("spikeReturnProcess")
            .variable("wf_n_deptApproval", 2)
            .variable("wf_n_financeApproval", 2)
            .start();
        taskService.complete(taskAt("apply", pi.getId()).getId());
        // 部门审批（MI×2）两个实例全部完成，才能走到财务审批节点
        tasksAt("deptApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        return pi;
    }

    private Task taskAt(String activityId, String piId) {
        return taskService.createTaskQuery()
            .processInstanceId(piId)
            .taskDefinitionKey(activityId)
            .singleResult();
    }

    private List<Task> tasksAt(String activityId, String piId) {
        return taskService.createTaskQuery()
            .processInstanceId(piId)
            .taskDefinitionKey(activityId)
            .list();
    }

    @Test
    @DisplayName("T05: 财务多实例(MI×2) → 退回到部门多实例(MI×2)：新MI根+双任务重建，财务无残留，可再次走完全程")
    void returnFinanceToDept_rebuildsMiTarget() {
        ProcessInstance pi = startToFinance();
        assertThat(tasksAt("financeApproval", pi.getId())).hasSize(2);

        // ---- 执行退回：财务 → 部门 ----
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "deptApproval")
            .changeState();

        // 断言1：目标节点多实例重新生成（2 个实例任务）
        List<Task> deptTasks = tasksAt("deptApproval", pi.getId());
        assertThat(deptTasks).hasSize(2);

        // 断言2：源节点无残留待办
        assertThat(tasksAt("financeApproval", pi.getId())).isEmpty();
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(2);

        // 断言3：财务审批产生历史（旧 MI 实例被取消，留痕）
        List<HistoricActivityInstance> financeHistory = historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId())
            .activityId("financeApproval")
            .list();
        assertThat(financeHistory).isNotEmpty();
        assertThat(financeHistory).allSatisfy(h -> assertThat(h.getEndTime()).isNotNull());

        // 断言4：退回后的部门审批可再次完成 → 财务 → 全部完成 → 结束
        deptTasks.forEach(t -> taskService.complete(t.getId()));
        List<Task> round2Finance = tasksAt("financeApproval", pi.getId());
        assertThat(round2Finance).hasSize(2);
        round2Finance.forEach(t -> taskService.complete(t.getId()));

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult()
            .getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("T05: 多实例部分完成（2 完 1 未完）时退回：活跃实例被取消，退回成功")
    void returnFromPartiallyCompletedMi_cancelsActiveInstances() {
        ProcessInstance pi = startToFinance();

        // 完成财务的两个实例中的第一个（部分完成状态）
        List<Task> financeTasks = tasksAt("financeApproval", pi.getId());
        assertThat(financeTasks).hasSize(2);
        taskService.complete(financeTasks.get(0).getId());

        // ---- 部分完成状态下退回 ----
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "deptApproval")
            .changeState();

        // 无残留财务待办，无残留流程级待办之外的任务
        assertThat(tasksAt("financeApproval", pi.getId())).isEmpty();
        assertThat(tasksAt("deptApproval", pi.getId())).hasSize(2);

        // 被取消的活跃实例同样有历史结束时间
        List<HistoricActivityInstance> financeHistory = historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId())
            .activityId("financeApproval")
            .list();
        assertThat(financeHistory).allSatisfy(h -> assertThat(h.getEndTime()).isNotNull());

        // 之后仍可走完全程
        tasksAt("deptApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        tasksAt("financeApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
    }

    @Test
    @DisplayName("T06: 财务多实例 → 退回申请人节点（非多实例）：申请人重新获得可编辑任务")
    void returnFinanceToApplicantNode() {
        ProcessInstance pi = startToFinance();

        // ---- 执行退回：财务 → 申请人 ----
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "apply")
            .changeState();

        // 断言1：申请人节点重新生成任务并指回原申请人
        Task applyTask = taskAt("apply", pi.getId());
        assertThat(applyTask).isNotNull();
        assertThat(applyTask.getAssignee()).isEqualTo("applicant");

        // 断言2：财务无残留
        assertThat(tasksAt("financeApproval", pi.getId())).isEmpty();
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);

        // 断言3：改完申请（重新提交）后可以一路走完
        taskService.complete(applyTask.getId());
        assertThat(tasksAt("deptApproval", pi.getId())).hasSize(2);
        tasksAt("deptApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        tasksAt("financeApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
    }

    @Test
    @DisplayName("已知缺陷哨兵: 同构 MI（相同cardinality字面量）之间退回 → 空壳MI根（issue #3944，升级引擎时自动报警重估）")
    void knownLimitation_returnBetweenIdenticallyConfiguredMi_shellsOut() {
        deployBpmn("spike-return-sameconfig.bpmn20.xml");
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeSameConfigMiProcess");
        taskService.complete(taskAt("apply", pi.getId()).getId());
        tasksAt("deptApproval", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        assertThat(tasksAt("financeApproval", pi.getId())).hasSize(2);

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "deptApproval")
            .changeState();

        // 引擎 bug：目标只剩空壳 MI 根（0 子执行/0 任务），流程卡死不结束
        // —— 这正是规一化门禁强制"每节点独立 cardinality 变量"的原因。
        // 若未来升级修复本缺陷（本断言翻绿为正常建任务），需重估规一化规则：
        List<Execution> execs = runtimeService.createExecutionQuery().processInstanceId(pi.getId()).list();
        assertThat(execs.stream().filter(e -> "deptApproval".equals(e.getActivityId()))
            .count()).isGreaterThanOrEqualTo(1);
        assertThat(tasksAt("deptApproval", pi.getId())).isEmpty();
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);
    }
}
