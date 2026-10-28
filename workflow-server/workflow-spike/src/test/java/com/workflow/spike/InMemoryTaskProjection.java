package com.workflow.spike;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;

/**
 * T12 雏形：wf_approval_task 投影的内存镜像（ taskId → RUNNING/COMPLETED/REMOVED ）。
 *
 * <p>引擎级事件监听驱动（TASK_CREATED/TASK_COMPLETED/ENTITY_DELETED + PROCESS_COMPLETED/PROCESS_CANCELLED），
 * 已被 S4 验证"正常流转/撤回瞬间都与引擎一致"；S5 贯穿案例复用做全程对账。
 * M3 平移到 MySQL 投影表（wf_approval_task）时保持同一事件语义。</p>
 */
public class InMemoryTaskProjection implements FlowableEventListener {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String REMOVED = "REMOVED";

    private final Map<String, String> taskStates = new LinkedHashMap<>();
    private String lastProcessEndState;

    public void reset() {
        taskStates.clear();
        lastProcessEndState = null;
    }

    public String stateOf(String taskId) {
        return taskStates.get(taskId);
    }

    public List<String> runningIds() {
        return taskStates.entrySet().stream()
            .filter(e -> RUNNING.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    }

    public String lastProcessEndState() {
        return lastProcessEndState;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        // 引擎事件类型众多，先取 type 分派；PROCESS_COMPLETED/CANCELLED 本身也带实体
        // （entity=流程实例执行），所以流程级语义必须在实体分派【之前】处理
        if (!(event.getType() instanceof FlowableEngineEventType type)) {
            return;
        }
        switch (type) {
            case PROCESS_COMPLETED -> lastProcessEndState = COMPLETED;
            case PROCESS_CANCELLED -> lastProcessEndState = "CANCELLED";
            case TASK_CREATED, TASK_COMPLETED, ENTITY_DELETED -> {
                if (event instanceof FlowableEngineEntityEvent entityEvent
                    && entityEvent.getEntity() instanceof TaskEntity task) {
                    switch (type) {
                        case TASK_CREATED -> taskStates.put(task.getId(), RUNNING);
                        case TASK_COMPLETED -> taskStates.put(task.getId(), COMPLETED);
                        case ENTITY_DELETED -> {
                            String prev = taskStates.get(task.getId());
                            // 已完成的任务被级联删除不折叠为 REMOVED（保历史语义）
                            if (!COMPLETED.equals(prev)) {
                                taskStates.put(task.getId(), REMOVED);
                            }
                        }
                        default -> { }
                    }
                }
            }
            default -> { /* 其他事件不投影 */ }
        }
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    // Flowable 7.1.0 起 FlowableEventListener 新增事务生命周期契约：
    // 本投影只做内存镜像、无事务横切需求，保持"每事件即时回调"旧行为
    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
