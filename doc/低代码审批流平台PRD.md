这版技术方案我会按可以直接进入后端开发的设计深度来规划，不只列几张表和几个接口，而是把 Flowable、MySQL、MongoDB 之间的职责、完整审批操作、外部用户接口、动态表单版本和异常恢复机制一起设计清楚。

结合你选择的三个方向，系统采用以下总体原则：

- 用户和组织支持内部维护、外部接口两种模式，可按系统或组织配置。
- MongoDB 保存完整的动态表单 JSON，MySQL 保存审批单、流程关联、任务查询和审批审计数据。
- Flowable 负责流程实例、任务、流程变量和流程流转；业务系统负责审批单、动态表单、权限和业务状态。
- 转交、加签、撤回、退回任意节点都通过统一的流程操作服务执行，不允许前端直接操作 Flowable。
- 已发布的表单和流程采用版本快照，确保历史审批单不受新配置影响。

特别需要提前明确：MySQL 和 MongoDB 之间不能依赖一个普通数据库事务实现原子提交。 因此，审批提交、撤回、加签等关键操作需要设计幂等、操作记录、状态校验、重试和补偿机制。

# 低代码审批流平台技术方案

**适用技术栈：Java + Spring Boot + Flowable + MySQL + MongoDB**

**文档版本：** V1.0\
**方案范围：** 后端架构、数据库设计、模块划分、接口规范、审批操作、数据一致性与安全设计。

---

# 一、技术架构设计

## 1.1 整体架构

系统采用模块化单体架构，第一版不拆分微服务。这样既可以复用 Spring 事务，又能减少服务间通信和部署成本。

```
Vue 3 前端
    |
    | HTTP / REST API
    v
Spring Boot 应用
    |
    +-- 认证与权限模块
    +-- 用户与组织模块
    +-- 审批类型模块
    +-- 动态表单模块
    +-- 流程配置模块
    +-- 审批业务模块
    +-- 流程操作模块
    +-- 外部接口配置模块
    +-- 通知与日志模块
    |
    +-------------------+
    |                   |
    v                   v
Flowable             MongoDB
    |                   |
    v                   v
MySQL 流程表       动态表单 JSON
    |
    +-- 审批单业务表
    +-- 审批任务查询表
    +-- 审批操作记录表
    +-- 用户及组织表
    +-- 表单和流程版本元数据
    +-- 接口配置与调用日志
    +-- 幂等记录与待执行事件
```

这里的 MySQL 和 Flowable 关系需要进一步明确：**推荐让 Flowable 和业务系统共用一个 MySQL 实例，但使用独立的表前缀或独立 schema，并使用统一事务管理器。**

例如：

- `wf_*`：自定义业务表。
- `ACT_*`：Flowable 引擎表，实际表名以引擎版本和配置为准。

不要直接修改 Flowable 自带的 `ACT_*` 表结构，也不要把自定义业务字段随意添加到 Flowable 表中。

## 1.2 技术组件

| 组件          | 职责                     |
| ----------- | ---------------------- |
| Java        | 后端开发语言                 |
| Spring Boot | 应用框架、依赖注入、事务、Web API   |
| Flowable    | BPMN 流程执行、任务、流程实例、历史记录 |
| MySQL       | 关系数据、审批单、任务投影、权限、操作审计  |
| MongoDB     | 动态表单数据、表单数据快照          |
| Vue 3       | 管理后台、表单设计器、流程设计器、审批页面  |
| Redis（可选）   | 缓存、分布式限流、短期锁等          |
| 消息队列（后续可选）  | 通知、异步事件、耗时任务           |

第一版可以不依赖 Redis 和消息队列。关键业务流程先使用 MySQL 事务、幂等操作记录和可靠重试实现。

## 1.3 核心设计原则

1. 业务主键统一使用 UUID 或其他全局唯一 ID，避免不同数据库主键冲突。
2. MySQL 保存审批单业务状态，MongoDB 保存动态表单内容。
3. Flowable 的流程实例 ID、任务 ID 和流程定义 ID 必须与业务数据建立明确关联。
4. 表单和流程定义发布后不可原地修改，应生成新版本。
5. 审批操作必须经过后端统一服务校验。
6. 历史审批记录不可随意覆盖或删除。
7. 外部接口只允许访问已授权的数据源。
8. 所有关键操作支持幂等，避免重复提交、重复审批和重复通知。

---

# 二、后端模块划分

建议采用 Maven 多模块结构。第一版各业务模块仍运行在同一个 Spring Boot 应用中。

## 2.1 Maven 项目结构

```
approval-platform
├── approval-boot                 # 启动模块、配置装配
├── approval-common               # 公共异常、返回结构、工具类
├── approval-security             # 登录、认证、权限校验
├── approval-identity             # 用户、部门、角色、外部身份映射
├── approval-form                 # 表单设计、版本、表单数据
├── approval-workflow             # 流程设计、BPMN 发布、Flowable 适配
├── approval-business             # 审批单、草稿、查询、业务状态
├── approval-task                 # 待办、已办、任务分配、任务操作
├── approval-integration          # 外部接口配置、请求、数据映射
├── approval-notification         # 站内通知、后续通知渠道
└── approval-audit                 # 操作审计、接口调用日志
```

模块职责应清晰：

- `approval-form` 不直接操作 Flowable。
- `approval-workflow` 不直接拼接 MongoDB 查询。
- `approval-task` 负责审批任务操作，并调用工作流适配层。
- `approval-business` 负责审批单生命周期和业务状态。
- `approval-integration` 统一管理所有外部 HTTP 调用。
- `approval-security` 提供统一的身份和资源权限校验。

## 2.2 关键服务类

| 服务类                          | 职责                 |
| ---------------------------- | ------------------ |
| `FormDefinitionService`      | 表单草稿、发布、版本查询       |
| `FormDataService`            | 表单 JSON 保存、读取、版本校验 |
| `WorkflowDefinitionService`  | 流程配置校验、发布、版本管理     |
| `FlowableDeploymentService`  | BPMN 部署及流程定义关联     |
| `ApprovalApplicationService` | 提交审批、撤回、取消         |
| `ApprovalTaskService`        | 待办、已办、审批操作         |
| `TaskAssignmentService`      | 审批人解析与任务分配         |
| `TaskTransferService`        | 转交审批任务             |
| `TaskAddSignService`         | 加签处理               |
| `TaskReturnService`          | 退回指定节点             |
| `ExternalApiService`         | 外部接口请求与响应映射        |
| `UserDirectoryService`       | 内部用户、外部用户及组织查询     |
| `ApprovalAuditService`       | 审批操作记录             |
| `ConsistencyRecoveryService` | 异常流程恢复和一致性检查       |

---

# 三、数据库总体设计

## 3.1 数据存储边界

### MySQL

存储以下数据：

- 用户、部门、角色及其关联。
- 审批类型。
- 表单定义元数据和版本。
- 流程定义元数据和版本。
- 审批单主记录。
- 待办、已办查询投影。
- 审批操作记录。
- 转交、加签和退回记录。
- 抄送和通知。
- 外部接口配置及调用日志。
- 幂等记录、可靠事件和异常恢复记录。

### MongoDB

存储以下数据：

- 表单定义 JSON 快照。
- 已提交审批单的完整表单 JSON。
- 草稿表单 JSON。
- 必要的表单数据版本快照。

MongoDB 只负责动态 JSON 内容，不负责维护流程状态和审批权限。

### Flowable

存储以下数据：

- BPMN 流程定义及部署。
- 流程实例。
- 运行中的任务。
- 流程变量。
- 流程历史。
- 引擎自身的身份链接及相关运行数据（根据配置）。

业务系统可以读取 Flowable 的任务和历史信息，但不能把直接修改引擎表作为正常业务操作方式。

## 3.2 命名约定

自定义 MySQL 表统一使用 `wf_` 前缀。

主要字段约定：

| 字段           | 说明                        |
| ------------ | ------------------------- |
| `id`         | 主键，建议使用 UUID 字符串或统一的全局 ID |
| `created_at` | 创建时间                      |
| `updated_at` | 更新时间                      |
| `created_by` | 创建人 ID                    |
| `deleted`    | 逻辑删除标记，仅适用于允许逻辑删除的配置数据    |

审批历史、已发布版本和审计数据不建议使用普通逻辑删除操作。

以下表结构是核心逻辑结构，省略了部分通用审计字段、外键声明和索引细节。正式实施时应补齐索引、唯一约束、字段长度和数据库迁移脚本。

---

# 四、用户与组织数据库

## 4.1 用户表：`wf_user`

| 字段                   | 类型           | 说明                  |
| -------------------- | ------------ | ------------------- |
| id                   | VARCHAR(36)  | 用户 ID               |
| username             | VARCHAR(100) | 登录账号                |
| password_hash        | VARCHAR(255) | 密码哈希，外部身份用户可为空      |
| display_name         | VARCHAR(100) | 姓名                  |
| email                | VARCHAR(255) | 邮箱                  |
| mobile               | VARCHAR(30)  | 手机号                 |
| primary_dept_id      | VARCHAR(36)  | 主部门 ID              |
| manager_user_id      | VARCHAR(36)  | 直属主管 ID             |
| source_type          | VARCHAR(20)  | INTERNAL / EXTERNAL |
| external_provider_id | VARCHAR(36)  | 外部身份提供方 ID          |
| external_user_id     | VARCHAR(200) | 外部用户 ID             |
| status               | VARCHAR(20)  | ENABLED / DISABLED  |
| created_at           | DATETIME(3)  | 创建时间                |
| updated_at           | DATETIME(3)  | 更新时间                |

关键约束：

- `username` 唯一（允许为空的外部身份记录需明确处理方式）。
- `(external_provider_id, external_user_id)` 唯一。
- 停用用户不能登录或被分配新的审批任务。
- 已存在的审批历史保留原用户 ID 和历史显示信息。

如果用户可以属于多个部门，应额外建立 `wf_user_dept` 关联表，不要只依赖 `primary_dept_id`。

## 4.2 部门表：`wf_department`

| 字段               | 类型           | 说明      |
| ---------------- | ------------ | ------- |
| id               | VARCHAR(36)  | 部门 ID   |
| parent_id        | VARCHAR(36)  | 上级部门 ID |
| dept_code        | VARCHAR(100) | 部门编码    |
| dept_name        | VARCHAR(200) | 部门名称    |
| manager_user_id  | VARCHAR(36)  | 部门负责人   |
| source_type      | VARCHAR(20)  | 内部或外部   |
| external_dept_id | VARCHAR(200) | 外部部门 ID |
| status           | VARCHAR(20)  | 状态      |
| sort_order       | INT          | 排序      |

建立 `(parent_id, sort_order)` 索引和外部部门映射唯一索引。

## 4.3 用户部门关联表：`wf_user_dept`

| 字段         | 类型          | 说明    |
| ---------- | ----------- | ----- |
| id         | VARCHAR(36) | 主键    |
| user_id    | VARCHAR(36) | 用户 ID |
| dept_id    | VARCHAR(36) | 部门 ID |
| is_primary | TINYINT     | 是否主部门 |

唯一约束：`(user_id, dept_id)`。

## 4.4 角色与关联表

`wf_role`

- `id`
- `role_code`
- `role_name`
- `status`

`wf_user_role`

- `id`
- `user_id`
- `role_id`

`wf_role` 的 `role_code` 唯一；`wf_user_role` 的 `(user_id, role_id)` 唯一。

## 4.5 外部身份提供方表：`wf_identity_provider`

| 字段             | 类型           | 说明                    |
| -------------- | ------------ | --------------------- |
| id             | VARCHAR(36)  | 提供方 ID                |
| provider_code  | VARCHAR(100) | 提供方编码                 |
| provider_name  | VARCHAR(200) | 提供方名称                 |
| provider_type  | VARCHAR(30)  | REST_API / INTERNAL   |
| config_json    | JSON         | 非敏感连接配置               |
| credential_ref | VARCHAR(500) | 密钥引用或安全存储引用           |
| status         | VARCHAR(20)  | 状态                    |
| sync_mode      | VARCHAR(20)  | NONE / PULL / WEBHOOK |

注意：

- 密码、Token 和 API Key 不应明文写入 `config_json`。
- 密钥应存放在受控的密钥管理设施或加密存储中。
- 外部用户和本地用户通过映射关系关联，不要每次请求时都依赖外部 ID 直接作为内部主键。

---

# 五、审批类型、表单及版本数据库

## 5.1 审批类型表：`wf_approval_definition`

该表表示一种业务审批，例如请假、采购或报销。

| 字段                      | 类型            | 说明                           |
| ----------------------- | ------------- | ---------------------------- |
| id                      | VARCHAR(36)   | 审批类型 ID                      |
| approval_code           | VARCHAR(100)  | 唯一编码                         |
| approval_name           | VARCHAR(200)  | 审批名称                         |
| category_id             | VARCHAR(36)   | 分类 ID                        |
| description             | VARCHAR(1000) | 描述                           |
| icon                    | VARCHAR(255)  | 图标标识                         |
| current_form_version_id | VARCHAR(36)   | 当前表单版本                       |
| current_flow_version_id | VARCHAR(36)   | 当前流程版本                       |
| allow_withdraw          | TINYINT       | 是否允许撤回                       |
| allow_cancel            | TINYINT       | 是否允许取消                       |
| status                  | VARCHAR(20)   | DRAFT / PUBLISHED / DISABLED |
| created_by              | VARCHAR(36)   | 创建人                          |

`approval_code` 必须唯一。

发布状态应由后端校验，不能因为两个版本 ID 非空就认为流程一定可用。

## 5.2 表单定义表：`wf_form_definition`

| 字段              | 类型           | 说明      |
| --------------- | ------------ | ------- |
| id              | VARCHAR(36)  | 表单 ID   |
| form_code       | VARCHAR(100) | 表单编码    |
| form_name       | VARCHAR(200) | 表单名称    |
| status          | VARCHAR(20)  | 状态      |
| current_version | INT          | 当前发布版本号 |
| created_by      | VARCHAR(36)  | 创建人     |

一个审批类型可以关联一个主表单；后续需要复杂子表单时，可增加表单组件引用关系。

## 5.3 表单版本表：`wf_form_version`

| 字段              | 类型           | 说明                           |
| --------------- | ------------ | ---------------------------- |
| id              | VARCHAR(36)  | 表单版本 ID                      |
| form_id         | VARCHAR(36)  | 表单 ID                        |
| version_no      | INT          | 版本号                          |
| mongo_schema_id | VARCHAR(100) | MongoDB 文档 ID                |
| schema_hash     | VARCHAR(64)  | JSON 内容摘要                    |
| status          | VARCHAR(20)  | DRAFT / PUBLISHED / ARCHIVED |
| published_at    | DATETIME(3)  | 发布时间                         |
| published_by    | VARCHAR(36)  | 发布人                          |

唯一约束：`(form_id, version_no)`。

MongoDB 中保存完整表单结构，MySQL 保存版本、状态和 MongoDB 文档引用。

## 5.4 MongoDB：`form_schema_versions`

示例文档：

```
{
  "_id": "form-version-uuid",
  "formId": "form-uuid",
  "version": 1,
  "schema": {
    "formCode": "expense_apply",
    "formName": "费用报销申请",
    "layout": {
      "columns": 2
    },
    "fields": [
      {
        "field": "applicant",
        "type": "user",
        "label": "申请人",
        "required": true
      },
      {
        "field": "amount",
        "type": "money",
        "label": "报销金额",
        "required": true
      }
    ]
  },
  "schemaHash": "sha256-value",
  "createdAt": "2026-10-28T00:00:00Z"
}
```

已发布的表单结构应视为不可变快照。

## 5.5 流程定义表：`wf_workflow_definition`

| 字段                     | 类型           | 说明        |
| ---------------------- | ------------ | --------- |
| id                     | VARCHAR(36)  | 流程业务定义 ID |
| approval_definition_id | VARCHAR(36)  | 所属审批类型    |
| workflow_code          | VARCHAR(100) | 流程编码      |
| workflow_name          | VARCHAR(200) | 流程名称      |
| status                 | VARCHAR(20)  | 状态        |
| current_version        | INT          | 当前发布版本号   |

## 5.6 流程版本表：`wf_workflow_version`

| 字段                     | 类型           | 说明                           |
| ---------------------- | ------------ | ---------------------------- |
| id                     | VARCHAR(36)  | 流程版本 ID                      |
| workflow_id            | VARCHAR(36)  | 流程业务定义 ID                    |
| version_no             | INT          | 版本号                          |
| flowable_deployment_id | VARCHAR(64)  | Flowable 部署 ID               |
| process_definition_id  | VARCHAR(255) | Flowable 流程定义 ID             |
| process_definition_key | VARCHAR(100) | BPMN 流程定义 Key                |
| bpmn_hash              | VARCHAR(64)  | BPMN 内容摘要                    |
| config_json            | JSON         | 节点配置和扩展元数据                   |
| status                 | VARCHAR(20)  | DRAFT / PUBLISHED / ARCHIVED |
| published_at           | DATETIME(3)  | 发布时间                         |

唯一约束：`(workflow_id, version_no)`。

流程发布时，将流程配置转换为 BPMN，部署至 Flowable，并保存 `deploymentId` 和 `processDefinitionId`。

流程版本发布之后不应覆盖原部署内容。

---

# 六、审批单及动态表单数据

## 6.1 审批单主表：`wf_approval_instance`

这是业务系统最核心的表。

| 字段                      | 类型           | 说明               |
| ----------------------- | ------------ | ---------------- |
| id                      | VARCHAR(36)  | 审批单 ID           |
| approval_no             | VARCHAR(50)  | 审批单编号            |
| approval_definition_id  | VARCHAR(36)  | 审批类型 ID          |
| form_version_id         | VARCHAR(36)  | 表单版本 ID          |
| workflow_version_id     | VARCHAR(36)  | 流程版本 ID          |
| process_instance_id     | VARCHAR(64)  | Flowable 流程实例 ID |
| process_definition_id   | VARCHAR(255) | Flowable 流程定义 ID |
| applicant_user_id       | VARCHAR(36)  | 申请人 ID           |
| applicant_name_snapshot | VARCHAR(100) | 申请人姓名快照          |
| applicant_dept_id       | VARCHAR(36)  | 申请人部门 ID         |
| applicant_dept_snapshot | VARCHAR(200) | 部门名称快照           |
| title                   | VARCHAR(500) | 审批标题             |
| form_data_id            | VARCHAR(100) | MongoDB 表单文档 ID  |
| business_status         | VARCHAR(30)  | 业务状态             |
| current_node_key        | VARCHAR(100) | 当前节点 Key         |
| current_node_name       | VARCHAR(200) | 当前节点名称           |
| submitted_at            | DATETIME(3)  | 提交时间             |
| completed_at            | DATETIME(3)  | 结束时间             |
| version                 | INT          | 乐观锁版本号           |
| created_at              | DATETIME(3)  | 创建时间             |
| updated_at              | DATETIME(3)  | 更新时间             |

唯一约束：

- `approval_no` 唯一。
- `process_instance_id` 唯一（允许草稿或启动前记录为空）。
- `form_data_id` 应建立索引。

常用索引：

- `(applicant_user_id, created_at)`
- `(approval_definition_id, business_status, created_at)`
- `(business_status, updated_at)`
- `(process_instance_id)`

### 业务状态建议

- `DRAFT`：草稿。
- `SUBMITTING`：正在提交。
- `PENDING`：审批中。
- `APPROVED`：审批通过。
- `REJECTED`：审批驳回并终止。
- `RETURNED`：退回修改或退回指定节点后的业务状态，具体语义需统一。
- `WITHDRAWN`：已撤回。
- `CANCELLED`：已取消。
- `ERROR`：流程异常。

不要只依赖 Flowable 的 `active/suspended/ended` 来表示业务状态。业务状态要能够表达审批系统自身的语义。

## 6.2 MongoDB：`approval_form_data`

每份审批单保存完整表单 JSON。

```
{
  "_id": "form-data-uuid",
  "approvalInstanceId": "approval-instance-uuid",
  "formVersionId": "form-version-uuid",
  "schemaHash": "schema-hash",
  "dataVersion": 1,
  "data": {
    "applicant": {
      "id": "user-001",
      "name": "张三"
    },
    "amount": 6800,
    "reason": "办公设备采购"
  },
  "createdAt": "2026-10-28T00:00:00Z",
  "updatedAt": "2026-10-28T00:00:00Z"
}
```

### 数据规则

1. `approvalInstanceId` 与 MySQL 审批单 ID 一致。
2. `formVersionId` 固定指向发起时使用的表单版本。
3. 表单 JSON 必须经过服务端校验。
4. 已提交的表单不应允许普通更新覆盖原始申请内容。
5. 修改申请应形成新的数据版本或明确的修订记录。
6. 附件文件本体建议存储于对象存储或文件服务，MongoDB 仅保存文件 ID、名称、大小、类型等元数据。

建议为 `approvalInstanceId` 建立唯一索引；如果采用独立的历史快照集合，则根据数据版本建立复合唯一索引。

## 6.3 MySQL 查询字段与 MongoDB 数据的关系

虽然完整表单存储在 MongoDB，但审批列表不应每次都读取全部 JSON 再过滤。

对常用查询条件，例如：

- 申请人。
- 申请部门。
- 申请金额。
- 申请日期。
- 业务状态。
- 当前审批人。

应将固定系统字段保存在 MySQL。

对于需要按自定义业务字段搜索的情况，第一版可以仅支持少量指定字段的查询投影。后续再增加 `wf_approval_field_index` 等字段索引表。

**不建议把每一个动态字段都设计成 MySQL 独立列，也不建议在第一版就对所有 MongoDB 动态字段做全量跨库查询。**

---

# 七、审批任务、操作记录与高级操作数据库

## 7.1 审批任务投影表：`wf_approval_task`

Flowable 是任务的执行事实来源，`wf_approval_task` 用于业务列表查询和展示。

| 字段                        | 类型           | 说明               |
| ------------------------- | ------------ | ---------------- |
| id                        | VARCHAR(36)  | 业务任务记录 ID        |
| approval_instance_id      | VARCHAR(36)  | 审批单 ID           |
| process_instance_id       | VARCHAR(64)  | Flowable 流程实例 ID |
| flowable_task_id          | VARCHAR(64)  | Flowable 任务 ID   |
| task_definition_key       | VARCHAR(100) | 节点 Key           |
| task_name                 | VARCHAR(200) | 节点名称             |
| assignee_user_id          | VARCHAR(36)  | 当前办理人            |
| original_assignee_user_id | VARCHAR(36)  | 初始办理人            |
| owner_user_id             | VARCHAR(36)  | 任务 Owner，按需使用    |
| task_status               | VARCHAR(20)  | 业务任务状态           |
| delegated_to_user_id      | VARCHAR(36)  | 委托处理人            |
| parent_task_id            | VARCHAR(64)  | 关联父任务            |
| due_at                    | DATETIME(3)  | 截止时间             |
| created_at                | DATETIME(3)  | 创建时间             |
| completed_at              | DATETIME(3)  | 完成时间             |

索引：

- `(assignee_user_id, task_status, created_at)`
- `(approval_instance_id, created_at)`
- `(process_instance_id)`
- `(flowable_task_id)`

`flowable_task_id` 必须唯一，任务重建或加签时应重新生成对应关联。

这张表是查询投影，不是第二套独立的流程引擎。任务完成、转交、加签后必须与 Flowable 事实保持一致。

## 7.2 审批操作记录表：`wf_approval_action`

记录用户实际执行的审批操作。

| 字段                   | 类型           | 说明                |
| -------------------- | ------------ | ----------------- |
| id                   | VARCHAR(36)  | 操作 ID             |
| approval_instance_id | VARCHAR(36)  | 审批单 ID            |
| flowable_task_id     | VARCHAR(64)  | 相关 Flowable 任务 ID |
| task_definition_key  | VARCHAR(100) | 节点 Key            |
| operator_user_id     | VARCHAR(36)  | 操作人               |
| action_type          | VARCHAR(30)  | 操作类型              |
| comment              | TEXT         | 审批意见              |
| target_user_id       | VARCHAR(36)  | 目标用户              |
| target_task_key      | VARCHAR(100) | 目标节点              |
| before_status        | VARCHAR(30)  | 操作前状态             |
| after_status         | VARCHAR(30)  | 操作后状态             |
| operation_id         | VARCHAR(36)  | 幂等操作 ID           |
| created_at           | DATETIME(3)  | 操作时间              |

`action_type` 示例：

- `SUBMIT`
- `APPROVE`
- `REJECT`
- `RETURN`
- `TRANSFER`
- `ADD_SIGN`
- `WITHDRAW`
- `CANCEL`
- `CC`
- `ADMIN_TERMINATE`

操作记录应只追加，不应因后续操作而覆盖之前的审批意见。

## 7.3 流程操作表：`wf_approval_operation`

用于管理复杂流程操作的执行状态和幂等。

| 字段                   | 类型           | 说明                                      |
| -------------------- | ------------ | --------------------------------------- |
| id                   | VARCHAR(36)  | 操作 ID                                   |
| approval_instance_id | VARCHAR(36)  | 审批单 ID                                  |
| operation_type       | VARCHAR(30)  | 操作类型                                    |
| idempotency_key      | VARCHAR(100) | 幂等键                                     |
| operator_user_id     | VARCHAR(36)  | 操作人                                     |
| request_json         | JSON         | 必要的请求摘要                                 |
| operation_status     | VARCHAR(20)  | PENDING / PROCESSING / SUCCESS / FAILED |
| result_json          | JSON         | 执行结果摘要                                  |
| error_message        | TEXT         | 错误信息                                    |
| created_at           | DATETIME(3)  | 创建时间                                    |
| updated_at           | DATETIME(3)  | 更新时间                                    |

唯一约束：`(operator_user_id, idempotency_key)`。

同一用户使用同一个幂等键重复提交时，后端返回已存在操作的结果，不再重复执行流程动作。

## 7.4 转交记录表：`wf_task_transfer`

| 字段                   | 类型            | 说明             |
| -------------------- | ------------- | -------------- |
| id                   | VARCHAR(36)   | 主键             |
| approval_instance_id | VARCHAR(36)   | 审批单 ID         |
| task_id              | VARCHAR(64)   | Flowable 任务 ID |
| from_user_id         | VARCHAR(36)   | 原办理人           |
| to_user_id           | VARCHAR(36)   | 新办理人           |
| reason               | VARCHAR(1000) | 转交原因           |
| operation_id         | VARCHAR(36)   | 关联操作 ID        |
| created_at           | DATETIME(3)   | 转交时间           |

## 7.5 加签记录表：`wf_task_add_sign`

| 字段                   | 类型          | 说明                              |
| -------------------- | ----------- | ------------------------------- |
| id                   | VARCHAR(36) | 主键                              |
| approval_instance_id | VARCHAR(36) | 审批单 ID                          |
| source_task_id       | VARCHAR(64) | 原始任务 ID                         |
| add_sign_task_id     | VARCHAR(64) | 加签任务 ID                         |
| add_sign_user_id     | VARCHAR(36) | 加签人员                            |
| add_sign_type        | VARCHAR(20) | BEFORE / AFTER                  |
| completion_rule      | VARCHAR(20) | ALL / ANY                       |
| status               | VARCHAR(20) | PENDING / COMPLETED / CANCELLED |
| operation_id         | VARCHAR(36) | 操作 ID                           |
| created_at           | DATETIME(3) | 创建时间                            |
| completed_at         | DATETIME(3) | 完成时间                            |

该表用于记录加签业务关系，实际任务仍由 Flowable 管理。

## 7.6 退回记录表：`wf_task_return`

| 字段                   | 类型           | 说明                              |
| -------------------- | ------------ | ------------------------------- |
| id                   | VARCHAR(36)  | 主键                              |
| approval_instance_id | VARCHAR(36)  | 审批单 ID                          |
| source_task_id       | VARCHAR(64)  | 退回发起任务                          |
| source_node_key      | VARCHAR(100) | 原节点 Key                         |
| target_node_key      | VARCHAR(100) | 目标节点 Key                        |
| target_activity_id   | VARCHAR(100) | 目标 BPMN 活动 ID                   |
| return_reason        | TEXT         | 退回原因                            |
| return_mode          | VARCHAR(30)  | NODE_RESTART / APPLICANT_EDIT 等 |
| operation_id         | VARCHAR(36)  | 操作 ID                           |
| created_at           | DATETIME(3)  | 操作时间                            |

## 7.7 抄送表：`wf_approval_cc`

记录抄送接收人、已读状态和抄送时间。

字段包括：

- `id`
- `approval_instance_id`
- `node_key`
- `sender_user_id`
- `receiver_user_id`
- `message`
- `read_status`
- `read_at`
- `created_at`

---

# 八、数据接口配置数据库

## 8.1 接口配置表：`wf_external_api`

| 字段                 | 类型            | 说明                      |
| ------------------ | ------------- | ----------------------- |
| id                 | VARCHAR(36)   | 接口 ID                   |
| api_code           | VARCHAR(100)  | 接口编码                    |
| api_name           | VARCHAR(200)  | 接口名称                    |
| base_url           | VARCHAR(1000) | 基础地址                    |
| path               | VARCHAR(500)  | 接口路径                    |
| http_method        | VARCHAR(10)   | GET / POST 等            |
| auth_type          | VARCHAR(30)   | NONE / BEARER / API_KEY |
| credential_ref     | VARCHAR(500)  | 凭证引用                    |
| timeout_ms         | INT           | 超时时间                    |
| config_json        | JSON          | 请求参数、响应映射等非敏感配置         |
| allowed_hosts_json | JSON          | 允许访问的主机范围               |
| status             | VARCHAR(20)   | ENABLED / DISABLED      |
| created_by         | VARCHAR(36)   | 创建人                     |

建议将认证凭证和接口配置分开管理，避免接口编辑接口直接返回密钥。

## 8.2 接口调用日志表：`wf_external_api_log`

记录：

- `id`
- `api_id`
- `approval_instance_id`
- `operator_user_id`
- `request_summary`
- `response_status`
- `duration_ms`
- `success`
- `error_code`
- `error_message`
- `created_at`

不建议长期保存完整敏感请求和响应。日志中的身份信息、手机号、令牌、业务数据应按安全策略脱敏。

---

# 九、表关系总览

```
wf_user -------- wf_department
   |                    |
   +---- wf_user_dept --+
   |
   +---- wf_user_role ---- wf_role

wf_approval_definition
   |
   +---- wf_form_definition
   |           |
   |           +---- wf_form_version ---- MongoDB 表单结构
   |
   +---- wf_workflow_definition
               |
               +---- wf_workflow_version ---- Flowable 流程定义

wf_approval_instance
   |
   +---- MongoDB approval_form_data
   |
   +---- wf_approval_task
   |
   +---- wf_approval_action
   |
   +---- wf_task_transfer
   |
   +---- wf_task_add_sign
   |
   +---- wf_task_return
   |
   +---- wf_approval_cc
   |
   +---- wf_approval_operation
```

所有审批业务表以 `approval_instance_id` 关联审批单主表。

MySQL 和 MongoDB 之间通过全局唯一的业务 ID 关联，不使用跨数据库外键。

---

# 十、完整审批生命周期设计

## 10.1 提交审批

建议流程：

1. 前端提交审批类型、表单版本和表单 JSON。
2. 后端认证当前用户，并检查审批类型是否可发起。
3. 后端读取已发布表单版本。
4. 后端校验字段、必填项、字段权限和数据类型。
5. 后端生成审批单 ID 和幂等键。
6. 将完整表单 JSON 保存到 MongoDB。
7. 开启 MySQL 事务，创建审批单业务记录。
8. 启动对应 Flowable 流程实例。
9. 将流程实例 ID 关联到审批单。
10. 根据 Flowable 生成的待办任务同步任务投影。
11. 写入审批操作记录和必要的通知事件。
12. 提交 MySQL 事务并返回审批单编号。

这里需要保证 MongoDB 文档先写入，再执行 MySQL 和 Flowable 事务；如果 MySQL 事务失败，MongoDB 可能留下未关联文档。应使用状态标记、重试和定期清理机制处理孤立数据。

如果业务要求极高的一致性，也可以采用持久化提交命令和后台恢复机制，但不要假设两个数据库能直接共享一个普通本地事务。

## 10.2 审批通过

1. 前端提交任务 ID、审批意见和幂等键。
2. 后端验证当前用户是否是合法办理人。
3. 校验任务仍处于待办状态。
4. 创建或获取幂等操作记录。
5. 在事务中执行 Flowable 任务完成操作。
6. 根据流程推进结果同步业务任务投影。
7. 如果流程已结束，更新审批单业务状态。
8. 追加审批操作记录。
9. 生成通知事件。
10. 提交事务并返回最新状态。

同一个待办不能因为重复点击而执行两次。

## 10.3 审批驳回

驳回不能只有一个简单的数据库状态更新。

需要先检查当前节点的驳回策略，再执行相应的流程操作。

第一版建议支持：

- 驳回至申请人修改。
- 驳回至指定历史审批节点。
- 驳回后终止流程。

具体模式需要在流程配置中明确，不能让前端随意指定不允许的退回目标。

---

# 十一、完整审批操作实现方案

## 11.1 转交审批

转交是把当前任务的办理责任交给另一个用户。

推荐规则：

1. 当前任务必须仍在待办状态。
2. 操作人必须有转交权限。
3. 目标用户必须有效且具备处理资格。
4. 校验不能把任务转交给不允许参与该流程的用户。
5. 记录原办理人、新办理人及转交原因。
6. 更新 Flowable 任务办理人。
7. 同步 MySQL 任务投影。
8. 写入操作审计。

Flowable 可通过任务服务的办理人更新能力完成转交，但不能只更新 `wf_approval_task.assignee_user_id`。

如果采用代理委托而不是永久转交，业务含义不同，应使用单独的委托处理机制，不要混淆两者。

## 11.2 加签审批

加签分为两种。

### 前加签

原审批人处理前，先由加签人员完成审批。

```
原审批人待办
    |
    v
前加签人员审批
    |
    v
原审批人继续审批
```

### 后加签

原审批人完成审批后，追加一个或多个审批人，再继续后续流程。

```
原审批人审批
    |
    v
后加签人员审批
    |
    v
继续后续流程
```

实现时需要注意：

- 加签不是简单地新增一条业务表记录。
- 加签任务必须实际进入 Flowable 的任务生命周期。
- 需要维护原任务、加签任务和后续任务的关联关系。
- 必须定义多人加签是全部同意还是任意一人同意。
- 需要明确加签驳回是否终止、退回原审批人还是退回申请人。
- 加签结束后要同步更新业务任务投影和通知。

建议先实现串行前加签和串行后加签，再扩展多人并行加签。

## 11.3 退回任意节点

这是整套系统中技术风险最高的能力之一。

Flowable 标准 BPMN 任务流转不等于任意节点都能随时退回。需要通过受控的流程状态变更能力实现，例如 Flowable 提供的活动状态变更机制；具体调用方式要根据采用的 Flowable 版本和当前流程结构验证。

建议流程：

1. 查询当前流程实例和当前活动节点。
2. 检查目标节点是否属于该流程定义版本。
3. 校验目标节点是否允许退回。
4. 校验目标节点是否可执行。
5. 校验并行网关、多实例任务和会签状态。
6. 检查是否存在尚未完成的子任务或加签任务。
7. 记录退回原因和源节点、目标节点。
8. 在流程引擎中执行受控的活动状态变更。
9. 同步更新当前任务和审批单状态。
10. 记录操作审计并通知相关人员。

### 退回规则

第一版建议：

- 只允许退回到同一流程实例中配置允许退回的审批节点。
- 默认不允许退回至开始节点或结束节点。
- 并行审批中的任务不允许随意跨分支退回。
- 多实例审批、复杂会签和加签场景需要单独校验。
- 退回后重新生成的任务必须能够追踪到原始任务和退回记录。

**不要直接删除旧任务或手工修改 Flowable 数据库表。** 退回操作需要调用流程引擎受支持的 API，并且必须在目标流程结构下做集成测试。

## 11.4 撤回审批

撤回是申请人主动终止正在执行的申请。

建议默认规则：

- 仅申请人可以撤回本人提交的审批。
- 只允许撤回尚未结束的流程。
- 是否允许撤回由审批类型及流程规则决定。
- 已完成审批的流程不能直接撤回。
- 撤回必须终止或以其他受控方式结束 Flowable 流程实例。
- 更新业务状态为 `WITHDRAWN`。
- 保存撤回原因和操作记录。
- 取消未执行的待办并同步任务投影。
- 对相关审批人员发送通知。

如果业务希望申请人修改后重新提交，应设计为“撤回后修改并重新发起”，而不是悄悄复用已经结束的旧流程实例。

## 11.5 取消审批

取消与撤回要有明确区别。

- 撤回：申请人主动收回自己的申请。
- 取消：由具有权限的用户或管理员取消业务流程，例如业务取消、审批类型停用后的特定处理。

取消操作需要记录取消人、原因和取消时间。

---

# 十二、Flowable 集成策略

## 12.1 流程定义发布

流程配置不直接让前端提交任意 BPMN XML 并部署。

建议：

1. 前端提交结构化流程 JSON。
2. 后端校验节点类型、连接关系、审批人规则和条件表达式。
3. 后端将配置转换为 BPMN XML。
4. 执行 BPMN 结构校验。
5. 部署到 Flowable。
6. 保存部署 ID 和流程定义 ID。
7. 更新审批类型的当前发布版本。

这样可以控制支持的节点类型，避免任意 BPMN 配置造成不可预期的执行行为。

## 12.2 审批人解析

流程节点应支持多种审批人来源：

```
指定用户
    |
指定角色
    |
发起人直属主管
    |
发起人所在部门负责人
    |
表单人员字段
    |
外部接口返回的人员
```

建议统一为 `TaskAssignmentService`。

它接收节点配置、申请人、表单数据及流程上下文，输出合法的用户 ID 集合，再交给 Flowable 创建任务或设置办理人。

对于找不到审批人的情况，必须有明确的策略：

- 阻止提交。
- 使用备用审批人。
- 转交管理员处理。

不能默默跳过审批节点。

## 12.3 流程变量

Flowable 流程变量只保存流程路由所需的必要信息。

例如：

```
{
  "approvalInstanceId": "approval-uuid",
  "applicantUserId": "user-001",
  "amount": 6800,
  "departmentId": "dept-001"
}
```

不建议把完整表单 JSON、附件二进制数据或大量外部接口返回内容放入流程变量。

## 12.4 任务查询

业务列表优先查询 `wf_approval_task` 和 `wf_approval_instance`，而不是每次都直接查询 Flowable 所有运行时任务。

Flowable 的运行状态是执行事实来源；MySQL 任务投影是业务查询优化层。两者通过统一的任务操作服务和一致性校验机制保持同步。

---

# 十三、REST API 接口设计

统一前缀：

```
/api/v1
```

统一返回格式：

```
{
  "code": "0",
  "message": "success",
  "data": {},
  "traceId": "request-trace-id"
}
```

列表返回格式：

```
{
  "code": "0",
  "message": "success",
  "data": {
    "records": [],
    "page": 1,
    "pageSize": 20,
    "total": 0
  },
  "traceId": "request-trace-id"
}
```

错误码建议至少包括：

- `AUTH_UNAUTHORIZED`
- `AUTH_FORBIDDEN`
- `VALIDATION_ERROR`
- `APPROVAL_NOT_FOUND`
- `TASK_NOT_FOUND`
- `TASK_ALREADY_COMPLETED`
- `INVALID_WORKFLOW_STATE`
- `FORM_VERSION_MISMATCH`
- `EXTERNAL_API_FAILED`
- `IDEMPOTENCY_CONFLICT`
- `WORKFLOW_OPERATION_FAILED`

## 13.1 审批类型接口

| 方法   | 接口                                   | 说明     |
| ---- | ------------------------------------ | ------ |
| GET  | `/approval-definitions`              | 查询审批类型 |
| POST | `/approval-definitions`              | 创建审批类型 |
| GET  | `/approval-definitions/{id}`         | 查询详情   |
| PUT  | `/approval-definitions/{id}`         | 修改草稿配置 |
| POST | `/approval-definitions/{id}/publish` | 发布审批类型 |
| POST | `/approval-definitions/{id}/disable` | 停用审批类型 |

发布接口必须执行表单、流程和审批人配置的综合校验。

## 13.2 表单设计接口

| 方法   | 接口                                       | 说明           |
| ---- | ---------------------------------------- | ------------ |
| POST | `/forms`                                 | 创建表单         |
| GET  | `/forms/{id}`                            | 查询表单         |
| PUT  | `/forms/{id}/draft`                      | 保存表单草稿       |
| POST | `/forms/{id}/validate`                   | 校验表单结构       |
| POST | `/forms/{id}/publish`                    | 发布表单版本       |
| GET  | `/forms/{id}/versions`                   | 查询表单版本       |
| GET  | `/form-versions/{versionId}`             | 获取指定版本       |
| GET  | `/approval-definitions/{id}/form-schema` | 获取审批类型当前表单结构 |

普通业务用户只能获取已发布表单，不能读取或编辑管理员的草稿。

## 13.3 流程设计接口

| 方法   | 接口                                  | 说明         |
| ---- | ----------------------------------- | ---------- |
| POST | `/workflows`                        | 创建流程定义     |
| GET  | `/workflows/{id}`                   | 查询流程定义     |
| PUT  | `/workflows/{id}/draft`             | 保存流程草稿     |
| POST | `/workflows/{id}/validate`          | 校验流程配置     |
| POST | `/workflows/{id}/preview`           | 使用测试数据预览路线 |
| POST | `/workflows/{id}/publish`           | 发布流程版本     |
| GET  | `/workflows/{id}/versions`          | 查询流程版本     |
| GET  | `/workflow-versions/{versionId}`    | 查询指定版本     |
| GET  | `/approval-instances/{id}/timeline` | 查询审批进度     |

## 13.4 审批提交与查询接口

| 方法   | 接口                          | 说明         |
| ---- | --------------------------- | ---------- |
| GET  | `/approvals/available`      | 获取可发起的审批类型 |
| POST | `/approvals/drafts`         | 保存草稿       |
| PUT  | `/approvals/drafts/{id}`    | 修改草稿       |
| GET  | `/approvals/drafts/{id}`    | 获取草稿       |
| POST | `/approvals/submit`         | 提交审批       |
| GET  | `/approvals/my`             | 查询我发起的审批   |
| GET  | `/approvals/{id}`           | 查询审批详情     |
| GET  | `/approvals/{id}/form-data` | 获取表单数据     |
| GET  | `/approvals/{id}/timeline`  | 查询流程时间线    |
| POST | `/approvals/{id}/withdraw`  | 撤回审批       |
| POST | `/approvals/{id}/cancel`    | 取消审批       |

提交请求必须携带幂等键，防止网络重试造成重复创建。

## 13.5 待办和已办接口

| 方法   | 接口                         | 说明       |
| ---- | -------------------------- | -------- |
| GET  | `/tasks/todo`              | 我的待办     |
| GET  | `/tasks/done`              | 我的已办     |
| GET  | `/tasks/{taskId}`          | 任务详情     |
| POST | `/tasks/{taskId}/approve`  | 同意审批     |
| POST | `/tasks/{taskId}/reject`   | 驳回审批     |
| POST | `/tasks/{taskId}/transfer` | 转交审批     |
| POST | `/tasks/{taskId}/add-sign` | 加签       |
| POST | `/tasks/{taskId}/return`   | 退回指定节点   |
| GET  | `/tasks/{taskId}/history`  | 查询任务操作历史 |

后端必须从已认证的用户上下文中获取操作人，不接受前端传入的 `operatorUserId` 作为可信身份。

## 13.6 用户和组织接口

| 方法   | 接口                              | 说明        |
| ---- | ------------------------------- | --------- |
| GET  | `/users`                        | 查询用户      |
| POST | `/users`                        | 新增内部用户    |
| PUT  | `/users/{id}`                   | 编辑用户      |
| POST | `/users/{id}/disable`           | 停用用户      |
| GET  | `/departments/tree`             | 查询部门树     |
| GET  | `/roles`                        | 查询角色      |
| POST | `/identity-providers`           | 配置身份数据源   |
| POST | `/identity-providers/{id}/test` | 测试外部身份接口  |
| POST | `/identity-providers/{id}/sync` | 手动同步用户或部门 |

## 13.7 外部接口管理

| 方法   | 接口                            | 说明          |
| ---- | ----------------------------- | ----------- |
| GET  | `/external-apis`              | 查询接口配置      |
| POST | `/external-apis`              | 创建接口配置      |
| PUT  | `/external-apis/{id}`         | 修改接口配置      |
| POST | `/external-apis/{id}/test`    | 测试接口        |
| POST | `/external-apis/{id}/disable` | 停用接口        |
| GET  | `/external-apis/{id}/logs`    | 查询调用日志      |
| POST | `/data-sources/{id}/query`    | 使用授权数据源查询数据 |

`/data-sources/{id}/query` 不应接受任意 URL；它只能调用已登记、已授权且符合安全策略的数据源。

---

# 十四、关键接口请求示例

## 14.1 提交审批

`POST /api/v1/approvals/submit`

请求头：

```
Idempotency-Key: 0ef7d2e4-9f8b-4c77-bc63-3f3c29f5b110
```

请求体：

```
{
  "approvalDefinitionId": "approval-type-uuid",
  "formVersionId": "form-version-uuid",
  "title": "办公设备采购申请",
  "formData": {
    "applicant": {
      "id": "user-001"
    },
    "amount": 6800,
    "reason": "办公设备采购"
  }
}
```

后端处理要点：

1. 从登录态获取申请人。
2. 校验审批类型及表单版本。
3. 校验字段权限和数据合法性。
4. 保存 MongoDB 表单数据。
5. 创建业务审批单。
6. 启动 Flowable 流程。
7. 关联流程实例。
8. 返回审批单 ID、审批编号和业务状态。

响应示例：

```
{
  "code": "0",
  "message": "success",
  "data": {
    "approvalInstanceId": "approval-instance-uuid",
    "approvalNo": "AP202610280001",
    "businessStatus": "PENDING"
  }
}
```

## 14.2 同意审批

`POST /api/v1/tasks/{taskId}/approve`

```
{
  "comment": "同意申请",
  "idempotencyKey": "task-action-uuid"
}
```

后端执行：

1. 校验任务归属和当前处理权限。
2. 检查任务是否仍然待办。
3. 获取或创建幂等操作记录。
4. 调用 Flowable 完成任务。
5. 更新业务任务投影。
6. 更新审批单状态。
7. 写入操作记录和通知事件。

## 14.3 转交审批

`POST /api/v1/tasks/{taskId}/transfer`

```
{
  "targetUserId": "user-002",
  "reason": "该事项由该同事负责"
}
```

后端必须校验目标用户有效、任务未完成、转交权限有效，并记录原办理人和新办理人。

## 14.4 加签审批

`POST /api/v1/tasks/{taskId}/add-sign`

```
{
  "targetUserIds": [
    "user-003"
  ],
  "type": "BEFORE",
  "completionRule": "ALL",
  "reason": "需要增加专业审核"
}
```

第一版建议先只允许单人前加签和单人后加签。多人会签式加签需要额外定义任务依赖、并行完成条件及异常终止策略。

## 14.5 退回指定节点

`POST /api/v1/tasks/{taskId}/return`

```
{
  "targetNodeKey": "department_manager",
  "reason": "采购金额填写错误"
}
```

服务端必须判断目标节点是否属于当前流程版本，并校验当前流程实例是否支持该种状态变更。

---

# 十五、数据接口配置和动态数据绑定

## 15.1 表单字段绑定外部接口

例如，采购表单中的供应商下拉框需要获取供应商列表。

管理员配置：

- 数据源：`get_suppliers`。
- 触发方式：表单初始化时加载。
- 显示字段：`supplierName`。
- 值字段：`supplierId`。
- 请求参数：当前登录用户 ID 或固定业务参数。
- 响应路径：`data.records`。

表单渲染器负责根据绑定定义发起业务数据请求，后端负责认证、授权和执行已登记接口。

## 15.2 级联数据

例如：

```
选择项目
   |
   v
获取项目合同
   |
   v
选择合同
   |
   v
获取合同明细
```

每个联动关系需要定义：

- 触发字段。
- 目标字段。
- 数据源 ID。
- 请求参数映射。
- 响应字段映射。
- 加载失败时的处理策略。

如果上游字段变化，下游字段应清空或重新加载，防止旧数据被误提交。

## 15.3 外部用户数据

如果审批人来自外部系统，建议通过 `UserDirectoryService` 统一解析用户：

```
审批节点配置
    |
    v
审批人解析服务
    |
    +-- 内部用户查询
    |
    +-- 外部用户映射查询
    |
    +-- 外部组织负责人查询
    |
    v
内部用户 ID 集合
    |
    v
Flowable 任务分配
```

不要在流程定义中直接写死外部接口地址，也不要让 Flowable 每次执行审批节点时都任意调用外部 HTTP 接口。

---

# 十六、事务、一致性和异常恢复

这是本项目必须优先实现的基础能力。

## 16.1 MySQL 与 Flowable

如果业务表和 Flowable 表共用 MySQL 数据源，并正确配置统一的 Spring 事务管理器，那么业务审批单写入和 Flowable 流程操作可以在同一个本地事务中完成。

例如：

```
开启 MySQL 事务
    |
    +-- 创建审批单
    +-- 启动 Flowable 流程
    +-- 关联流程实例 ID
    +-- 同步当前任务投影
    +-- 写入审批操作记录
    |
提交事务
```

任意关键操作失败时，应整体回滚。

但必须通过实际集成测试验证 Flowable 使用的事务管理器、数据源和事务传播配置，不能仅凭使用了 `@Transactional` 就认为事务一定生效。

## 16.2 MongoDB 与 MySQL

两个数据库之间不使用普通本地事务强行保证原子提交。

推荐提交策略：

1. 生成全局审批单 ID。
2. 保存 MongoDB 表单数据。
3. 开启 MySQL 事务。
4. 创建审批单并启动流程。
5. 提交 MySQL 事务。
6. 若失败，保留失败记录或标记待清理数据。
7. 后台定期检查没有有效业务记录的 MongoDB 文档。
8. 超过安全保留期限后再清理孤立数据。

提交过程需要幂等键和操作状态，防止重复请求生成多个审批单。

## 16.3 可靠事件表

建议增加 `wf_outbox_event`，用于可靠通知和异步副作用。

核心字段：

- `id`
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload_json`
- `status`
- `retry_count`
- `next_retry_at`
- `created_at`
- `processed_at`

审批业务事务中同时写入事件，后台任务负责发送通知、执行非关键同步和重试。

这能避免出现“审批已经成功，但通知没有发出去”的静默故障。

## 16.4 异常恢复

后台任务定期检查：

- `SUBMITTING` 长时间未结束的审批单。
- 流程实例已结束但业务状态未更新的审批单。
- Flowable 运行任务与 `wf_approval_task` 不一致的记录。
- 长时间未处理的 outbox 事件。
- MongoDB 中没有对应审批单的孤立数据。

自动恢复前必须先判断操作是否已经执行成功，避免重复创建任务或重复启动流程。

---

# 十七、权限与安全设计

## 17.1 审批权限

所有操作都需要验证：

- 当前用户是否已认证。
- 用户是否具有当前任务处理权限。
- 任务是否仍处于有效状态。
- 申请人是否有权撤回或取消。
- 目标用户是否允许被分配。
- 当前流程版本是否允许对应操作。
- 操作是否符合节点配置和业务状态。

前端按钮隐藏只是一种交互优化，不是安全措施。

## 17.2 接口安全

外部接口调用需要：

- HTTPS。
- 主机白名单。
- 禁止访问未经授权的内网和云元数据地址。
- 限制请求超时及响应大小。
- 凭证加密和脱敏。
- 请求日志脱敏。
- 用户级和接口级权限控制。
- 限流及异常熔断策略。

## 17.3 数据安全

- 审批单详情接口必须验证资源访问权限。
- MongoDB 文档不能仅凭前端传入的 `_id` 就允许读取。
- 附件下载必须校验所属审批单及用户权限。
- 已发布流程和表单版本不可被普通管理员无痕修改。
- 重要操作应保留操作者、时间、原因和操作前后状态。

---

# 十八、开发实施顺序

建议按以下阶段开发，避免一开始就同时实现所有复杂审批能力。

## 第一阶段：基础工程

- Spring Boot 项目结构。
- MySQL、MongoDB 连接。
- 用户、部门、角色。
- 登录认证和权限。
- Flowable 基础集成。
- 数据库迁移机制。

验收目标：用户能登录，服务端能创建并执行一个最简单的流程。

## 第二阶段：动态表单

- 表单设计器协议。
- 常用控件。
- JSON Schema 或统一自定义校验协议。
- 表单版本发布。
- MongoDB 表单数据读写。
- 表单默认值及外部数据源绑定。

验收目标：管理员创建一个新表单后，业务人员可以填写并保存数据，不需要新增数据库业务表。

## 第三阶段：基础审批流程

- 流程设计器协议。
- BPMN 生成与发布。
- 审批人解析。
- 审批提交。
- 我的待办、已办和我发起的。
- 同意、驳回、流程时间线。
- 操作审计。

验收目标：完成从配置、提交到审批结束的完整闭环。

## 第四阶段：高级审批

- 转交。
- 前加签和后加签。
- 退回指定节点。
- 撤回和取消。
- 会签。
- 并行流程的限制与校验。

验收目标：每种操作都有明确的状态变化、权限检查和历史记录。

## 第五阶段：可靠性与安全

- 幂等机制。
- Outbox 事件。
- 异常恢复。
- 数据一致性检查。
- 接口安全。
- 历史版本回归测试。
- 并发审批测试。

验收目标：重复请求、事务失败和流程异常不会造成重复审批或无法追溯的数据状态。

---

# 十九、开发验收清单

上线前至少完成以下验证：

- 创建两种不同表单的审批类型。
- 不新增业务数据库表即可新增一种简单审批。
- 表单发布新版本后，历史审批仍能正常展示。
- 流程发布新版本后，运行中的旧流程不受影响。
- 外部接口能够为表单提供下拉选项。
- 外部接口失败时，用户能看到明确提示。
- 同一个提交请求重复发送不会产生重复审批单。
- 同一个任务重复审批不会重复完成。
- 转交后原办理人不能继续处理已经转交的任务。
- 前加签完成前，原审批任务不能提前完成。
- 后加签按约定规则完成后才能继续后续流程。
- 退回任意节点前会校验流程结构和节点状态。
- 撤回后不会残留有效待办。
- 会签场景下，任务状态和流程状态一致。
- 用户不能通过猜测审批单 ID 查看他人数据。
- MySQL 事务失败后可以识别并清理 MongoDB 孤立文档。
- 通知失败后可以重试，不影响审批主流程。
- 外部接口不能被利用来访问未经授权的内网地址。

---

# 二十、最终技术决策

第一版推荐确定以下方案：

1. **架构：** Spring Boot 模块化单体，不急于拆分微服务。
2. **流程引擎：** Flowable，流程配置通过后端受控转换为 BPMN。
3. **关系数据：** MySQL 保存审批单、任务投影、权限、版本元数据和审计。
4. **动态数据：** MongoDB 保存表单定义 JSON 和完整表单数据。
5. **身份体系：** 内部用户和外部身份提供方统一映射为内部用户 ID。
6. **任务操作：** 统一通过流程操作服务执行审批、转交、加签、退回和撤回。
7. **一致性：** MySQL 与 Flowable 共享本地事务；MongoDB 通过幂等、补偿和恢复机制协调。
8. **可靠通知：** 采用 MySQL Outbox，第一版可用后台任务轮询。
9. **版本管理：** 已发布表单和流程定义采用不可变版本。
10. **安全控制：** 后端负责权限、字段校验、接口授权及流程状态校验。

这套设计能够支持从基础审批到复杂审批的逐步演进，同时避免将动态表单、业务状态和流程引擎耦合在一起。



## 下一步最值得先做的事情

我建议暂时不要马上编写全部业务代码，先把以下三个技术难点做成可运行的验证项目。

1\. Flowable 高级审批能力验证

先验证转交、前加签、后加签、退回历史节点、撤回，以及并行会签下的任务状态变化。尤其是退回任意节点，不能只看 API 是否能调用，还要验证流程历史和待办是否正确。

2\. MySQL + MongoDB 数据一致性验证

验证提交失败、重复请求、数据库异常时，审批单和表单 JSON 不会产生难以恢复的数据错位。

3\. 动态表单与动态流程验证

验证新增一个审批类型，只通过配置表单、审批节点和外部接口，就能完整跑通审批，而不需要修改 Java 业务代码。

我的建议：先实现一个“采购审批”示例作为贯穿整个系统的验证场景。 它同时包含动态金额字段、外部供应商接口、金额条件分支、部门负责人审批、财务审批和转交操作。等这个场景跑通，再扩展其他审批类型。

这样既能验证整体架构，也能尽早发现 Flowable 高级操作和跨数据库一致性上的问题。