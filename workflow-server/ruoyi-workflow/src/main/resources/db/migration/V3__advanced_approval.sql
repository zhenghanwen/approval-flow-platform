-- ============================================================
-- M4 高级审批 · V3：转交 / 加签 / 退回 操作记录表
-- 对应 PRD §11.1 / §11.2 / §11.3
-- id 统一 VARCHAR(36)（UUID），时间统一 DATETIME(3)
-- ============================================================

-- 11.1 转交记录表
create table wf_task_transfer (
    id                  varchar(36)  not null comment '转交记录ID',
    approval_instance_id varchar(36) not null comment '审批单ID',
    flowable_task_id    varchar(64)  not null comment 'Flowable 任务ID',
    task_definition_key varchar(100) null comment '节点Key',
    from_user_id        varchar(64)  not null comment '原办理人',
    to_user_id          varchar(64)  not null comment '新办理人',
    reason              varchar(1000) null comment '转交原因',
    operator_user_id    varchar(64)  not null comment '操作人',
    created_at          datetime(3)  not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_wf_task_transfer_instance (approval_instance_id),
    key idx_wf_task_transfer_task (flowable_task_id)
) engine = innodb default charset = utf8mb4 comment = '任务转交记录表（PRD 11.1）';

-- 11.2 加签记录表
create table wf_task_add_sign (
    id                  varchar(36)  not null comment '加签记录ID',
    approval_instance_id varchar(36) not null comment '审批单ID',
    flowable_task_id    varchar(64)  not null comment '加签操作所针对的任务ID',
    task_definition_key varchar(100) null comment '节点Key',
    sign_type           varchar(16)  not null comment 'FIRST=前加签/AFTER=后加签',
    add_user_id         varchar(64)  not null comment '加签人',
    reason              varchar(1000) null comment '加签原因',
    operator_user_id    varchar(64)  not null comment '操作人',
    created_at          datetime(3)  not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_wf_task_add_sign_instance (approval_instance_id),
    key idx_wf_task_add_sign_task (flowable_task_id)
) engine = innodb default charset = utf8mb4 comment = '任务加签记录表（PRD 11.2）';

-- 11.3 退回记录表
create table wf_task_return (
    id                     varchar(36)  not null comment '退回记录ID',
    approval_instance_id   varchar(36)  not null comment '审批单ID',
    source_flowable_task_id varchar(64) not null comment '退回源任务ID',
    source_node_key        varchar(100) null comment '退回源节点Key',
    target_node_key        varchar(100) not null comment '退回目标节点Key',
    target_node_type       varchar(16)  not null comment 'APPLICANT=申请人节点/MI=多实例审批节点/PLAIN=普通节点',
    reason                 varchar(1000) null comment '退回原因',
    operator_user_id       varchar(64)  not null comment '操作人',
    created_at             datetime(3)  not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_wf_task_return_instance (approval_instance_id),
    key idx_wf_task_return_source (source_flowable_task_id)
) engine = innodb default charset = utf8mb4 comment = '任务退回记录表（PRD 11.3）';
