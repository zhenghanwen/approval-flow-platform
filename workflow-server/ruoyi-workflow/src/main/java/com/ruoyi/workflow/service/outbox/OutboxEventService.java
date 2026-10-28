package com.ruoyi.workflow.service.outbox;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.workflow.domain.WfOutboxEvent;
import com.ruoyi.workflow.mapper.WfOutboxEventMapper;

/**
 * 可靠事件服务（PRD 16.3）。
 *
 * <ul>
 *     <li>{@link #publish}：业务事务内写入 PENDING 事件（与审批动作同事务提交，原子）；</li>
 *     <li>{@link #dispatchDueEvents}：后台轮询到期事件 → 通知器投递 → 成功 PROCESSED /
 *         失败记重试 + 指数退避，超过 maxRetries 标记 FAILED（死信留痕，不阻塞其他事件）；</li>
 *     <li>每事件独立事务（TransactionTemplate）：单个事件失败不影响批次其余事件。</li>
 * </ul>
 */
@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    /** 重试退避基数（毫秒）：1s → 2s → 4s → 8s ...（可配，测试可缩短）。 */
    private static final long DEFAULT_BACKOFF_BASE_MS = 1000L;

    private final WfOutboxEventMapper eventMapper;
    private final OutboxEventHandler eventHandler;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final long backoffBaseMs;

    public OutboxEventService(WfOutboxEventMapper eventMapper,
                              OutboxEventHandler eventHandler,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper,
                              @org.springframework.beans.factory.annotation.Value(
                                  "${workflow.outbox.backoff-base-ms:1000}") long backoffBaseMs) {
        this.eventMapper = eventMapper;
        this.eventHandler = eventHandler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.backoffBaseMs = backoffBaseMs;
    }

    /**
     * 发布事件（须在业务事务内调用：与业务写入同事务原子提交）。
     */
    public void publish(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        WfOutboxEvent event = new WfOutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayloadJson(toJson(payload));
        event.setStatus(WfOutboxEvent.STATUS_PENDING);
        event.setRetryCount(0);
        event.setMaxRetries(5);
        event.setCreatedAt(new Date());
        eventMapper.insert(event);
    }

    /**
     * 轮询投递到期事件。测试可直接调用（调度注解仅作为生产入口，初始延迟避免测试期误触发）。
     */
    @Scheduled(fixedDelayString = "${workflow.outbox.poll-ms:60000}",
               initialDelayString = "${workflow.outbox.initial-delay-ms:60000}")
    public void dispatchDueEvents() {
        List<WfOutboxEvent> due = eventMapper.selectDuePending(new Date(), 100);
        for (WfOutboxEvent event : due) {
            try {
                transactionTemplate.execute(status -> {
                    eventHandler.handle(event);
                    event.setStatus(WfOutboxEvent.STATUS_PROCESSED);
                    event.setProcessedAt(new Date());
                    eventMapper.update(event);
                    return null;
                });
            } catch (Exception e) {
                recordFailure(event, e);
            }
        }
    }

    /**
     * 投递失败：重试次数 + 指数退避；达到上限标记 FAILED（死信）。
     */
    private void recordFailure(WfOutboxEvent event, Exception cause) {
        try {
            transactionTemplate.execute(status -> {
                int retry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
                event.setRetryCount(retry);
                event.setLastError(cause.getMessage());
                if (retry >= event.getMaxRetries()) {
                    event.setStatus(WfOutboxEvent.STATUS_FAILED);
                    log.warn("[outbox] 事件达到最大重试标记 FAILED: aggregateId={}, eventType={}, error={}",
                        event.getAggregateId(), event.getEventType(), cause.getMessage());
                } else {
                    event.setStatus(WfOutboxEvent.STATUS_PENDING);
                    event.setNextRetryAt(new Date(System.currentTimeMillis() + backoffMillis(retry)));
                }
                eventMapper.update(event);
                return null;
            });
        } catch (Exception ex) {
            log.error("[outbox] 记录失败状态异常 aggregateId={}", event.getAggregateId(), ex);
        }
    }

    /** 指数退避：retry 次 → base * 2^(retry-1)（封顶 30min）。 */
    private long backoffMillis(int retry) {
        long shift = Math.min(retry - 1, 10);
        return backoffBaseMs << shift;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return payload == null ? null : objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
