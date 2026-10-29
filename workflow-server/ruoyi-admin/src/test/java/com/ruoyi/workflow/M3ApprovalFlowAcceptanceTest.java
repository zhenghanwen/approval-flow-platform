package com.ruoyi.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ruoyi.RuoYiApplication;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalDefinitionMapper;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.mapper.WfFormDefinitionMapper;
import com.ruoyi.workflow.service.ApprovalDefinitionService;
import com.ruoyi.workflow.service.ApprovalSubmissionService;
import com.ruoyi.workflow.service.ApprovalTaskService;
import com.ruoyi.workflow.service.FormDefinitionService;
import com.ruoyi.workflow.service.WorkflowDefinitionService;

/**
 * M3 验收：基础审批闭环全链（定义 → 发布 → 提交 → 同意 → 驳回 → 留痕）。
 *
 * <p>验证：</p>
 * <ol>
 *     <li>审批类型 + 表单 + 流程 的定义与发布（BPMN 校验规一化门禁 → 部署 Flowable → 版本快照）；</li>
 *     <li>提交事务链：Mongo 先写 → MySQL 审批单 + 流程启动 + 任务投影 + 操作记录；幂等键扣重；</li>
 *     <li>同意：Flowable complete → 流程结束 → 审批单 APPROVED + 投影 COMPLETED；</li>
 *     <li>驳回：change-state 到申请人节点 → 审批单 RETURNED + 申请人任务重建；</li>
 *     <li>待办/我发起/时间线留痕。</li>
 * </ol>
 *
 * <p>依赖：MySQL(workflow_dev)、MongoDB(workflow) 在线；WF_DB_PASSWORD/WF_MONGO_URI 注入。</p>
 */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("druid")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M3ApprovalFlowAcceptanceTest {

    @Autowired
    private ApprovalDefinitionService approvalDefinitionService;

    @Autowired
    private FormDefinitionService formDefinitionService;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private ApprovalSubmissionService approvalSubmissionService;

    @Autowired
    private ApprovalTaskService approvalTaskService;

    @Autowired
    private WfApprovalDefinitionMapper approvalDefinitionMapper;

    @Autowired
    private WfApprovalInstanceMapper approvalInstanceMapper;

    @Autowired
    private WfApprovalTaskMapper approvalTaskMapper;

    @Autowired
    private WfFormDefinitionMapper formDefinitionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RepositoryService repositoryService;

    private static String approvalDefinitionId;
    private static String formVersionId;
    private static String workflowDefinitionId;
    private static String approvedInstanceId;
    private static String returnedInstanceId;

    /** 前置清理：删除上次运行的测试残留（审批/流程/表单定义 + Flowable 部署与实例），保证可重跑。 */
    @Test
    @Order(0)
    void cleanupPreviousRun() {
        // 1. Flowable 部署级联删除（含运行实例与历史）：M3 自身 + M4 跨轮遗留（M4 会把审批单留在 apply 节点）
        for (Deployment d : repositoryService.createDeploymentQuery().list()) {
            if ("费用报销流程".equals(d.getName())
                || "M4高级审批流程".equals(d.getName()) || "或签比例流程".equals(d.getName())) {
                repositoryService.deleteDeployment(d.getId(), true);
            }
        }
        // 2. MySQL 测试数据（按唯一编码定位，先删子表）：M3 expense_flow + M4 m4_* 跨轮遗留
        for (String flowCode : List.of("expense_flow", "m4_expense_flow", "m4_or_percent_flow")) {
            jdbcTemplate.update("""
                delete from wf_task_transfer where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_task_add_sign where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_task_return where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_approval_operation where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_approval_action where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_approval_task where approval_instance_id in (
                    select id from wf_approval_instance where workflow_version_id in (
                        select id from wf_workflow_version where workflow_id in (
                            select id from wf_workflow_definition where workflow_code = '%s')))
                """.formatted(flowCode));
            jdbcTemplate.update("""
                delete from wf_approval_instance where workflow_version_id in (
                    select id from wf_workflow_version where workflow_id in (
                        select id from wf_workflow_definition where workflow_code = '%s'))
                """.formatted(flowCode));
            jdbcTemplate.update("delete from wf_workflow_version where workflow_id in "
                + "(select id from wf_workflow_definition where workflow_code = '%s')".formatted(flowCode));
            jdbcTemplate.update("delete from wf_workflow_definition where workflow_code = '%s'".formatted(flowCode));
        }
        jdbcTemplate.update("delete from wf_approval_definition where approval_code in "
            + "('expense_approval', 'm4_expense_approval', 'm4_or_percent_approval')");
        jdbcTemplate.update("delete from wf_form_version where form_id in "
            + "(select id from wf_form_definition where form_code in "
            + "('expense_form', 'm4_expense_form', 'm4_or_percent_form'))");
        jdbcTemplate.update("delete from wf_form_definition where form_code in "
            + "('expense_form', 'm4_expense_form', 'm4_or_percent_form')");

        // 3. outbox 事件（M5 起审批动作会写入 wf_outbox_event，测试数据全清避免跨轮残留）
        jdbcTemplate.update("delete from wf_outbox_event");
    }

    /** 审批流程 BPMN：填写申请 → 部门审批 → 结束（普通 UserTask，规一化门禁统一改写为多实例）。 */
    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.m3/bpmn">
          <process id="m3ExpenseFlow" name="费用报销流程">
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="apply"/>
            <userTask id="apply" name="填写报销单"/>
            <sequenceFlow id="f1" sourceRef="apply" targetRef="deptApproval"/>
            <userTask id="deptApproval" name="部门审批"/>
            <sequenceFlow id="f2" sourceRef="deptApproval" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    /** 节点审批人配置：deptApproval 指定用户 2。 */
    private static final String CONFIG_JSON = """
        {"nodes":{"deptApproval":{"assignType":"USER","assignValue":"2"}}}
        """;

    @Test
    @Order(1)
    void createAndPublishApprovalFlow() {
        // 1. 创建审批类型（DRAFT）
        WfApprovalDefinition approvalDef = approvalDefinitionService.create(
            "expense_approval", "费用报销", null, "费用报销审批", null, "admin");
        approvalDefinitionId = approvalDef.getId();
        assertEquals("DRAFT", approvalDef.getStatus());

        // 2. 表单定义 + 草稿 + 发布
        WfFormDefinition formDef = formDefinitionService.createDefinition(
            "expense_form", "费用报销单", "admin");
        Map<String, Object> schema = Map.of(
            "formCode", "expense_form",
            "formName", "费用报销单",
            "layout", Map.of("columns", 2),
            "fields", List.of(
                Map.of("field", "applicant", "type", "user", "label", "申请人", "required", true),
                Map.of("field", "amount", "type", "money", "label", "报销金额", "required", true),
                Map.of("field", "reason", "type", "textarea", "label", "事由", "required", false)));
        WfFormVersion formDraft = formDefinitionService.saveDraft(formDef.getId(), schema, "admin");
        WfFormVersion formPublished = formDefinitionService.publish(formDef.getId(), formDraft.getId(), "admin");
        formVersionId = formPublished.getId();
        assertEquals("PUBLISHED", formPublished.getStatus());

        // 3. 流程定义 + 发布（门禁：校验 + 规一化 → 部署 Flowable → 版本快照）
        WfWorkflowDefinition workflowDef = workflowDefinitionService.createDefinition(
            approvalDefinitionId, "expense_flow", "费用报销流程", "admin");
        workflowDefinitionId = workflowDef.getId();
        WfWorkflowVersion flowVersion = workflowDefinitionService.publish(
            workflowDefinitionId, BPMN, CONFIG_JSON, "admin");
        assertEquals("PUBLISHED", flowVersion.getStatus());
        assertNotNull(flowVersion.getFlowableDeploymentId(), "Flowable 部署 ID 应记录");
        assertNotNull(flowVersion.getProcessDefinitionId(), "ProcessDefinition ID 应记录");
        assertEquals(1, flowVersion.getVersionNo());
        assertNotNull(flowVersion.getBpmnHash(), "BPMN 摘要应记录");

        // 4. 审批类型绑定表单版本 + 流程版本 → 发布（综合校验）
        WfApprovalDefinition bind = new WfApprovalDefinition();
        bind.setId(approvalDefinitionId);
        bind.setCurrentFormVersionId(formVersionId);
        bind.setCurrentFlowVersionId(flowVersion.getId());
        approvalDefinitionService.update(bind);
        WfApprovalDefinition published = approvalDefinitionService.publish(approvalDefinitionId, "admin");
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(formVersionId, published.getCurrentFormVersionId());
        assertEquals(flowVersion.getId(), published.getCurrentFlowVersionId());
    }

    @Test
    @Order(2)
    void submitStartsFlowAndProjectsApproverTask() {
        Map<String, Object> formData = Map.of(
            "applicant", "1",
            "amount", 6800,
            "reason", "办公设备采购");

        // 提交事务链：Mongo 先写 → MySQL 事务（审批单 + 流程启动 + 投影 + 操作记录）
        WfApprovalInstance instance = approvalSubmissionService.submit(
            approvalDefinitionId, formVersionId, "办公设备采购-6800元", formData,
            "1", "admin", "103", "submit-key-001", List.of());
        assertEquals("PENDING", instance.getBusinessStatus());
        assertNotNull(instance.getProcessInstanceId(), "应启动 Flowable 流程");
        assertNotNull(instance.getApprovalNo());

        // 审批人任务投影：deptApproval 待办 assignee=2
        List<WfApprovalTask> todo = approvalTaskService.todo("2");
        WfApprovalTask deptTask = todo.stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey()))
            .findFirst().orElse(null);
        assertNotNull(deptTask, "部门审批待办应存在");
        assertEquals("2", deptTask.getAssigneeUserId());
        assertEquals("PENDING", deptTask.getTaskStatus());

        // 申请人填单节点已自动完成（提交即进入审批），无申请人待办
        assertTrue(approvalTaskService.todo("1").isEmpty(), "申请人不应有待办");

        // 操作记录：SUBMIT 留痕
        List<WfApprovalAction> timeline = approvalTaskService.timeline(instance.getId());
        assertTrue(timeline.stream().anyMatch(a -> "SUBMIT".equals(a.getActionType())),
            "时间线应含 SUBMIT 记录");
    }

    @Test
    @Order(3)
    void approveCompletesFlowAndMarksApproved() {
        WfApprovalTask deptTask = approvalTaskService.todo("2").stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey()))
            .findFirst().orElseThrow(() -> new AssertionError("应存在部门审批待办"));
        assertNull(approvedInstanceId);

        // 同意（幂等键）
        WfApprovalInstance instance = approvalTaskService.approve(
            deptTask.getFlowableTaskId(), "同意报销", "2", "approve-key-001");
        approvedInstanceId = instance.getId();

        // 流程结束 → APPROVED
        assertEquals("APPROVED", instance.getBusinessStatus());
        assertNotNull(instance.getCompletedAt());

        // 投影 COMPLETED
        WfApprovalTask done = approvalTaskMapper.selectByFlowableTaskId(deptTask.getFlowableTaskId());
        assertEquals("COMPLETED", done.getTaskStatus());
        assertNotNull(done.getCompletedAt());

        // 已办可见，待办清空
        assertTrue(approvalTaskService.done("2").stream()
            .anyMatch(t -> deptTask.getFlowableTaskId().equals(t.getFlowableTaskId())));
        assertTrue(approvalTaskService.todo("2").isEmpty());

        // 时间线含 APPROVE
        assertTrue(approvalTaskService.timeline(approvedInstanceId).stream()
            .anyMatch(a -> "APPROVE".equals(a.getActionType())));
    }

    @Test
    @Order(4)
    void rejectReturnsToApplicantNode() {
        // 第二个审批单：驳回场景
        Map<String, Object> formData = Map.of(
            "applicant", "1",
            "amount", 1200,
            "reason", "差旅报销");
        WfApprovalInstance instance = approvalSubmissionService.submit(
            approvalDefinitionId, formVersionId, "差旅报销-1200元", formData,
            "1", "admin", "103", "submit-key-002", List.of());
        WfApprovalTask deptTask = approvalTaskService.todo("2").stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey()))
            .findFirst().orElseThrow(() -> new AssertionError("应存在部门审批待办"));

        // 驳回（change-state 到申请人节点）
        WfApprovalInstance rejected = approvalTaskService.reject(
            deptTask.getFlowableTaskId(), "材料不齐，驳回修改", "2", "reject-key-001");
        returnedInstanceId = rejected.getId();

        assertEquals("RETURNED", rejected.getBusinessStatus());
        assertEquals("apply", rejected.getCurrentNodeKey(), "当前节点应为申请人节点");

        // 当前审批任务投影 COMPLETED
        assertEquals("COMPLETED",
            approvalTaskMapper.selectByFlowableTaskId(deptTask.getFlowableTaskId()).getTaskStatus());

        // 申请人任务重建（待办回到申请人）
        List<WfApprovalTask> applicantTodo = approvalTaskService.todo("1");
        assertTrue(applicantTodo.stream().anyMatch(t -> "apply".equals(t.getTaskDefinitionKey())),
            "申请人应看到重建的填单待办");

        // 时间线含 REJECT
        assertTrue(approvalTaskService.timeline(returnedInstanceId).stream()
            .anyMatch(a -> "REJECT".equals(a.getActionType())));

        // 我发起的两个审批单都在
        List<WfApprovalInstance> mine = approvalTaskService.myInstances("1");
        assertTrue(mine.stream().anyMatch(i -> approvedInstanceId.equals(i.getId())));
        assertTrue(mine.stream().anyMatch(i -> returnedInstanceId.equals(i.getId())));
    }

    @Test
    @Order(5)
    void idempotencyKeyDeduplicatesApprove() {
        // 用相同幂等键重复同意：返回已存在结果，不重复执行
        WfApprovalTask done = approvalTaskService.done("2").stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey()))
            .findFirst().orElseThrow(() -> new AssertionError("应存在已办任务"));
        WfApprovalInstance again = approvalTaskService.approve(
            done.getFlowableTaskId(), "重复点击", "2", "approve-key-001");
        assertEquals(approvedInstanceId, again.getId(), "重复幂等键应返回同一审批单");
    }
}
