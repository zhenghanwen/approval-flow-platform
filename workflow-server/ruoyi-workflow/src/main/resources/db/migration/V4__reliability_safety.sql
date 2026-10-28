-- ============================================================
-- M5 可靠与安全 · V4：可靠事件表（Outbox）
-- 对应 PRD §16.3（wf_outbox_event：可靠通知与异步副作用）
-- ============================================================

-- 16.3 可靠事件表
create table wf_outbox_event (
    id             varchar(36)  not null comment '事件ID',
    aggregate_type varchar(50)  not null comment '聚合类型(APPROVAL)',
    aggregate_id   varchar(64)  not null comment '聚合ID(如审批单ID)',
    event_type     varchar(50)  not null comment '事件类型(APPROVAL_SUBMITTED/APPROVED/REJECTED/RETURNED/WITHDRAWN/CANCELLED)',
    payload_json   json         null comment '事件负载',
    status         varchar(20)  not null default 'PENDING' comment 'PENDING/PROCESSING/PROCESSED/FAILED',
    retry_count    int          not null default 0 comment '已重试次数',
    max_retries    int          not null default 5 comment '最大重试次数',
    next_retry_at  datetime(3)  null comment '下次重试时间',
    last_error     text         null comment '最近一次错误',
    created_at     datetime(3)  not null default current_timestamp(3) comment '创建时间',
    processed_at   datetime(3)  null comment '处理完成时间',
    primary key (id),
    key idx_wf_outbox_event_status (status, next_retry_at),
    key idx_wf_outbox_event_aggregate (aggregate_id)
) engine = innodb default charset = utf8mb4 comment = '可靠事件表（PRD 16.3）';
