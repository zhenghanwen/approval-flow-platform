-- ============================================================
-- V5：补齐产品缺口
--   1) 抄送记录表（P7「抄送我的」真实数据源；action_type=CC 已在 V2 预留）
--   2) 审批类型软删除标记（P8「删除」真实删除；软删以保留审批历史）
-- ============================================================

-- 7.4 抄送记录表（抄送我的）
create table wf_approval_cc (
    id                   varchar(36)  not null comment '抄送记录ID',
    approval_instance_id varchar(36)  not null comment '审批单ID',
    cc_user_id           varchar(36)  not null comment '被抄送用户ID',
    cc_by_user_id        varchar(36)  null comment '抄送发起人（提交人）',
    read_flag            tinyint      not null default 0 comment '是否已读 0/1',
    created_at           datetime(3)  not null default current_timestamp(3) comment '抄送时间',
    primary key (id),
    key idx_wf_approval_cc_instance (approval_instance_id),
    key idx_wf_approval_cc_user (cc_user_id, read_flag, created_at)
) engine = innodb default charset = utf8mb4 comment = '审批抄送记录表（P7 抄送我的）';

-- 5.1 审批类型软删除（P8：删除 = del_flag 置 1，列表/详情/发布校验自动过滤）
alter table wf_approval_definition
    add column del_flag char(1) not null default '0' comment '删除标志 0正常/1已删除' after status;
