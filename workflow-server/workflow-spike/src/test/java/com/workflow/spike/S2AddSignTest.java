package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S2 加签能力实测（T03/T04/T13）——验证 D1（审批节点统一多实例建模）下的核心 API 路径。
 *
 * <p>流程：申请人(apply) → 财务审批(MI并行，collection 人员列表 + ALL 规则) → 结束。
 * 加签 = 对活跃 MI 节点执行 addMultiInstanceExecution。</p>
 *
 * <p>语义约定（v1 平台规则）：加签仅在并行 ALL 规则下使用；"前加签"（当前任务未完成时加人）
 * 与"后加签"（当前任务已完成部分实例时加人）在引擎层面为同一 API，业务层负责语义区分与记录。</p>
 */
@DisplayName("S2-加签: addMultiInstanceExecution 前加签(T03)/中段加签(T13)/原有任务不动")
class S2AddSignTest extends SpikeEngineSupport {

    private static final String BPMN = "spike-addsign-process.bpmn20.xml";

    @BeforeEach
    void setUp() {
        deployBpmn(BPMN);
    }

    private ProcessInstance startToFinance(List<String> users) {
        ProcessInstance pi = runtimeService.createProcessInstanceBuilder()
            .processDefinitionKey("spikeAddSignProcess")
            .variable("wf_users_financeApproval", users)
            .start();
        taskService.complete(
            taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey("apply").singleResult().getId());
        return pi;
    }

    private List<Task> financeTasks(String piId) {
        return taskService.createTaskQuery()
            .processInstanceId(piId)
            .taskDefinitionKey("financeApproval")
            .list();
    }

    @Test
    @DisplayName("T03 前加签: 2人未完成时加1人 → 3任务(parties各异)，原有任务id不动，全员完成才结束")
    void frontAddSign_appendsInstance() {
        ProcessInstance pi = startToFinance(List.of("lisi", "wangwu"));
        List<Task> before = financeTasks(pi.getId());
        assertThat(before).extracting(Task::getAssignee).containsExactly("lisi", "wangwu");

        // ---- 前加签：加 zhaoliu ----
        runtimeService.addMultiInstanceExecution("financeApproval", pi.getId(), Map.of("wfUser", "zhaoliu"));

        List<Task> after = financeTasks(pi.getId());
        assertThat(after).hasSize(3);
        assertThat(after).extracting(Task::getAssignee)
            .containsExactlyInAnyOrder("lisi", "wangwu", "zhaoliu");

        // 原有两个任务保持原样（不重建、不改 id）
        List<String> beforeIds = before.stream().map(Task::getId).toList();
        assertThat(after).extracting(Task::getId).containsAll(beforeIds);

        // ALL 规则：只完成原来 2 人，流程不能走完（还差 zhaoliu）
        after.stream().filter(t -> !"zhaoliu".equals(t.getAssignee())).forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isEqualTo(1);

        // 加签人完成 → 全部完成 → 结束
        Task last = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(last).isNotNull();
        taskService.complete(last.getId());
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
    }

    @Test
    @DisplayName("T13 中段加签: 已完成1人时加人，已完成实例不重算，补完剩余即结束")
    void midAddSign_completedInstancesPreserved() {
        ProcessInstance pi = startToFinance(List.of("lisi", "wangwu"));
        List<Task> before = financeTasks(pi.getId());
        assertThat(before).hasSize(2);

        // lisi 完成（部分完成状态）
        taskService.complete(before.stream().filter(t -> "lisi".equals(t.getAssignee())).findFirst().orElseThrow().getId());

        // ---- 中段加签：加 zhaoliu ----
        runtimeService.addMultiInstanceExecution("financeApproval", pi.getId(), Map.of("wfUser", "zhaoliu"));

        List<Task> after = financeTasks(pi.getId());
        assertThat(after).hasSize(2);
        assertThat(after).extracting(Task::getAssignee)
            .containsExactlyInAnyOrder("wangwu", "zhaoliu");

        // wangwu 与 zhaoliu 都完成后，流程结束（1已完成 + 2后完成 = 3/3）
        after.forEach(t -> taskService.complete(t.getId()));
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId())
            .singleResult()
            .getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("T04 加签语义复核: 加签后满足 completionCondition 的最后一次完成仍然生效（不遗留影子执行）")
    void addSign_completionConditionStillEvaluatedOnAdded() {
        ProcessInstance pi = startToFinance(List.of("lisi", "wangwu"));
        List<Task> tasks = financeTasks(pi.getId());

        // 加签后立刻由"最后一人"续完：完成 wangwu（原），完成加签 zhaoliu，完成 lisi（通常前加签场景原任务已在位）
        // 顺序：先加签（3人在位），然后依次完成 wangwu、zhaoliu、lisi —— 最后一次完成同时满足 3/3
        runtimeService.addMultiInstanceExecution("financeApproval", pi.getId(), Map.of("wfUser", "zhaoliu"));
        List<Task> all3 = financeTasks(pi.getId());
        assertThat(all3).hasSize(3);
        all3.forEach(t -> taskService.complete(t.getId()));

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        // 历史：3 个财务审批实例全部有结束时点，均有留痕
        historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId())
            .activityId("financeApproval")
            .list()
            .forEach(h -> assertThat(h.getEndTime()).isNotNull());
    }
}
