package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.bpmn.BpmnValidationService;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.document.FormSchemaVersionDoc;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalOperation;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.domain.WfWorkflowDefinition;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalActionMapper;
import com.ruoyi.workflow.mapper.WfApprovalDefinitionMapper;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalOperationMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.mapper.WfFormVersionMapper;
import com.ruoyi.workflow.mapper.WfWorkflowDefinitionMapper;
import com.ruoyi.workflow.mapper.WfWorkflowVersionMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;
import com.ruoyi.workflow.repository.FormSchemaVersionRepository;
import com.ruoyi.workflow.service.outbox.OutboxEventService;

/**
 * 审批提交事务链（PRD 10.1 + 14.1）。
 *
 * <p>提交流程：</p>
 * <ol>
 *     <li>幂等键校验（wf_approval_operation：同一用户同一幂等键不重复执行）；</li>
 *     <li>校验审批类型、表单版本、流程版本（发布状态由后端校验）；</li>
 *     <li>Mongo 先写完整表单 JSON（approval_form_data）；</li>
 *     <li>MySQL 事务：创建审批单投影 → 启动 Flowable 流程 → 关联流程实例
 *         → 解析审批人注入节点集合变量 → 同步任务投影 → 写操作记录；</li>
 *     <li>幂等操作标记 SUCCESS。MySQL 失败时 Mongo 可能留下孤立文档，由状态标记 + 定期清理处理（PRD 10.1）。</li>
 * </ol>
 */
@Service
public class ApprovalSubmissionService {

    public static final String ACTION_SUBMIT = "SUBMIT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUBMITTING = "SUBMITTING";

    private final WfApprovalDefinitionMapper approvalDefinitionMapper;
    private final WfWorkflowDefinitionMapper workflowDefinitionMapper;
    private final WfWorkflowVersionMapper workflowVersionMapper;
    private final WfFormVersionMapper formVersionMapper;
    private final WfApprovalInstanceMapper approvalInstanceMapper;
    private final WfApprovalTaskMapper approvalTaskMapper;
    private final WfApprovalActionMapper approvalActionMapper;
    private final WfApprovalOperationMapper operationMapper;
    private final ApprovalFormDataRepository formDataRepository;
    private final FormSchemaVersionRepository schemaRepository;
    private final FormSchemaValidator validator;
    private final TaskAssignmentService taskAssignmentService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;
    private final OutboxEventService outboxEventService;
    private final ApprovalCcService approvalCcService;

    public ApprovalSubmissionService(WfApprovalDefinitionMapper approvalDefinitionMapper,
                                     WfWorkflowDefinitionMapper workflowDefinitionMapper,
                                     WfWorkflowVersionMapper workflowVersionMapper,
                                     WfFormVersionMapper formVersionMapper,
                                     WfApprovalInstanceMapper approvalInstanceMapper,
                                     WfApprovalTaskMapper approvalTaskMapper,
                                     WfApprovalActionMapper approvalActionMapper,
                                     WfApprovalOperationMapper operationMapper,
                                     ApprovalFormDataRepository formDataRepository,
                                     FormSchemaVersionRepository schemaRepository,
                                     FormSchemaValidator validator,
                                     TaskAssignmentService taskAssignmentService,
                                     RuntimeService runtimeService,
                                     TaskService taskService,
                                     RepositoryService repositoryService,
                                     ObjectMapper objectMapper,
                                     OutboxEventService outboxEventService,
                                     ApprovalCcService approvalCcService) {
        this.approvalDefinitionMapper = approvalDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.formVersionMapper = formVersionMapper;
        this.approvalInstanceMapper = approvalInstanceMapper;
        this.approvalTaskMapper = approvalTaskMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.operationMapper = operationMapper;
        this.formDataRepository = formDataRepository;
        this.schemaRepository = schemaRepository;
        this.validator = validator;
        this.taskAssignmentService = taskAssignmentService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
        this.outboxEventService = outboxEventService;
        this.approvalCcService = approvalCcService;
    }

    /**
     * 提交审批（PRD 10.1 事务链）。
     *
     * @param approvalDefinitionId 审批类型ID
     * @param formVersionId        表单版本ID
     * @param title                审批标题
     * @param formData             表单 JSON
     * @param applicantUserId      申请人ID（从登录态取）
     * @param applicantName        申请人姓名快照
     * @param applicantDeptId      申请人部门ID
     * @param idempotencyKey       幂等键
     * @param ccUserIds            抄送人ID列表（可为空）
     * @return 审批单
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance submit(String approvalDefinitionId, String formVersionId, String title,
                                     Map<String, Object> formData, String applicantUserId, String applicantName,
                                     String applicantDeptId, String idempotencyKey, List<String> ccUserIds) {
        // 1. 幂等校验（PRD 7.3）：同一用户同一幂等键已处理则返回已存在结果
        WfApprovalOperation existing = operationMapper.selectByOperatorAndKey(applicantUserId, idempotencyKey);
        if (existing != null && "SUCCESS".equals(existing.getOperationStatus())) {
            WfApprovalInstance done = approvalInstanceMapper.selectById(existing.getApprovalInstanceId());
            if (done != null) {
                return done;
            }
        }

        // 2. 校验审批类型 / 表单版本 / 流程版本
        WfApprovalDefinition approvalDef = approvalDefinitionMapper.selectById(approvalDefinitionId);
        if (approvalDef == null || !"PUBLISHED".equals(approvalDef.getStatus())) {
            throw new ServiceException("审批类型不存在或未发布");
        }
        WfFormVersion formVersion = formVersionMapper.selectById(formVersionId);
        if (formVersion == null || !"PUBLISHED".equals(formVersion.getStatus())) {
            throw new ServiceException("表单版本不存在或未发布");
        }
        List<WfWorkflowDefinition> workflows = workflowDefinitionMapper.selectByApprovalDefinition(approvalDefinitionId);
        WfWorkflowDefinition workflowDef = workflows.stream()
            .filter(w -> "PUBLISHED".equals(w.getStatus()))
            .findFirst().orElse(null);
        if (workflowDef == null) {
            throw new ServiceException("审批类型未绑定已发布流程");
        }
        WfWorkflowVersion workflowVersion = workflowVersionMapper.selectByWorkflowAndVersion(
            workflowDef.getId(), workflowDef.getCurrentVersion());
        if (workflowVersion == null) {
            throw new ServiceException("流程版本不存在: " + workflowDef.getCurrentVersion());
        }

        // 3. 表单校验（必填 + 类型）
        FormSchemaVersionDoc schemaDoc = schemaRepository.findById(formVersion.getMongoSchemaId())
            .orElseThrow(() -> new ServiceException("MongoDB 版本快照缺失"));
        validator.validate(schemaDoc.getSchema(), formData, true);

        // 4. Mongo 先写完整表单 JSON
        ApprovalFormDataDoc dataDoc = new ApprovalFormDataDoc();
        dataDoc.setId(UUID.randomUUID().toString());
        dataDoc.setApprovalInstanceId(UUID.randomUUID().toString());
        dataDoc.setFormVersionId(formVersionId);
        dataDoc.setSchemaHash(schemaDoc.getSchemaHash());
        dataDoc.setDataVersion(1);
        dataDoc.setData(formData);
        dataDoc.setCreatedAt(new Date());
        dataDoc.setUpdatedAt(new Date());
        formDataRepository.save(dataDoc);

        // 5. 幂等操作记录（先写，防重复）
        WfApprovalOperation operation = new WfApprovalOperation();
        operation.setId(UUID.randomUUID().toString());
        operation.setApprovalInstanceId(dataDoc.getApprovalInstanceId());
        operation.setOperationType(ACTION_SUBMIT);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperatorUserId(applicantUserId);
        operation.setOperationStatus("PROCESSING");
        operation.setRequestJson(toJsonString(Map.of("approvalDefinitionId", approvalDefinitionId,
            "formVersionId", formVersionId, "title", title)));
        operationMapper.insert(operation);

        // 6. 创建审批单投影
        WfApprovalInstance instance = new WfApprovalInstance();
        instance.setId(dataDoc.getApprovalInstanceId());
        instance.setApprovalNo(generateApprovalNo());
        instance.setApprovalDefinitionId(approvalDefinitionId);
        instance.setFormVersionId(formVersionId);
        instance.setWorkflowVersionId(workflowVersion.getId());
        instance.setApplicantUserId(applicantUserId);
        instance.setApplicantNameSnapshot(applicantName);
        instance.setApplicantDeptId(applicantDeptId);
        instance.setTitle(title);
        instance.setFormDataId(dataDoc.getId());
        instance.setBusinessStatus(STATUS_SUBMITTING);
        instance.setVersion(0);
        instance.setCreatedAt(new Date());
        approvalInstanceMapper.insert(instance);

        // 6.1 抄送记录（P7：提交时指定抄送人，被抄送人可在「抄送我的」查看）
        approvalCcService.addCc(instance.getId(), applicantUserId, ccUserIds);

        // 7. 启动 Flowable 流程，注入节点审批人集合变量（collection 驱动多实例）
        Map<String, Object> variables = new HashMap<>();
        variables.put("approvalInstanceId", instance.getId());
        variables.put("applicantUserId", applicantUserId);

        // 解析各审批节点审批人（节点配置从流程 config_json 读取）
        Map<String, Map<String, Object>> nodeAssignments = extractNodeAssignments(workflowVersion.getConfigJson());
        Map<String, List<String>> resolved = taskAssignmentService.resolveAll(
            nodeAssignments, applicantUserId, applicantDeptId, formData);

        // 流程中每个 UserTask 都必须有节点专属 collection（规一化 N1 强制多实例）：
        // 已配置审批人的节点用解析结果，其余（申请/填单节点）默认申请人本人
        for (String taskKey : userTaskKeys(workflowVersion.getProcessDefinitionId())) {
            List<String> users = resolved.getOrDefault(taskKey, List.of(applicantUserId));
            variables.put(BpmnValidationService.USERS_COLLECTION_PREFIX + taskKey, users);
        }

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
            workflowVersion.getProcessDefinitionKey(), instance.getId(), variables);
        instance.setProcessInstanceId(pi.getId());
        instance.setProcessDefinitionId(pi.getProcessDefinitionId());
        instance.setBusinessStatus(STATUS_PENDING);
        instance.setSubmittedAt(new Date());
        approvalInstanceMapper.update(instance);

        // 8. 任务投影同步：申请/填单节点自动完成进入审批；审批节点任务同步到投影表作为待办
        syncTasks(instance.getId(), pi.getId(), resolved.keySet());

        // 9. 操作记录
        WfApprovalAction action = new WfApprovalAction();
        action.setId(UUID.randomUUID().toString());
        action.setApprovalInstanceId(instance.getId());
        action.setOperatorUserId(applicantUserId);
        action.setActionType(ACTION_SUBMIT);
        action.setComment("提交审批");
        action.setBeforeStatus(STATUS_SUBMITTING);
        action.setAfterStatus(STATUS_PENDING);
        action.setOperationId(operation.getId());
        action.setCreatedAt(new Date());
        approvalActionMapper.insert(action);

        // 10. 幂等操作成功
        operation.setOperationStatus("SUCCESS");
        operation.setResultJson(toJsonString(Map.of("approvalInstanceId", instance.getId(),
            "approvalNo", instance.getApprovalNo())));
        operationMapper.update(operation);

        // 11. M5 outbox：提交事件（PRD 16.3，与审批事务原子提交，通知失败可重试不影响主流程）
        outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_SUBMITTED",
            Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                "applicantUserId", applicantUserId, "title", title));

        return instance;
    }

    /**
     * 从流程 config_json 提取节点审批人配置。
     * 约定：{ "nodes": { "<nodeId>": { "assignType": "USER|ROLE|...", "assignValue": "...", "formField": "..." } } }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> extractNodeAssignments(String configJson) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (configJson == null || configJson.isBlank()) {
            return result;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object nodes = config.get("nodes");
            if (nodes instanceof Map m) {
                for (Object entryObj : m.entrySet()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                    if (entry.getValue() instanceof Map) {
                        result.put(String.valueOf(entry.getKey()), (Map<String, Object>) entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            throw new ServiceException("流程节点配置解析失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 同步启动后产生的任务到投影表：
     * 第一轮自动完成填单节点任务（提交即进入审批，如申请节点实例数=1 退化为普通任务，M0 N1）；
     * 第二轮将新建的审批节点任务写入投影（PENDING）。
     * 注意：createTaskQuery 返回的是快照，完成填单节点会产生新任务，必须两轮查询。
     *
     * @param approvalNodeKeys 已解析审批人的节点Key集合（用于识别填单节点）
     */
    private void syncTasks(String approvalInstanceId, String processInstanceId,
                           java.util.Set<String> approvalNodeKeys) {
        // 第一轮：自动完成填单节点任务
        for (Task t : taskService.createTaskQuery().processInstanceId(processInstanceId).list()) {
            if (!approvalNodeKeys.contains(t.getTaskDefinitionKey())) {
                taskService.complete(t.getId());
            }
        }
        // 第二轮：审批节点任务写投影
        for (Task t : taskService.createTaskQuery().processInstanceId(processInstanceId).list()) {
            WfApprovalTask existing = approvalTaskMapper.selectByFlowableTaskId(t.getId());
            if (existing != null) {
                continue;
            }
            WfApprovalTask task = new WfApprovalTask();
            task.setId(UUID.randomUUID().toString());
            task.setApprovalInstanceId(approvalInstanceId);
            task.setProcessInstanceId(processInstanceId);
            task.setFlowableTaskId(t.getId());
            task.setTaskDefinitionKey(t.getTaskDefinitionKey());
            task.setTaskName(t.getName());
            task.setAssigneeUserId(t.getAssignee());
            task.setOriginalAssigneeUserId(t.getAssignee());
            task.setTaskStatus("PENDING");
            task.setCreatedAt(new Date());
            approvalTaskMapper.insert(task);
        }
    }

    /**
     * 流程定义中所有 UserTask 的节点 Key（供注入节点专属 collection 变量）。
     */
    private List<String> userTaskKeys(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        List<String> keys = new java.util.ArrayList<>();
        if (model != null) {
            for (org.flowable.bpmn.model.Process process : model.getProcesses()) {
                for (FlowElement fe : process.getFlowElements()) {
                    if (fe instanceof UserTask ut && ut.getId() != null) {
                        keys.add(ut.getId());
                    }
                }
            }
        }
        return keys;
    }

    private String generateApprovalNo() {
        java.time.format.DateTimeFormatter fmt =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String ts = java.time.LocalDateTime.now().format(fmt);
        String rand = String.format("%04d", (int) (Math.random() * 10000));
        return "AP" + ts + rand;
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
