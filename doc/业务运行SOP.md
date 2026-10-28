# 低代码审批流平台 · 业务运行 SOP（v2 · UI 原型驱动版）

**定位：** 描述"从零配置一个审批流程 → 走完一单审批"的完整业务作业程序，**细化到页面级**——每个页面配什么控件、哪些字段、哪些按钮、点按钮调哪个 API、状态怎么展示。
**用途：** 直接作为前端 UI 原型 / 页面设计稿的输入（页面清单见 §2，逐页规格见 §3）。
**配套文档：** 《后端SOP.md》（开发/运维视角）、《低代码审批流平台PRD.md》《开发计划.md》。
**可运行证明：** M3/M4/M5 验收测试就是本 SOP 的可执行脚本（§8）。

---

## 0. 怎么用这份 SOP 出 UI 原型

1. **先看 §2 页面清单**——总共 13 个页面，分管理端（配置期）/ 业务端（运行期）/ 运营保障三类。
2. **再看 §3 逐页规格**——每页含：路由、访问角色、布局、字段清单（含 UI 控件建议）、操作按钮 → 对应 API、状态标签、校验规则、交互跳转。
3. **配合 §4 字典速查**——所有枚举值（状态、操作类型、审批人来源、表单字段类型…）写原型时不查代码直接抄。
4. **§5 API 明细**给每个按钮背后接口的请求/响应体，原型联调 mock 直接用。
5. **生成顺序建议：** 先业务端（P07–P12，使用频次最高）→ 管理端（P01–P06，配置链路）→ 运营保障（P13）。

**组件约定（按若依生态建议）：** 列表用 Table + Tag；表单用 Form + 各控件；详情用 Descriptions；时间线用 Timeline；操作弹窗用 Modal/Dialog；向导用 Steps；树用 Tree。

---

## 1. 角色与权限矩阵

| 角色 | 典型身份 | 可访问页面 | 关键权限 |
|------|---------|-----------|---------|
| 平台管理员 | sys_user id=1（v1 硬编码 "1"） | 全部（P01–P13） | 表单/流程/审批类型/外部接口配置；取消任意 PENDING 审批单 |
| 配置人员（管理员角色） | 具备 admin 角色 | P01–P06、P13 | 配置期全部操作；不参与业务审批 |
| 普通用户（员工） | 任意有效用户 | P07–P12 | 发起审批、我的发起、待办/已办、详情、撤回自己的单 |
| 审批人 | 被节点审批人规则命中的用户 | P09–P12 | 处理分配给自己的待办：同意/驳回/转交/加签/退回 |
| 申请人 | 审批单的 applicantUserId | P08、P11 | 查看详情/时间线；PENDING 且 allowWithdraw=true 时撤回 |

> **资源级鉴权（查询层强制，PRD 17.3）：** 审批单详情/时间线只允许 **admin("1") / 申请人 / 该单任务办理人（投影 assignee）** 查看，其余一律拒绝。前端原型不必做权限隐藏逻辑，但后端会拦。

---

## 2. 页面清单（页面 → API 映射）

### A. 管理端（配置期）

| 编号 | 页面 | 路由建议 | 核心 API |
|------|------|---------|---------|
| P01 | 表单管理列表 | `/workflow/forms` | `GET /workflow/forms/list` |
| P02 | 表单设计器（版本草稿/发布） | `/workflow/forms/{formId}/design` | `POST /workflow/forms/{formId}/versions/draft`、`PUT …/versions/{versionId}/publish`、`GET …/versions` |
| P03 | 流程管理列表 | `/workflow/workflows` | `GET /workflow/workflows/list` |
| P04 | 流程设计器（BPMN + 节点配置） | `/workflow/workflows/{workflowId}/design` | `POST …/validate`、`POST …/publish` |
| P05 | 审批类型管理（三件套绑定+发布） | `/workflow/approval-definitions` | `GET/POST /workflow/approval-definitions`、`PUT /{id}`、`POST /{id}/publish`、`POST /{id}/disable` |
| P06 | 外部接口配置 | `/workflow/external-apis` | `GET/POST /workflow/external-apis`、`PUT`、`POST /{apiId}/test-call` |

### B. 业务端（运行期）

| 编号 | 页面 | 路由建议 | 核心 API |
|------|------|---------|---------|
| P07 | 发起审批（选类型→填单→提交/存草稿） | `/workflow/approvals/create` | `GET /workflow/approval-definitions/list`、`GET /workflow/forms/{formId}/schema`、`POST /workflow/form-data/draft`、`POST /workflow/form-data/submit`、`POST /workflow/approvals` |
| P08 | 我的发起 | `/workflow/approvals/my` | `GET /workflow/approvals/my` |
| P09 | 我的待办 | `/workflow/tasks/todo` | `GET /workflow/tasks/todo` |
| P10 | 我的已办 | `/workflow/tasks/done` | `GET /workflow/tasks/done` |
| P11 | 审批详情（含时间线+操作区） | `/workflow/approvals/{id}` | `GET /workflow/approvals/{id}`、`GET /workflow/approvals/{id}/timeline`、`GET /workflow/form-data/{approvalInstanceId}` |
| P12 | 审批操作弹窗（同意/驳回/转交/加签/退回） | —（P09/P11 触发） | `POST /workflow/tasks/{taskId}/approve|reject|transfer|add-sign|return`、`GET …/return-targets` |

### C. 运营保障

| 编号 | 页面 | 路由建议 | 核心数据源 |
|------|------|---------|-----------|
| P13 | 后台保障监控（outbox 死信/卡单巡检） | `/workflow/ops/monitor` | 巡检 SQL（§3.13） |

---

## 3. 页面规格

> 字段表约定：`控件` 列给出原型建议（Input/Textarea/InputNumber/DatePicker/Select/Switch/Tag/Table 列…）；`来源` 列 = 实体字段名或 API 返回字段。

### 3.1 P01 表单管理列表

- **路由** `/workflow/forms`　**角色** 配置人员/admin
- **布局**：顶部工具栏 + 列表表格；点击行或"设计"进入 P02。
- **工具栏**：新建表单（Modal 输入 formCode/formName）。
- **表格列**：

| 列 | 控件 | 来源 | 备注 |
|----|------|------|------|
| 表单名称 | Table 列 | formName | 主列，可点击进设计器 |
| 表单编码 | Table 列 | formCode | 唯一 |
| 状态 | Tag | status | DRAFT=灰/PUBLISHED=绿/DISABLED=红 |
| 当前版本 | Table 列 | currentVersion | 未发布显示 `—` |
| 创建人 | Table 列 | createdBy | |
| 创建时间 | Table 列 | createdAt | yyyy-MM-dd HH:mm:ss |

- **行操作**：`设计`（→P02）、`版本`（→P02 版本 Tab）。
- **新建表单** `POST /workflow/forms` 请求体 `{ formCode, formName }` → 返回创建的实体（含 id），随即跳转 P02。

### 3.2 P02 表单设计器（版本草稿/发布）

- **路由** `/workflow/forms/{formId}/design`　**角色** 配置人员/admin
- **布局**：左侧字段类型面板 + 中间字段编辑区 + 右侧 schema JSON 预览（或直接字段行编辑）；底部/顶部"保存草稿""发布"按钮；上方 Tab：「设计」「版本历史」。
- **字段类型面板（13 种，§4.6）**：text / textarea / number / money / date / datetime / select / radio / checkbox / user / dept / file / boolean。拖拽或点击添加一行字段。
- **字段行编辑项**：

| 配置项 | 控件 | 说明 |
|--------|------|------|
| field（字段键） | Input | 英文键，写表单数据 key |
| label（显示名） | Input | 校验报错用显示名 |
| type（类型） | Select（13 种） | 决定提交时校验规则（§4.6） |
| required（必填） | Switch | 提交强制必填；存草稿不强制 |
| options（选项） | 动态编辑（仅 select/radio/checkbox） | 键值对列表 |
| 默认值/占位/提示 | Input | 原型可自由扩展 |

- **保存草稿** `POST /workflow/forms/{formId}/versions/draft` 请求体 `{ schema }`，schema 结构：
```json
{ "fields": [ { "field": "amount", "type": "money", "label": "采购金额", "required": true } ] }
```
返回 `WfFormVersion`（含 versionId）。草稿可反复覆盖（DRAFT 状态）。
- **发布** `PUT /workflow/forms/{formId}/versions/{versionId}/publish` → 版本快照冻结（PUBLISHED，schema_hash 生成），发布后不可改。
- **版本历史 Tab**：`GET /workflow/forms/{formId}/versions` 列出版本：versionNo / status（DRAFT/PUBLISHED/ARCHIVED）/ publishedAt / publishedBy / schemaHash。当前发布版本可高亮。
- **取已发布 schema 供填报**：`GET /workflow/forms/{formId}/schema`（P07 填单页用）。

### 3.3 P03 流程管理列表

- **路由** `/workflow/workflows`　**角色** 配置人员/admin
- **布局**：顶部工具栏 + 表格；点击行或"设计"进入 P04。
- **工具栏**：新建流程（Modal：workflowCode / workflowName / 关联审批类型 approvalDefinitionId）。
- **表格列**：流程名称（可点击）/ 流程编码 / 所属审批类型（联查 approvalName）/ 状态（DRAFT/PUBLISHED/DISABLED）/ 当前版本 / 创建人 / 创建时间。
- **行操作**：`设计`（→P04）、`版本`（`GET /workflow/workflows/{workflowId}/versions`，展示 versionNo/status/bpmnHash/publishedAt/publishedBy）。
- **新建流程** `POST /workflow/workflows` 请求体 `{ approvalDefinitionId, workflowCode, workflowName }`。

### 3.4 P04 流程设计器（BPMN + 节点配置）

> **完整实现规格见《流程设计器UI规格.md》**（技术选型/页面结构/画布约束/属性面板/视觉设计/代码骨架/验收检查单），本节省略号内仅留 API 契约与要点。

- **路由** `/workflow/workflows/{workflowId}/design`　**角色** 配置人员/admin
- **技术选型**：`bpmn-js`（^18）+ `bpmn-js-properties-panel`（^3）+ `@bpmn-io/properties-panel`（^3），原生 JS 封装 Vue3 组件；workflow-web 现有 Vue3.5 + Vite6 + Element Plus，**尚无 bpmn-js，需新增依赖**。
- **布局**：顶栏（校验/发布/撤销/重做/删除/缩放）+ 左调色板 + 中画布 + 右属性面板（三栏）。
- **调色板只暴露白名单 6 类（§4.7）**：startEvent / endEvent / userTask / sequenceFlow / exclusiveGateway / parallelGateway；自定义 PaletteProvider + Rules 拦截，画布放不出其它 BPMN 元素。
- **申请节点**：起始事件直达的第一个 UserTask 自动识别，绿色徽标「申请」+ 属性面板禁配审批人；提交后自动完成、不进待办。
- **属性面板字段（选中 UserTask，写入 configJson）**：

| 配置项 | 控件 | configJson 键 |
|--------|------|--------------|
| 节点 ID | Input（正则+唯一校验） | element.id |
| 节点名称 | Input | element.name |
| 审批人来源 | Select | assignType（六来源 §4.4） |
| 来源值 | Input（按来源联动） | assignValue |
| 表单字段 | Input（仅 FORM_FIELD） | formField |
| 会签规则 | Select | completionRule（ALL/OR/PERCENT） |
| 比例 % | InputNumber(1-100)（仅 PERCENT） | percent |

- **校验** `POST /workflow/workflows/{workflowId}/validate` 请求体 `{ bpmnXml }` → 返回**规一化后的 BPMN XML**（普通审批 UserTask 被改写为并行多实例 collection）。**发布时直接用校验返回的规一化 XML，不要用画布原始 XML 覆盖。**
- **发布** `POST /workflow/workflows/{workflowId}/publish` 请求体 `{ bpmnXml, configJson }` → 部署 Flowable + 版本快照冻结。configJson 结构：
```json
{
  "nodes": {
    "deptApproval":    { "assignType": "DEPT_LEADER",  "completionRule": "ALL" },
    "financeApproval": { "assignType": "USER",         "assignValue": "5",   "completionRule": "OR" },
    "bossApproval":    { "assignType": "FORM_FIELD",   "formField": "boss",  "completionRule": "PERCENT", "percent": 60 }
  }
}
```
> 节点 ID 必须与画布 UserTask 节点 ID 一致；每个审批 UserTask 都应有节点配置（找不到人提交被 BLOCK，§4.4）。**网关金额条件 v1 运行时不可用**（表单字段未注入流程变量，M6 补），前端可先做连线条件编辑能力。

### 3.5 P05 审批类型管理（三件套绑定 + 发布）

- **路由** `/workflow/approval-definitions`　**角色** 配置人员/admin
- **布局**：列表 + 详情/编辑抽屉（Drawer）。这是"审批类型 = 表单版本 + 流程版本 + 审批人规则"的绑定页。
- **表格列**：审批名称 / 审批编码 / 分类 / 状态（DRAFT/PUBLISHED/DISABLED）/ 当前表单版本 / 当前流程版本 / 描述 / 更新时间。
- **新建** `POST /workflow/approval-definitions` 请求体 `{ approvalCode, approvalName, categoryId, description, icon }`。
- **编辑抽屉（PUT /workflow/approval-definitions/{id}）**：

| 配置项 | 控件 | 来源 | 说明 |
|--------|------|------|------|
| 审批名称/编码/分类/描述/图标 | Input / Select / Input | approvalName / approvalCode / categoryId / description / icon | |
| 绑定表单版本 | Select | currentFormVersionId | 选项来自 P02 已 PUBLISHED 的表单版本（`GET /workflow/forms/{formId}/versions` 过滤） |
| 绑定流程版本 | Select | currentFlowVersionId | 选项来自 P03 已 PUBLISHED 的流程版本 |
| 允许撤回 | Switch | allowWithdraw | 控制 P08/P11 的"撤回"按钮是否可见/可用 |
| 允许取消 | Switch | allowCancel | 控制管理员"取消"按钮可用性 |

- **发布** `POST /workflow/approval-definitions/{id}/publish` → 状态 PUBLISHED，此后可发起该类型审批（后端综合校验表单/流程均已发布）。**发布后修改需重新发布；已发起单不受影响。**
- **停用** `POST /workflow/approval-definitions/{id}/disable` → DISABLED（不可再发起新单，历史单继续走）。

### 3.6 P06 外部接口配置

- **路由** `/workflow/external-apis`　**角色** 配置人员/admin
- **布局**：列表 + 新建/编辑抽屉 + "测试调用"弹窗。用途：给 `EXTERNAL_API` 审批人来源（§4.4）提供已登记接口。
- **表格列**：接口名称 / 接口编码 / 基础地址+路径 / HTTP 方法 / 认证类型 / 状态（ENABLED/DISABLED）/ 超时 / 创建人。
- **新建/编辑（POST / PUT /workflow/external-apis）字段**：

| 字段 | 控件 | 说明 |
|------|------|------|
| apiCode / apiName | Input | 编码唯一，审批人配置里填的就是它 |
| baseUrl / path | Input | 如 `https://api.example.com` + `/v1/users` |
| httpMethod | Select | GET / POST |
| authType | Select | NONE / BEARER / API_KEY（凭证本体不落库，仅 credentialRef 引用） |
| timeoutMs | InputNumber | 超时毫秒 |
| allowedHostsJson | Textarea | **SSRF 白名单**：允许访问的主机列表 JSON（如 `["api.example.com"]`），不在白名单拒绝调用 |
| configJson | Textarea | 请求参数/响应映射等非敏感配置 |
| status | Select | ENABLED / DISABLED |

- **测试调用** `POST /workflow/external-apis/{apiId}/test-call` 请求体 `{ requestParams }` → 返回调用结果（白名单内主机，结果落 `wf_external_api_log`）。

### 3.7 P07 发起审批（选类型 → 填单 → 提交/存草稿）

- **路由** `/workflow/approvals/create`　**角色** 普通用户
- **布局（三步向导 Steps）**：
  1. **选择审批类型**：卡片/列表展示已 PUBLISHED 的审批类型（`GET /workflow/approval-definitions/list`），展示 icon + 名称 + 描述；DISABLED 不展示。
  2. **填写表单**：按该类型绑定表单版本的已发布 schema（`GET /workflow/forms/{formId}/schema`）**动态渲染表单**——字段类型→控件映射见 §4.6。顶部有"标题"输入框（title）。
  3. **提交确认**：回显关键字段 + 提交/存草稿按钮。
- **表单控件映射（§4.6 驱动动态渲染）**：text→Input；textarea→Textarea；number/money→InputNumber（money 禁止负数）；date/datetime→DatePicker；select→Select；radio→Radio.Group；checkbox→Checkbox.Group；user→用户选择器（弹窗选人，值存 userId）；dept→部门选择器；file→附件上传（值含 fileId）；boolean→Switch。
- **必填校验**：required=true 的字段提交时后端强制；前端同规则先拦。
- **存草稿** `POST /workflow/form-data/draft` 请求体 `{ formVersionId, data, title }`（data=表单 JSON）→ 不强制必填，返回审批单实例（草稿态）。
- **提交（表单式）** `POST /workflow/form-data/submit`（同上，强制必填+类型校验）。
- **提交（审批式，推荐）** `POST /workflow/approvals` 请求体：
```json
{
  "approvalDefinitionId": "…",
  "formVersionId": "…",
  "title": "采购单-20261028-001",
  "formData": { "amount": 12000, "requester": "3", "vendor": "某供应商" },
  "idempotencyKey": "前端生成的UUID"
}
```
返回 `WfApprovalInstance`（含 approvalNo、id、businessStatus=PENDING、currentNodeName=首个审批节点名）。
- **⚠️ 提交后语义（必须写进原型交互提示）**：提交即视为已进入审批，**没有"待提交"环节**；`apply` 填单节点自动完成，**不会出现在任何人的待办里**；第一个审批节点的办理人会立刻在 P09 看到该单。
- **成功跳转** → P11 详情页（或 P08 我的发起）。

### 3.8 P08 我的发起

- **路由** `/workflow/approvals/my`　**角色** 申请人（本人数据）
- **布局**：表格 + 顶部筛选（状态/时间）。
- **表格列**：单号 approvalNo / 标题 title / 审批类型（联查 approvalName）/ 状态 Tag / 当前节点 currentNodeName / 申请人 applicantNameSnapshot / 提交时间 submittedAt / 完成时间 completedAt。
- **行操作**：`详情`（→P11）；`撤回`——仅 PENDING 且该审批类型 allowWithdraw=true 且自己为申请人时显示（按钮校验与后端一致）。
- **撤回** `POST /workflow/approvals/{id}/withdraw` 请求体 `{ reason, idempotencyKey }` → 业务状态 WITHDRAWN，待办全部取消。

### 3.9 P09 我的待办

- **路由** `/workflow/tasks/todo`　**角色** 审批人/办理人
- **布局**：表格 + 状态筛选（活跃任务即 CREATED/PENDING）。
- **表格列（任务投影字段 + 需联查审批单字段）**：

| 列 | 来源 | 备注 |
|----|------|------|
| 标题 | 联查 `WfApprovalInstance.title` | 按 approvalInstanceId 联查 |
| 单号 | 联查 `approvalNo` | |
| 申请人 | 联查 `applicantNameSnapshot` | |
| 审批节点 | taskName / taskDefinitionKey | |
| 当前办理人 | assigneeUserId | 多实例节点每人一条记录 |
| 收到时间 | createdAt | |
| 截止时间 | dueAt | v1 无 SLA，原型可留空 |

> ⚠️ **后端已知缺口**：`GET /workflow/tasks/todo` 只返回任务投影字段（无标题/单号/申请人）。原型阶段建议前端按行联查详情接口，或后端后续提供富化 VO。

- **行操作**：`处理`（→P12 操作弹窗）；`查看详情`（→P11）。
- **点击处理前的按钮可用性（后端强制，前端可展示但最终以后端拦截为准）**：同意/驳回/转交/加签/退回。

### 3.10 P10 我的已办

- **路由** `/workflow/tasks/done`　**角色** 审批人/办理人
- **布局**：表格，列同 P09（+完成时间 completedAt）。
- **状态过滤**：COMPLETED / CANCELLED。
- **行操作**：`查看详情`（→P11，只读，无操作区按钮或按钮全禁用）。

### 3.11 P11 审批详情（含时间线 + 操作区）

- **路由** `/workflow/approvals/{id}`　**角色** admin / 申请人 / 该单办理人（资源级鉴权，无权直接 403）
- **布局（从上到下）**：
  1. **头部**：单号 + 标题 + 状态 Tag + 当前节点 + 申请人/部门 + 提交时间（Descriptions）。
  2. **审批进度条（Steps，可选原型增强）**：把流程节点顺序渲染为步骤条，当前节点高亮；数据来自流程版本 BPMN（原型阶段可 mock 或由 P04 流程数据推导）。
  3. **表单内容（只读）**：`GET /workflow/form-data/{approvalInstanceId}` 返回 `{ approvalInstanceId, formVersionId, schemaHash, dataVersion, data }`，按 schema 渲染只读详情（Descriptions/只读 Form）。
  4. **审批时间线**：`GET /workflow/approvals/{id}/timeline` → `WfApprovalAction[]`（§4.2/§4.8），Timeline 组件展示：操作人（联查姓名）/操作类型徽标/意见/目标/状态变化（before→after）/时间。
  5. **操作区**：按当前用户身份与单状态渲染按钮：
     - 我是当前待办办理人 → P12 操作按钮组（同意/驳回/转交/加签/退回）；
     - 我是申请人且 PENDING 且 allowWithdraw → `撤回`；
     - 我是 admin("1") 且 PENDING 且 allowCancel → `取消`；
     - 其余只读。
- **详情接口** `GET /workflow/approvals/{id}` 返回 `WfApprovalInstance`（§4.8 字段全表）。
- **⚠️ 已知缺口**：详情页"当前活跃任务"无专用端点；原型可基于 `GET /workflow/tasks/todo`（本人视角过滤 approvalInstanceId）或 mock。

### 3.12 P12 审批操作弹窗

- **触发**：P09 行"处理" / P11 操作区按钮。**角色** 当前任务办理人（后端强校验 assignee）。
- **所有操作共有的底层语义**：每条操作都必须带 `idempotencyKey`（前端生成 UUID，**失败重试必须用同一 key**，后端幂等扣重防双击）；操作全部留痕到时间线 + `wf_approval_operation`。
- **各弹窗规格**：

| 操作 | 弹窗内容 | 请求体 | 成功后 |
|------|---------|--------|--------|
| 同意 | 意见 Textarea（可选） | `{ comment, idempotencyKey }` → `POST /workflow/tasks/{taskId}/approve` | 流程结束→APPROVED；未结束→刷新到下一节点 |
| 驳回 | 意见 Textarea（必填建议） | `{ comment, idempotencyKey }` → `POST …/reject` | 业务态 RETURNED，退到申请人节点，申请人可再次推进 |
| 转交 | 目标人选择器 + 原因 | `{ toUserId, reason, idempotencyKey }` → `POST …/transfer` | 当前任务办理人变为目标人，原办理人留痕 |
| 加签 | signType Radio（FIRST前/AFTER后）+ 加签人选择器 + 原因 | `{ signType, addUsers[], reason, idempotencyKey }` → `POST …/add-sign` | 仅**并行 ALL** 多实例节点可用（串行/或签/比例签后端拒绝） |
| 退回 | 目标节点 Select + 原因 | `{ targetNodeKey, reason, idempotencyKey }` → `POST …/return` | 目标列表来自 `GET …/return-targets`（§4.4 注意 MI 目标限制） |

- **退回目标选择器数据**：`GET /workflow/tasks/{taskId}/return-targets` → `[ { nodeKey, nodeName, nodeType(APPLICANT/MI/PLAIN), available, reason } ]`；`available=false` 的选项禁用并展示 reason 提示（如 MI 目标无可退前置串行节点）。

### 3.13 P13 后台保障监控（运营侧）

- **路由** `/workflow/ops/monitor`　**角色** admin/运维
- **用途**：对应 §4 后台保障的每日巡检，前端可做"运维仪表盘"：
  - **Outbox 死信**：`wf_outbox_event` 中 status=FAILED 的事件（事件类型/数量/最近错误），可展示为表格 + 重新投递入口（原型占位）；
  - **卡单**：`wf_approval_instance` 中 SUBMITTING 超过 30 分钟的记录（提交事务链中断兜底）；
  - **巡检 SQL（§5.3）** 直接作为数据源，或后端封装为监控接口。

---

## 4. 字典速查（写原型不查代码）

### 4.1 状态枚举

**审批单业务状态** `WfApprovalInstance.businessStatus`

| 值 | 含义 | Tag 建议色 | 前端展示 |
|----|------|-----------|---------|
| DRAFT | 草稿（建表默认，预留） | 灰 | 待提交 |
| SUBMITTING | 提交中（事务链中途，短暂窗口） | 橙 | 提交中 |
| PENDING | 审批中 | 蓝 | 审批中 |
| APPROVED | 已通过 | 绿 | 已通过 |
| RETURNED | 已驳回（退回申请人） | 红 | 已驳回 |
| WITHDRAWN | 已撤回 | 紫 | 已撤回 |
| CANCELLED | 已取消 | 灰黑 | 已取消 |
| FAILED | 提交失败（一致性恢复兜底） | 红 | 已失败 |

> 实体注释里还写了 REJECTED/ERROR，代码中未使用（历史注释残留），原型不要引用。

**任务状态** `WfApprovalTask.taskStatus`

| 值 | 含义 | 说明 |
|----|------|------|
| CREATED / PENDING | 活跃待办 | 待办查询按 `in ('CREATED','PENDING')`；写入统一用 PENDING |
| COMPLETED | 已办 | 同意/转交/驳回后 |
| CANCELLED | 已取消 | 撤回/取消批量置此 |

**定义/版本状态**：表单定义、流程定义、审批类型共用 `DRAFT / PUBLISHED / DISABLED`；版本类（表单版本/流程版本）用 `DRAFT / PUBLISHED / ARCHIVED`。
**外部接口状态**：`ENABLED / DISABLED`。
**Outbox 事件状态**：`PENDING / PROCESSING / PROCESSED / FAILED`。

### 4.2 操作类型（时间线徽标 + wf_approval_operation.operation_type）

实际使用：`SUBMIT / APPROVE / REJECT / TRANSFER / ADD_SIGN / RETURN / WITHDRAW / CANCEL`
（实体注释里还有 CC / ADMIN_TERMINATE 为 v1 未用保留值，原型不展示。）
前端徽标文案建议：提交 / 同意 / 驳回 / 转交 / 加签 / 退回 / 撤回 / 取消。

### 4.3 Outbox 事件类型（`wf_outbox_event.event_type`，聚合类型 APPROVAL）

`APPROVAL_SUBMITTED / APPROVAL_APPROVED / APPROVAL_REJECTED / APPROVAL_RETURNED / APPROVAL_WITHDRAWN / APPROVAL_CANCELLED`

### 4.4 审批人来源（节点 assignType，六种）

| 值 | 含义 | assignValue 用法 | 找不到人行为 |
|----|------|-----------------|-------------|
| USER | 指定用户 | 逗号分隔用户ID，如 `3,4` | BLOCK（阻止提交） |
| ROLE | 指定角色 | 角色 key（sys_role.role_key，按 sys_user_role 关联有效用户） | BLOCK |
| DIRECT_MANAGER | 发起人直属主管 | 留空（取 sys_user.manager_user_id） | BLOCK |
| DEPT_LEADER | 发起人部门负责人 | 留空（取 sys_dept.leader） | BLOCK |
| FORM_FIELD | 表单人员字段 | 配 formField=表单字段 key（字段值为 userId 或 `{id}` 对象） | BLOCK |
| EXTERNAL_API | 外部接口返回人员 | assignValue=接口编码 apiCode（响应期望 `{users:[{id}]}`，走 P06 白名单） | BLOCK |

> **BLOCK 语义**：审批节点解析不到任何人 → 提交直接失败，**禁止默默跳过审批节点**（PRD 12.2）。

### 4.5 会签规则（节点 completionRule）

| 值 | 含义 | 引擎行为 |
|----|------|---------|
| ALL（缺省） | 全员会签 | 所有实例都完成才放行（不写 completionCondition） |
| OR | 或签（任一人） | 任意 1 人完成即放行 |
| PERCENT | 比例签 | 需配 percent(1-100)，`completed*100 >= instances*percent` 即放行 |

> 加签 v1 仅支持"并行 ALL"节点：串行节点或 OR/PERCENT 节点后端拒绝加签。

### 4.6 表单字段类型（13 种，驱动动态渲染 + 校验）

| type | 前端控件建议 | 后端校验规则 |
|------|-------------|-------------|
| text | Input | 字符串 |
| textarea | Textarea | 字符串 |
| number | InputNumber | Number 类型 |
| money | InputNumber（禁负） | Number 且 ≥0 |
| date | DatePicker | 字符串/数字 |
| datetime | DatePicker(datetime) | 字符串/数字 |
| select | Select | 字符串 |
| radio | Radio.Group | 字符串 |
| checkbox | Checkbox.Group | 对象/字符串/数组 |
| user | 用户选择器 | 字符串（存 userId） |
| dept | 部门选择器 | 字符串 |
| file | 附件上传 | 对象须含 fileId 或 id 元数据 |
| boolean | Switch | Boolean 类型 |

**schema 结构**：`{ "fields": [ { "field", "type", "label", "required", "options"? } ] }`。校验维度：必填（提交强制/草稿不强制）、类型、未知类型拒绝、未知字段不校验（白名单仅类型）。

### 4.7 BPMN 节点白名单（P04 Palette）

`StartEvent / EndEvent / UserTask / SequenceFlow / ExclusiveGateway / ParallelGateway`；最大网关嵌套深度 10；节点 ID `[A-Za-z0-9_-]{1,100}`。申请节点（起始事件直达的 UserTask）特殊：自动注入申请人，不进待办，不配审批人。

### 4.8 数据表字段 → UI 呈现速查

**WfApprovalInstance（审批单）**

| 字段 | 控件建议 | 备注 |
|------|---------|------|
| id | 隐藏 | UUID |
| approvalNo | 列表列/详情头 | 单号 `AP+yyyyMMddHHmmss+4位随机` |
| title | 列表列 | 审批标题 |
| approvalDefinitionId | 隐藏（联查审批类型名） | |
| applicantNameSnapshot / applicantDeptSnapshot | 列表列/详情 | 申请人姓名/部门快照 |
| businessStatus | Tag | §4.1 |
| currentNodeKey / currentNodeName | 列表列"当前节点"/详情 | 无活跃任务时为 null |
| submittedAt / completedAt / createdAt | 时间列 | |
| formVersionId / workflowVersionId / processInstanceId / formDataId / version | 隐藏 | 运维排查用 |

**WfApprovalTask（任务投影，P09/P10 行）**：id(隐藏) / approvalInstanceId(联查单) / flowableTaskId(隐藏) / taskDefinitionKey+taskName(节点名列) / assigneeUserId(办理人列) / originalAssigneeUserId / ownerUserId(隐藏) / taskStatus(Tag) / delegatedToUserId(转交人) / parentTaskId(隐藏) / dueAt(截止列) / createdAt / completedAt。

**WfApprovalAction（时间线项）**：actionType(徽标§4.2) / operatorUserId(操作人，联查姓名) / comment(意见) / targetUserId(转交目标) / targetTaskKey(退回目标) / beforeStatus→afterStatus(状态变化) / taskDefinitionKey(节点) / createdAt(时间)。

**WfApprovalDefinition（P05）**：approvalCode / approvalName / categoryId / description / icon / currentFormVersionId / currentFlowVersionId / allowWithdraw(Switch) / allowCancel(Switch) / status / createdBy / createdAt / updatedAt。

**WfFormDefinition（P01）**：formCode / formName / status / currentVersion / createdBy / createdAt。
**WfFormVersion（P02 版本 Tab）**：versionNo / status / schemaHash / publishedAt / publishedBy。
**WfWorkflowDefinition（P03）**：workflowCode / workflowName / approvalDefinitionId / status / currentVersion。
**WfWorkflowVersion（P03 版本 Tab）**：versionNo / status / processDefinitionKey / bpmnHash / configJson / publishedAt / publishedBy。
**WfExternalApi（P06）**：见 §3.6 全字段。

---

## 5. API 明细（含请求/响应示例）

> 通用：均返回若依 `AjaxResult`（`{ code, msg, data }`）；所有**变更类**接口必传 `idempotencyKey`。

### 5.1 配置期

| 方法 | 路径 | 请求体 | 返回 data |
|------|------|--------|-----------|
| POST | `/workflow/forms` | `{ formCode, formName }` | WfFormDefinition |
| GET | `/workflow/forms/list` | 查询参数 | WfFormDefinition[] |
| GET | `/workflow/forms/{formId}` | — | WfFormDefinition |
| POST | `/workflow/forms/{formId}/versions/draft` | `{ schema }` | WfFormVersion |
| PUT | `/workflow/forms/{formId}/versions/{versionId}/publish` | — | WfFormVersion |
| GET | `/workflow/forms/{formId}/versions` | — | WfFormVersion[] |
| GET | `/workflow/forms/{formId}/schema` | — | 已发布 schema（动态渲染用） |
| POST | `/workflow/workflows` | `{ approvalDefinitionId, workflowCode, workflowName }` | WfWorkflowDefinition |
| POST | `/workflow/workflows/{workflowId}/validate` | `{ bpmnXml }` | 规一化 BPMN XML（字符串） |
| POST | `/workflow/workflows/{workflowId}/publish` | `{ bpmnXml, configJson }` | WfWorkflowVersion |
| GET | `/workflow/workflows/{workflowId}` / `/list` / `/{id}/versions` | — | 定义/列表/版本数组 |
| POST | `/workflow/approval-definitions` | `{ approvalCode, approvalName, categoryId, description, icon }` | WfApprovalDefinition |
| PUT | `/workflow/approval-definitions/{id}` | `{ currentFormVersionId, currentFlowVersionId, allowWithdraw, allowCancel, … }` | WfApprovalDefinition |
| POST | `/workflow/approval-definitions/{id}/publish` | — | WfApprovalDefinition |
| POST | `/workflow/approval-definitions/{id}/disable` | — | WfApprovalDefinition |
| GET | `/workflow/approval-definitions/list` / `/{id}` | — | 列表/详情 |
| POST | `/workflow/external-apis` | WfExternalApi 全字段 | WfExternalApi |
| PUT | `/workflow/external-apis` | WfExternalApi 全字段 | WfExternalApi |
| GET | `/workflow/external-apis/list` | 查询参数 | WfExternalApi[] |
| POST | `/workflow/external-apis/{apiId}/test-call` | `{ requestParams }` | 调用结果 Map |

### 5.2 运行期

| 方法 | 路径 | 请求体 | 返回 data |
|------|------|--------|-----------|
| POST | `/workflow/form-data/draft` | `{ formVersionId, data, title }` | WfApprovalInstance |
| POST | `/workflow/form-data/submit` | 同上 | WfApprovalInstance |
| GET | `/workflow/form-data/{approvalInstanceId}` | — | `{ approvalInstanceId, formVersionId, schemaHash, dataVersion, data }` |
| POST | `/workflow/approvals` | `{ approvalDefinitionId, formVersionId, title, formData, idempotencyKey }` | WfApprovalInstance |
| GET | `/workflow/approvals/{id}` | —（鉴权） | WfApprovalInstance |
| GET | `/workflow/approvals/{id}/timeline` | —（鉴权） | WfApprovalAction[] |
| GET | `/workflow/approvals/my` | — | WfApprovalInstance[] |
| POST | `/workflow/approvals/{id}/withdraw` | `{ reason, idempotencyKey }` | WfApprovalInstance |
| POST | `/workflow/approvals/{id}/cancel` | `{ reason, idempotencyKey }` | WfApprovalInstance |
| GET | `/workflow/tasks/todo` / `/done` | — | WfApprovalTask[] |
| POST | `/workflow/tasks/{taskId}/approve` | `{ comment, idempotencyKey }` | WfApprovalInstance |
| POST | `/workflow/tasks/{taskId}/reject` | `{ comment, idempotencyKey }` | WfApprovalInstance |
| POST | `/workflow/tasks/{taskId}/transfer` | `{ toUserId, reason, idempotencyKey }` | WfApprovalInstance |
| POST | `/workflow/tasks/{taskId}/add-sign` | `{ signType, addUsers[], reason, idempotencyKey }` | WfApprovalInstance |
| POST | `/workflow/tasks/{taskId}/return` | `{ targetNodeKey, reason, idempotencyKey }` | WfApprovalInstance |
| GET | `/workflow/tasks/{taskId}/return-targets` | — | `[{ nodeKey, nodeName, nodeType, available, reason }]` |

**提交示例（完整）**：
```json
// POST /workflow/approvals
{
  "approvalDefinitionId": "fd8f…",
  "formVersionId": "3a2b…",
  "title": "采购单-20261028-001",
  "formData": { "amount": 12000, "vendor": "某供应商", "requester": "3" },
  "idempotencyKey": "0f8e1c2a-…"
}
// 响应 data:
{
  "id": "…", "approvalNo": "AP202610281030150001",
  "businessStatus": "PENDING", "currentNodeKey": "deptApproval",
  "currentNodeName": "部门负责人审批", "applicantUserId": "3",
  "applicantNameSnapshot": "张三", "title": "采购单-20261028-001",
  "processInstanceId": "…", "submittedAt": "2026-10-28 10:30:15.000"
}
```

**同意示例（流程未结束时）**：`POST /workflow/tasks/{taskId}/approve` 返回同结构 WfApprovalInstance，`currentNodeName` 已刷新为下一节点（后端自动补齐新节点待办投影）。

### 5.3 后台保障巡检 SQL（P13 数据源）

```sql
-- outbox 死信
select event_type, count(*) as cnt, max(last_error) as last_error
  from wf_outbox_event where status='FAILED' group by event_type;
-- 提交卡死（SUBMITTING 超 30 分钟）
select id, approval_no, business_status, created_at
  from wf_approval_instance
 where business_status='SUBMITTING' and created_at < now() - interval 30 minute;
```

---

## 6. 端到端页面串联

### 6.1 配置期（管理端，纯配置零 Java）

```
P01 建表单 → P02 设计 schema → 发布
P03 建流程 → P04 画 BPMN + 配节点审批人/会签规则 → validate → publish
P05 建审批类型 → 绑定表单版本 + 流程版本 → 设置 allowWithdraw/allowCancel → publish
（可选）P06 登记外部接口（若审批人来源用 EXTERNAL_API）
```
**验收判据**：P05 发布成功，即可在 P07 看到该审批类型并发起新单。

### 6.2 运行期（业务端，走完一单）

```
P07 选类型 → 动态填单 → 提交（携带 idempotencyKey）
  → P08 我的发起出现该单（PENDING，当前节点=首个审批节点）
  → 首个审批节点的办理人登录 → P09 待办可见该单
  → P12 同意 → （多节点）下一节点办理人继续，或流程结束 → 状态 APPROVED
  → P10 已办留痕；P11 时间线完整
```
**异常路径**：驳回 → RETURNED 退申请人 → 申请人重新提交（P07 或详情页再提交）→ 重新走审批；撤回（仅申请人/允许）/ 取消（仅 admin/允许）。

### 6.3 典型案例：采购审批贯穿（M6 目标）

1. 表单含动态金额字段 `amount(money)`、`requester(user)`、`vendor(text)`；
2. 流程含**金额条件分支**：ExclusiveGateway 按金额分流（<1000 部门负责人直接通过，≥1000 进财务）；
3. 部门节点审批人 = `DEPT_LEADER`；财务节点 = `USER`；财务节点前可调用外部供应商接口 = `EXTERNAL_API`；
4. 财务节点办理人可**转交**。
> **已知依赖/缺口**：v1 启动流程时只注入 `approvalInstanceId / applicantUserId / wf_users_*` 变量，**表单金额未注入流程变量**，金额网关条件需 M6 提供"表单字段→流程变量映射"后才能生效；原型阶段该网关判定可 mock。

---

## 7. 关键实现语义（前端必须知道的坑）

1. **apply 填单节点不进待办**：提交后流程已自动推进到首个审批节点；"申请环节可反复修改"不是 v1 语义。
2. **驳回 = 退回申请人（RETURNED）**，不是终态；申请人节点重建任务后可再次提交推进。
3. **加签仅并行 ALL 节点**：串行/或签/比例签节点后端拒绝（`requireParallelAllNode`）。
4. **退回 MI 目标受限**：Flowable 直退多实例会造空壳，MI 目标只走"退前置串行节点 + 集合变量重写"；无前置普通节点则 `return-targets.available=false`。
5. **版本不可变**：改审批人/表单后必须重新发布；已发起单不受影响。
6. **资源级鉴权是查询层强制**：详情/时间线只对 admin/申请人/办理人开放，前端无需（也无法）绕过。
7. **幂等键必传**：所有变更操作（提交/同意/驳回/转交/加签/退回/撤回/取消）都要求 `idempotencyKey`；前端失败重试用同一 key，否则后端会按"新操作"处理（同一 key 已 SUCCESS 时直接返回原结果，防双击）。
8. **多实例节点 = 每人一条任务**：会签节点每个审批人各有一条待办记录（taskDefinitionKey 相同、assignee 不同）；推进条件是全体/任一/比例（§4.5）。
9. **撤回/取消后流程实例删除**：待办批量 CANCELLED，时间线留痕，单状态终态化（WITHDRAWN/CANCELLED）。
10. **转交留痕**：`wf_task_transfer` + 时间线 targetUserId；投影 assignee 更新，`delegated_to_user_id` 记转交人，original 保持初始办理人。

---

## 8. 验收链路映射（测试即 SOP）

| SOP 步骤 | 对应页面 | M3/M4/M5 验收用例 |
|---------|---------|------------------|
| 配置期（P01–P05） | 管理端 | `createAndPublishApprovalFlow`（M3/M4/M5 各一套） |
| 提交事务链 + outbox 原子发布（P07） | 发起审批 | `outboxPublishIsAtomicAndDispatchMarksProcessed` |
| 审批推进 + 新节点补投影（P09/P12） | 待办/处理 | M4 `T12 投影一致性`；M5 `recoverProjectionMismatch…` |
| 高级操作（P12） | 操作弹窗 | M4 转交/加签/退回/撤回/取消/或签比例 全用例 |
| 鉴权（P11） | 详情 | M5 `resourceLevelAuthPreventsIdTraversal` |
| 后台保障（P13） | 监控 | M5 outbox 重试/死信 + 恢复四类漂移用例 |

**示例流程（M5 BPMN）**：`apply → deptApproval(多实例 3,4) → financeApproval(5) → end`
一单全绿 = 提交 → 3、4 各同意 → 5 同意 → APPROVED + `APPROVAL_APPROVED` 事件 PROCESSED。

---

*版本 V2.0 · 2026-10-28 · M5 收口后整理（页面级 UI 原型驱动版）。所有字段/枚举/端点均核对自代码；与实现不符处以后端代码与验收测试为准并回写本文档。*
