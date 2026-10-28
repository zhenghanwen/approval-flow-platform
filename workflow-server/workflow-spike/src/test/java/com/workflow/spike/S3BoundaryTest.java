package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S3 白名单边界实测（T14b/T15）——退回目标"禁区清单"的引擎行为锚点。
 *
 * <p>对应《可行性分析》B1/B2 结论，给 BPMN 校验规一化门禁提供规则库：</p>
 * <ul>
 *     <li>B2/T15：change-state 的目标若为网关，引擎【静默无操作】（不挪、不报错）；
 *         平台必须在服务层白名单拦截，不能依赖引擎异常。</li>
 *     <li>B1/T14b：并行网关某单支内部退回/跨支退回，引擎会产生幽灵执行导致合并网关永久挂起；
 *         平台禁止单支跨支退回。</li>
 *     <li>合法路径：并行区块整体收回退回必须用 moveExecutionsToSingleActivityId 收集全部活跃分支执行。</li>
 * </ul>
 *
 * <p>哨兵语义：这些测试断言的是【引擎当前的具体缺陷行为】，
 * 若升级 Flowable 后行为变化（如改为抛异常或正确迁移），本类测试会红，届时重估服务层规则。</p>
 */
@DisplayName("S3-白名单边界: 网关目标(T15)/跨支幽灵执行(T14b)/并行整区收回(合法)")
class S3BoundaryTest extends SpikeEngineSupport {

    private static final String GW_BPMN = "spike-gateway-process.bpmn20.xml";
    private static final String PARALLEL_BPMN = "spike-parallel-branches.bpmn20.xml";

    @BeforeEach
    void setUp() {
        deployBpmn(GW_BPMN);
        deployBpmn(PARALLEL_BPMN);
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

    private void completePrepAndAssertBranches(ProcessInstance pi) {
        taskService.complete(taskAt("prep", pi.getId()).getId());
        assertThat(taskAt("b1", pi.getId())).isNotNull();
        assertThat(taskAt("b2", pi.getId())).isNotNull();
    }

    @Test
    @DisplayName("T15: 目标=排他网关 → 引擎按出线条件重走一圈（任务重开、id 变化）——服务层必须白名单拦截")
    void gatewayTarget_rewalksGatewayAndResetsTask() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeGatewayProcess", null, Map.of("level", "B"));
        taskService.complete(taskAt("apply", pi.getId()).getId());
        assertThat(taskAt("financeApproval", pi.getId())).isNotNull();
        String originalTaskId = taskAt("financeApproval", pi.getId()).getId();

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "gwSplit")
            .changeState();

        // 引擎真实行为：执行被挪到网关后按条件(level=B)重走 → 回到 financeApproval、原任务重建为新 id
        // —— "退回到网关"实际效果 = 当前节点原地重开且丢失原任务，业务上无意义且危险，服务层拦截
        Task stillThere = taskAt("financeApproval", pi.getId());
        assertThat(stillThere).isNotNull();
        assertThat(stillThere.getId()).isNotEqualTo(originalTaskId);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T14b: 并行双支活跃时跨支退回 b2→b1 → b1 幽灵复制×2、b2 执行被吞、join 永久挂起——服务层禁止")
    void crossBranchReturn_dublesTargetAndHangsJoin() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeParallelBranches");
        completePrepAndAssertBranches(pi);

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("b2", "b1")
            .changeState();

        // 幽灵复制：b1 出现 2 个任务，b2 空了
        assertThat(tasksAt("b1", pi.getId())).hasSize(2);
        assertThat(tasksAt("b2", pi.getId())).isEmpty();

        // 全部完成后 join 依旧挂起（实例无法结束）——缺陷行为锚点
        tasksAt("b1", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T14b: 支线一已完成后单执行跨支退回 → join 依旧永久挂起——服务层禁止")
    void crossBranchReturnAfterSiblingDone_alsoHangsJoin() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeParallelBranches");
        completePrepAndAssertBranches(pi);
        taskService.complete(taskAt("b1", pi.getId()).getId()); // 支线一通过，join 等支线二
        assertThat(taskAt("b2", pi.getId())).isNotNull();

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("b2", "b1")
            .changeState();

        // 移动完成后存在 b1 任务 + join 上的挂起分支执行；完成全部任务实例也无法结束
        assertThat(tasksAt("b1", pi.getId()).size()).isGreaterThanOrEqualTo(1);
        tasksAt("b1", pi.getId()).forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("合法路径: 并行整区收回 moveExecutionsToSingleActivityId(全部活跃分支执行→fork前置节点) → 可完整走完")
    void wholeParallelBlockCollapse_isLegitAndClean() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeParallelBranches");
        completePrepAndAssertBranches(pi);
        taskService.complete(taskAt("b1", pi.getId()).getId()); // 支线一先走一步，制造部分完成态（含 join 挂起执行）更贴近真实

        // 合规口径：收集【流程实例全部子执行】（含 join 上的挂起执行），整体收回 prep
        // —— 只收分支节点执行会漏掉 join 挂起执行，留下幽灵 join 计数（本测试的开发教训，见 git 历史）
        List<String> allChildExecutionIds = new ArrayList<>();
        runtimeService.createExecutionQuery().processInstanceId(pi.getId()).list()
            .forEach(e -> {
                if (!e.getId().equals(pi.getId())) {
                    allChildExecutionIds.add(e.getId());
                }
            });
        assertThat(allChildExecutionIds).hasSize(2); // join 挂起执行 + b2 活跃执行

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveExecutionsToSingleActivityId(allChildExecutionIds, "prep")
            .changeState();

        // 回到前置任务：干净重建 single 任务，fork 分支与 join 挂起执行全部清空
        assertThat(taskAt("prep", pi.getId())).isNotNull();
        assertThat(tasksAt("b1", pi.getId())).isEmpty();
        assertThat(tasksAt("b2", pi.getId())).isEmpty();
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);

        // 全流程可干净走完：prep → fork(重开) → b1+b2 → join → afterJoin → end
        taskService.complete(taskAt("prep", pi.getId()).getId());
        assertThat(taskAt("b1", pi.getId())).isNotNull();
        assertThat(taskAt("b2", pi.getId())).isNotNull();
        taskService.complete(taskAt("b1", pi.getId()).getId());
        taskService.complete(taskAt("b2", pi.getId()).getId());
        assertThat(taskAt("afterJoin", pi.getId())).isNotNull();
        taskService.complete(taskAt("afterJoin", pi.getId()).getId());

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult()
            .getEndTime()).isNotNull();
    }
}
