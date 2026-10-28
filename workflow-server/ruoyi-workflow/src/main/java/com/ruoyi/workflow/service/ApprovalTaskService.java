package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.domain.WfApprovalAction;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalOperation;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.mapper.WfApprovalActionMapper;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalOperationMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;

/**
 * 审批任务服务（PRD 10.2/10.3 + 14.2/14.3）。
 *
 * <p>同意：Flowable complete → 投影 COMPLETED → 流程结束则审批单 APPROVED。</p>
 * <p>驳回（v1 白名单：仅允许退回申请人节点）：change-state 到申请人节点 →
 * 投影 COMPLETED + 重建申请人任务投影 → 审批单 RETURNED（PRD 10.3 模式一：驳回至申请人修改）。</p>
 * <p>待办/已办走投影表（PRD 12.4），我发起走审批单表，时间线聚合操作记录。</p>
 */
@Service
public class ApprovalTaskService {

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String OP_PROCESSING = "PROCESSING";
    public static final String OP_SUCCESS = "SUCCESS";

    private final WfApprovalTaskMapper approvalTaskMapper;
    private final WfApprovalInstanceMapper approvalInstanceMapper;
    private final WfApprovalActionMapper approvalActionMapper;
    private final WfApprovalOperationMapper operationMapper;
    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;

    public ApprovalTaskService(WfApprovalTaskMapper approvalTaskMapper,
                               WfApprovalInstanceMapper approvalInstanceMapper,
                               WfApprovalActionMapper approvalActionMapper,
                               WfApprovalOperationMapper operationMapper,
                               TaskService taskService,
                               RuntimeService runtimeService,
                               RepositoryService repositoryService) {
        this.approvalTaskMapper = approvalTaskMapper;
        this.approvalInstanceMapper = approvalInstanceMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.operationMapper = operationMapper;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
    }

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

        // 幂等操作记录（PROCESSING）
        WfApprovalOperation op = beginOperation(instance.getId(), ACTION_APPROVE, operator, idempotencyKey, taskId, comment);

        // Flowable 完成任务
        taskService.complete(task.getId());

        // 任务投影 → COMPLETED
        if (projection != null) {
            projection.setTaskStatus(TASK_STATUS_COMPLETED);
            projection.setCompletedAt(new Date());
            approvalTaskMapper.update(projection);
        }

        // 审批单状态：流程结束 → APPROVED；否则维持 PENDING 并刷新当前节点
        boolean ended = runtimeService.createProcessInstanceQuery()
            .processInstanceId(task.getProcessInstanceId()).singleResult() == null;
        Date now = new Date();
        if (ended) {
            instance.setBusinessStatus(STATUS_APPROVED);
            instance.setCompletedAt(now);
        } else {
            refreshCurrentNode(instance, task.getProcessInstanceId());
        }
        approvalInstanceMapper.update(instance);

        // 操作记录
        insertAction(instance.getId(), task.getId(), task.getTaskDefinitionKey(), operator, ACTION_APPROVE,
            comment, beforeStatus, ended ? STATUS_APPROVED : STATUS_PENDING, op.getId());

        finishOperation(op, instance.getId());
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

        // 白名单 v1：目标 = 申请人节点（流程起始 userTask）
        String applicantNodeKey = applicantNodeKey(task.getProcessInstanceId());
        String applicantNodeName = nodeName(task.getProcessInstanceId(), applicantNodeKey);

        // change-state 退回申请人节点（M0 结论：changeState 可进出多实例节点）
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(task.getProcessInstanceId())
            .moveActivityIdTo(task.getTaskDefinitionKey(), applicantNodeKey)
            .changeState();

        // 当前审批任务投影 → COMPLETED
        if (projection != null) {
            projection.setTaskStatus(TASK_STATUS_COMPLETED);
            projection.setCompletedAt(new Date());
            approvalTaskMapper.update(projection);
        }

        // 同步重建的申请人任务投影
        syncApplicantTask(instance, task.getProcessInstanceId(), applicantNodeKey);

        // 审批单 → RETURNED，当前节点 = 申请人节点
        instance.setBusinessStatus(STATUS_RETURNED);
        instance.setCurrentNodeKey(applicantNodeKey);
        instance.setCurrentNodeName(applicantNodeName);
        approvalInstanceMapper.update(instance);

        insertAction(instance.getId(), task.getId(), task.getTaskDefinitionKey(), operator, ACTION_REJECT,
            comment, beforeStatus, STATUS_RETURNED, op.getId());

        finishOperation(op, instance.getId());
        return instance;
    }

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
     * 审批单详情。
     */
    public WfApprovalInstance instanceDetail(String approvalInstanceId) {
        WfApprovalInstance instance = approvalInstanceMapper.selectById(approvalInstanceId);
        if (instance == null) {
            throw new ServiceException("审批单不存在: " + approvalInstanceId);
        }
        return instance;
    }

    /**
     * 时间线：审批操作记录按时间正序聚合（PRD GET /approvals/{id}/timeline）。
     */
    public List<WfApprovalAction> timeline(String approvalInstanceId) {
        instanceDetail(approvalInstanceId);
        return approvalActionMapper.selectByInstance(approvalInstanceId);
    }

    // ---------- 内部工具 ----------

    /**
     * 幂等校验：同一用户同一幂等键已 SUCCESS 则直接返回结果，不重复执行（PRD 10.2 步骤 4）。
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
        op.setRequestJson("{\"taskId\":\"" + taskId + "\",\"comment\":\"" + safe(comment) + "\"}");
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
     * 刷新审批单当前节点（取第一个活跃任务）。
     */
    private void refreshCurrentNode(WfApprovalInstance instance, String processInstanceId) {
        List<Task> active = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        if (!active.isEmpty()) {
            instance.setCurrentNodeKey(active.get(0).getTaskDefinitionKey());
            instance.setCurrentNodeName(active.get(0).getName());
        }
    }

    /**
     * 驳回后同步申请人节点重建的任务投影。
     */
    private void syncApplicantTask(WfApprovalInstance instance, String processInstanceId, String applicantNodeKey) {
        List<Task> applicantTasks = taskService.createTaskQuery()
            .processInstanceId(processInstanceId).taskDefinitionKey(applicantNodeKey).list();
        for (Task t : applicantTasks) {
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
     * 申请人节点 Key（白名单 v1）：流程起始事件出边直达的 UserTask。
     */
    private String applicantNodeKey(String processInstanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new ServiceException("流程实例不存在: " + processInstanceId);
        }
        BpmnModel model = repositoryService.getBpmnModel(pi.getProcessDefinitionId());
        if (model != null) {
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
        }
        throw new ServiceException("流程未配置申请人节点，无法驳回");
    }

    /**
     * 节点显示名称。
     */
    private String nodeName(String processInstanceId, String nodeKey) {
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

    private String safe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }
}
