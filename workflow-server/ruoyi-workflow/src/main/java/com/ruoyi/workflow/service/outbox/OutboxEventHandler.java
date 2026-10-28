package com.ruoyi.workflow.service.outbox;

import com.ruoyi.workflow.domain.WfOutboxEvent;

/**
 * Outbox 事件处理器：投递通知 / 异步副作用的扩展点（PRD 16.3）。
 *
 * <p>默认实现只记录日志（v1 无真实通知渠道）；后续接入站内信/WebSocket/短信时
 * 提供新实现，审批主流程不感知。</p>
 */
public interface OutboxEventHandler {

    /**
     * 投递单个事件。抛出异常视为投递失败，由 OutboxEventService 计入重试。
     */
    void handle(WfOutboxEvent event);
}
