package com.ruoyi.workflow.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalDefinition;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalOperation;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.domain.WfTaskAddSign;
import com.ruoyi.workflow.domain.WfTaskReturn;
import com.ruoyi.workflow.domain.WfTaskTransfer;
import com.ruoyi.workflow.domain.WfWorkflowVersion;
import com.ruoyi.workflow.mapper.WfApprovalActionMapper;
import com.ruoyi.workflow.mapper.WfApprovalDefinitionMapper;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalOperationMapper;
import com.ruoyi.workflow.service.outbox.OutboxEventService;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.mapper.WfTaskAddSignMapper;
import com.ruoyi.workflow.mapper.WfTaskReturnMapper;
import com.ruoyi.workflow.mapper.WfTaskTransferMapper;
import com.ruoyi.workflow.mapper.WfWorkflowVersionMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;

/**
 * 审批任务服务（PRD 10.2/10.3 + 11.1–11.5 + 14.2/14.3）。
 *
 * <p>基础：同意=Flowable complete→流程结束则 APPROVED；驳回=change-state 到申请人节点→RETURNED。</p>
 * <p>M4 高级操作（全部接入 wf_approval_operation 幂等）：</p>
 * <ul>
 *     <li>转交：setAssignee + 投影同步 + wf_task_transfer（PRD 11.1）；</li>
 *     <li>加签：addMultiInstanceExecution（v1 并行 ALL 规则）+ wf_task_add_sign（PRD 11.2）；</li>
 *     <li>退回任意节点：白名单校验 + change-state；多实例目标走 §3.3.2 方式二（退前置 + 集合变量重写，
 *         规避 Flowable 6.8 collection-MI 直退空壳）+ wf_task_return（PRD 11.3）；</li>
 *     <li>撤回（仅申请人）/ 取消（管理员）：deleteProcessInstance + 投影批量 CANCELLED（PRD 11.4/11.5）。</li>
 * </ul>
 */
@Service
public class ApprovalTaskService {

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_TRANSFER = "TRANSFER";
    public static final String ACTION_ADD_SIGN = "ADD_SIGN";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_WITHDRAW = "WITHDRAW";
    public static final String ACTION_CANCEL = "CANCEL";

    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";
    public static final String TASK_STATUS_CANCELLED = "CANCELLED";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String OP_PROCESSING = "PROCESSING";
    public static final String OP_SUCCESS = "SUCCESS";

    public static final String RETURN_TARGET_APPLICANT = "APPLICANT";
    public static final String RETURN_TARGET_MI = "MI";
    public static final String RETURN_TARGET_PLAIN = "PLAIN";

    /** 加签元素变量前缀（与规一化一致：wf_assignee_&lt;nodeId&gt;）。 */
    public static final String ELEMENT_VARIABLE_PREFIX = "wf_assignee_";

    private final WfApprovalTaskMapper approvalTaskMapper;
    private final WfApprovalInstanceMapper approvalInstanceMapper;
    private final WfApprovalActionMapper approvalActionMapper;
    private final WfApprovalOperationMapper operationMapper;
    private final WfTaskTransferMapper taskTransferMapper;
    private final WfTaskAddSignMapper taskAddSignMapper;
    private final WfTaskReturnMapper taskReturnMapper;
    private final WfWorkflowVersionMapper workflowVersionMapper;
    private final WfApprovalDefinitionMapper approvalDefinitionMapper;
    private final TaskAssignmentService taskAssignmentService;
    private final ApprovalFormDataRepository formDataRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final OutboxEventService outboxEventService;

    public ApprovalTaskService(WfApprovalTaskMapper approvalTaskMapper,
                               WfApprovalInstanceMapper approvalInstanceMapper,
                               WfApprovalActionMapper approvalActionMapper,
                               WfApprovalOperationMapper operationMapper,
                               WfTaskTransferMapper taskTransferMapper,
                               WfTaskAddSignMapper taskAddSignMapper,
                               WfTaskReturnMapper taskReturnMapper,
                               WfWorkflowVersionMapper workflowVersionMapper,
                               WfApprovalDefinitionMapper approvalDefinitionMapper,
                               TaskAssignmentService taskAssignmentService,
                               ApprovalFormDataRepository formDataRepository,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               TaskService taskService,
                               RuntimeService runtimeService,
                               RepositoryService repositoryService,
                               OutboxEventService outboxEventService) {
        this.approvalTaskMapper = approvalTaskMapper;
        this.approvalInstanceMapper = approvalInstanceMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.operationMapper = operationMapper;
        this.taskTransferMapper = taskTransferMapper;
        this.taskAddSignMapper = taskAddSignMapper;
        this.taskReturnMapper = taskReturnMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.approvalDefinitionMapper = approvalDefinitionMapper;
        this.taskAssignmentService = taskAssignmentService;
        this.formDataRepository = formDataRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.outboxEventService = outboxEventService;
    }

    // ==================== 基础操作（M3） ====================

    /**
     * 同意审批（PRD 10.2）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance approve(String taskId, String comment, String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        Task task = requirePendingTask(taskId, operator);
        WfApprovalInstance instance = instanceOf(task);
        WfApprovalTask projection = approvalTaskMapper.selectByFlowableTaskId(task.getId());
        String beforeStatus = projection == null ? TASK_STATUS_PENDING : projection.getTaskStatus();

        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_APPROVE, operator, idempotencyKey, taskId, comment);

        taskService.complete(task.getId());

        if (projection != null) {
            projection.setTaskStatus(TASK_STATUS_COMPLETED);
            projection.setCompletedAt(new Date());
            approvalTaskMapper.update(projection);
        }

        boolean ended = runtimeService.createProcessInstanceQuery()
            .processInstanceId(task.getProcessInstanceId()).singleResult() == null;
        Date now = new Date();
        if (ended) {
            instance.setBusinessStatus(STATUS_APPROVED);
            instance.setCompletedAt(now);
        } else {
            String nextNodeKey = refreshCurrentNode(instance, task.getProcessInstanceId());
            // M4：审批推进到新节点时同步补齐新节点任务投影
            // （M3 单节点流程未暴露此缺口；多节点流程下 complete 会生成下游任务，须补投影）
            if (nextNodeKey != null) {
                syncTasksForNode(instance, task.getProcessInstanceId(), nextNodeKey);
            }
        }
        approvalInstanceMapper.update(instance);

        insertAction(instance.getId(), task.getId(), task.getTaskDefinitionKey(), operator, ACTION_APPROVE,
            comment, beforeStatus, ended ? STATUS_APPROVED : STATUS_PENDING, op.getId());

        finishOperation(op, instance.getId());

        // M5 outbox：流程结束才发终态事件（PRD 16.3，与审批事务原子提交）
        if (ended) {
            outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_APPROVED",
                Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                    "applicantUserId", instance.getApplicantUserId()));
        }
        return instance;
    }

    /**
     * 驳回审批（PRD 10.3 模式一：驳回至申请人修改，白名单 v1 仅申请人节点）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance reject(String taskId, String comment, String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        Task task = requirePendingTask(taskId, operator);
        WfApprovalInstance instance = instanceOf(task);
        WfApprovalTask projection = approvalTaskMapper.selectByFlowableTaskId(task.getId());
        String beforeStatus = projection == null ? TASK_STATUS_PENDING : projection.getTaskStatus();

        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_REJECT, operator, idempotencyKey, taskId, comment);

        String applicantNodeKey = applicantNodeKey(task.getProcessInstanceId());
        String applicantNodeName = nodeName(task.getProcessInstanceId(), applicantNodeKey);

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(task.getProcessInstanceId())
            .moveActivityIdTo(task.getTaskDefinitionKey(), applicantNodeKey)
            .changeState();

        if (projection != null) {
            projection.setTaskStatus(TASK_STATUS_COMPLETED);
            projection.setCompletedAt(new Date());
            approvalTaskMapper.update(projection);
        }

        syncTasksForNode(instance, task.getProcessInstanceId(), applicantNodeKey);

        instance.setBusinessStatus(STATUS_RETURNED);
        instance.setCurrentNodeKey(applicantNodeKey);
        instance.setCurrentNodeName(applicantNodeName);
        approvalInstanceMapper.update(instance);

        insertAction(instance.getId(), task.getId(), task.getTaskDefinitionKey(), operator, ACTION_REJECT,
            comment, beforeStatus, STATUS_RETURNED, op.getId());

        finishOperation(op, instance.getId());
        outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_REJECTED",
            Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                "operatorUserId", operator));
        return instance;
    }

    // ==================== M4 高级操作 ====================

    /**
     * 转交审批（PRD 11.1）：把当前任务的办理责任交给另一个用户。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance transfer(String taskId, String toUserId, String reason,
                                       String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        Task task = requirePendingTask(taskId, operator);
        if (toUserId == null || toUserId.isBlank()) {
            throw new ServiceException("转交目标用户不能为空");
        }
        if (operator.equals(toUserId)) {
            throw new ServiceException("不能转交给自己");
        }
        requireValidUser(toUserId);
        WfApprovalInstance instance = instanceOf(task);
        String fromUserId = task.getAssignee();

        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_TRANSFER, operator, idempotencyKey,
            taskId, reason);

        // Flowable 办理人更新（不能只改投影）
        taskService.setAssignee(taskId, toUserId);
        taskService.setOwner(taskId, operator);

        // 投影同步：assignee 更新，delegated_to_user_id 留转交人，original 保持初始办理人
        WfApprovalTask projection = approvalTaskMapper.selectByFlowableTaskId(taskId);
        if (projection != null) {
            projection.setAssigneeUserId(toUserId);
            projection.setDelegatedToUserId(operator);
            approvalTaskMapper.update(projection);
        }

        // 转交记录（原办理人、新办理人、原因）
        WfTaskTransfer record = new WfTaskTransfer();
        record.setId(UUID.randomUUID().toString());
        record.setApprovalInstanceId(instance.getId());
        record.setFlowableTaskId(taskId);
        record.setTaskDefinitionKey(task.getTaskDefinitionKey());
        record.setFromUserId(fromUserId);
        record.setToUserId(toUserId);
        record.setReason(reason);
        record.setOperatorUserId(operator);
        record.setCreatedAt(new Date());
        taskTransferMapper.insert(record);

        insertAction(instance.getId(), taskId, task.getTaskDefinitionKey(), operator, ACTION_TRANSFER,
            reason, TASK_STATUS_PENDING, TASK_STATUS_PENDING, op.getId());

        finishOperation(op, instance.getId());
        return instance;
    }

    /**
     * 加签审批（PRD 11.2）：对活跃的多实例审批节点追加审批人实例（v1 并行 ALL 规则）。
     *
     * @param signType FIRST=前加签 / AFTER=后加签（引擎层同一 API，业务层区分记录）
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance addSign(String taskId, String signType, List<String> addUsers, String reason,
                                      String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        Task task = requirePendingTask(taskId, operator);
        if (addUsers == null || addUsers.isEmpty()) {
            throw new ServiceException("加签人员不能为空");
        }
        if (!"FIRST".equals(signType) && !"AFTER".equals(signType)) {
            throw new ServiceException("加签类型仅支持 FIRST（前加签）/ AFTER（后加签）");
        }
        WfApprovalInstance instance = instanceOf(task);
        String nodeKey = task.getTaskDefinitionKey();

        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_ADD_SIGN, operator, idempotencyKey,
            taskId, reason);

        // v1 仅并行 ALL 多实例节点可加签（M0 S2 结论）
        requireParallelAllNode(task.getProcessInstanceId(), nodeKey);

        // 加签：addMultiInstanceExecution 追加实例，元素变量 = wf_assignee_<node>
        for (String user : addUsers) {
            requireValidUser(user);
            runtimeService.addMultiInstanceExecution(nodeKey, task.getProcessInstanceId(),
                Map.of(ELEMENT_VARIABLE_PREFIX + nodeKey, user));
            WfTaskAddSign record = new WfTaskAddSign();
            record.setId(UUID.randomUUID().toString());
            record.setApprovalInstanceId(instance.getId());
            record.setFlowableTaskId(taskId);
            record.setTaskDefinitionKey(nodeKey);
            record.setSignType(signType);
            record.setAddUserId(user);
            record.setReason(reason);
            record.setOperatorUserId(operator);
            record.setCreatedAt(new Date());
            taskAddSignMapper.insert(record);
        }

        // 同步新建的加签任务投影（addMultiInstanceExecution 后需重新查询）
        syncTasksForNode(instance, task.getProcessInstanceId(), nodeKey);

        insertAction(instance.getId(), taskId, nodeKey, operator, ACTION_ADD_SIGN,
            reason, TASK_STATUS_PENDING, TASK_STATUS_PENDING, op.getId());

        finishOperation(op, instance.getId());
        return instance;
    }

    /**
     * 退回任意节点（PRD 11.3）：白名单校验 + change-state。
     *
     * <p>目标类型：</p>
     * <ul>
     *     <li>APPLICANT/PLAIN：直接 {@code moveActivityIdTo}；</li>
     *     <li>MI：走 §3.3.2 方式二 —— 退到目标节点的前置串行节点 + 按最新审批人配置重写
     *         {@code wf_users_<target>} 集合变量，随后流程自然推进按新集合重建实例。
     *         （Flowable 6.8 直退 collection-MI 目标会造空壳执行树，S6 探针实证，故不能方式一。）</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance returnTask(String taskId, String targetNodeKey, String reason,
                                         String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        Task task = requirePendingTask(taskId, operator);
        if (targetNodeKey == null || targetNodeKey.isBlank()) {
            throw new ServiceException("退回目标节点不能为空");
        }
        WfApprovalInstance instance = instanceOf(task);
        String processInstanceId = task.getProcessInstanceId();

        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_RETURN, operator, idempotencyKey,
            taskId, reason);

        // 白名单校验 + 解析目标（同版本 BPMN）
        ReturnTargetInfo target = resolveReturnTarget(processInstanceId, task.getTaskDefinitionKey(), targetNodeKey);

        // 执行受控活动状态变更
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(processInstanceId)
            .moveActivityIdTo(task.getTaskDefinitionKey(), target.moveToNodeKey)
            .changeState();

        // MI 目标：方式二 —— 重写集合变量（按最新审批人配置重建，人员可更新）
        if (RETURN_TARGET_MI.equals(target.targetNodeType)) {
            rewriteUsersCollection(instance, targetNodeKey);
        }

        // 源任务投影 → COMPLETED
        WfApprovalTask projection = approvalTaskMapper.selectByFlowableTaskId(taskId);
        if (projection != null) {
            projection.setTaskStatus(TASK_STATUS_COMPLETED);
            projection.setCompletedAt(new Date());
            approvalTaskMapper.update(projection);
        }

        // 重建目标（前置）节点任务投影
        syncTasksForNode(instance, processInstanceId, target.moveToNodeKey);

        // 审批单 → RETURNED，当前节点 = 实际退回位置
        instance.setBusinessStatus(STATUS_RETURNED);
        instance.setCurrentNodeKey(target.moveToNodeKey);
        instance.setCurrentNodeName(nodeName(processInstanceId, target.moveToNodeKey));
        approvalInstanceMapper.update(instance);

        // 退回记录
        WfTaskReturn record = new WfTaskReturn();
        record.setId(UUID.randomUUID().toString());
        record.setApprovalInstanceId(instance.getId());
        record.setSourceFlowableTaskId(taskId);
        record.setSourceNodeKey(task.getTaskDefinitionKey());
        record.setTargetNodeKey(targetNodeKey);
        record.setTargetNodeType(target.targetNodeType);
        record.setReason(reason);
        record.setOperatorUserId(operator);
        record.setCreatedAt(new Date());
        taskReturnMapper.insert(record);

        insertAction(instance.getId(), taskId, task.getTaskDefinitionKey(), operator, ACTION_RETURN,
            reason, TASK_STATUS_PENDING, STATUS_RETURNED, op.getId());

        finishOperation(op, instance.getId());
        outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_RETURNED",
            Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                "targetNodeKey", targetNodeKey, "operatorUserId", operator));
        return instance;
    }

    /**
     * 退回目标白名单（PRD 11.3 校验清单第 3 条）：返回任务可退回的节点列表。
     */
    public List<ReturnTargetVo> returnTargets(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ServiceException("任务不存在: " + taskId);
        }
        String currentKey = task.getTaskDefinitionKey();
        String applicantKey = findApplicantNodeKey(task.getProcessInstanceId());
        List<ReturnTargetVo> result = new ArrayList<>();
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionIdOf(task.getProcessInstanceId()));
        if (model == null) {
            return result;
        }
        for (Process process : model.getProcesses()) {
            for (FlowElement fe : process.getFlowElements()) {
                if (!(fe instanceof UserTask ut) || currentKey.equals(ut.getId())) {
                    continue;
                }
                String nodeType;
                boolean available = true;
                String reason = null;
                if (ut.getId().equals(applicantKey)) {
                    nodeType = RETURN_TARGET_APPLICANT;
                } else if (ut.getLoopCharacteristics() != null) {
                    nodeType = RETURN_TARGET_MI;
                    // MI 目标走方式二：要求存在可退回的前置串行节点
                    String predecessor = predecessorPlainNode(ut);
                    if (predecessor == null) {
                        available = false;
                        reason = "多实例节点无可退前置串行节点（方式二不可用）";
                    }
                } else {
                    nodeType = RETURN_TARGET_PLAIN;
                }
                result.add(new ReturnTargetVo(ut.getId(), ut.getName(), nodeType, available, reason));
            }
        }
        return result;
    }

    /**
     * 撤回审批（PRD 11.4）：仅申请人可撤回本人 PENDING 的审批。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance withdraw(String approvalInstanceId, String reason,
                                       String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        WfApprovalInstance instance = instanceDetail(approvalInstanceId);
        if (operator == null || !operator.equals(instance.getApplicantUserId())) {
            throw new ServiceException("仅申请人可撤回本人提交的审批");
        }
        if (!STATUS_PENDING.equals(instance.getBusinessStatus())) {
            throw new ServiceException("仅 PENDING 状态的审批可撤回");
        }
        requireAllowOperation(instance.getApprovalDefinitionId(), true, "撤回");

        WfApprovalOperation op = beginOperation(approvalInstanceId, ACTION_WITHDRAW, operator, idempotencyKey,
            null, reason);
        endProcessInstance(instance, reason);
        instance.setBusinessStatus(STATUS_WITHDRAWN);
        instance.setCurrentNodeKey(null);
        instance.setCurrentNodeName(null);
        approvalInstanceMapper.update(instance);
        insertAction(approvalInstanceId, null, null, operator, ACTION_WITHDRAW,
            reason, STATUS_PENDING, STATUS_WITHDRAWN, op.getId());
        finishOperation(op, approvalInstanceId);
        outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_WITHDRAWN",
            Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                "operatorUserId", operator));
        return instance;
    }

    /**
     * 取消审批（PRD 11.5）：有权限的用户/管理员取消业务流程（v1：admin 用户 ID 1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WfApprovalInstance cancel(String approvalInstanceId, String reason,
                                     String operator, String idempotencyKey) {
        WfApprovalInstance idempotent = checkIdempotent(operator, idempotencyKey);
        if (idempotent != null) {
            return idempotent;
        }
        WfApprovalInstance instance = instanceDetail(approvalInstanceId);
        if (operator == null || !"1".equals(operator)) {
            throw new ServiceException("仅管理员可取消审批");
        }
        if (!STATUS_PENDING.equals(instance.getBusinessStatus())) {
            throw new ServiceException("仅 PENDING 状态的审批可取消");
        }
        requireAllowOperation(instance.getApprovalDefinitionId(), false, "取消");

        WfApprovalOperation op = beginOperation(approvalInstanceId, ACTION_CANCEL, operator, idempotencyKey,
            null, reason);
        endProcessInstance(instance, reason);
        instance.setBusinessStatus(STATUS_CANCELLED);
        instance.setCurrentNodeKey(null);
        instance.setCurrentNodeName(null);
        approvalInstanceMapper.update(instance);
        insertAction(approvalInstanceId, null, null, operator, ACTION_CANCEL,
            reason, STATUS_PENDING, STATUS_CANCELLED, op.getId());
        finishOperation(op, approvalInstanceId);
        outboxEventService.publish("APPROVAL", instance.getId(), "APPROVAL_CANCELLED",
            Map.of("approvalInstanceId", instance.getId(), "approvalNo", instance.getApprovalNo(),
                "operatorUserId", operator));
        return instance;
    }

    // ==================== 查询 ====================

    /**
     * 我的待办（投影表 task_status in CREATED/PENDING）。
     */
    public List<WfApprovalTask> todo(String operator) {
        return approvalTaskMapper.selectTodoByAssignee(operator);
    }

    /**
     * 我的已办（投影表 task_status in COMPLETED/CANCELLED）。
     */
    public List<WfApprovalTask> done(String operator) {
        return approvalTaskMapper.selectDoneByAssignee(operator);
    }

    /**
     * 我发起的审批单。
     */
    public List<WfApprovalInstance> myInstances(String operator) {
        return approvalInstanceMapper.selectByApplicant(operator);
    }

    /**
     * 审批单详情（内部：无鉴权，供审批动作/恢复等内部调用）。
     */
    public WfApprovalInstance instanceDetail(String approvalInstanceId) {
        WfApprovalInstance instance = approvalInstanceMapper.selectById(approvalInstanceId);
        if (instance == null) {
            throw new ServiceException("审批单不存在: " + approvalInstanceId);
        }
        return instance;
    }

    /**
     * 审批单详情（对外：资源级鉴权，PRD 17.3）。
     *
     * <p>仅申请人 / 该单任务办理人 / 管理员（"1"）可查看，防 ID 遍历。</p>
     */
    public WfApprovalInstance instanceDetail(String approvalInstanceId, String operator) {
        WfApprovalInstance instance = instanceDetail(approvalInstanceId);
        requireCanView(instance, operator);
        return instance;
    }

    /**
     * 时间线：审批操作记录按时间正序聚合（PRD GET /approvals/{id}/timeline）。
     */
    public List<WfApprovalAction> timeline(String approvalInstanceId) {
        instanceDetail(approvalInstanceId);
        return approvalActionMapper.selectByInstance(approvalInstanceId);
    }

    /**
     * 时间线（对外：资源级鉴权，PRD 17.3）。
     */
    public List<WfApprovalAction> timeline(String approvalInstanceId, String operator) {
        instanceDetail(approvalInstanceId, operator);
        return approvalActionMapper.selectByInstance(approvalInstanceId);
    }

    /**
     * 资源级鉴权：仅申请人 / 该单任务办理人 / 管理员（"1"）可访问。
     */
    public boolean canView(WfApprovalInstance instance, String operator) {
        if (operator == null || operator.isBlank()) {
            return false;
        }
        if ("1".equals(operator)) {
            return true;
        }
        if (operator.equals(instance.getApplicantUserId())) {
            return true;
        }
        return approvalTaskMapper.existsByInstanceAndAssignee(instance.getId(), operator);
    }

    private void requireCanView(WfApprovalInstance instance, String operator) {
        if (!canView(instance, operator)) {
            throw new ServiceException("无权查看该审批单");
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 幂等校验：同一用户同一幂等键已 SUCCESS 则直接返回结果，不重复执行。
     */
    private WfApprovalInstance checkIdempotent(String operator, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException("幂等键不能为空");
        }
        WfApprovalOperation existing = operationMapper.selectByOperatorAndKey(operator, idempotencyKey);
        if (existing != null && OP_SUCCESS.equals(existing.getOperationStatus())) {
            WfApprovalInstance done = approvalInstanceMapper.selectById(existing.getApprovalInstanceId());
            if (done != null) {
                return done;
            }
        }
        return null;
    }

    /**
     * 校验任务存在且待办、操作人是办理人。
     */
    private Task requirePendingTask(String taskId, String operator) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ServiceException("任务不存在或已处理: " + taskId);
        }
        if (operator == null || !operator.equals(task.getAssignee())) {
            throw new ServiceException("当前用户不是该任务的办理人，无权处理");
        }
        return task;
    }

    /**
     * 校验用户是有效系统用户（sys_user 有效状态）。
     */
    private void requireValidUser(String userId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from sys_user where user_id = ? and del_flag = '0' and status = '0'",
            Integer.class, userId);
        if (count == null || count == 0) {
            throw new ServiceException("目标用户不存在或已停用: " + userId);
        }
    }

    /**
     * 校验节点是并行 ALL 多实例节点（v1 加签仅并行 ALL 规则，M0 S2 结论）。
     */
    private void requireParallelAllNode(String processInstanceId, String nodeKey) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionIdOf(processInstanceId));
        if (model == null) {
            throw new ServiceException("流程定义不存在");
        }
        for (Process process : model.getProcesses()) {
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof UserTask ut && nodeKey.equals(ut.getId())) {
                    MultiInstanceLoopCharacteristics mi = ut.getLoopCharacteristics();
                    if (mi == null || mi.isSequential()) {
                        throw new ServiceException("节点 [" + nodeKey + "] 非并行多实例节点，不支持加签");
                    }
                    String condition = mi.getCompletionCondition();
                    if (condition != null && !condition.isBlank()) {
                        throw new ServiceException("节点 [" + nodeKey
                            + "] 为或签/比例签（completionCondition 非 ALL），v1 不支持加签");
                    }
                    return;
                }
            }
        }
        throw new ServiceException("节点 [" + nodeKey + "] 不存在");
    }

    /**
     * 从流程实例反查审批单（投影缺失时兜底走流程变量）。
     */
    @SuppressWarnings("unchecked")
    private WfApprovalInstance instanceOf(Task task) {
        WfApprovalTask projection = approvalTaskMapper.selectByFlowableTaskId(task.getId());
        String approvalInstanceId = projection != null
            ? projection.getApprovalInstanceId()
            : String.valueOf(((Map<String, Object>) runtimeService.getVariables(task.getProcessInstanceId()))
                .getOrDefault("approvalInstanceId", ""));
        WfApprovalInstance instance = approvalInstanceMapper.selectById(approvalInstanceId);
        if (instance == null) {
            throw new ServiceException("审批单不存在: " + approvalInstanceId);
        }
        return instance;
    }

    private WfApprovalOperation beginOperation(String approvalInstanceId, String operationType, String operator,
                                               String idempotencyKey, String taskId, String comment) {
        WfApprovalOperation op = new WfApprovalOperation();
        op.setId(UUID.randomUUID().toString());
        op.setApprovalInstanceId(approvalInstanceId);
        op.setOperationType(operationType);
        op.setIdempotencyKey(idempotencyKey);
        op.setOperatorUserId(operator);
        op.setOperationStatus(OP_PROCESSING);
        op.setRequestJson("{\"taskId\":\"" + (taskId == null ? "" : taskId)
            + "\",\"comment\":\"" + safe(comment) + "\"}");
        operationMapper.insert(op);
        return op;
    }

    private void finishOperation(WfApprovalOperation op, String approvalInstanceId) {
        op.setOperationStatus(OP_SUCCESS);
        op.setResultJson("{\"approvalInstanceId\":\"" + approvalInstanceId + "\"}");
        operationMapper.update(op);
    }

    private void insertAction(String approvalInstanceId, String flowableTaskId, String taskKey, String operator,
                              String actionType, String comment, String before, String after, String operationId) {
        WfApprovalAction action = new WfApprovalAction();
        action.setId(UUID.randomUUID().toString());
        action.setApprovalInstanceId(approvalInstanceId);
        action.setFlowableTaskId(flowableTaskId);
        action.setTaskDefinitionKey(taskKey);
        action.setOperatorUserId(operator);
        action.setActionType(actionType);
        action.setComment(comment);
        action.setBeforeStatus(before);
        action.setAfterStatus(after);
        action.setOperationId(operationId);
        action.setCreatedAt(new Date());
        approvalActionMapper.insert(action);
    }

    /**
     * 刷新审批单当前节点（取第一个活跃任务），返回活跃节点 Key（无活跃任务返回 null）。
     */
    private String refreshCurrentNode(WfApprovalInstance instance, String processInstanceId) {
        List<Task> active = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        if (!active.isEmpty()) {
            instance.setCurrentNodeKey(active.get(0).getTaskDefinitionKey());
            instance.setCurrentNodeName(active.get(0).getName());
            return active.get(0).getTaskDefinitionKey();
        }
        return null;
    }

    /**
     * 同步指定节点下 Flowable 任务的投影（change-state / 加签后新建任务补齐）。
     */
    private void syncTasksForNode(WfApprovalInstance instance, String processInstanceId, String nodeKey) {
        List<Task> tasks = taskService.createTaskQuery()
            .processInstanceId(processInstanceId).taskDefinitionKey(nodeKey).list();
        for (Task t : tasks) {
            if (approvalTaskMapper.selectByFlowableTaskId(t.getId()) != null) {
                continue;
            }
            WfApprovalTask projection = new WfApprovalTask();
            projection.setId(UUID.randomUUID().toString());
            projection.setApprovalInstanceId(instance.getId());
            projection.setProcessInstanceId(processInstanceId);
            projection.setFlowableTaskId(t.getId());
            projection.setTaskDefinitionKey(t.getTaskDefinitionKey());
            projection.setTaskName(t.getName());
            projection.setAssigneeUserId(t.getAssignee());
            projection.setOriginalAssigneeUserId(t.getAssignee());
            projection.setTaskStatus(TASK_STATUS_PENDING);
            projection.setCreatedAt(new Date());
            approvalTaskMapper.insert(projection);
        }
    }

    /**
     * 申请人节点 Key：流程起始事件出边直达的 UserTask。
     */
    private String applicantNodeKey(String processInstanceId) {
        String key = findApplicantNodeKey(processInstanceId);
        if (key == null) {
            throw new ServiceException("流程未配置申请人节点，无法驳回");
        }
        return key;
    }

    private String findApplicantNodeKey(String processInstanceId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionIdOf(processInstanceId));
        if (model == null) {
            return null;
        }
        for (Process process : model.getProcesses()) {
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof StartEvent start && !start.getOutgoingFlows().isEmpty()) {
                    FlowElement first = start.getOutgoingFlows().get(0).getTargetFlowElement();
                    if (first instanceof UserTask) {
                        return first.getId();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 节点显示名称。
     */
    private String nodeName(String processInstanceId, String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            return nodeKey;
        }
        BpmnModel model = repositoryService.getBpmnModel(pi.getProcessDefinitionId());
        if (model != null) {
            for (Process process : model.getProcesses()) {
                for (FlowElement fe : process.getFlowElements()) {
                    if (nodeKey.equals(fe.getId()) && fe.getName() != null) {
                        return fe.getName();
                    }
                }
            }
        }
        return nodeKey;
    }

    private String processDefinitionIdOf(String processInstanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new ServiceException("流程实例不存在: " + processInstanceId);
        }
        return pi.getProcessDefinitionId();
    }

    /**
     * 白名单校验 + 解析退回目标（PRD 11.3 校验清单：同版本 UserTask，禁止网关/开始/结束，禁止退回当前节点）。
     */
    private ReturnTargetInfo resolveReturnTarget(String processInstanceId, String currentNodeKey, String targetNodeKey) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionIdOf(processInstanceId));
        if (model == null) {
            throw new ServiceException("流程定义不存在，无法退回");
        }
        String applicantKey = findApplicantNodeKey(processInstanceId);
        UserTask target = null;
        for (Process process : model.getProcesses()) {
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof UserTask ut && targetNodeKey.equals(ut.getId())) {
                    target = ut;
                }
            }
        }
        if (target == null) {
            throw new ServiceException("退回目标必须是本流程版本内的审批节点（UserTask）: " + targetNodeKey);
        }
        if (currentNodeKey.equals(targetNodeKey)) {
            throw new ServiceException("不能退回到当前节点");
        }
        if (targetNodeKey.equals(applicantKey)) {
            return new ReturnTargetInfo(targetNodeKey, RETURN_TARGET_APPLICANT, targetNodeKey);
        }
        if (target.getLoopCharacteristics() != null) {
            // MI 目标：方式二 —— 退前置串行节点
            String predecessor = predecessorPlainNode(target);
            if (predecessor == null) {
                throw new ServiceException("目标多实例节点 [" + targetNodeKey
                    + "] 无可用前置串行节点（方式二不可用），无法退回");
            }
            return new ReturnTargetInfo(targetNodeKey, RETURN_TARGET_MI, predecessor);
        }
        return new ReturnTargetInfo(targetNodeKey, RETURN_TARGET_PLAIN, targetNodeKey);
    }

    /**
     * 目标节点的前置串行节点：incoming 起点为普通 UserTask（非网关/非多实例/非开始事件）时返回其 Key。
     */
    private String predecessorPlainNode(UserTask target) {
        if (target.getIncomingFlows() == null || target.getIncomingFlows().isEmpty()) {
            return null;
        }
        for (SequenceFlow flow : target.getIncomingFlows()) {
            FlowElement source = flow.getSourceFlowElement();
            if (source instanceof UserTask ut && ut.getLoopCharacteristics() == null
                && !(source instanceof StartEvent)) {
                return ut.getId();
            }
        }
        return null;
    }

    /**
     * MI 目标退回方式二：按最新审批人配置重写 wf_users_&lt;target&gt; 集合变量（人员可更新，§3.3.2）。
     * 节点配置缺失或解析失败时保持原集合（不阻塞退回）。
     */
    @SuppressWarnings("unchecked")
    private void rewriteUsersCollection(WfApprovalInstance instance, String targetNodeKey) {
        try {
            WfWorkflowVersion version = instance.getWorkflowVersionId() == null
                ? null : workflowVersionMapper.selectById(instance.getWorkflowVersionId());
            if (version == null || version.getConfigJson() == null || version.getConfigJson().isBlank()) {
                return;
            }
            Object root = objectMapper.readValue(version.getConfigJson(), Object.class);
            if (!(root instanceof Map<?, ?> rootMap)) {
                return;
            }
            Object nodesObj = rootMap.get("nodes");
            if (!(nodesObj instanceof Map<?, ?> nodes) || !nodes.containsKey(targetNodeKey)) {
                return;
            }
            Object cfgObj = nodes.get(targetNodeKey);
            if (!(cfgObj instanceof Map<?, ?> cfg)) {
                return;
            }
            Map<String, Object> config = new HashMap<>();
            for (Map.Entry<?, ?> e : cfg.entrySet()) {
                if (e.getKey() instanceof String k) {
                    config.put(k, e.getValue());
                }
            }
            Map<String, Object> formData = new HashMap<>();
            ApprovalFormDataDoc doc = formDataRepository.findByApprovalInstanceId(instance.getId()).orElse(null);
            if (doc != null && doc.getData() instanceof Map<?, ?> data) {
                for (Map.Entry<?, ?> e : data.entrySet()) {
                    if (e.getKey() instanceof String k) {
                        formData.put(k, e.getValue());
                    }
                }
            }
            Map<String, List<String>> resolved = taskAssignmentService.resolveAll(
                Map.of(targetNodeKey, config),
                instance.getApplicantUserId(), instance.getApplicantDeptId(), formData);
            List<String> users = resolved.get(targetNodeKey);
            if (users != null && !users.isEmpty()) {
                runtimeService.setVariable(instance.getProcessInstanceId(),
                    com.ruoyi.workflow.bpmn.BpmnValidationService.USERS_COLLECTION_PREFIX + targetNodeKey, users);
            }
        } catch (Exception e) {
            // 方式二人员重写失败不阻塞退回，保持原集合
        }
    }

    /**
     * 结束流程实例并批量取消待办投影（撤回/取消共用）。
     */
    private void endProcessInstance(WfApprovalInstance instance, String reason) {
        String piId = instance.getProcessInstanceId();
        if (piId == null) {
            throw new ServiceException("审批单未关联流程实例");
        }
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
            .processInstanceId(piId).singleResult();
        if (pi == null) {
            throw new ServiceException("流程实例不存在或已结束，无法操作");
        }
        runtimeService.deleteProcessInstance(pi.getId(), reason);
        approvalTaskMapper.updateStatusByProcessInstance(pi.getId(), TASK_STATUS_CANCELLED, new Date());
    }

    /**
     * 审批类型允许撤回/取消校验（PRD 11.4/11.5）。
     */
    private void requireAllowOperation(String approvalDefinitionId, boolean withdraw, String label) {
        WfApprovalDefinition definition = approvalDefinitionMapper.selectById(approvalDefinitionId);
        if (definition == null) {
            return;
        }
        boolean allowed = withdraw
            ? Boolean.TRUE.equals(definition.getAllowWithdraw())
            : Boolean.TRUE.equals(definition.getAllowCancel());
        if (!allowed) {
            throw new ServiceException("该审批类型不允许" + label);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    /** 退回目标解析结果。 */
    private static final class ReturnTargetInfo {
        final String targetNodeKey;
        final String targetNodeType;
        final String moveToNodeKey;

        ReturnTargetInfo(String targetNodeKey, String targetNodeType, String moveToNodeKey) {
            this.targetNodeKey = targetNodeKey;
            this.targetNodeType = targetNodeType;
            this.moveToNodeKey = moveToNodeKey;
        }
    }

    /** 退回目标白名单 VO。 */
    public static class ReturnTargetVo {
        public final String nodeKey;
        public final String nodeName;
        public final String nodeType;
        public final boolean available;
        public final String reason;

        public ReturnTargetVo(String nodeKey, String nodeName, String nodeType,
                              boolean available, String reason) {
            this.nodeKey = nodeKey;
            this.nodeName = nodeName;
            this.nodeType = nodeType;
            this.available = available;
            this.reason = reason;
        }
    }
}
