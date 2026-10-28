package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4 探针：加签 API 与退回 MI 节点在 collection 驱动（规一化产物）下的真实行为。
 *
 * <p>T01 加签：{@code addMultiInstanceExecution(nodeKey, piId, Map.of("wf_assignee_<node>", user))}
 * 在 {@code flowable:collection="wf_users_<node>"} + {@code flowable:elementVariable="wf_assignee_<node>"}
 * + {@code assignee=${wf_assignee_<node>}}（产品规一化产物）下成立 —— 新任务生成、assignee 正确、原有任务不动。</p>
 *
 * <p>T02 直退 collection-MI 空壳：change-state 的 {@code moveActivityIdTo} 直进 collection-MI 目标节点，
 * 生成的是空壳执行树（act_ru_task 无任务、scope 为 inactive），验证方式一（引擎直退 MI 整组重建）
 * 在 Flowable 6.8 下不可用，佐证退回 MI 目标必须走 §3.3.2 方式二（退前置 + 集合变量重写）。</p>
 */
@DisplayName("M4探针-加签(collection-MI)与直退MI空壳")
class S7AddSignAndReturnProbeTest extends SpikeEngineSupport {

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.spike/m4">
          <process id="m4ProbeProcess" name="M4探针">
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="apply"/>
            <userTask id="apply" name="填写申请"/>
            <sequenceFlow id="f1" sourceRef="apply" targetRef="deptApproval"/>
            <userTask id="deptApproval" name="部门审批"/>
            <sequenceFlow id="f2" sourceRef="deptApproval" targetRef="financeApproval"/>
            <userTask id="financeApproval" name="财务审批"/>
            <sequenceFlow id="f3" sourceRef="financeApproval" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    @Test
    @DisplayName("T01 加签: wf_assignee_ 元素变量 + collection-MI 下 addMultiInstanceExecution 成立")
    void addMultiInstanceExecutionWithProductElementVariable() {
        // 部署与启动注入节点专属集合（与产品规一化产物一致）
        deployBpmnContent("m4-addsign-probe.bpmn20.xml", normalizedXml());
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUserId", "applicant-001");
        vars.put("wf_users_apply", List.of("applicant-001"));
        vars.put("wf_users_deptApproval", List.of("approver-001", "approver-002"));
        vars.put("wf_users_financeApproval", List.of("finance-001"));
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("m4ProbeProcess", vars);

        // 完成 apply → deptApproval 并行 MI ×2
        Task applyTask = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("apply").singleResult();
        taskService.complete(applyTask.getId());
        List<Task> before = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("deptApproval").list();
        assertThat(before).hasSize(2);
        List<String> beforeIds = before.stream().map(Task::getId).toList();

        // 加签：元素变量名 = wf_assignee_<nodeKey>（产品命名）
        runtimeService.addMultiInstanceExecution("deptApproval", pi.getId(),
            Map.of("wf_assignee_deptApproval", "zhaoliu"));

        // 新任务生成（3 个），assignee 含 zhaoliu，原有任务 id 不动
        List<Task> after = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("deptApproval").list();
        assertThat(after).hasSize(3);
        assertThat(after).extracting(Task::getAssignee)
            .containsExactlyInAnyOrder("approver-001", "approver-002", "zhaoliu");
        assertThat(after.stream().map(Task::getId).toList()).containsAll(beforeIds);

        // 全员完成 → 流程进入 financeApproval 并最终结束（createTaskQuery 为快照，须两轮查询）
        for (Task t : taskService.createTaskQuery().processInstanceId(pi.getId()).list()) {
            taskService.complete(t.getId());
        }
        for (Task t : taskService.createTaskQuery().processInstanceId(pi.getId()).list()) {
            taskService.complete(t.getId());
        }
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
            .isNull();
    }

    @Test
    @DisplayName("T02 直退 collection-MI: moveActivityIdTo 直进 MI 目标只造空壳（act_ru_task 无任务）")
    void directReturnToCollectionMiCreatesEmptyShell() {
        deployBpmnContent("m4-return-probe.bpmn20.xml", normalizedXml());
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicantUserId", "applicant-002");
        vars.put("wf_users_apply", List.of("applicant-002"));
        vars.put("wf_users_deptApproval", List.of("approver-003", "approver-004"));
        vars.put("wf_users_financeApproval", List.of("finance-002"));
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("m4ProbeProcess", vars);

        // 推进到 financeApproval（deptApproval 全员完成）
        Task applyTask = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("apply").singleResult();
        taskService.complete(applyTask.getId());
        for (Task t : taskService.createTaskQuery().processInstanceId(pi.getId()).list()) {
            taskService.complete(t.getId());
        }
        Task financeTask = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("financeApproval").singleResult();
        assertThat(financeTask).isNotNull();

        // change-state 直退到已完成的 collection-MI 节点（方式一姿势）
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(pi.getId())
            .moveActivityIdTo("financeApproval", "deptApproval")
            .changeState();

        // 空壳实证：deptApproval 无任何运行时任务，但存在 MI scope 执行（流程未死，卡死在壳内）
        List<Task> shellTasks = taskService.createTaskQuery().processInstanceId(pi.getId())
            .taskDefinitionKey("deptApproval").list();
        assertThat(shellTasks).isEmpty();
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(pi.getId()).list();
        long shellScopes = executions.stream()
            .filter(e -> "deptApproval".equals(e.getActivityId()))
            .count();
        assertThat(shellScopes).isGreaterThanOrEqualTo(1);
        System.out.println("=== T02 空壳执行: activityId=deptApproval 的执行数=" + shellScopes
            + "，deptApproval 任务数=0，流程实例存活="
            + runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count() + " ===");
    }

    /** 规一化产物：apply 普通任务 + 其余节点 collection-MI（与产品 BpmnValidationService 同构）。 */
    private byte[] normalizedXml() {
        try {
            javax.xml.stream.XMLStreamReader reader = javax.xml.stream.XMLInputFactory.newFactory()
                .createXMLStreamReader(new java.io.StringReader(BPMN));
            org.flowable.bpmn.model.BpmnModel model =
                new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(reader);
            for (org.flowable.bpmn.model.Process process : model.getProcesses()) {
                for (org.flowable.bpmn.model.FlowElement fe : process.getFlowElements()) {
                    if (fe instanceof org.flowable.bpmn.model.UserTask ut) {
                        if ("apply".equals(ut.getId())) {
                            ut.setAssignee("${applicantUserId}");
                            continue;
                        }
                        String elementVariable = "wf_assignee_" + ut.getId();
                        org.flowable.bpmn.model.MultiInstanceLoopCharacteristics mi =
                            new org.flowable.bpmn.model.MultiInstanceLoopCharacteristics();
                        mi.setSequential(false);
                        mi.setInputDataItem("wf_users_" + ut.getId());
                        mi.setElementVariable(elementVariable);
                        ut.setLoopCharacteristics(mi);
                        ut.setAssignee("${" + elementVariable + "}");
                    }
                }
            }
            return new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(model);
        } catch (Exception e) {
            throw new IllegalStateException("规一化失败", e);
        }
    }
}
