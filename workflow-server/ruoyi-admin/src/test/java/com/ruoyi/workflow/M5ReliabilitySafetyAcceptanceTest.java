package com.ruoyi.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.flowable.engine.HistoryService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.domain.WfExternalApi;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.domain.WfOutboxEvent;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.mapper.WfOutboxEventMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;
import com.ruoyi.workflow.security.ExternalApiSecurityGuard;
import com.ruoyi.workflow.service.ApprovalDefinitionService;
import com.ruoyi.workflow.service.ApprovalSubmissionService;
import com.ruoyi.workflow.service.ApprovalTaskService;
import com.ruoyi.workflow.service.ConsistencyRecoveryService;
import com.ruoyi.workflow.service.ExternalApiService;
import com.ruoyi.workflow.service.FormDefinitionService;
import com.ruoyi.workflow.service.WorkflowDefinitionService;
import com.ruoyi.workflow.service.outbox.OutboxEventHandler;

/**
 * M5 验收：可靠与安全（幂等泛化 / Outbox / 一致性恢复 / SSRF / 资源级鉴权）。
 *
 * <p>验证（PRD 16.2/16.3/16.4 + 17.2/17.3 + 18 第五阶段 + 19 验收清单）：</p>
 * <ol>
 *     <li>Outbox：业务事务内发布 PENDING 事件 → 投递 PROCESSED；失败指数退避重试后成功；超 maxRetries 死信 FAILED（不阻塞批次其他事件）；</li>
 *     <li>一致性恢复：引擎结束但业务单 PENDING → APPROVED（投影兜底）；活跃任务缺投影补插 + 幻影投影修复；超 24h 孤儿 MongoDB 文档清理；SUBMITTING 卡死 → FAILED；</li>
 *     <li>SSRF：内网/保留/环回/链路本地/云元数据/组播/6to4/v4-mapped 拦截，白名单 + 黑名单联合校验，响应有界读取；</li>
 *     <li>资源级鉴权：详情/时间线仅申请人/办理人/管理员可访问，防 ID 遍历。</li>
 * </ol>
 *
 * <p>依赖：MySQL(workflow_dev)、MongoDB(workflow) 在线；WF_DB_PASSWORD/WF_MONGO_URI 注入；
 * 测试最小插入 sys_user 3/4/5；outbox 退避基数压到 50ms 加速重试/死信断言，调度初始延迟拉长防测试期误触发。</p>
 */
@SpringBootTest(classes = {RuoYiApplication.class, M5ReliabilitySafetyAcceptanceTest.M5OutboxTestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "workflow.outbox.backoff-base-ms=50",
        "workflow.outbox.initial-delay-ms=3600000",
        "workflow.outbox.poll-ms=3600000",
        "workflow.recovery.initial-delay-ms=3600000",
        "workflow.recovery.poll-ms=3600000"
    })
@ActiveProfiles("druid")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M5ReliabilitySafetyAcceptanceTest {

    /** 注入可编程失败的 Outbox 通知器（@Primary 覆盖默认实现，仅作用于本测试上下文）。 */
    @TestConfiguration
    static class M5OutboxTestConfig {
        @Bean
        @Primary
        FailingOutboxEventHandler failingOutboxEventHandler() {
            return new FailingOutboxEventHandler();
        }
    }

    /** 可编程失败通知器：默认成功；可按事件 ID 注入"失败 N 次后成功"或"永远失败"。 */
    static class FailingOutboxEventHandler implements OutboxEventHandler {

        private final Map<String, Integer> remainingFailures = new ConcurrentHashMap<>();
        private final Set<String> alwaysFail = ConcurrentHashMap.newKeySet();

        void failNextTimes(String eventId, int times) {
            remainingFailures.put(eventId, times);
        }

        void failAlways(String eventId) {
            alwaysFail.add(eventId);
        }

        @Override
        public void handle(WfOutboxEvent event) {
            if (alwaysFail.contains(event.getId())) {
                throw new IllegalStateException("注入失败（死信场景）");
            }
            Integer remaining = remainingFailures.get(event.getId());
            if (remaining != null) {
                if (remaining <= 1) {
                    remainingFailures.remove(event.getId());
                } else {
                    remainingFailures.put(event.getId(), remaining - 1);
                }
                throw new IllegalStateException("注入失败（重试场景）");
            }
        }
    }

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
    private ConsistencyRecoveryService recoveryService;

    @Autowired
    private ExternalApiSecurityGuard securityGuard;

    @Autowired
    private ExternalApiService externalApiService;

    @Autowired
    private WfApprovalInstanceMapper approvalInstanceMapper;

    @Autowired
    private WfApprovalTaskMapper approvalTaskMapper;

    @Autowired
    private WfOutboxEventMapper outboxEventMapper;

    @Autowired
    private ApprovalFormDataRepository formDataRepository;

    @Autowired
    private FailingOutboxEventHandler failingHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private com.ruoyi.workflow.service.outbox.OutboxEventService outboxEventService;

    private static String m5ApprovalDefinitionId;
    private static String m5FormVersionId;
    private static String m5WorkflowVersionId;

    /** 前置清理：删除上次运行的测试残留（M5 流程 + 审批类型 + outbox + 孤儿 Mongo 文档），保证可重跑。 */
    @Test
    @Order(0)
    void cleanupPreviousRun() {
        // 1. Flowable 部署级联删除
        for (Deployment d : repositoryService.createDeploymentQuery().list()) {
            if ("M5可靠与安全流程".equals(d.getName())) {
                repositoryService.deleteDeployment(d.getId(), true);
            }
        }
        // 2. MySQL 测试数据（按唯一编码定位，先删子表）
        for (String flowCode : List.of("m5_expense_flow")) {
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
        jdbcTemplate.update("delete from wf_approval_definition where approval_code = 'm5_expense_approval'");
        jdbcTemplate.update("delete from wf_form_version where form_id in "
            + "(select id from wf_form_definition where form_code = 'm5_expense_form')");
        jdbcTemplate.update("delete from wf_form_definition where form_code = 'm5_expense_form'");

        // 3. outbox 事件（M5 起审批动作会写入，测试数据全清避免跨轮残留）
        jdbcTemplate.update("delete from wf_outbox_event");

        // 4. 孤儿 MongoDB 文档（上一轮残留，approvalInstanceId 以 m5-orphan- 标记）
        formDataRepository.findAll().stream()
            .filter(d -> d.getApprovalInstanceId() != null
                && d.getApprovalInstanceId().startsWith("m5-orphan-"))
            .forEach(formDataRepository::delete);

        // 5. 最小插入 sys_user 3/4/5（M5 审批人），已存在则跳过
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

    /** M5 主流程 BPMN：填写申请 → 部门审批(MI 3,4) → 财务审批(5) → 结束。 */
    private static final String M5_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://workflow.m5/bpmn">
          <process id="m5ExpenseFlow" name="M5可靠与安全流程">
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

    /** M5 节点配置：deptApproval 3,4（ALL）；financeApproval 5（ALL）。 */
    private static final String M5_CONFIG_JSON = """
        {"nodes":{
          "deptApproval":{"assignType":"USER","assignValue":"3,4"},
          "financeApproval":{"assignType":"USER","assignValue":"5"}
        }}
        """;

    @Test
    @Order(1)
    void createAndPublishApprovalFlow() {
        WfApprovalDefinition def = approvalDefinitionService.create(
            "m5_expense_approval", "M5可靠与安全审批", null, "M5可靠与安全验收", null, "admin");
        m5ApprovalDefinitionId = def.getId();

        WfFormDefinition form = formDefinitionService.createDefinition(
            "m5_expense_form", "M5可靠与安全单", "admin");
        Map<String, Object> schema = Map.of(
            "formCode", "m5_expense_form",
            "formName", "M5可靠与安全单",
            "layout", Map.of("columns", 2),
            "fields", List.of(
                Map.of("field", "applicant", "type", "user", "label", "申请人", "required", true),
                Map.of("field", "amount", "type", "money", "label", "金额", "required", true)));
        WfFormVersion draft = formDefinitionService.saveDraft(form.getId(), schema, "admin");
        WfFormVersion published = formDefinitionService.publish(form.getId(), draft.getId(), "admin");
        m5FormVersionId = published.getId();

        WfWorkflowDefinition flow = workflowDefinitionService.createDefinition(
            m5ApprovalDefinitionId, "m5_expense_flow", "M5可靠与安全流程", "admin");
        WfWorkflowVersion flowVersion = workflowDefinitionService.publish(
            flow.getId(), M5_BPMN, M5_CONFIG_JSON, "admin");
        assertEquals("PUBLISHED", flowVersion.getStatus());
        m5WorkflowVersionId = flowVersion.getId();

        WfApprovalDefinition bind = new WfApprovalDefinition();
        bind.setId(m5ApprovalDefinitionId);
        bind.setCurrentFormVersionId(m5FormVersionId);
        bind.setCurrentFlowVersionId(m5WorkflowVersionId);
        approvalDefinitionService.update(bind);
        approvalDefinitionService.publish(m5ApprovalDefinitionId, "admin");
    }

    /** Outbox 发布原子性 + 投递 PROCESSED（PRD 16.3）。 */
    @Test
    @Order(2)
    void outboxPublishIsAtomicAndDispatchMarksProcessed() {
        WfApprovalInstance instance = submit("outbox-提交事件", "m5-outbox-key-001");

        // 提交事件与审批事务同事务写入：PENDING + retry 0
        WfOutboxEvent submitted = eventOf(instance, "APPROVAL_SUBMITTED");
        assertNotNull(submitted, "提交后应有 APPROVAL_SUBMITTED 事件");
        assertEquals("PENDING", submitted.getStatus());
        assertEquals(0, submitted.getRetryCount());

        // 默认投递成功 → PROCESSED
        outboxEventServiceDispatch();

        // 推进流程到结束 → APPROVAL_APPROVED 事件；投递 PROCESSED
        completeAllPending(instance);
        WfOutboxEvent approved = eventOf(instance, "APPROVAL_APPROVED");
        assertNotNull(approved, "流程结束应发布 APPROVAL_APPROVED 事件");
        assertEquals("PENDING", approved.getStatus(), "终态事件应在审批事务内原子写入");
        outboxEventServiceDispatch();
        assertEquals("PROCESSED", eventOf(instance, "APPROVAL_APPROVED").getStatus());
        assertEquals("PROCESSED", eventOf(instance, "APPROVAL_SUBMITTED").getStatus());
    }

    /** Outbox 失败重试：注入失败 N 次 → 退避重试后成功（PRD 16.3）。 */
    @Test
    @Order(3)
    void outboxRetriesWithBackoffThenSucceeds() throws InterruptedException {
        WfApprovalInstance instance = submit("outbox-重试场景", "m5-outbox-retry-key-001");
        WfOutboxEvent event = eventOf(instance, "APPROVAL_SUBMITTED");
        failingHandler.failNextTimes(event.getId(), 2);

        // 第 1 次投递失败 → PENDING + retry=1 + next_retry_at 排期（退避 50ms）
        outboxEventServiceDispatch();
        WfOutboxEvent after1 = outboxEventMapper.selectById(event.getId());
        assertEquals("PENDING", after1.getStatus());
        assertEquals(1, after1.getRetryCount());
        assertNotNull(after1.getNextRetryAt());
        assertNotNull(after1.getLastError());

        // 到期后再投递仍失败 → retry=2
        sleep(120);
        outboxEventServiceDispatch();
        assertEquals(2, outboxEventMapper.selectById(event.getId()).getRetryCount());

        // 到期后再投递 → 成功 PROCESSED（remainingFailures 已清零）
        sleep(220);
        outboxEventServiceDispatch();
        WfOutboxEvent finalState = outboxEventMapper.selectById(event.getId());
        assertEquals("PROCESSED", finalState.getStatus());
        assertEquals(2, finalState.getRetryCount());

        completeAllPending(instance);
    }

    /** Outbox 死信：永远失败 → 超 maxRetries=5 → FAILED，且不阻塞批次其他事件（PRD 16.3）。 */
    @Test
    @Order(4)
    void outboxDeadLetterAfterMaxRetriesAndDoesNotBlockBatch() throws InterruptedException {
        WfApprovalInstance instance = submit("outbox-死信场景", "m5-outbox-dl-key-001");
        WfOutboxEvent event = eventOf(instance, "APPROVAL_SUBMITTED");
        failingHandler.failAlways(event.getId());

        // 5 次投递全部失败（退避 50ms 基数：50+100+200+400）→ 第 5 次达到 maxRetries → FAILED
        for (int i = 1; i <= 4; i++) {
            outboxEventServiceDispatch();
            sleep(60L * (1L << i)); // 60ms, 120ms, 240ms, 480ms —— 覆盖各轮退避窗口
        }
        outboxEventServiceDispatch();

        WfOutboxEvent dead = outboxEventMapper.selectById(event.getId());
        assertEquals("FAILED", dead.getStatus(), "超 maxRetries 应标记 FAILED 死信");
        assertEquals(5, dead.getRetryCount());
        assertNotNull(dead.getLastError());

        // 死信不阻塞批次：同一聚合的终态事件（无失败注入）仍可投递成功
        completeAllPending(instance);
        WfOutboxEvent approved = eventOf(instance, "APPROVAL_APPROVED");
        assertNotNull(approved);
        outboxEventServiceDispatch();
        assertEquals("PROCESSED", outboxEventMapper.selectById(approved.getId()).getStatus());
        // 死信保持 FAILED 不被重复处理
        assertEquals("FAILED", outboxEventMapper.selectById(event.getId()).getStatus());
    }

    /** 一致性恢复：引擎已结束但业务单 PENDING → APPROVED + 投影兜底 COMPLETED（PRD 16.4）。 */
    @Test
    @Order(5)
    void recoverEndedInstancesFixesStatusAndProjections() {
        WfApprovalInstance instance = submit("结束漂移-700元", "m5-ended-key-001");
        String pid = instance.getProcessInstanceId();
        // 绕过业务服务直接用引擎 complete 走完流程（模拟业务侧漏同步）
        completeEngineDirect(pid);
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult(),
            "引擎实例应已结束");
        assertNotNull(historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pid).finished().singleResult(), "历史应标记 finished");
        assertEquals("PENDING", approvalInstanceMapper.selectById(instance.getId()).getBusinessStatus(),
            "业务单应仍为 PENDING（模拟漂移）");

        int fixed = recoveryService.recoverEndedInstances();
        assertTrue(fixed >= 1, "应修复至少 1 个漂移审批单");

        WfApprovalInstance after = approvalInstanceMapper.selectById(instance.getId());
        assertEquals("APPROVED", after.getBusinessStatus());
        assertNotNull(after.getCompletedAt());
        assertTrue(approvalTaskMapper.selectByInstance(instance.getId()).stream()
                .allMatch(t -> "COMPLETED".equals(t.getTaskStatus())),
            "投影应兜底标记 COMPLETED");
    }

    /** 一致性恢复：活跃任务缺投影补插 + 幻影投影修复（PRD 16.4）。 */
    @Test
    @Order(6)
    void recoverProjectionMismatchRepairsMissingAndPhantom() {
        WfApprovalInstance instance = submit("投影漂移-800元", "m5-proj-key-001");
        String pid = instance.getProcessInstanceId();
        // 提交后流程已推进到部门审批（apply 填单节点在 syncTasks 自动完成）
        assertEquals(2, projectionPendingCount(instance), "deptApproval 应有 2 个 PENDING 投影");

        // 漂移 A：删除 3 的投影（活跃任务缺投影）
        WfApprovalTask proj3 = pendingTaskOf(instance, "deptApproval", "3");
        jdbcTemplate.update("delete from wf_approval_task where id = ?", proj3.getId());
        // 漂移 B：插入引擎不存在的幻影投影
        WfApprovalTask phantom = new WfApprovalTask();
        phantom.setId(UUID.randomUUID().toString());
        phantom.setApprovalInstanceId(instance.getId());
        phantom.setProcessInstanceId(pid);
        phantom.setFlowableTaskId("phantom-" + UUID.randomUUID());
        phantom.setTaskDefinitionKey("deptApproval");
        phantom.setTaskName("部门审批");
        phantom.setAssigneeUserId("9");
        phantom.setOriginalAssigneeUserId("9");
        phantom.setTaskStatus("PENDING");
        phantom.setCreatedAt(new Date());
        approvalTaskMapper.insert(phantom);

        int fixed = recoveryService.recoverProjectionMismatch();
        assertTrue(fixed >= 2, "应补插缺失投影 + 修复幻影投影");

        // 缺失投影已补插（引擎活跃 2 个任务 → PENDING 投影恢复为 2）
        assertEquals(2, projectionPendingCount(instance));
        assertNotNull(pendingTaskOf(instance, "deptApproval", "3"), "缺失投影应补插");
        // 幻影投影已标记 COMPLETED
        assertEquals("COMPLETED", approvalTaskMapper.selectById(phantom.getId()).getTaskStatus());

        completeAllPending(instance);
    }

    /** 一致性恢复：超 24h 孤儿 MongoDB 文档清理，未到期保留（PRD 16.2/16.4）。 */
    @Test
    @Order(7)
    void recoverOrphanMongoDocsCleansStaleOnly() {
        // 超 24h 孤儿 → 应删除
        ApprovalFormDataDoc stale = new ApprovalFormDataDoc();
        stale.setApprovalInstanceId("m5-orphan-stale-" + UUID.randomUUID());
        stale.setCreatedAt(new Date(System.currentTimeMillis() - 25 * 3600 * 1000L));
        stale.setData(Map.of("x", 1));
        formDataRepository.save(stale);

        // 未超 24h 孤儿 → 应保留
        ApprovalFormDataDoc recent = new ApprovalFormDataDoc();
        recent.setApprovalInstanceId("m5-orphan-recent-" + UUID.randomUUID());
        recent.setCreatedAt(new Date(System.currentTimeMillis() - 3600 * 1000L));
        recent.setData(Map.of("x", 1));
        formDataRepository.save(recent);

        int deleted = recoveryService.recoverOrphanMongoDocs();
        assertTrue(deleted >= 1, "应清理至少 1 个超期孤儿文档");
        assertFalse(formDataRepository.existsById(stale.getId()), "超 24h 孤儿应被删除");
        assertTrue(formDataRepository.existsById(recent.getId()), "未超 24h 孤儿应保留");

        formDataRepository.delete(recent);
    }

    /** 一致性恢复：SUBMITTING 卡死（流程未启动）→ FAILED（表单双写行保留，PRD 16.2/16.4）。 */
    @Test
    @Order(8)
    void recoverStuckSubmittingMarksFailed() {
        WfApprovalInstance stuck = new WfApprovalInstance();
        stuck.setId(UUID.randomUUID().toString());
        stuck.setApprovalNo("M5STUCK" + System.currentTimeMillis());
        stuck.setApprovalDefinitionId(m5ApprovalDefinitionId);
        stuck.setFormVersionId(m5FormVersionId);
        stuck.setWorkflowVersionId(m5WorkflowVersionId);
        stuck.setApplicantUserId("1");
        stuck.setTitle("卡死提交-模拟崩溃");
        stuck.setBusinessStatus("SUBMITTING");
        stuck.setVersion(0);
        stuck.setCreatedAt(new Date());
        stuck.setSubmittedAt(new Date());
        approvalInstanceMapper.insert(stuck);

        int fixed = recoveryService.recoverStuckSubmitting();
        assertTrue(fixed >= 1, "应标记至少 1 个卡死提交");
        assertEquals("FAILED", approvalInstanceMapper.selectById(stuck.getId()).getBusinessStatus());
    }

    /** SSRF：内网/保留/环回/链路本地/云元数据/组播/6to4/v4-mapped 拦截（PRD 17.2）。 */
    @Test
    @Order(9)
    void ssrfGuardRejectsInternalAndCloudMetadata() {
        // 黑名单：私网/保留/环回/链路本地/云元数据/组播/文档段
        for (String blocked : List.of(
            "10.0.0.1", "172.16.5.5", "192.168.1.1", "100.64.0.1", "198.18.0.1", "198.19.255.1",
            "198.51.100.1", "203.0.113.1", "192.0.0.1", "0.0.0.0", "240.0.0.1", "255.255.255.255",
            "169.254.169.254", "169.254.0.10", "127.0.0.1",
            "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1", "2002:0a00:0001::",
            "localhost")) {
            assertTrue(securityGuard.isBlockedHost(blocked), "应拦截: " + blocked);
        }
        // 公网地址放行
        assertFalse(securityGuard.isBlockedHost("8.8.8.8"));
        assertFalse(securityGuard.isBlockedHost("1.1.1.1"));

        // 联合校验：白名单精确匹配 + SSRF 黑名单
        assertEquals("8.8.8.8", securityGuard.assertSafeTarget("http://8.8.8.8/ping", List.of("8.8.8.8")));
        assertThrows(ServiceException.class, () -> securityGuard.assertSafeTarget(
            "http://169.254.169.254/latest/meta-data", List.of("169.254.169.254")),
            "白名单命中但命中云元数据黑名单应拒绝");
        assertThrows(ServiceException.class, () -> securityGuard.assertSafeTarget(
            "http://api.example.com/x", List.of("other.example.com")),
            "白名单不匹配应拒绝");

        // 有界响应读取：超 1MB 拒绝
        assertEquals(2, readBoundedSafely(securityGuard,
            new ByteArrayInputStream("ok".getBytes()), 1024).length);
        assertThrows(IOException.class, () -> securityGuard.readBounded(
            new ByteArrayInputStream(new byte[1024 * 1024 + 1]), 1024 * 1024), "超 1MB 响应应中止读取");

        // ExternalApiService 链路：登记元数据地址接口（白名单命中）→ testCall 被 SSRF 拦截，不发起请求
        long ts = System.currentTimeMillis();
        WfExternalApi meta = new WfExternalApi();
        meta.setApiCode("m5-ssrf-meta-" + ts);
        meta.setApiName("元数据探测");
        meta.setBaseUrl("http://169.254.169.254");
        meta.setPath("/latest/meta-data/");
        meta.setHttpMethod("GET");
        meta.setAuthType("NONE");
        meta.setTimeoutMs(5000);
        meta.setAllowedHostsJson("[\"169.254.169.254\"]");
        externalApiService.register(meta, "admin");
        assertThrows(ServiceException.class, () -> externalApiService.testCall(meta.getId(), null, "1"),
            "testCall 应被 SSRF 拦截");

        // 白名单不匹配 → 拒绝
        WfExternalApi mismatch = new WfExternalApi();
        mismatch.setApiCode("m5-ssrf-mismatch-" + ts);
        mismatch.setApiName("白名单不符");
        mismatch.setBaseUrl("http://8.8.8.8");
        mismatch.setPath("/x");
        mismatch.setHttpMethod("GET");
        mismatch.setAuthType("NONE");
        mismatch.setTimeoutMs(5000);
        mismatch.setAllowedHostsJson("[\"other.example.com\"]");
        externalApiService.register(mismatch, "admin");
        assertThrows(ServiceException.class, () -> externalApiService.testCall(mismatch.getId(), null, "1"),
            "base_url 主机不在白名单应拒绝");
    }

    /** 资源级鉴权：详情/时间线防 ID 遍历（PRD 17.3）。 */
    @Test
    @Order(10)
    void resourceLevelAuthPreventsIdTraversal() {
        WfApprovalInstance instance = submit("鉴权-500元", "m5-auth-key-001");
        String id = instance.getId();

        // 无关用户 999 → 拒绝（任意阶段）
        assertThrows(ServiceException.class, () -> approvalTaskService.instanceDetail(id, "999"));
        assertThrows(ServiceException.class, () -> approvalTaskService.timeline(id, "999"));
        assertFalse(approvalTaskService.canView(instance, "999"));

        // 申请人（1，同 admin）→ 允许
        assertEquals(id, approvalTaskService.instanceDetail(id, "1").getId());
        assertTrue(approvalTaskService.canView(instance, "1"));
        assertNotNull(approvalTaskService.timeline(id, "1"));

        // 提交后流程已推进到部门审批（apply 填单节点自动完成）：3 是办理人 → 允许；财务 5 尚未成为办理人 → 拒绝
        assertEquals(id, approvalTaskService.instanceDetail(id, "3").getId());
        assertThrows(ServiceException.class, () -> approvalTaskService.instanceDetail(id, "5"),
            "未成为办理人前应无权查看（防 ID 遍历）");

        // 完成部门审批：5 成为财务办理人 → 允许（权限随流程推进变化）
        completeDept(instance);
        WfApprovalTask financeTask = pendingTaskOf(instance, "financeApproval", "5");
        assertNotNull(financeTask, "推进后应出现财务审批任务");
        assertEquals(id, approvalTaskService.instanceDetail(id, "5").getId());
        assertTrue(approvalTaskService.canView(instance, "5"));

        completeAllPending(instance);
    }

    // ==================== 辅助 ====================

    private WfApprovalInstance submit(String title, String idempotencyKey) {
        Map<String, Object> formData = Map.of("applicant", "1", "amount", 6800);
        return approvalSubmissionService.submit(
            m5ApprovalDefinitionId, m5FormVersionId, title, formData,
            "1", "admin", "103", idempotencyKey, List.of());
    }

    private WfOutboxEvent eventOf(WfApprovalInstance instance, String eventType) {
        return outboxEventMapper.selectByAggregate(instance.getId()).stream()
            .filter(e -> eventType.equals(e.getEventType()))
            .findFirst().orElse(null);
    }

    private void outboxEventServiceDispatch() {
        outboxEventService.dispatchDueEvents();
    }

    private byte[] readBoundedSafely(ExternalApiSecurityGuard guard,
                                     java.io.InputStream in, int max) {
        try {
            return guard.readBounded(in, max);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** 完成部门审批全部待办（进入财务审批）。 */
    private void completeDept(WfApprovalInstance instance) {
        for (WfApprovalTask t : approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> "deptApproval".equals(t.getTaskDefinitionKey())
                && "PENDING".equals(t.getTaskStatus())).toList()) {
            approvalTaskService.approve(t.getFlowableTaskId(), "同意", t.getAssigneeUserId(),
                "m5-cd-" + instance.getId() + "-" + t.getFlowableTaskId());
        }
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

    private long projectionPendingCount(WfApprovalInstance instance) {
        return approvalTaskMapper.selectByInstance(instance.getId()).stream()
            .filter(t -> "PENDING".equals(t.getTaskStatus())).count();
    }

    /** 完成当前节点全部待办（跨节点轮询直到流程结束）。 */
    private void completeAllPending(WfApprovalInstance instance) {
        for (int round = 0; round < 6; round++) {
            List<WfApprovalTask> pending = approvalTaskMapper.selectByInstance(instance.getId()).stream()
                .filter(t -> "PENDING".equals(t.getTaskStatus())).toList();
            boolean progressed = false;
            for (WfApprovalTask t : pending) {
                Task flowable = taskService.createTaskQuery().taskId(t.getFlowableTaskId()).singleResult();
                if (flowable != null) {
                    approvalTaskService.approve(t.getFlowableTaskId(), "同意", t.getAssigneeUserId(),
                        "m5-run-" + instance.getId() + "-" + t.getFlowableTaskId() + "-" + round);
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
    }

    /** 绕过业务服务直接用引擎 complete 走完流程（模拟业务侧漏同步）。 */
    private void completeEngineDirect(String pid) {
        int guard = 0;
        while (guard++ < 20) {
            Task t = taskService.createTaskQuery().processInstanceId(pid)
                .orderByTaskCreateTime().asc().list().stream().findFirst().orElse(null);
            if (t == null) {
                break;
            }
            taskService.complete(t.getId());
        }
    }
}
