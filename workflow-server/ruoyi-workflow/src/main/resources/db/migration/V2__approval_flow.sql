-- ============================================================
-- M3 基础审批闭环 · V2：审批类型 / 流程定义与版本 / 任务投影 / 操作记录 / 幂等操作
-- 对应 PRD §5.1 / §5.5 / §5.6 / §7.1 / §7.2 / §7.3
-- id 统一 VARCHAR(36)（UUID），时间统一 DATETIME(3)
-- ============================================================

-- 5.1 审批类型表
create table wf_approval_definition (
    id                      varchar(36)  not null comment '审批类型ID',
    approval_code           varchar(100) not null comment '唯一编码',
    approval_name           varchar(200) not null comment '审批名称',
    category_id             varchar(36)  null comment '分类ID',
    description             varchar(1000) null comment '描述',
    icon                    varchar(255) null comment '图标标识',
    current_form_version_id varchar(36)  null comment '当前表单版本',
    current_flow_version_id varchar(36)  null comment '当前流程版本',
    allow_withdraw          tinyint      not null default 1 comment '是否允许撤回',
    allow_cancel            tinyint      not null default 1 comment '是否允许取消',
    status                  varchar(20)  not null default 'DRAFT' comment 'DRAFT/PUBLISHED/DISABLED',
    created_by              varchar(36)  null comment '创建人',
    created_at              datetime(3)  not null default current_timestamp(3) comment '创建时间',
    updated_at              datetime(3)  not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_approval_definition_code (approval_code)
) engine = innodb default charset = utf8mb4 comment = '审批类型表（PRD 5.1）';

-- 5.5 流程定义表
create table wf_workflow_definition (
    id                     varchar(36)  not null comment '流程业务定义ID',
    approval_definition_id varchar(36)  not null comment '所属审批类型',
    workflow_code          varchar(100) not null comment '流程编码',
    workflow_name          varchar(200) not null comment '流程名称',
    status                 varchar(20)  not null default 'DRAFT' comment 'DRAFT/PUBLISHED/DISABLED',
    current_version        int          not null default 0 comment '当前发布版本号',
    created_by             varchar(36)  null comment '创建人',
    created_at             datetime(3)  not null default current_timestamp(3) comment '创建时间',
    updated_at             datetime(3)  not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_workflow_definition_code (workflow_code),
    key idx_wf_workflow_definition_approval (approval_definition_id)
) engine = innodb default charset = utf8mb4 comment = '流程定义表（PRD 5.5）';

-- 5.6 流程版本表
create table wf_workflow_version (
    id                      varchar(36)   not null comment '流程版本ID',
    workflow_id             varchar(36)   not null comment '流程业务定义ID',
    version_no              int           not null comment '版本号',
    flowable_deployment_id  varchar(64)   null comment 'Flowable 部署ID',
    process_definition_id   varchar(255)  null comment 'Flowable 流程定义ID',
    process_definition_key  varchar(100)  null comment 'BPMN 流程定义Key',
    bpmn_hash               varchar(64)   not null comment 'BPMN 内容摘要',
    config_json             json          null comment '节点配置和扩展元数据',
    status                  varchar(20)   not null default 'DRAFT' comment 'DRAFT/PUBLISHED/ARCHIVED',
    published_at            datetime(3)   null comment '发布时间',
    published_by            varchar(36)   null comment '发布人',
    created_at              datetime(3)   not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_wf_workflow_version (workflow_id, version_no),
    key idx_wf_workflow_version_workflow (workflow_id)
) engine = innodb default charset = utf8mb4 comment = '流程版本表（PRD 5.6）';

-- 7.1 审批任务投影表（Flowable 是任务事实来源，本表用于业务列表查询）
create table wf_approval_task (
    id                        varchar(36)  not null comment '业务任务记录ID',
    approval_instance_id      varchar(36)  not null comment '审批单ID',
    process_instance_id       varchar(64)  not null comment 'Flowable 流程实例ID',
    flowable_task_id          varchar(64)  not null comment 'Flowable 任务ID',
    task_definition_key       varchar(100) not null comment '节点Key',
    task_name                 varchar(200) null comment '节点名称',
    assignee_user_id          varchar(36)  null comment '当前办理人',
    original_assignee_user_id varchar(36)  null comment '初始办理人',
    owner_user_id             varchar(36)  null comment '任务Owner，按需使用',
    task_status               varchar(20)  not null default 'CREATED' comment 'CREATED/PENDING/COMPLETED/CANCELLED',
    delegated_to_user_id      varchar(36)  null comment '委托处理人',
    parent_task_id            varchar(64)  null comment '关联父任务',
    due_at                    datetime(3)  null comment '截止时间',
    created_at                datetime(3)  not null default current_timestamp(3) comment '创建时间',
    completed_at              datetime(3)  null comment '完成时间',
    primary key (id),
    unique key uk_wf_approval_task_flowable (flowable_task_id),
    key idx_wf_approval_task_assignee (assignee_user_id, task_status, created_at),
    key idx_wf_approval_task_instance (approval_instance_id, created_at),
    key idx_wf_approval_task_process (process_instance_id)
) engine = innodb default charset = utf8mb4 comment = '审批任务投影表（PRD 7.1）';

-- 7.2 审批操作记录表（只追加，不覆盖历史意见）
create table wf_approval_action (
    id                   varchar(36)  not null comment '操作ID',
    approval_instance_id varchar(36)  not null comment '审批单ID',
    flowable_task_id     varchar(64)  null comment '相关Flowable任务ID',
    task_definition_key  varchar(100) null comment '节点Key',
    operator_user_id     varchar(36)  null comment '操作人',
    action_type          varchar(30)  not null comment '操作类型(SUBMIT/APPROVE/REJECT/RETURN/TRANSFER/ADD_SIGN/WITHDRAW/CANCEL/CC/ADMIN_TERMINATE)',
    comment              text         null comment '审批意见',
    target_user_id       varchar(36)  null comment '目标用户',
    target_task_key      varchar(100) null comment '目标节点',
    before_status        varchar(30)  null comment '操作前状态',
    after_status         varchar(30)  null comment '操作后状态',
    operation_id         varchar(36)  null comment '幂等操作ID',
    created_at           datetime(3)  not null default current_timestamp(3) comment '操作时间',
    primary key (id),
    key idx_wf_approval_action_instance (approval_instance_id, created_at),
    key idx_wf_approval_action_operator (operator_user_id, created_at)
) engine = innodb default charset = utf8mb4 comment = '审批操作记录表（PRD 7.2）';

-- 7.3 流程操作表（幂等）
create table wf_approval_operation (
    id                   varchar(36)  not null comment '操作ID',
    approval_instance_id varchar(36)  not null comment '审批单ID',
    operation_type       varchar(30)  not null comment '操作类型',
    idempotency_key      varchar(100) not null comment '幂等键',
    operator_user_id     varchar(36)  null comment '操作人',
    request_json         json         null comment '必要的请求摘要',
    operation_status     varchar(20)  not null default 'PENDING' comment 'PENDING/PROCESSING/SUCCESS/FAILED',
    result_json          json         null comment '执行结果摘要',
    error_message        text         null comment '错误信息',
    created_at           datetime(3)  not null default current_timestamp(3) comment '创建时间',
    updated_at           datetime(3)  not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_approval_operation (operator_user_id, idempotency_key),
    key idx_wf_approval_operation_instance (approval_instance_id)
) engine = innodb default charset = utf8mb4 comment = '流程操作表（PRD 7.3）';
