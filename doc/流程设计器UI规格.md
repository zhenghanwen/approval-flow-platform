# 流程设计器（P04）UI 规格 —— bpmn-js 版

**用途：** 给前端实现"流程设计器"页面用的完整规格：用什么 JS、页面怎么搭、交互约束是什么、最终长什么样。
**对应 SOP 页面：** 《业务运行SOP.md》§3.4（本文件是其详细展开）。
**后端事实源：** `BpmnValidationService`（节点白名单/规一化/会签规则） + `TaskAssignmentService`（审批人六来源）——**所有约束以这两处代码为准**。

---

## 1. 技术选型（用什么 JS）

**框架栈（与 workflow-web 现有栈对齐）：** Vue 3.5 + Vite 6 + Element Plus 2.13 + Pinia。

**画图引擎：`bpmn-js`（社区事实标准，浏览器端 BPMN 建模工具包）。** 配套三个官方扩展：

| 包 | 用途 | 版本建议 |
|----|------|---------|
| `bpmn-js` | 建模器核心（Modeler：画布 + 调色板 + 缩放 + 导入导出） | ^18（当前 18.31） |
| `bpmn-js-properties-panel` | 右侧属性面板容器 + 渲染器 | ^5（当前 5.65） |
| `@bpmn-io/properties-panel` | 属性面板 UI 组件（TextField/SelectEntry 等，写自定义条目用） | ^3（当前 3.58） |
| `camunda-bpmn-moddle` | BPMN 模型扩展（**可选**，仅当要把自定义属性写进 XML extensionElements 时需要） | ^8 |

> bpmn-js 是框架无关的 vanilla JS 库，不需要 Vue 包装器——用原生 `new BpmnModeler()` + 一个 Vue 组件挂载生命周期即可。

**安装（pnpm，注意 workflow-web 用 pnpm workspace）：**
```bash
pnpm add bpmn-js bpmn-js-properties-panel @bpmn-io/properties-panel
pnpm add -D camunda-bpmn-moddle   # 可选
```
**必须引入的 4 个 CSS（不引画布是裸的）：**
```ts
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
import 'bpmn-js-properties-panel/dist/assets/properties-panel.css';
```

---

## 2. 页面整体结构（页面怎么弄）

### 2.1 路由与进入方式

- 路由 `/workflow/workflows/{workflowId}/design`，从 P03 流程列表"设计"进入。
- 进入时 `GET /workflow/workflows/{workflowId}` 取流程定义（名称等）；若已有发布版本，可从 `GET /workflow/workflows/{workflowId}/versions` 取最新版本的 BPMN 回显（**v1 原型可先空画布起步，回显作为增强项**）。
- 顶部面包屑：`流程管理 / {workflowName} / 设计`。

### 2.2 布局（三栏 + 顶栏，线框如下）

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ 顶栏: [流程名称]  [校验] [发布] │ [撤销][重做] [删除选中] │ [适应画布][+][−][1:1]  │
├──────────┬──────────────────────────────────────────────┬──────────────────────┤
│ 调色板    │             画布 (bpmn-js canvas)            │   属性面板            │
│          │                                              │   ┌────────────────┐ │
│ ▸ 事件    │   ○ → [填写申请] → ◇(排他) → [部门审批] → ●    │   │ 常规            │ │
│   ○ 开始  │        └────── 金额<1000 → [直接通过] → ●     │   │ 节点ID (Input)  │ │
│   ● 结束  │                                              │   │ 节点名称 (Input)│ │
│ ▸ 任务    │   [填写申请]=申请节点(绿色徽标, 免配审批人)     │   │ 审批配置        │ │
│   ▢ 审批节点│                                           │   │ 审批人来源(Select)│ │
│ ▸ 网关    │                                              │   │ 值/表单字段(Input)│ │
│   ◇ 排他   │                                              │   │ 会签规则(Select)  │ │
│   ◇ 并行   │                                              │   │ 比例(InputNumber)│ │
└──────────┴──────────────────────────────────────────────┴──────────────────────┘
```

- **左栏（约 72px 宽）**：调色板，仅暴露后端白名单内的 6 类元素（§3.1）。
- **中栏（自适应）**：bpmn-js 画布，浅灰点阵网格背景。
- **右栏（约 300px 宽）**：属性面板，选中节点/连线时展示对应表单（§4）；未选中时显示提示"请选择画布中的节点"。
- **顶栏**：见 §5。

### 2.3 组件拆分建议（Vue 目录）

```
src/views/workflow/design/
├── index.vue              # 页面壳：顶栏 + 三栏布局 + 状态管理
├── BpmnEditor.vue         # 封装 BpmnModeler（init/importXML/saveXML/undo/redo/zoom）
├── palette/customPaletteProvider.js  # 调色板白名单
├── rules/customRules.js              # 连线/创建约束
├── properties/customPropertiesProvider.js  # 属性面板自定义条目
├── modelerConfig.js       # modeler 初始化（additionalModules 注入）
└── assets/*.css           # 画布/属性面板样式覆写
```

---

## 3. 画布与交互约束（画图必须遵守的后端规则）

> 这些约束不是产品经理拍的，是**后端 `BpmnValidationService` / `ApprovalSubmissionService` 强制执行的**，编辑器必须前置拦掉非法操作，而不是等发布时报错。

### 3.1 节点白名单（调色板只放这些）

| 允许元素 | bpmn-js type | 画布形状 |
|---------|-------------|---------|
| 开始事件 | `bpmn:StartEvent` | 细圆圈 ○ |
| 结束事件 | `bpmn:EndEvent` | 粗圆圈 ● |
| 审批节点（用户任务） | `bpmn:UserTask` | 圆角矩形 + 人形图标 ▢ |
| 排他网关 | `bpmn:ExclusiveGateway` | 菱形 + ✕ |
| 并行网关 | `bpmn:ParallelGateway` | 菱形 + ＋ |
| 顺序流（连线） | `bpmn:SequenceFlow` | 箭头线 |

**实现：** 自定义 `PaletteProvider`（`palette.registerProvider`），`getPaletteEntries` 只返回上面 6 类；其余 BPMN 元素（ServiceTask、TimerEvent、SubProcess、BoundaryEvent…）一律不暴露。**删除/替换后画布元素也要被自定义 Rules 拦掉**（§3.4）。

### 3.2 结构约束（发布前校验，后端硬校验）

| 规则 | 说明 | 前端做法 |
|------|------|---------|
| 必须有开始事件 | 至少 1 个 `StartEvent` | 发布前 JS 检查，缺则弹错 |
| 必须有结束事件 | 至少 1 个 `EndEvent` | 同上 |
| 每节点有入边/出边 | 开始事件免入边、结束事件免出边；其余节点入/出边均 ≥1 | Rules 层禁止"悬空节点"（拖到画布但未连线就允许，但发布前校验连线完整性） |
| 节点 ID 格式 | `[A-Za-z0-9_-]{1,100}`，全流程唯一 | 属性面板 ID 输入框做正则校验 + 重名校验 |
| 网关嵌套深度 ≤10 | 沿网关出边递归计数 | 发布前 JS 弱校验（后端硬拦），超限提示"流程嵌套过深" |
| 无孤立环/死路 | 结构合法 | 发布前 JS 检查（可选，后端也拦） |

### 3.3 申请节点特殊规则（最容易踩的坑）

- **定义：** 起始事件直达的第一个 `UserTask` = "申请/填单节点"（如 `apply` / `填写申请`）。
- **后端行为：** 自动注入办理人 `${applicantUserId}`，**不转多实例、不进任何人的待办**，提交后自动完成。
- **编辑器要求：**
  1. 自动识别：`startEvent.getOutgoingFlows()[0].target` 若是 UserTask，则标记为申请节点；
  2. 视觉标记：节点加**绿色徽标「申请」**，并在属性面板显示提示"该节点为申请节点，无需配置审批人，提交后自动完成"；
  3. **禁用该节点的审批人配置**（assignType 等字段隐藏或禁用），防止配置人员误配。
- **约束：** 一个流程只识别一个申请节点（起始事件直达的第一个 UserTask）。

### 3.4 建模 Rules（连线/创建合法性，画布交互级拦截）

用 `rules.registerProvider` 实现 `canConnect` / `canCreate` / `canDelete`：

- `canConnect(source, target)`：仅允许 FlowNode→FlowNode 的 `bpmn:SequenceFlow`；**不允许**把节点连到自己（自环）；**不允许**连回已造成结构死循环的路径（弱校验，可选）。
- `canCreate(type, container)`：只允许白名单 6 类元素创建到 process 容器内。
- `canDelete`：默认允许；删除开始/结束事件时提示（发布前会再校验）。
- 拖拽连线时目标高亮合法落点，非法目标置灰/红色禁止光标。

### 3.5 会签语义（编辑器怎么表达）

- **后端规一化（编辑器不用管细节，但要感知结果）：** 除申请节点外，所有 UserTask 会被后端改写为 **collection 驱动的并行多实例**；实例数 = 该节点解析出的审批人个数。
- **编辑器表达方式：** 用户不需要懂"多实例"，只需要在属性面板给节点配：
  - 审批人来源（§4.2）→ 决定实例集合；
  - 会签规则 `completionRule`：`ALL`（全员，缺省）/ `OR`（任一即过）/ `PERCENT`（比例，配 percent）→ 决定多实例完成条件。
- **节点徽标：** 属性面板选完规则后，画布节点可叠加小徽标：`会签 ALL` / `或签 OR` / `比例 60%`，方便一眼看出节点性质。

### 3.6 连线条件（网关分支）

- 排他网关出边可编辑"条件表达式"（`bpmn:SequenceFlow` 的 `conditionExpression`，属性面板给 Textarea）。
- **⚠️ 已知限制：** v1 启动流程时只注入 `approvalInstanceId / applicantUserId / wf_users_*` 变量，**表单字段（如金额）未注入流程变量**，所以"金额<1000 → 直接通过"这类条件**运行时暂不生效**（M6 待补"表单字段→流程变量映射"）。前端可先做好编辑能力，条件表达式默认透传，运行时求值等 M6。

---

## 4. 属性面板规格（右侧栏）

### 4.1 面板结构（自定义 PropertiesProvider）

用 `propertiesPanel.registerProvider(500, provider)` 注册自定义 provider，`getGroups(element)` 返回分组。**不要用官方 Camunda provider**（它暴露一堆用不上的 Camunda 专有属性），只留我们自己的 2 个分组：

**组 1「常规」（对所有元素）**
| 条目 | 控件 | 绑定 |
|------|------|------|
| 节点 ID | TextField（Input） | `element.id`（bpmn-js 自动生成，可改；正则 `[A-Za-z0-9_-]{1,100}` + 唯一性校验） |
| 节点名称 | TextField | `element.businessObject.name`（画布上显示的标签） |

**组 2「审批配置」（仅 UserTask 且非申请节点）**
| 条目 | 控件 | 值 → configJson 键 |
|------|------|------------------|
| 审批人来源 | SelectEntry | `assignType`（六来源，§4.2） |
| 来源值 | TextField（按来源联动显示） | `assignValue` |
| 表单字段 | TextField（仅 FORM_FIELD 显示） | `formField` |
| 会签规则 | SelectEntry | `completionRule`（ALL/OR/PERCENT） |
| 通过比例 % | NumberEntry（仅 PERCENT 显示，1–100） | `percent` |

**联动逻辑：**
- `assignType=USER` → 显示"来源值"占位 `如 3,4（逗号分隔用户ID）`；
- `=ROLE` → 占位 `角色key`；`=EXTERNAL_API` → 占位 `接口编码（P06 已登记）`；
- `=FORM_FIELD` → 隐藏"来源值"、显示"表单字段"；
- `=DIRECT_MANAGER / DEPT_LEADER` → 两个输入都隐藏，提示"自动取发起人主管/部门负责人"；
- `completionRule=PERCENT` → 显示"通过比例 %"。

**组 3「提示」（仅申请节点）**：静态文本"申请节点：提交后自动完成，不进入待办，无需配置审批人"。

### 4.2 审批人来源（六来源，值由后端 `TaskAssignmentService` 解析）

`USER`（指定用户，assignValue=逗号分隔ID）/ `ROLE`（角色key）/ `DIRECT_MANAGER`（发起人直属主管）/ `DEPT_LEADER`（发起人部门负责人）/ `FORM_FIELD`（表单人员字段，配 formField）/ `EXTERNAL_API`（接口编码，走 P06 白名单）。
**BLOCK 语义：** 解析不到人 → 提交时后端直接失败。属性面板可加弱提示"该节点请确认能解析到审批人，否则提交会被阻止"。

### 4.3 自定义属性的持久化方案（原型推荐）

1. **Vue 响应式状态为唯一真源：** 组件内维护 `nodeConfigs: Record<nodeId, {assignType, assignValue, formField, completionRule, percent}>`；属性面板改动 → 同步写 `nodeConfigs[nodeId]`。
2. **回显一致性：** 节点被删除/重命名时同步清理/迁移 `nodeConfigs`。
3. **（可选增强）写进 XML：** 用 `camunda-bpmn-moddle` 把配置写进 UserTask 的 `flowable:extensionElements`，这样 `saveXML` 导出时配置随图走、刷新不丢。**注意：后端 `parseNodeConfigs` 只读 configJson 的 `nodes` 字段，不读 extensionElements**——所以**发布时仍然要把 nodeConfigs 组回 configJson 一起提交**（§6.2），XML 内嵌只是编辑器自身的持久化手段。

---

## 5. 顶栏功能（工具栏）

| 按钮 | 行为 | 备注 |
|------|------|------|
| 校验 | `POST /workflow/workflows/{workflowId}/validate`，body `{ bpmnXml }`（saveXML 导出）→ 后端门禁全量校验 + 规一化，**返回规一化后的 BPMN**；成功绿提示"校验通过"，失败弹错误列表（定位到违规节点，如"节点 [x] 无出边"） | 后端返回的规一化 XML 可暂存为"待发布 XML" |
| 发布 | `POST /workflow/workflows/{workflowId}/publish`，body `{ bpmnXml: 校验返回的规一化XML, configJson }` → 部署 Flowable + 版本快照 | 发布前必做客户端校验（§3.2）+ 已过 validate；成功后跳回 P03 |
| 撤销 / 重做 | `modeler.get('commandStack').undo()/redo()` | 需命令栈有操作记录 |
| 删除选中 | `modeler.get('modeling').removeElements(selection)` | 快捷键 Del 同步 |
| 适应画布 | `canvas.zoom('fit-viewport')` | |
| 放大 / 缩小 / 1:1 | `canvas.zoom(1.1) / zoom(0.9) / zoom(1, {x,y})` | |
| 导入 XML（增强） | 文件上传 → `importXML` | 运维排障用，普通配置可不开放 |
| 导出 XML（增强） | `saveXML({format:true})` 下载 | 同上 |

---

## 6. 与后端 API 的数据流转

### 6.1 出图（saveXML）
```ts
const { xml } = await modeler.saveXML({ format: true });  // bpmnXml：标准 BPMN 2.0 XML
```

### 6.2 组 configJson
遍历 `nodeConfigs`（跳过申请节点），按节点 ID 组装：
```json
{
  "nodes": {
    "deptApproval":    { "assignType": "DEPT_LEADER",  "completionRule": "ALL" },
    "financeApproval": { "assignType": "USER",         "assignValue": "5",   "completionRule": "OR" },
    "bossApproval":    { "assignType": "FORM_FIELD",   "formField": "boss",  "completionRule": "PERCENT", "percent": 60 }
  }
}
```
> 与后端 `extractNodeAssignments` / `parseNodeConfigs` 约定完全一致：`{ "nodes": { "<nodeId>": { assignType, assignValue, formField, completionRule, percent } } }`。

### 6.3 后端返回的规一化 XML 结构（前端要心里有数）

以 M5 验收样例为准（`apply → deptApproval(MI 3,4) → financeApproval(5) → end`）。前端提交的原始 XML 是这样的（**编辑器导出就是这个形态**，不需要手工写 MI）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://workflow/m5/bpmn">
  <process id="m5ExpenseFlow" name="M5可靠与安全流程">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="apply"/>
    <userTask id="apply" name="填写申请"/>
    <sequenceFlow id="f1" sourceRef="apply" targetRef="deptApproval"/>
    <userTask id="deptApproval" name="部门审批"/>
    <sequenceFlow id="f2" sourceRef="deptApproval" targetRef="financeApproval"/>
    <userTask id="financeApproval" name="财务审批"/>
    <sequenceFlow id="f3" sourceRef="financeApproval" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>
```
后端 `validate` 返回的是**规一化后**的 XML（`deptApproval` / `financeApproval` 已注入 `flowable:collection="wf_users_<nodeId>"` + `assignee=${wf_assignee_<nodeId>}` 多实例改写，`apply` 保持 `${applicantUserId}` 普通任务）。发布提交 `{ bpmnXml: 规一化XML, configJson }`。

> **给原型的提示：** 编辑器产出的原始 XML 与后端规一化 XML 是不同的东西。简单做法——**校验通过后，把后端返回的规一化 XML 直接作为发布请求的 bpmnXml**（后端门禁保证它是合法的）。不要用画布原 XML 覆盖发布。

---

## 7. 具体长什么样（视觉设计）

### 7.1 整体观感
- 页面背景：若依标准灰（`#f0f2f5` 或布局背景色）；画布区域白色 + 浅灰点阵网格（bpmn-js 默认网格）。
- 三栏用 1px 分隔线（`#e5e7eb`）；顶栏白底，下边框同色。
- 画布节点默认 BPMN 标准配色：**白色填充 + 黑色描边**（开始/结束事件圆形，UserTask 圆角矩形带人形图标，网关菱形）。

### 7.2 选中 / 交互状态
- 选中节点：蓝色描边（`#1890ff`）+ 蓝角柄；可拖拽移动、拉伸连线。
- 拖线落点：合法目标绿色高亮，非法红色禁止。
- 悬停：节点轻微阴影 + 光标提示。

### 7.3 特殊节点视觉
- **申请节点**：浅绿填充徽标（右上角小圆角标签「申请」）+ 属性面板固定提示；让配置人员一眼知道"这不用配"。
- **会签规则徽标**：节点下方小标签，`会签 ALL`（蓝）/ `或签 OR`（橙）/ `比例 60%`（紫）。
- **校验失败节点**（客户端校验或后端 validate 返回错误定位）：红色描边 + 角标 ⚠，悬停显示错误文案。

### 7.4 属性面板观感
- Element Plus 风格：分组标题小字号灰字（如 `常规` / `审批配置`），条目为 label + 控件两行式。
- 与 bpmn-js-properties-panel 默认样式协调：覆盖其默认 CSS 变量（`--properties-panel-*`）适配 Element Plus 视觉即可。
- 未选中元素：面板中央灰字提示"请选择画布中的节点 / 连线"。

### 7.5 示例成品图（文字描述）

进入"采购审批流程"设计页：
- 顶栏左"采购审批流程"，右侧一排操作按钮；
- 画布上从左到右：`○ 开始 → ▢ 填写申请（绿色「申请」徽标）→ ◇ 排他网关（菱形✕）→ ▢ 部门审批（徽标「会签 ALL」）→ ▢ 财务审批（徽标「或签 OR」）→ ● 结束`；
- 网关下侧一条分支线连到 `▢ 直接通过（金额<1000）→ ● 结束`；
- 此刻选中"部门审批"节点，右栏显示：常规（节点ID `deptApproval` / 节点名称 `部门审批`）+ 审批配置（审批人来源 `DEPT_LEADER`、会签规则 `ALL`）。

---

## 8. 关键代码骨架（可直接落地）

### 8.1 BpmnEditor.vue（核心：初始化 + 导入导出）

```vue
<template>
  <div class="modeler">
    <div ref="canvasRef" class="canvas"></div>
    <div ref="panelRef" class="properties"></div>
  </div>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import { BpmnPropertiesPanelModule } from 'bpmn-js-properties-panel'
import CustomPaletteProvider from './palette/customPaletteProvider'
import CustomRules from './rules/customRules'
import CustomPropertiesProvider from './properties/customPropertiesProvider'

const canvasRef = ref()
const panelRef = ref()
let modeler = null

onMounted(() => {
  modeler = new BpmnModeler({
    container: canvasRef.value,
    propertiesPanel: { parent: panelRef.value },
    additionalModules: [BpmnPropertiesPanelModule, CustomPaletteProvider, CustomRules, CustomPropertiesProvider]
  })
  // 空画布起步（或回显已有 XML：modeler.importXML(xml)）
  modeler.importXML(EMPTY_DIAGRAM)
})
onBeforeUnmount(() => modeler && modeler.destroy())

async function saveXml() {
  const { xml } = await modeler.saveXML({ format: true })
  return xml
}
async function validate() { /* POST /workflow/workflows/{id}/validate, body { bpmnXml: await saveXml() } */ }
async function publish() { /* POST /workflow/workflows/{id}/publish, body { bpmnXml: 规一化XML, configJson } */ }
defineExpose({ saveXml, validate, publish })
</script>
```

### 8.2 调色板白名单（customPaletteProvider.js）

```js
// palette.registerProvider(this); getPaletteEntries 只暴露 6 类
// 关键: 'create.start-event' / 'create.end-event' / 'create.user-task'
//      / 'create.exclusive-gateway' / 'create.parallel-gateway' / 'create.sequence-flow'
// 用 elementFactory.createShape({ type: 'bpmn:UserTask' }) 等，create.start(event, shape)
```

### 8.3 连线/创建约束（customRules.js）

```js
// rules.registerProvider(this)
// canConnect(source, target) { return 均为 FlowNode ? { type: 'bpmn:SequenceFlow' } : false }
// canCreate(shape) { return WHITELIST.includes(shape.type) }
// canDelete(element) { return true }
```

### 8.4 属性面板（customPropertiesProvider.js）

```js
// propertiesPanel.registerProvider(500, this)
// getGroups(element) => groups => {
//   push({ id:'general', label:'常规', entries:[idField, nameField] })
//   if (element.type === 'bpmn:UserTask' && !isApplicantNode(element)) {
//     push({ id:'approval', label:'审批配置', entries:[assignType, assignValue, formField, completionRule, percent] })
//   }
//   return groups
// }
// 条目用 @bpmn-io/properties-panel 的 TextField/SelectEntry/NumberEntry 组件，
// 读写 element.businessObject + 同步 nodeConfigs 响应式状态
```

---

## 9. 验收口径（编辑器做没做对的检查单）

| # | 检查项 | 对应约束 |
|---|-------|---------|
| 1 | 调色板只有 6 类元素，无 ServiceTask/TimerEvent 等 | §3.1 |
| 2 | 画布上放不出非法节点（Rules 拦截） | §3.4 |
| 3 | 申请节点自动识别 + 绿色徽标 + 禁配审批人 | §3.3 |
| 4 | 属性面板能配齐 assignType 六来源 + completionRule（含 PERCENT 的 percent 联动） | §4.1/§4.2 |
| 5 | 保存/发布能导出标准 BPMN XML + 组出合法 configJson（nodes 结构） | §6 |
| 6 | 发布前客户端校验（起止事件/入出边/ID 格式/深度） | §3.2 |
| 7 | 校验按钮走后端 validate，失败定位错误节点 | §5 |
| 8 | 发布走后端 publish，成功后回 P03 列表 | §5 |
| 9 | 保存的图刷新后回显（XML 或 nodeConfigs 持久化） | §4.3 |

---

*版本 V1.0 · 2026-10-28 · 与《业务运行SOP.md》§3.4 配套。所有约束核对自 `BpmnValidationService` / `TaskAssignmentService` / 控制器代码；与实现不符以后端代码为准并回写本文档。*
