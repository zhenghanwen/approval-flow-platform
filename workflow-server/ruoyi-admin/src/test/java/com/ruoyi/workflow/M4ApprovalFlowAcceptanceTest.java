package com.ruoyi.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.domain.WfTaskAddSign;
import com.ruoyi.workflow.domain.WfTaskReturn;
import com.ruoyi.workflow.domain.WfTaskTransfer;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalDefinitionMapper;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.mapper.WfTaskAddSignMapper;
import com.ruoyi.workflow.mapper.WfTaskReturnMapper;
import com.ruoyi.workflow.mapper.WfTaskTransferMapper;
import com.ruoyi.workflow.service.ApprovalDefinitionService;
import com.ruoyi.workflow.service.ApprovalSubmissionService;
import com.ruoyi.workflow.service.ApprovalTaskService;
import com.ruoyi.workflow.service.ApprovalTaskService.ReturnTargetVo;
import com.ruoyi.workflow.service.FormDefinitionService;
import com.ruoyi.workflow.service.WorkflowDefinitionService;

/**
 * M4 验收：高级审批（转交 / 加签 / 退回任意节点 / 撤回 / 取消 / 或签比例 / 投影一致性）。
 *
 * <p>验证（PRD 11.1–11.5 + 12.4）：</p>
 * <ol>
 *     <li>转交：Flowable setAssignee + 投影同步 + wf_task_transfer 留痕 + 幂等；</li>
 *     <li>加签：addMultiInstanceExecution（并行 ALL）+ wf_task_add_sign 留痕，全员完成流程走通；</li>
 *     <li>退回 MI 目标：§3.3.2 方式二（退前置 + 集合变量重写），退回后流程可继续推进至结束；</li>
 *     <li>退回目标白名单：申请人节点直退 + MI 节点可用性标记；</li>
 *     <li>撤回（仅申请人）/ 取消（admin）：deleteProcessInstance + 投影批量 CANCELLED + 权限校验；</li>
 *     <li>或签（OR）/ 比例签（PERCENT）：completionRule 生效；</li>
 *     <li>T12：Flowable 活跃任务与投影 PENDING 数量一致性（统一任务操作服务保持同步）。</li>
 * </ol>
 *
 * <p>依赖：MySQL(workflow_dev)、MongoDB(workflow) 在线；WF_DB_PASSWORD/WF_MONGO_URI 注入；
 * 测试最小插入 sys_user 3/4/5（user_id/user_name/nick_name）。</p>
 */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("druid")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M4ApprovalFlowAcceptanceTest {

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
    private WfApprovalInstanceMapper approvalInstanceMapper;

    @Autowired
    private WfApprovalTaskMapper approvalTaskMapper;

    @Autowired
    private WfTaskTransferMapper taskTransferMapper;

    @Autowired
    private WfTaskAddSignMapper taskAddSignMapper;

    @Autowired
    private WfTaskReturnMapper taskReturnMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private static String m4ApprovalDefinitionId;
    private static String m4FormVersionId;
    private static String orPercentApprovalDefinitionId;
    private static String orPercentFormVersionId;

    /** 前置清理：删除上次运行的测试残留（M4 两个流程 + 审批类型），保证可重跑。 */
    @Test
    @Order(0)
    void cleanupPreviousRun() {
        // 1. Flowable 部署级联删除（含运行实例与历史）
        for (Deployment d : repositoryService.createDeploymentQuery().list()) {
            if ("M4高级审批流程".equals(d.getName()) || "或签比例流程".equals(d.getName())) {
                repositoryService.deleteDeployment(d.getId(), true);
            }
        }
        // 2. MySQL 测试数据（按唯一编码定位，先删子表）
        for (String flowCode : List.of("m4_expense_flow", "m4_or_percent_flow")) {
            jdbcTemplate.update("delete from wf_task_transfer where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_task_add_sign where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_task_return where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_approval_operation where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_approval_action where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_approval_task where approval_instance_id in ("
                + "select id from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "')))");
            jdbcTemplate.update("delete from wf_approval_instance where workflow_version_id in ("
                + "select id from wf_workflow_version where workflow_id in ("
                + "select id from wf_workflow_definition where workflow_code = '" + flowCode + "'))");
            jdbcTemplate.update("delete from wf_workflow_version where workflow_id in "
                + "(select id from wf_workflow_definition where workflow_code = '" + flowCode + "')");
            jdbcTemplate.update("delete from wf_workflow_definition where workflow_code = '" + flowCode + "'");
        }
        jdbcTemplate.update("delete from wf_approval_definition where approval_code in "
            + "('m4_expense_approval', 'm4_or_percent_approval')");
        jdbcTemplate.update("delete from wf_form_version where form_id in "
            + "(select id from wf_form_definition where form_code in ('m4_expense_form', 'm4_or_percent_form'))");
        jdbcTemplate.update("delete from wf_form_definition where form_code in "
            + "('m4_expense_form', 'm4_or_percent_form')");

        // 3. outbox 事件（M5 起审批动作会写入 wf_outbox_event，测试数据全清避免跨轮残留）
        jdbcTemplate.update("delete from wf_outbox_event");

        // 4. 最小插入 sys_user 3/4/5（M4 审批人），已存在则跳过
        ensureUser("3", "approver3");
        ensureUser("4", "approver4");
        ensureUser("5", "approver5");
    }

    private void ensureUser(String userId, String name) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from sys_user where user_id = ?", Integer.class, userId);
        if (count == null || count == 0) {
            jdbcTemplate.update("insert into sys_user (user_id, user_name, nick_name, status, del_flag) "
                + "values (?, ?, ?, '0', '0')", userId, name, name);
        }
    }

    /** M4 主流程 BPMN：填写申请 → 部门审批(MI) → 财务审批(MI) → 结束。 */
    private static final String M4_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.m4/bpmn">
          <process id="m4ExpenseFlow" name="M4高级审批流程">
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

    /** M4 主流程节点配置：deptApproval 3,4（ALL）；financeApproval 5（ALL）。 */
    private static final String M4_CONFIG_JSON = """
        {"nodes":{
          "deptApproval":{"assignType":"USER","assignValue":"3,4"},
          "financeApproval":{"assignType":"USER","assignValue":"5"}
        }}
        """;

    /** 或签/比例流程 BPMN：填写申请 → 或签(MI OR) → 比例签(MI PERCENT 60) → 结束。 */
    private static final String OR_PERCENT_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.m4/bpmn">
          <process id="m4OrPercentFlow" name="或签比例流程">
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="apply"/>
            <userTask id="apply" name="填写申请"/>
            <sequenceFlow id="f1" sourceRef="apply" targetRef="orApproval"/>
            <userTask id="orApproval" name="或签审批"/>
            <sequenceFlow id="f2" sourceRef="orApproval" targetRef="percentApproval"/>
            <userTask id="percentApproval" name="比例审批"/>
            <sequenceFlow id="f3" sourceRef="percentApproval" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    /** 或签/比例节点配置：orApproval 3,4 OR（任意一人）；percentApproval 5,4 PERCENT 60（需 2/2）。 */
    private static final String OR_PERCENT_CONFIG_JSON = """
        {"nodes":{
          "orApproval":{"assignType":"USER","assignValue":"3,4","completionRule":"OR"},
          "percentApproval":{"assignType":"USER","assignValue":"5,4","completionRule":"PERCENT","percent":60}
        }}
        """;

    @Test
    @Order(1)
    void createAndPublishApprovalFlows() {
        // 1. M4 主流程：审批类型 + 表单 + 流程 发布
        WfApprovalDefinition m4Def = approvalDefinitionService.create(
            "m4_expense_approval", "M4高级审批", null, "M4高级审批验收", null, "admin");
        m4ApprovalDefinitionId = m4Def.getId();

        WfFormDefinition m4Form = formDefinitionService.createDefinition(
            "m4_expense_form", "M4高级审批单", "admin");
        Map<String, Object> schema = Map.of(
            "formCode", "m4_expense_form",
            "formName", "M4高级审批单",
            "layout", Map.of("columns", 2),
            "fields", List.of(
                Map.of("field", "applicant", "type", "user", "label", "申请人", "required", true),
                Map.of("field", "amount", "type", "money", "label", "金额", "required", true)));
        WfFormVersion m4Draft = formDefinitionService.saveDraft(m4Form.getId(), schema, "admin");
        WfFormVersion m4Published = formDefinitionService.publish(m4Form.getId(), m4Draft.getId(), "admin");
        m4FormVersionId = m4Published.getId();

        WfWorkflowDefinition m4Flow = workflowDefinitionService.createDefinition(
            m4ApprovalDefinitionId, "m4_expense_flow", "M4高级审批流程", "admin");
        WfWorkflowVersion m4FlowVersion = workflowDefinitionService.publish(
            m4Flow.getId(), M4_BPMN, M4_CONFIG_JSON, "admin");
        assertEquals("PUBLISHED", m4FlowVersion.getStatus());

        WfApprovalDefinition m4Bind = new WfApprovalDefinition();
        m4Bind.setId(m4ApprovalDefinitionId);
        m4Bind.setCurrentFormVersionId(m4FormVersionId);
        m4Bind.setCurrentFlowVersionId(m4FlowVersion.getId());
        approvalDefinitionService.update(m4Bind);
        approvalDefinitionService.publish(m4ApprovalDefinitionId, "admin");

        // 2. 或签/比例流程：审批类型 + 表单 + 流程 发布
        WfApprovalDefinition orPercentDef = approvalDefinitionService.create(
            "m4_or_percent_approval", "或签比例审批", null, "或签比例验收", null, "admin");
        orPercentApprovalDefinitionId = orPercentDef.getId();

        WfFormDefinition opForm = formDefinitionService.createDefinition(
            "m4_or_percent_form", "或签比例单", "admin");
        WfFormVersion opDraft = formDefinitionService.saveDraft(opForm.getId(), schema, "admin");
        WfFormVersion opPublished = formDefinitionService.publish(opForm.getId(), opDraft.getId(), "admin");
        orPercentFormVersionId = opPublished.getId();

        WfWorkflowDefinition opFlow = workflowDefinitionService.createDefinition(
            orPercentApprovalDefinitionId, "m4_or_percent_flow", "或签比例流程", "admin");
        WfWorkflowVersion opFlowVersion = workflowDefinitionService.publish(
            opFlow.getId(), OR_PERCENT_BPMN, OR_PERCENT_CONFIG_JSON, "admin");
        assertEquals("PUBLISHED", opFlowVersion.getStatus());

        WfApprovalDefinition opBind = new WfApprovalDefinition();
        opBind.setId(orPercentApprovalDefinitionId);
        opBind.setCurrentFormVersionId(orPercentFormVersionId);
        opBind.setCurrentFlowVersionId(opFlowVersion.getId());
        approvalDefinitionService.update(opBind);
        approvalDefinitionService.publish(orPercentApprovalDefinitionId, "admin");
    }

    @Test
    @Order(2)
    void transferChangesAssigneeAndRecordsAudit() {
        WfApprovalInstance instance = submit("转交场景-800元", "m4-transfer-key-001");
        WfApprovalTask deptTaskOf3 = deptTask(instance, "3");
        String taskId = deptTaskOf3.getFlowableTaskId();

        // 转交：3 的任务转给 5（Flowable setAssignee + 投影同步 + 记录）
        WfApprovalInstance after = approvalTaskService.transfer(
            taskId, "5", "转给财务初审", "3", "m4-transfer-op-001");

        // Flowable 任务办理人已更新
        Task flowable = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertEquals("5", flowable.getAssignee());
        // 投影 assignee 同步
        assertEquals("5", approvalTaskMapper.selectByFlowableTaskId(taskId).getAssigneeUserId());
        // 转交记录
        List<WfTaskTransfer> transfers = taskTransferMapper.selectByInstance(instance.getId());
        assertTrue(transfers.stream().anyMatch(t -> "5".equals(t.getToUserId())
            && "3".equals(t.getFromUserId()) && "转给财务初审".equals(t.getReason())));
        // 时间线 TRANSFER
        assertTrue(timelineHas(instance.getId(), "TRANSFER"));
        assertEquals(instance.getId(), after.getId());

        // 幂等：重复同键返回同审批单
        assertEquals(instance.getId(),
            approvalTaskService.transfer(taskId, "5", "重复", "3", "m4-transfer-op-001").getId());

        // 清理：该审批单流程结束（5 完成 → 4 的部门任务 → 财务）
        completeAllPending(instance);
    }

    @Test
    @Order(3)
    void addSignAddsParallelInstanceAndFlowsToEnd() {
        WfApprovalInstance instance = submit("加签场景-500元", "m4-addsign-key-001");
        List<WfApprovalTask> deptTasks = deptTasks(instance);
        assertEquals(2, deptTasks.size(), "deptApproval 初始应为 2 个待办（3、4）");
        String taskIdOf3 = deptTask(instance, "3").getFlowableTaskId();

        // 加签 5（并行 ALL 节点，前加签）
        WfApprovalInstance after = approvalTaskService.addSign(
            taskIdOf3, "FIRST", List.of("5"), "增加财务会签", "3", "m4-addsign-op-001");
        assertEquals(instance.getId(), after.getId());

        // 引擎层：3 个实例任务，assignee 含 5
        List<Task> engineTasks = taskService.createTaskQuery().processInstanceId(instance.getProcessInstanceId())
            .taskDefinitionKey("deptApproval").list();
        assertEquals(3, engineTasks.size());
        assertTrue(engineTasks.stream().anyMatch(t -> "5".equals(t.getAssignee())));
        // 投影同步补齐（syncTasksForNode）
        List<WfApprovalTask> projections = approvalTaskMapper.selectByInstance(instance.getId());
        long deptPending = projections.stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey())
                && "PENDING".equals(t.getTaskStatus()))
            .count();
        assertEquals(3, deptPending, "deptApproval 投影应补齐为 3 个 PENDING");
        // 加签记录
        List<WfTaskAddSign> signs = taskAddSignMapper.selectByInstance(instance.getId());
        assertTrue(signs.stream().anyMatch(s -> "5".equals(s.getAddUserId())
            && "FIRST".equals(s.getSignType()) && "deptApproval".equals(s.getTaskDefinitionKey())));
        assertTrue(timelineHas(instance.getId(), "ADD_SIGN"));

        // 幂等：重复同键返回同审批单
        assertEquals(instance.getId(),
            approvalTaskService.addSign(taskIdOf3, "FIRST", List.of("5"), "重复",
                "3", "m4-addsign-op-001").getId());

        // 全员完成 → 财务审批 → 结束 → APPROVED
        completeAllPending(instance);
        WfApprovalInstance finalState = approvalInstanceMapper.selectById(instance.getId());
        assertEquals("APPROVED", finalState.getBusinessStatus());
    }

    @Test
    @Order(4)
    void returnToMiTargetUsesWayTwoAndFlowsOn() {
        WfApprovalInstance instance = submit("退回MI场景-900元", "m4-return-mi-key-001");
        // 推进到财务审批（部门 3、4 全完成）
        completeDept(instance);
        WfApprovalTask financeTask = financeTask(instance);

        // 退回 MI 目标 deptApproval（方式二：退前置 apply + 集合变量重写）
        WfApprovalInstance returned = approvalTaskService.returnTask(
            financeTask.getFlowableTaskId(), "deptApproval", "单据有误退回部门", "5", "m4-return-mi-op-001");
        assertEquals("RETURNED", returned.getBusinessStatus());
        // 实际退回位置 = 目标节点的前置串行节点 apply（方式二）
        assertEquals("apply", returned.getCurrentNodeKey());
        // 退回记录 targetNodeType=MI
        List<WfTaskReturn> returns = taskReturnMapper.selectByInstance(instance.getId());
        assertTrue(returns.stream().anyMatch(r -> "deptApproval".equals(r.getTargetNodeKey())
            && "MI".equals(r.getTargetNodeType()) && "单据有误退回部门".equals(r.getReason())));
        assertTrue(timelineHas(instance.getId(), "RETURN"));

        // 申请人重新提交（approve apply）→ deptApproval 按重写后的集合重建实例
        WfApprovalTask applyTask = pendingTaskOf(instance, "apply");
        approvalTaskService.approve(applyTask.getFlowableTaskId(), "重新提交", "1", "m4-return-mi-resubmit-001");
        List<WfApprovalTask> rebuilt = deptTasks(instance);
        assertEquals(2, rebuilt.size(), "退回后重提应重建 deptApproval 2 个实例");

        // 走通到结束 → APPROVED（方式二无卡死）
        completeAllPending(instance);
        assertEquals("APPROVED", approvalInstanceMapper.selectById(instance.getId()).getBusinessStatus());
    }

    @Test
    @Order(5)
    void returnTargetsWhitelistAndDirectReturnToApplicant() {
        WfApprovalInstance instance = submit("退回申请人场景-300元", "m4-return-applicant-key-001");
        completeDept(instance);
        WfApprovalTask financeTask = financeTask(instance);

        // 白名单：apply(APPLICANT, 可用) + deptApproval(MI, 可用，有前置串行节点)
        List<ReturnTargetVo> targets = approvalTaskService.returnTargets(financeTask.getFlowableTaskId());
        ReturnTargetVo applicant = targets.stream().filter(t -> "apply".equals(t.nodeKey))
            .findFirst().orElseThrow(() -> new AssertionError("白名单应含申请人节点"));
        assertEquals("APPLICANT", applicant.nodeType);
        assertTrue(applicant.available);
        ReturnTargetVo mi = targets.stream().filter(t -> "deptApproval".equals(t.nodeKey))
            .findFirst().orElseThrow(() -> new AssertionError("白名单应含部门审批节点"));
        assertEquals("MI", mi.nodeType);
        assertTrue(mi.available, "deptApproval 前置为 apply（普通节点），方式二应可用");

        // 直退申请人节点 → RETURNED，当前节点 apply
        WfApprovalInstance returned = approvalTaskService.returnTask(
            financeTask.getFlowableTaskId(), "apply", "直接退回申请人", "5", "m4-return-applicant-op-001");
        assertEquals("RETURNED", returned.getBusinessStatus());
        assertEquals("apply", returned.getCurrentNodeKey());
        List<WfTaskReturn> returns = taskReturnMapper.selectByInstance(instance.getId());
        assertTrue(returns.stream().anyMatch(r -> "apply".equals(r.getTargetNodeKey())
            && "APPLICANT".equals(r.getTargetNodeType())));

        // 非法目标校验：退回当前节点拒绝；退回开始事件拒绝（白名单拦截）
        WfApprovalTask applyTask = pendingTaskOf(instance, "apply");
        assertThrows(ServiceException.class, () -> approvalTaskService.returnTask(
            applyTask.getFlowableTaskId(), "apply", "退回当前节点", "1", "m4-return-self-op-001"));
        assertThrows(ServiceException.class, () -> approvalTaskService.returnTask(
            applyTask.getFlowableTaskId(), "start", "退到开始事件", "1", "m4-return-start-op-001"));

        // 收尾：走通该审批单至结束（避免跨轮残留 PENDING 申请人待办污染其他测试的 todo 查询）
        completeAllPending(instance);
    }

    @Test
    @Order(6)
    void withdrawRequiresApplicantAndCancelsProjections() {
        WfApprovalInstance instance = submit("撤回场景-200元", "m4-withdraw-key-001");

        // 非申请人撤回 → 拒绝
        assertThrows(ServiceException.class, () -> approvalTaskService.withdraw(
            instance.getId(), "替人撤回", "2", "m4-withdraw-non-applicant-001"));
        // 申请人撤回 → WITHDRAWN + 投影批量 CANCELLED + 流程实例删除
        WfApprovalInstance withdrawn = approvalTaskService.withdraw(
            instance.getId(), "信息有误撤回", "1", "m4-withdraw-op-001");
        assertEquals("WITHDRAWN", withdrawn.getBusinessStatus());
        List<WfApprovalTask> projections = approvalTaskMapper.selectByInstance(instance.getId());
        assertTrue(projections.stream().allMatch(t -> "CANCELLED".equals(t.getTaskStatus())),
            "撤回后待办投影应批量 CANCELLED");
        assertNull(runtimeService.createProcessInstanceQuery()
            .processInstanceId(instance.getProcessInstanceId()).singleResult(), "流程实例应已删除");
        assertTrue(timelineHas(instance.getId(), "WITHDRAW"));
        // 幂等
        assertEquals(instance.getId(), approvalTaskService.withdraw(
            instance.getId(), "重复", "1", "m4-withdraw-op-001").getId());
    }

    @Test
    @Order(7)
    void cancelRequiresAdminAndCancelsProjections() {
        WfApprovalInstance instance = submit("取消场景-400元", "m4-cancel-key-001");

        // 非 admin 取消 → 拒绝
        assertThrows(ServiceException.class, () -> approvalTaskService.cancel(
            instance.getId(), "替人取消", "2", "m4-cancel-non-admin-001"));
        // admin(1) 取消 → CANCELLED + 投影批量 CANCELLED
        WfApprovalInstance cancelled = approvalTaskService.cancel(
            instance.getId(), "业务终止取消", "1", "m4-cancel-op-001");
        assertEquals("CANCELLED", cancelled.getBusinessStatus());
        assertTrue(approvalTaskMapper.selectByInstance(instance.getId())
            .stream().allMatch(t -> "CANCELLED".equals(t.getTaskStatus())));
        assertNull(runtimeService.createProcessInstanceQuery()
            .processInstanceId(instance.getProcessInstanceId()).singleResult());
        assertTrue(timelineHas(instance.getId(), "CANCEL"));
        // 幂等
        assertEquals(instance.getId(), approvalTaskService.cancel(
            instance.getId(), "重复", "1", "m4-cancel-op-001").getId());
    }

    @Test
    @Order(8)
    void orAndPercentCompletionRulesTakeEffect() {
        Map<String, Object> formData = Map.of("applicant", "1", "amount", 1500);
        WfApprovalInstance instance = approvalSubmissionService.submit(
            orPercentApprovalDefinitionId, orPercentFormVersionId, "或签比例场景-1500元", formData,
            "1", "admin", "103", "m4-orpercent-key-001", List.of());

        // 或签：3 同意（OR 任意一人达标）→ 直接进比例签
        WfApprovalTask orTask3 = pendingTaskOf(instance, "orApproval", "3");
        approvalTaskService.approve(orTask3.getFlowableTaskId(), "或签同意", "3", "m4-orpercent-or-001");
        WfApprovalTask percentTask5 = pendingTaskOf(instance, "percentApproval", "5");
        assertNotNull(percentTask5, "或签通过后应进入比例签节点");
        WfApprovalTask percentTask4 = pendingTaskOf(instance, "percentApproval", "4");
        assertNotNull(percentTask4, "比例签 2 人实例应都在");

        // 比例签：5 同意（1/2 = 50% < 60%）→ 任务仍在（流程未推进）
        approvalTaskService.approve(percentTask5.getFlowableTaskId(), "比例签1人", "5", "m4-orpercent-p1-001");
        WfApprovalInstance mid = approvalInstanceMapper.selectById(instance.getId());
        assertEquals("PENDING", mid.getBusinessStatus(), "1/2=50% 未达 60%，流程应仍 PENDING");
        assertNotNull(pendingTaskOf(instance, "percentApproval", "4"), "4 的实例任务应仍在");

        // 4 同意（2/2 = 100% ≥ 60%）→ 流程结束 APPROVED
        WfApprovalTask percentTask4Again = pendingTaskOf(instance, "percentApproval", "4");
        approvalTaskService.approve(percentTask4Again.getFlowableTaskId(), "比例签2人", "4", "m4-orpercent-p2-001");
        assertEquals("APPROVED", approvalInstanceMapper.selectById(instance.getId()).getBusinessStatus());
    }

    @Test
    @Order(9)
    void projectionConsistencyWithEngine() {
        // T12：Flowable 活跃任务与投影 PENDING 数量一致（统一任务操作服务保持同步）
        WfApprovalInstance instance = submit("投影一致性场景-600元", "m4-projection-key-001");
        assertEquals(2, engineActiveCount(instance), "deptApproval 引擎活跃任务 2");
        assertEquals(2, projectionPendingCount(instance), "投影 PENDING 2");

        approvalTaskService.approve(deptTask(instance, "3").getFlowableTaskId(), "同意", "3", "m4-proj-a1-001");
        assertEquals(1, engineActiveCount(instance), "3 同意后引擎活跃任务 1");
        assertEquals(1, projectionPendingCount(instance), "投影 PENDING 1");

        approvalTaskService.approve(deptTask(instance, "4").getFlowableTaskId(), "同意", "4", "m4-proj-a2-001");
        assertEquals(1, engineActiveCount(instance), "进入财务审批，引擎活跃任务 1");
        assertEquals(1, projectionPendingCount(instance), "投影 PENDING 1");

        approvalTaskService.approve(financeTask(instance).getFlowableTaskId(), "同意", "5", "m4-proj-a3-001");
        assertEquals(0, engineActiveCount(instance), "流程结束，引擎活跃任务 0");
        assertEquals(0, projectionPendingCount(instance), "投影 PENDING 0");
    }

    // ==================== 辅助 ====================

    private WfApprovalInstance submit(String title, String idempotencyKey) {
        Map<String, Object> formData = Map.of("applicant", "1", "amount", 6800);
        return approvalSubmissionService.submit(
            m4ApprovalDefinitionId, m4FormVersionId, title, formData,
            "1", "admin", "103", idempotencyKey, List.of());
    }

    private WfApprovalTask deptTask(WfApprovalInstance instance, String assignee) {
        return pendingTaskOf(instance, "deptApproval", assignee);
    }

    private List<WfApprovalTask> deptTasks(WfApprovalInstance instance) {
        return approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey())
                && "PENDING".equals(t.getTaskStatus()))
            .toList();
    }

    private WfApprovalTask financeTask(WfApprovalInstance instance) {
        return pendingTaskOf(instance, "financeApproval", "5");
    }

    private WfApprovalTask pendingTaskOf(WfApprovalInstance instance, String nodeKey) {
        return approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> nodeKey.equals(t.getTaskDefinitionKey())
                && "PENDING".equals(t.getTaskStatus()))
            .findFirst().orElseThrow(() -> new AssertionError("应存在 " + nodeKey + " 待办投影"));
    }

    private WfApprovalTask pendingTaskOf(WfApprovalInstance instance, String nodeKey, String assignee) {
        return approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> nodeKey.equals(t.getTaskDefinitionKey())
                && "PENDING".equals(t.getTaskStatus())
                && assignee.equals(t.getAssigneeUserId()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "应存在 " + nodeKey + "/" + assignee + " 待办投影"));
    }

    /** 完成部门审批全部待办（进入财务审批）。幂等键按实例+任务派生保证跨审批单唯一。 */
    private void completeDept(WfApprovalInstance instance) {
        for (WfApprovalTask t : deptTasks(instance)) {
            approvalTaskService.approve(t.getFlowableTaskId(), "同意", t.getAssigneeUserId(),
                "m4-cd-" + instance.getId() + "-" + t.getFlowableTaskId());
        }
    }

    /** 完成当前节点全部待办（可跨节点，直到流程结束；循环轮询直至无活跃引擎任务，覆盖 apply→dept→finance 多级推进）。 */
    private void completeAllPending(WfApprovalInstance instance) {
        for (int round = 0; round < 6; round++) {
            List<WfApprovalTask> pending = approvalTaskMapper.selectByInstance(instance.getId()).stream()
                .filter(t -> "PENDING".equals(t.getTaskStatus())).toList();
            boolean progressed = false;
            for (WfApprovalTask t : pending) {
                // 幂等键唯一（实例+任务+轮次）；若投影尚未同步（引擎已无任务）则跳过
                Task flowable = taskService.createTaskQuery().taskId(t.getFlowableTaskId()).singleResult();
                if (flowable != null) {
                    approvalTaskService.approve(t.getFlowableTaskId(), "同意", t.getAssigneeUserId(),
                        "m4-run-" + instance.getId() + "-" + t.getFlowableTaskId() + "-" + round);
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
    }

    private int engineActiveCount(WfApprovalInstance instance) {
        return (int) taskService.createTaskQuery()
            .processInstanceId(instance.getProcessInstanceId()).count();
    }

    private long projectionPendingCount(WfApprovalInstance instance) {
        return approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> "PENDING".equals(t.getTaskStatus())).count();
    }

    private boolean timelineHas(String instanceId, String actionType) {
        return approvalTaskService.timeline(instanceId).stream()
            .anyMatch(a -> actionType.equals(a.getActionType()));
    }
}
