-- ============================================================
-- M2 动态表单 · V1：表单定义 / 表单版本 / 审批单投影 / 外部接口
-- 对应 PRD §5.2 / §5.3 / §6.1 / §8.1 / §8.2
-- id 统一 VARCHAR(36)（UUID），时间统一 DATETIME(3)
-- ============================================================

-- 5.2 表单定义表
create table wf_form_definition (
    id                varchar(36)  not null comment '表单ID',
    form_code         varchar(100) not null comment '表单编码',
    form_name         varchar(200) not null comment '表单名称',
    status            varchar(20)  not null default 'DRAFT' comment '状态（DRAFT/PUBLISHED/DISABLED）',
    current_version   int          not null default 0 comment '当前发布版本号',
    created_by        varchar(36)  null comment '创建人',
    created_at        datetime(3)  not null default current_timestamp(3) comment '创建时间',
    updated_at        datetime(3)  not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_form_definition_code (form_code)
) engine = innodb default charset = utf8mb4 comment = '表单定义表（PRD 5.2）';

-- 5.3 表单版本表（版本快照不可变，schema_hash 校验）
create table wf_form_version (
    id              varchar(36)  not null comment '表单版本ID',
    form_id         varchar(36)  not null comment '表单ID',
    version_no      int          not null comment '版本号',
    mongo_schema_id varchar(100) null comment 'MongoDB 文档ID',
    schema_hash     varchar(64)  not null comment 'JSON 内容摘要',
    status          varchar(20)  not null default 'DRAFT' comment 'DRAFT/PUBLISHED/ARCHIVED',
    published_at    datetime(3)  null comment '发布时间',
    published_by    varchar(36)  null comment '发布人',
    created_at      datetime(3)  not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_wf_form_version (form_id, version_no),
    key idx_wf_form_version_form (form_id)
) engine = innodb default charset = utf8mb4 comment = '表单版本表（PRD 5.3）';

-- 6.1 审批单主表（M2 起作为表单数据 MySQL 投影；M3 审批闭环复用）
create table wf_approval_instance (
    id                        varchar(36)   not null comment '审批单ID',
    approval_no               varchar(50)   not null comment '审批单编号',
    approval_definition_id    varchar(36)   null comment '审批类型ID',
    form_version_id           varchar(36)   null comment '表单版本ID',
    workflow_version_id       varchar(36)   null comment '流程版本ID',
    process_instance_id       varchar(64)   null comment 'Flowable 流程实例ID',
    process_definition_id     varchar(255)  null comment 'Flowable 流程定义ID',
    applicant_user_id         varchar(36)   null comment '申请人ID',
    applicant_name_snapshot   varchar(100)  null comment '申请人姓名快照',
    applicant_dept_id         varchar(36)   null comment '申请人部门ID',
    applicant_dept_snapshot   varchar(200)  null comment '部门名称快照',
    title                     varchar(500)  null comment '审批标题',
    form_data_id              varchar(100)  null comment 'MongoDB 表单文档ID',
    business_status           varchar(30)   not null default 'DRAFT' comment '业务状态',
    current_node_key          varchar(100)  null comment '当前节点Key',
    current_node_name         varchar(200)  null comment '当前节点名称',
    submitted_at              datetime(3)   null comment '提交时间',
    completed_at              datetime(3)   null comment '结束时间',
    version                   int           not null default 0 comment '乐观锁版本号',
    created_at                datetime(3)   not null default current_timestamp(3) comment '创建时间',
    updated_at                datetime(3)   not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_approval_instance_no (approval_no),
    unique key uk_wf_approval_instance_proc (process_instance_id),
    key idx_wf_approval_instance_applicant (applicant_user_id, created_at),
    key idx_wf_approval_instance_def_status (approval_definition_id, business_status, created_at),
    key idx_wf_approval_instance_status (business_status, updated_at),
    key idx_wf_approval_instance_form_data (form_data_id)
) engine = innodb default charset = utf8mb4 comment = '审批单主表（PRD 6.1）';

-- 8.1 外部接口配置表
create table wf_external_api (
    id                 varchar(36)    not null comment '接口ID',
    api_code           varchar(100)   not null comment '接口编码',
    api_name           varchar(200)   not null comment '接口名称',
    base_url           varchar(1000)  null comment '基础地址',
    path               varchar(500)   null comment '接口路径',
    http_method        varchar(10)    null comment 'GET/POST 等',
    auth_type          varchar(30)    not null default 'NONE' comment 'NONE/BEARER/API_KEY',
    credential_ref     varchar(500)   null comment '凭证引用（凭证本体不落库）',
    timeout_ms         int            not null default 5000 comment '超时时间',
    config_json        json           null comment '请求参数、响应映射等非敏感配置',
    allowed_hosts_json json           null comment '允许访问的主机范围',
    status             varchar(20)    not null default 'ENABLED' comment 'ENABLED/DISABLED',
    created_by         varchar(36)    null comment '创建人',
    created_at         datetime(3)    not null default current_timestamp(3) comment '创建时间',
    updated_at         datetime(3)    not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_wf_external_api_code (api_code)
) engine = innodb default charset = utf8mb4 comment = '外部接口配置表（PRD 8.1）';

-- 8.2 外部接口调用日志表（敏感信息脱敏存储）
create table wf_external_api_log (
    id                   varchar(36)   not null comment '日志ID',
    api_id               varchar(36)   not null comment '接口ID',
    approval_instance_id varchar(36)   null comment '审批单ID',
    operator_user_id     varchar(36)   null comment '操作人ID',
    request_summary      varchar(2000) null comment '请求摘要（脱敏）',
    response_status      int           null comment '响应状态码',
    duration_ms          int           null comment '耗时毫秒',
    success              tinyint       not null default 0 comment '是否成功',
    error_code           varchar(100)  null comment '错误码',
    error_message        varchar(1000) null comment '错误信息',
    created_at           datetime(3)   not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_wf_external_api_log_api (api_id)
) engine = innodb default charset = utf8mb4 comment = '外部接口调用日志表（PRD 8.2）';
