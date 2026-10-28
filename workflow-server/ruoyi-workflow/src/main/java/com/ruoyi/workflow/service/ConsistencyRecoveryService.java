package com.ruoyi.workflow.service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfApprovalTask;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfApprovalTaskMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;

/**
 * 一致性恢复服务（PRD 16.2/16.4）。
 *
 * <p>后台定期扫描 MySQL 审批单 / 任务投影 / MongoDB 表单数据与 Flowable 引擎的漂移并修复：</p>
 * <ul>
 *     <li>{@link #recoverEndedInstances}：PENDING 单的引擎实例已消失且历史已完成 → APPROVED + 投影兜底 COMPLETED；</li>
 *     <li>{@link #recoverProjectionMismatch}：活跃任务缺投影补插、PENDING 投影引擎任务已消失 → COMPLETED；</li>
 *     <li>{@link #recoverOrphanMongoDocs}：创建超 24h 且无对应审批单的 MongoDB 文档 → 删除；</li>
 *     <li>{@link #recoverStuckSubmitting}：SUBMITTING 单流程未启动/消失 → FAILED（表单双写行保留）。</li>
 * </ul>
 *
 * <p>各方法 public 供测试直调；调度注解仅作生产入口，初始延迟避免测试期误触发。</p>
 */
@Service
public class ConsistencyRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyRecoveryService.class);

    public static final String STATUS_SUBMITTING = "SUBMITTING";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";

    /** 孤儿 MongoDB 文档的安全保留期：24h（PRD 16.2/16.4）。 */
    private static final long ORPHAN_RETENTION_MS = 24 * 3600 * 1000L;

    /** 孤儿清理单次扫描上限，避免大库长时间占用。 */
    private static final int ORPHAN_SCAN_LIMIT = 500;

    private final WfApprovalInstanceMapper instanceMapper;
    private final WfApprovalTaskMapper taskMapper;
    private final ApprovalFormDataRepository formDataRepository;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;

    public ConsistencyRecoveryService(WfApprovalInstanceMapper instanceMapper,
                                      WfApprovalTaskMapper taskMapper,
                                      ApprovalFormDataRepository formDataRepository,
                                      RuntimeService runtimeService,
                                      HistoryService historyService,
                                      TaskService taskService) {
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.formDataRepository = formDataRepository;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
    }

    /**
     * 引擎实例已结束但业务单仍 PENDING → 修正为 APPROVED，并将该流程下未完成投影兜底 COMPLETED。
     *
     * @return 修复的审批单数量
     */
    public int recoverEndedInstances() {
        int fixed = 0;
        List<WfApprovalInstance> pending = instanceMapper.selectPendingWithProcess();
        for (WfApprovalInstance instance : pending) {
            String pid = instance.getProcessInstanceId();
            boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).singleResult() != null;
            if (running) {
                continue;
            }
            boolean finished = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).finished().singleResult() != null;
            if (!finished) {
                // 引擎侧运行与历史都无记录 → 无法判定结局，保守跳过
                continue;
            }
            instance.setBusinessStatus(STATUS_APPROVED);
            instance.setCompletedAt(new Date());
            instanceMapper.update(instance);
            // 投影兜底：该流程下未完成投影标记 COMPLETED
            List<WfApprovalTask> open = taskMapper.selectByProcessInstanceAndStatus(pid, TASK_STATUS_PENDING);
            for (WfApprovalTask t : open) {
                t.setTaskStatus(TASK_STATUS_COMPLETED);
                t.setCompletedAt(new Date());
                taskMapper.update(t);
            }
            fixed++;
        }
        if (fixed > 0) {
            log.info("[recovery] recoverEndedInstances 修复 {} 个 PENDING 审批单", fixed);
        }
        return fixed;
    }

    /**
     * 任务投影与引擎任务漂移修复：活跃任务缺投影补插；PENDING 投影引擎任务已消失 → COMPLETED。
     *
     * @return 修复的投影任务数量
     */
    public int recoverProjectionMismatch() {
        int fixed = 0;
        List<WfApprovalInstance> pending = instanceMapper.selectPendingWithProcess();
        for (WfApprovalInstance instance : pending) {
            String pid = instance.getProcessInstanceId();
            List<Task> active = taskService.createTaskQuery().processInstanceId(pid).list();
            Set<String> activeIds = active.stream().map(Task::getId).collect(Collectors.toSet());

            // 1) 活跃任务缺投影 → 补插
            for (Task t : active) {
                if (taskMapper.selectByFlowableTaskId(t.getId()) != null) {
                    continue;
                }
                WfApprovalTask projection = buildProjection(instance, t);
                taskMapper.insert(projection);
                fixed++;
            }
            // 2) PENDING 投影引擎任务已消失 → COMPLETED
            for (WfApprovalTask projection : taskMapper.selectPendingByInstance(instance.getId())) {
                if (projection.getFlowableTaskId() != null && !activeIds.contains(projection.getFlowableTaskId())) {
                    projection.setTaskStatus(TASK_STATUS_COMPLETED);
                    projection.setCompletedAt(new Date());
                    taskMapper.update(projection);
                    fixed++;
                }
            }
        }
        if (fixed > 0) {
            log.info("[recovery] recoverProjectionMismatch 修复 {} 个任务投影", fixed);
        }
        return fixed;
    }

    /**
     * 清理 MongoDB 孤立表单数据：创建超 24h 且无对应审批单 → 删除（PRD 16.2/16.4）。
     *
     * @return 删除的文档数量
     */
    public int recoverOrphanMongoDocs() {
        Date cutoff = new Date(System.currentTimeMillis() - ORPHAN_RETENTION_MS);
        List<ApprovalFormDataDoc> docs = formDataRepository.findByCreatedAtBefore(cutoff);
        int deleted = 0;
        for (ApprovalFormDataDoc doc : docs) {
            if (deleted >= ORPHAN_SCAN_LIMIT) {
                break;
            }
            boolean orphan = doc.getApprovalInstanceId() == null
                || instanceMapper.selectById(doc.getApprovalInstanceId()) == null;
            if (orphan) {
                formDataRepository.delete(doc);
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("[recovery] recoverOrphanMongoDocs 清理 {} 个孤立表单文档", deleted);
        }
        return deleted;
    }

    /**
     * 提交卡死恢复：SUBMITTING 单流程未启动或引擎实例消失 → FAILED（表单双写行保留）。
     *
     * @return 标记 FAILED 的审批单数量
     */
    public int recoverStuckSubmitting() {
        int fixed = 0;
        List<WfApprovalInstance> stuck = instanceMapper.selectStuckSubmitting();
        for (WfApprovalInstance instance : stuck) {
            String pid = instance.getProcessInstanceId();
            boolean alive = pid != null
                && runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult() != null;
            if (alive) {
                continue;
            }
            instance.setBusinessStatus(STATUS_FAILED);
            instanceMapper.update(instance);
            fixed++;
        }
        if (fixed > 0) {
            log.info("[recovery] recoverStuckSubmitting 标记 {} 个卡死提交为 FAILED", fixed);
        }
        return fixed;
    }

    /**
     * 全量恢复入口（生产调度；测试直接调用各 public 方法）。
     */
    @Scheduled(fixedDelayString = "${workflow.recovery.poll-ms:60000}",
               initialDelayString = "${workflow.recovery.initial-delay-ms:60000}")
    public void recoverAll() {
        recoverEndedInstances();
        recoverProjectionMismatch();
        recoverOrphanMongoDocs();
        recoverStuckSubmitting();
    }

    private WfApprovalTask buildProjection(WfApprovalInstance instance, Task t) {
        WfApprovalTask projection = new WfApprovalTask();
        projection.setId(UUID.randomUUID().toString());
        projection.setApprovalInstanceId(instance.getId());
        projection.setProcessInstanceId(instance.getProcessInstanceId());
        projection.setFlowableTaskId(t.getId());
        projection.setTaskDefinitionKey(t.getTaskDefinitionKey());
        projection.setTaskName(t.getName());
        projection.setAssigneeUserId(t.getAssignee());
        projection.setOriginalAssigneeUserId(t.getAssignee());
        projection.setTaskStatus(TASK_STATUS_PENDING);
        projection.setCreatedAt(new Date());
        return projection;
    }
}
