package com.ruoyi.workflow.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ruoyi.workflow.domain.WfOutboxEvent;

/**
 * 默认 Outbox 通知器：v1 仅记录日志（无真实通知渠道），事件仍按可靠语义标记 PROCESSED。
 */
@Component
public class DefaultOutboxEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultOutboxEventHandler.class);

    @Override
    public void handle(WfOutboxEvent event) {
        log.info("[outbox] 投递事件 aggregateType={}, aggregateId={}, eventType={}, retryCount={}",
            event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getRetryCount());
    }
}
