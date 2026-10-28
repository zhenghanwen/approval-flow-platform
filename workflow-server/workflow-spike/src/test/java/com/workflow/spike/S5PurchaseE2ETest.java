package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S5 采购审批贯穿案例 —— 一个真实场景串起 S1–S4 全部实测规则：
 *
 * <ul>
 *     <li>申请（无 MI，Numbercardinality 注入由规一化负责——本流程直接演示混合形态）</li>
 *     <li>会签：节点专属 collection（wf_users_counterSign）+ 并行 ALL 规则（N3 形态，S2 实证）</li>
 *     <li>排他网关金额分流（小单直达结束，大单进财务 MI）</li>
 *     <li>财务 MI：独立变量 cardinality ${wf_n_financeApproval}（N2 形态，S1 实证）</li>
 *     <li>退回：只允许【合法姿势】整区收回（S3 实证：全部子执行含挂起执行）</li>
 *     <li>撤回：deleteProcessInstance（S4 五项核对语义）+ 全程投影对账（S4 InMemoryTaskProjection）</li>
 * </ul>
 */
@DisplayName("S5-采购贯穿: T21 正常流转 / T22 会签中段合法退回 / T23 撤回+投影对账")
class S5PurchaseE2ETest extends SpikeEngineSupport {

    private static final String BPMN = "spike-purchase-process.bpmn20.xml";

    private static final InMemoryTaskProjection PROJECTION = new InMemoryTaskProjection();

    @BeforeAll
    static void setUpClass() {
        runtimeService.addEventListener(PROJECTION);
    }

    @AfterAll
    static void tearDownClass() {
        try {
            runtimeService.removeEventListener(PROJECTION);
        } catch (Exception ignored) {
            // engine 关闭时 remove 可能为空操作，容忍
        }
    }

    @BeforeEach
    void setUp() {
        PROJECTION.reset();
        deployBpmn(BPMN);
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

    private ProcessInstance startPurchase(long amount, List<String> signers) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("wf_users_counterSign", signers);
        vars.put("wf_n_financeApproval", 1);
        return runtimeService.startProcessInstanceByKey("spikePurchaseProcess", null, vars);
    }

    private void completeAllAt(String activityId, String piId) {
        tasksAt(activityId, piId).forEach(t -> taskService.complete(t.getId()));
    }

    private void assertCleanEnd(ProcessInstance pi) {
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId()).singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
        assertThat(hpi.getDeleteReason()).isNull();
        // 投影对账：全部 COMPLETED/REMOVED，无 RUNNING 残留
        assertThat(PROJECTION.runningIds()).isEmpty();
        assertThat(PROJECTION.lastProcessEndState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("T21: 正常流转贯穿 —— 小单会签完直达结束 / 大单再走财务")
    void purchaseNormalFlow() {
        // 小单：amount < 50000，会签完直接结束
        ProcessInstance small = startPurchase(8_000, List.of("u1", "u2"));
        assertThat(taskAt("apply", small.getId())).isNotNull();
        PROJECTION.reset();
        completeAllAt("apply", small.getId());
        List<Task> signTasks = tasksAt("counterSign", small.getId());
        assertThat(signTasks).hasSize(2); // collection 驱动并行 MI，2 实例
        signTasks.forEach(t -> taskService.complete(t.getId()));
        assertCleanEnd(small); // 网关按条件直达 end

        // 大单：amount >= 50000，会签完进财务审批
        ProcessInstance big = startPurchase(120_000, List.of("uA", "uB"));
        completeAllAt("apply", big.getId());
        signTasks = tasksAt("counterSign", big.getId());
        assertThat(signTasks).hasSize(2);
        signTasks.forEach(t -> taskService.complete(t.getId()));
        assertThat(taskAt("financeApproval", big.getId())).isNotNull(); // 独立变量 cardinality=1 实例
        completeAllAt("financeApproval", big.getId());
        assertCleanEnd(big);
    }

    @Test
    @DisplayName("T22: 会签完成1人后整区退回到申请 —— MI节点级 moveActivityIdTo 是合法姿势，重走全流程干净")
    void purchaseReturnToApplyViaLegalCollapse() {
        ProcessInstance pi = startPurchase(120_000, List.of("uA", "uB"));
        completeAllAt("apply", pi.getId());
        List<Task> signTasks = tasksAt("counterSign", pi.getId());
        assertThat(signTasks).hasSize(2);
        taskService.complete(signTasks.get(0).getId()); // 会签完成 1/2

        // S5 新实证（MI 专属口径，与 S3 普通并行网关的"全部子执行"口径不同）：
        // 并行 MI 的执行树是 根→MI根执行→实例执行×n；把【MI根执行】塞进
        // moveExecutionsToSingleActivityId 会撞 act_ru_task 外键（删序缺陷，引擎不报业务错误而抛 SQL 错）。
        // MI 部分完成态整区收回的合法姿势 = 节点级 moveActivityIdTo —— 引擎正确取消 MI 全部分支并重建上游节点。
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("counterSign", "apply")
            .changeState();

        // 回到申请人，重建单任务
        assertThat(taskAt("apply", pi.getId())).isNotNull();
        assertThat(tasksAt("counterSign", pi.getId())).isEmpty();

        // 重走：申请 → 会签(重新 2 实例) → 网关 → 财务 → 完
        completeAllAt("apply", pi.getId());
        signTasks = tasksAt("counterSign", pi.getId());
        assertThat(signTasks).hasSize(2); // collection 变量仍在，重新进入会签重建
        signTasks.forEach(t -> taskService.complete(t.getId()));
        assertThat(taskAt("financeApproval", pi.getId())).isNotNull();
        completeAllAt("financeApproval", pi.getId());
        assertCleanEnd(pi);
    }

    @Test
    @DisplayName("T23: 大单财务审批中申请人撤回 —— 运行清零/历史留痕/投影无 RUNNING 残留")
    void purchaseWithdrawMidway() {
        ProcessInstance pi = startPurchase(120_000, List.of("uA", "uB"));
        completeAllAt("apply", pi.getId());
        completeAllAt("counterSign", pi.getId());
        Task finance = taskAt("financeApproval", pi.getId());
        assertThat(finance).isNotNull();

        PROJECTION.reset(); // 对齐 S4 语义：本测试只从撤回时刻起核对
        runtimeService.deleteProcessInstance(pi.getId(), "申请人撤回大单（S5 贯穿撤回）");

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isZero();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId()).singleResult();
        assertThat(hpi.getEndTime()).isNotNull();
        assertThat(hpi.getDeleteReason()).contains("撤回");

        assertThat(PROJECTION.runningIds()).isEmpty();
        assertThat(PROJECTION.lastProcessEndState()).isEqualTo("CANCELLED");
        assertThat(PROJECTION.stateOf(finance.getId())).isEqualTo("REMOVED");
    }
}
