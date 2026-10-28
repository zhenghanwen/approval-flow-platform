package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M3 探针：collection 驱动的多实例规一化，model 序列化 → 部署 → 运行时 assignee 是否成立。
 *
 * <p>目的：确认 Flowable 6.8 的 {@code convertToXML} 对
 * {@code setInputDataItem(var) + setElementVariable(el) + setAssignee("${el}")}
 * 的产物可被引擎部署，且启动注入 {@code wf_users_<id>} 集合后各实例办理人正确。</p>
 */
@DisplayName("M3探针-collection驱动多实例序列化与运行时")
class S6CollectionProbeTest extends SpikeEngineSupport {

    private static final String PLAIN_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.spike/m3">
          <process id="m3ProbeProcess" name="M3探针">
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="apply"/>
            <userTask id="apply" name="填写申请"/>
            <sequenceFlow id="f1" sourceRef="apply" targetRef="deptApproval"/>
            <userTask id="deptApproval" name="部门审批"/>
            <sequenceFlow id="f2" sourceRef="deptApproval" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    @Test
    @DisplayName("规一化产物可部署且运行时 assignee 正确")
    void normalizedModelDeploysAndAssigns() throws Exception {
        // 1. 解析 + 规一化（与 BpmnValidationService.ensureMultiInstance 同构）
        BpmnModel model = parse(PLAIN_BPMN);
        for (Process process : model.getProcesses()) {
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof UserTask ut) {
                    String elementVariable = "wf_assignee_" + ut.getId();
                    MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
                    mi.setSequential(false);
                    mi.setInputDataItem("wf_users_" + ut.getId());
                    mi.setElementVariable(elementVariable);
                    ut.setLoopCharacteristics(mi);
                    ut.setAssignee("${" + elementVariable + "}");
                }
            }
        }
        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        String normalized = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("=== NORMALIZED XML ===");
        System.out.println(normalized);

        // 2. 部署
        repositoryService.createDeployment().addBytes("m3-probe.bpmn20.xml", xml).deploy();

        // 3. 启动注入节点专属集合
        Map<String, Object> vars = new HashMap<>();
        vars.put("wf_users_apply", List.of("applicant-001"));
        vars.put("wf_users_deptApproval", List.of("approver-001", "approver-002"));
        String piId = runtimeService.startProcessInstanceByKey("m3ProbeProcess", vars).getId();

        // 4. 校验 apply 任务（单实例，办理人=applicant-001）
        List<Task> applyTasks = taskService.createTaskQuery()
            .processInstanceId(piId).taskDefinitionKey("apply").list();
        assertThat(applyTasks).hasSize(1);
        assertThat(applyTasks.get(0).getAssignee()).isEqualTo("applicant-001");

        // 5. 完成 apply，进入 deptApproval（并行 MI ×2，办理人各自正确）
        taskService.complete(applyTasks.get(0).getId());
        List<Task> deptTasks = taskService.createTaskQuery()
            .processInstanceId(piId).taskDefinitionKey("deptApproval").list();
        assertThat(deptTasks).hasSize(2);
        assertThat(deptTasks).extracting(Task::getAssignee)
            .containsExactlyInAnyOrder("approver-001", "approver-002");

        // 6. 全部完成后流程结束
        for (Task t : taskService.createTaskQuery().processInstanceId(piId).list()) {
            taskService.complete(t.getId());
        }
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(piId).singleResult())
            .isNull();
    }

    private BpmnModel parse(String xml) throws Exception {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader reader = inputFactory.createXMLStreamReader(new StringReader(xml));
        return new BpmnXMLConverter().convertToBpmnModel(reader);
    }
}
