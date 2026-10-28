# Flowable 高级审批能力可行性分析

**目标版本：** Flowable 7.x（下文结论以 7.x 为准，与 6.x 差异处单独标注）
**分析对象：** 《低代码审批流平台PRD.md》第四阶段"高级审批"中的六类操作能力
**分析方式：** Flowable 官方仓库源码审查 + 官方文档核对，并对照《Flowable高级审批能力验证.md》的既有结论做修正与补充
**结论性质：** 源码级确认 + **M0 Spike 实测已复核**（2026-10-28：S0–S5 集成测试 22/22 全绿，见文末"M0 实测修订"一节；B0–B5 边界已逐一用引擎实际行为验证）

---

# 一、结论摘要

| # | 能力 | 结论 | 核心依据 |
|---|------|------|----------|
| 1 | 转交审批 | ✅ 可行（原生 API） | `TaskService.setAssignee / delegateTask / resolveTask` |
| 2 | 前加签 / 后加签 | ⚠️ 无原生 addSign API；**可做，需业务层 + BPMN 建模约定** | 多实例节点 + `RuntimeService.addMultiInstanceExecution` |
| 3 | 退回历史节点 | ✅ 可行（串行、多实例进出、（嵌套）子流程、网关收敛移动均获官方测试背书） | `RuntimeService.createChangeActivityStateBuilder().moveActivityIdTo(...)` + `ChangeState*Test` 共 100+ 官方用例 |
| 4 | 退回会签节点 / 并行分支跨分支退回 / 目标=网关节点 / CallActivity 进出 | ⚠️ change-state 对多实例/网关区域/CallActivity 进出均有官方测试覆盖；仅"流程版本升级迁移"（`ProcessInstanceMigrationService`）对多实例不支持 | 专项测试一手核对：`ChangeStateForMultiInstanceTest`(20例)、`ChangeStateForGatewaysTest`(30例)、`ChangeStateForCallActivityTest`(6例) |
| 5 | 撤回 / 取消审批 | ✅ 可行（原生 API） | `RuntimeService.deleteProcessInstance(piId, deleteReason)` |
| 6 | 会签（全部/任一/比例） | ✅ 可行（原生） | 多实例 + `completionCondition` |
| 7 | 并行网关 | ✅ 流程建模可行 / ⚠️ 中途状态变更受限 | 标准 BPMN；仅部分结构被官方测试覆盖 |
| 8 | 流程历史与待办一致性 | ✅ 机制存在；业务投影需自建同步与校验 | 历史级别 `audit`/`full` + `HistoricTask/ActivityInstance` 查询 |

**一句话总结：** 六类能力全部可实现。转交、撤回/取消、会签、退回（串行+多实例进出）、并行建模有引擎原生能力与官方测试背书；前加签/后加签没有现成 API，但走"统一多实例建模 + `addMultiInstanceExecution`"即有官方测试背书。真正要**用配置挡住**的只有三类：目标节点直接设为网关本身、并行分支单执行跨分支退回、低配置场景下对 `ProcessInstanceMigrationService`（版本升级迁移，多实例/CallActivity 仍 upcoming）的误用——这些用退回目标白名单统一兜住即可。

---

# 二、验证依据（本次实际核对的源码与文档）

| 来源 | 文件 / 位置 | 用途 |
|------|------------|------|
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/dynamic/AbstractDynamicStateManager.java` | 退回（change state）主循环：删当前执行、建目标执行、调度继续 |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/persistence/entity/ExecutionEntityManagerImpl.java` | `deleteExecutionAndRelatedData(..., deleteHistory=false, ...)` → `recordActivityEnd`：退回时**记录活动结束而非删除历史** |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/util/TaskHelper.java` → `changeTaskAssignee` | 转交：更新任务+历史、触发 `TASK_ASSIGNED` 事件、加 identity link；**不移除候选组链接** |
| 源码 | `modules/flowable-task-service/src/main/java/org/flowable/task/service/impl/HistoricTaskServiceImpl.java` | 转交会**覆盖** HistoricTaskInstance 的最终 assignee，并追加 ASSIGNEE 类型历史 identity link |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/ClaimTaskCmd.java` | claim/setAssignee/unclaim 的语义差异 |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/AddMultiInstanceExecutionCmd.java` | 运行中多实例活动动态加实例（加签的引擎入口） |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/bpmn/behavior/ParallelMultiInstanceBehavior.java`、`MultiInstanceActivityBehavior.java` | 会签完成条件触发后 `cleanupMiRoot` 级联取消剩余实例 |
| 源码 | `modules/flowable-engine/src/main/java/org/flowable/engine/impl/bpmn/behavior/SequentialMultiInstanceBehavior.java` | 串行多实例推进机制 |
| 测试 | `modules/flowable-engine/src/test/java/org/flowable/engine/test/api/runtime/changestate/ChangeStateTest.java` | **57 例（一手核对）：**串行正/反向跳转、进出与嵌套子流程、定时器节点、信号/消息捕获事件、事件子流程 |
| 测试 | `.../changestate/ChangeStateForGatewaysTest.java` | **30 例：**并行/包含网关多执行→单执行、单执行→多执行、退回汇聚网关前方分支（first/last）、并行子流程 |
| 测试 | `.../changestate/ChangeStateForMultiInstanceTest.java` | **20 例：**进/出串行与并行多实例节点、多实例父执行移动、跨多实例任务移动、多实例并行任务收敛到单活动、网关嵌套在多实例子流程内 |
| 测试 | `.../changestate/ChangeStateForCallActivityTest.java` | **6 例：**CallActivity 主流程/子流程实例内移动、按 calledElement 表达式、指定子流程定义版本 |
| 测试 | `.../bpmn/multiinstance/DynamicMultiInstanceTest.java` | 运行时 `addMultiInstanceExecution` + completionCondition：加人后按条件才结束 |
| 文档 | `docs/docusaurus/docs/bpmn/ch08-ProcessInstanceMigration.md` | 状态变更/迁移的**支持边界**（upcoming 列表即"当前不支持"清单） |
| 文档 | `docs/docusaurus/docs/bpmn/ch10-History.md` | 历史级别（audit/full）与历史查询能力 |
| 文档 | `docs/docusaurus/docs/bpmn/ch14-REST.md` | REST 入口：`DELETE runtime/process-instances/{id}?deleteReason=`、`PUT runtime/tasks/{id}`、历史查询 |

**源码获取方式说明：** 本环境 `raw.githubusercontent.com` 在 TLS 层被拦截、`github.com` 不通；`ChangeActivityStateBuilder.java` 及以上 `ChangeState*Test` 均通过 **api.github.com Contents API（main 分支）拉取一手核对**，非仅凭文档转述。

**`ChangeActivityStateBuilder` 完整方法面（一手核对，main 分支）：** `processInstanceId`、`moveExecutionToActivityId`、`moveExecutionsToSingleActivityId`、`moveSingleExecutionToActivityIds`、`moveActivityIdTo`、`moveActivityIdsToSingleActivityId`、`moveSingleActivityIdToActivityIds`、`moveActivityIdToParentActivityId`（终止子流程实例并把全部执行移回父流程目标活动）、`moveActivityIdToSubProcessInstanceActivityId`（×2 重载，支持指定子流程定义版本）、`enableEventSubProcessStartEvent`、`processVariable(s)`、`localVariable(s)`、`changeState()`。（注意：**不存在** `withProcessInstanceId` / `moveActivityIdToForMultipleExecutions` 这两个方法，网络文章常见误写。）

说明：Flowable 7.x 与 6.x 相比，上述核心 API（`TaskService`、`RuntimeService.createChangeActivityStateBuilder`、多实例行为、历史体系）签名与语义保持稳定；7.x 主要变化在 Spring Boot 3 / JDK 17 基座与 CMMN 迁移增强，不影响本文结论。

---

# 三、逐项分析与实现路径

## 3.1 转交审批 —— ✅ 可行，入口为原生 API

**PRD 对应：** 11.1、接口 `POST /tasks/{taskId}/transfer`、表 `wf_task_transfer`

**引擎侧行为（源码确认）：**

- 永久转交：`taskService.setAssignee(taskId, newAssignee)`。内部 `TaskHelper.changeTaskAssignee` 会：
  1. 更新任务实体并**更新历史任务实例的 assignee**（`HistoricTaskServiceImpl` 确认）；
  2. 触发 assignment 类任务监听器与 `TASK_ASSIGNED` 事件；
  3. 为新办理人追加 ASSIGNEE 类型 identity link。
- 委托模式（语义不同，勿混用）：`taskService.delegateTask(taskId, userId)` 将任务置为 PENDING 委托态，受托人处理时用 `resolveTask(taskId)` 把任务交回原 owner；委托结果仍由 owner 最终 complete。
- REST 同样可用：`PUT runtime/tasks/{taskId}`（仅传 `assignee` 即为转交）。

**两个必须由业务层兜底的点（引擎不帮你做）：**

1. **候选链接不会自动清除。** `changeTaskAssignee` 只设置 assignee，不删除已有的 candidate user/group 链接。若节点是"角色/组"型任务（组内多人可见），转交给某人后，**组内其他人仍能看到并可认领该任务**。业务层需同时执行 `taskService.deleteCandidateUser/deleteCandidateGroup`（或设计上让组任务先 claim 再转交）。
2. **Flowable 历史只保留"最终 assignee"。** 原办理人 → 新办理人的完整转交链路，引擎历史无法完整表达，必须落 `wf_task_transfer` 表（PRD 已设计，此表是必选项而非可选）。

**结论：** 一次 `setAssignee` + 投影同步 + 业务审计记录即可闭环。属于六类能力中最简单、风险最低的一项。

---

## 3.2 前加签 / 后加签 —— ⚠️ 无原生 API；推荐一条有官方测试背书的实现路径

**PRD 对应：** 11.2、接口 `POST /tasks/{taskId}/add-sign`、表 `wf_task_add_sign`

### 3.2.1 引擎现状

Flowable **没有** `addSign()` 这类通用加签 API（与《验证文档》结论一致）。但引擎提供了两个可组合的构件：

- **多实例（multi-instance）用户任务**：一个节点按人员集合生成多个任务实例，支持并行/串行、`completionCondition` 完成条件。
- **`RuntimeService.addMultiInstanceExecution(activityId, processInstanceId, executionVariables)`**：给**运行中**的多实例活动动态追加一个实例（`AddMultiInstanceExecutionCmd`：定位多实例根执行 → `nrOfInstances` +1 → 拷贝实例级变量 → 并行模式调度继续执行）。

### 3.2.2 推荐路径：审批节点统一建模为多实例节点（并行）

流程生成器（BPMN 生成阶段）将**每个审批节点**统一生成为并行多实例用户任务，即使默认只有一个审批人：

```xml
<userTask id="dept_manager_approval" name="部门负责人审批" flowable:assignee="${assignee}">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="${node_assignee_list}" flowable:elementVariable="assignee">
    <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition> <!-- ALL -->
  </multiInstanceLoopCharacteristics>
</userTask>
```

在此模型下：

| 场景 | 操作 | 引擎行为 |
|------|------|----------|
| **前加签** | 原审批人任务未完成时，`addMultiInstanceExecution` 为该节点追加加签人实例 | 并行多实例，ALL 规则下**全部完成节点才离开** → 原审批人无法在加签人完成前推进流程 ✅ |
| **后加签** | 原审批人先完成自己的实例（节点因 ALL 未满足而**不离开**），再 `addMultiInstanceExecution` 追加加签人 | 加签人完成后节点才继续 ✅ |
| **或签（ANY）** | `completionCondition` 改为 `${nrOfCompletedInstances >= 1}` | 任一人完成后 `cleanupMiRoot` 级联取消其余活动实例（历史保留）✅ |
| **比例会签** | `${nrOfCompletedInstances / nrOfInstances >= 0.6}` 等表达式 | 原生支持 ✅ |

`DynamicMultiInstanceTest` 直接覆盖了"运行中向并行多实例节点加人 + completionCondition 才结束"这一组合，是官方背书的行为。

### 3.2.3 此路径的边界与第一版取舍

1. **前提是节点本身就是多实例节点。** `addMultiInstanceExecution` 无法把一个"普通单人 userTask"运行时改造成多实例——这正是推荐"所有审批节点统一多实例建模"的原因。
2. **或签节点不能加签。** ANY 规则下任一人完成即触发收尾，加签窗口不存在。流程配置需标记"允许加签的节点完成规则必须为 ALL（或比例 > 当前已完成数）"。
3. **串行多实例不建议动态加人。** `AddMultiInstanceExecutionCmd` 对 sequential 只创建子执行、不调度 continue（源码注释即 "for sequential it relies on the normal take mechanism"），动态加实例的行为与官方测试覆盖均弱于并行版。**第一版：加签一律基于并行多实例节点，不提供串行加签。**
4. **加签任务的驳回/超时/终止策略引擎不管**，由业务层定义（退回原审批人/退回申请人/终止流程），落到 `wf_task_add_sign` + `wf_approval_action`。
5. 任务投影：每个多实例实例是一个独立的 Flowable 任务，`wf_approval_task` 按 `flowable_task_id` 唯一逐条投影，天然兼容。

**结论：** 可行，且是"业务层 + 建模约定"路径，不是调用一个不存在的 API。第一版范围建议：**单人→单人（前/后）、多人并行加签（ALL 规则）**；或签与串行加签暂不放开。

---

## 3.3 退回历史节点 —— ✅ 可行（官方测试覆盖广；仅两类边界需白名单挡住）

**PRD 对应：** 11.3（自称"技术风险最高的能力之一"，判断正确）、接口 `POST /tasks/{taskId}/return`、表 `wf_task_return`

**引擎入口：**

```java
runtimeService.createChangeActivityStateBuilder()
    .processInstanceId(processInstanceId)
    // 串行：当前节点 → 任意历史/前方审批节点
    .moveActivityIdTo("finance_approval", "dept_manager_approval")
    // 多执行（并行分支收敛后再整体退）：多个 executionId → 单个目标节点
    .moveExecutionsToSingleActivityId(executionIds, "dept_manager_approval")
    // 多个待办活动 → 单个目标节点（同上，按 activityId 维度）
    .moveActivityIdsToSingleActivityId(List.of("finance_approval"), "dept_manager_approval")
    // CallActivity：进出子流程实例（支持指定版本）
    .moveActivityIdToParentActivityId("sub_task", "parent_approval")          // 终止子流程实例，全部执行移回父流程
    .moveActivityIdToSubProcessInstanceActivityId("parent_task", "sub_task", "callActivityId") // 进入子流程
    .changeState();
```

**退回后的引擎行为（`AbstractDynamicStateManager` / `ExecutionEntityManagerImpl` 源码确认）：**

| 检查项 | 实际行为 |
|--------|----------|
| 当前执行 | 被删除，但 `deleteHistory=false` → 通过 `recordActivityEnd` **记录活动结束（含 delete reason，如 "Change activity to ..."），历史不丢** |
| 历史 | 旧 HistoricTaskInstance/HistoricActivityInstance 全部保留，可追溯"取消 + 重新进入"；或签/会签的 `cleanupMiRoot` 同理 |
| 新任务 | 为目标 UserTask **新建执行 → 创建新任务（新 ID）**；旧任务不会"重开"——与《验证文档》第 90 行判断一致 |
| 流程实例 | 保持运行态 |
| 再次推进 | 到达目标节点时**绕过了目标节点 incoming 条件评估**（直接启动活动），但目标任务完成后的**出线条件会正常重评估** |
| 事件 | 被取消的活动会派发 activity cancelled 事件（可用于同步待办投影） |

`ChangeStateTest` 覆盖串行反向跳转，`ChangeStateForGatewaysTest` 覆盖部分网关场景——PRD 采购示例（财务审批退回部门负责人审批）属于典型串行链路，✅ 可实现。

### 3.3.1 边界与限制（一手源码/测试核对后的准确结论）

| # | 边界 | 实际状态（前身说法已修正） | 处置 |
|---|------|------|------|
| B0 | **退回进/出多实例（会签）节点** | ✅ **change-state 支持**：`ChangeStateForMultiInstanceTest` 20 例官方测试覆盖进/出串行与并行多实例、跨多实例任务移动、多实例并行任务收敛单活动。早前依据的 "move a wait state to a multi instance activity 未支持" 出自**版本升级迁移**文档（`ProcessInstanceMigrationService`），不适用于 change-state —— 两者是不同 API。**M0 实测（7.1.0）**：并行 MI 部分完成态整区收回的合法姿势 = **节点级 `moveActivityIdTo`**（`S5PurchaseE2ETest` T22，重走到底干净）；把 **MI 根执行**塞进 `moveExecutionsToSingleActivityId` 会撞 `act_ru_task` 外键（删序缺陷）——MI 根执行禁入收编集合 | 会签节点可进退回白名单，但目标进入多实例时**按集合变量整组重建实例**，语义在 §3.3.2 明确 |
| B1 | **并行分支中单个执行跨分支退回** | ⚠️ 有风险。官方测试只覆盖"多执行→单执行收敛"与"单执行→多执行分叉"的结构性移动（`ChangeStateForGatewaysTest` 30 例），单执行退回会让汇聚网关等待已取消分支。**M0 实测（7.1.0）**：跨支退回引擎不报错——b2 执行被吞、b1 幽灵复制×2、补完成后 join **永久挂起**（双支活跃与单执行两种形态均复现，`S3BoundaryTest` 哨兵） | 并行网关内节点默认禁止作为退回目标；若必须退，用 `moveExecutionsToSingleActivityId` 收敛**全部子执行（含 join 挂起执行）**再整体退，且必须过 T10/T14b 测试 |
| B2 | **直接移动到网关节点本身（排他/并行/包含）** | ⚠️ 网关不是等待状态，目标应为网关**前后的活动**；官方测试的 "IntoSynchronizingParallelGatewayFirst/Last" 指退回到汇聚网关前方的分支任务，不是把网关元素设为目标。**M0 实测（7.1.0）**：目标=排他网关时引擎**不抛异常也不静默不动**，而是按出线条件重走一圈——任务重建成新 ID、原地"重开"丢失当前办理进度（`S3BoundaryTest` 哨兵） | 目标白名单仅允许 UserTask（含多实例 UserTask），禁止网关/边界事件等元素；**拦截必须前置服务层，不能依赖引擎报错** |
| B3 | **CallActivity 进/出子流程实例** | ✅ **支持**：builder 提供 `moveActivityIdToParentActivityId`（终止子流程实例、全部执行移回父流程）与 `moveActivityIdToSubProcessInstanceActivityId`（含指定定义版本重载），`ChangeStateForCallActivityTest` 6 例覆盖 | 第一版低代码流程通常不生成 CallActivity，不需要启用；如启用，退回语义按"整体进出"定义 |
| B4 | **流程版本升级迁移中的未支持项** | ⚠️ `ProcessInstanceMigrationService`（迁移到新版本流程定义）对 moving multi instance executions、进入多实例、带 CallActivity 的子流程内部移动仍在 **upcoming** 清单 —— 本项目因"已发布版本不可变 + 历史单按旧版本跑完"的设计（PRD §5.6）基本用不到该 API | 除非将来要"运行中实例升级到新版本"，否则不受影响；若启用必须先验证 B4 清单 |
| B5 | **目标节点进入条件不重评估** | ✅ 确认：move 直接启动目标活动，不判断 incoming sequence flow 条件；但目标节点完成后的**出线条件正常重评估** | 无需处置，语义上正好符合"退回后重新审批再按条件推进" |

### 3.3.2 多实例（会签）节点退回的两种实现

change-state **可以**直接进出多实例节点（B0，官方测试覆盖），因此"驳回退回会签节点"有两种做法：

**方式一（引擎直退，最简）：**

```java
builder.moveActivityIdTo("finance_approval", "dept_manager_agree"); // 目标是多实例节点
```

- 行为：取消当前执行 → 进入目标多实例节点 → **按当前集合变量整组重建实例**（旧实例历史带结束原因保留，`ChangeStateForMultiInstanceTest` 的进多实例用例即此行为）。
- 适用：目标节点人员集合不需要变化（退回重议）。

**方式二（退前置 + 变量重置重建，人员可更新）：**

1. 先 change-state 退到会签节点的**前置串行节点**；
2. 业务层按最新审批人配置**重写**集合变量 `node_assignee_list`；
3. 再 normal 推进进入该会签节点 → 多实例按新集合生成实例。

- 适用：退回时希望按新审批人规则重建（如人员已离职、或签改会签）。
- 同样全部由受支持 API 组合，不触碰 ACT_* 表。

两者都受支持；第一版建议把方式二作为统一实现（与"审批人解析服务"天然衔接），方式一作为同数据场景的快捷路径。

### 3.3.3 必要的服务端校验清单（接口层强校验，B0–B5 之外的兜底）

1. 当前流程实例运行中、当前节点待办存在且属于操作人；
2. 目标节点存在于**同一 process_definition 版本**（流程定义 ID 一致）；
3. 目标节点在节点配置的「允许退回目标」白名单内（默认禁止：开始/结束节点、**网关元素本身**、并行分支内节点；多实例审批节点**允许**进入白名单，按 §3.3.2 语义）；
4. 当前节点无未完成的加签任务（有则先按加签驳回策略处理）；
5. 退回原因必填，落 `wf_task_return`，operation 幂等键防重。

**结论：** 退回是六类能力中**功能性最完整**的一项：串行链路互退、退回申请人、进出会签节点、收敛式并行回退、CallActivity 进出均有受支持 API 与官方测试背书；仅"单执行跨分支退回""目标=网关元素本身"需白名单挡掉（B1/B2）。

---

## 3.4 撤回 / 取消审批 —— ✅ 可行（原生 API）

**PRD 对应：** 11.4、11.5、接口 `POST /approvals/{id}/withdraw` 与 `/cancel`

```java
runtimeService.deleteProcessInstance(processInstanceId, "WITHDRAW:申请人主动撤回");
// REST: DELETE runtime/process-instances/{id}?deleteReason=WITHDRAW...
```

引擎行为：

- 终止实例、级联取消**所有**活动执行与待办任务（运行时数据 ACT_RU_* 清除）；
- 历史不删除：HistoricProcessInstance 记录 `endTime` + `deleteReason`，HistoricTaskInstance 的未完成任务也带 deleteReason 结束；
- 撤回/取消的语义差异（申请人 vs 有权管理员）完全是业务权限层的事，引擎侧无差别。

业务层职责（PRD 已设计，缺一不可）：

1. 校验操作人 = 申请人（撤回，需 `allow_withdraw`）/ 有权用户（取消）；
2. 流程确未结束（引擎查询）且业务状态合法；
3. 同步清理 `wf_approval_task` 投影（状态改为已取消）；
4. `wf_approval_action` 追加 `WITHDRAW`/`CANCEL` 记录（操作只追加不覆盖）；
5. Outbox 事件通知原待办人。

注意：`deleteProcessInstance` 后流程实例不可复用，**"撤回后修改重提"必须走"撤回 + 新审批单 + 新流程实例"**（PRD 11.4 已明确，正确）。

---

## 3.5 会签 —— ✅ 可行（原生），注意与会签相关的两个"退回/加签"交叉点

多实例 + `completionCondition` 已覆盖全部 PRD 诉求（全部/任一/比例，见 §3.2.2 表格）。引擎保证：

- 部分完成**不会**提前推进（`DynamicMultiInstanceTest` 断言：完成 3/4 仍有 3 个待办）；
- 条件满足后，**剩余未完成实例被级联取消**（`ParallelMultiInstanceBehavior.leave → cleanupMiRoot`），历史保留 —— 满足"或签一人完成后其他人不再办理"。

交叉点提醒：

1. 会签节点**可以**作为 change-state 退回目标（§3.3 B0，官方测试覆盖）→ 推荐"退前置节点 + 重置集合变量再进入"以获得人员重建能力（§3.3.2 方式二）；
2. 会签节点的动态加签 = `addMultiInstanceExecution`（§3.2），与"加签是审批节点能力的特例"自然统一：**会签与加签在引擎侧是同一个机制**，实现时做成同一套代码（多实例节点服务），降低成本；
3. 会签人员集合来源多样（角色/主管/表单字段/外部接口）→ 统一在 `TaskAssignmentService` 解析后写入集合变量，BPMN 里只声明 collection 引用。

---

## 3.6 并行网关 —— ✅ 建模可行；⚠️ 中途状态变更仅支持部分结构

- 并行/包含网关的分叉汇合是 BPMN 标准能力，Flowable 原生执行，无需担心。
- 风险全在"运行中变更"：官方测试覆盖的是"多执行→单执行收敛""单执行→多执行分叉""退回汇聚网关前方分支"这类**结构性移动**（`ChangeStateForGatewaysTest` 30 例）；单个执行跨分支的中途退回没有官方保证（§3.3 B1）。
- PRD 的两道防线（并行任务不允许随意跨分支退回、并行流程单独校验）均正确，落地即：并行网关内部节点**不进退回白名单**；确有"并行整体回退"需求时用 `moveExecutionsToSingleActivityId` 收敛后整体退，且**必须**作为独立集成测试项验证分支汇合、剩余任务、历史一致性（对应《验证文档》T10）。

---

## 3.7 历史与待办一致性 —— ✅ 机制存在；投影同步是业务层必须履行的义务

- 引擎侧：`historyLevel=audit`（默认）记录流程/活动/任务历史；`full` 追加变量级细节。7.x 的 HistoricTaskInstance 查询支持 `deleteReason`、`finished`、时间区间等条件，足够支撑"每次退回/加签/转交都可追溯"的审计要求。
- 引擎**不会**自动维护 `wf_approval_task` 投影。投影是单向 подчин（Flowable 为事实源），同步点覆盖全部五类操作：

| 操作 | 投影动作 |
|------|----------|
| 退回 | 旧任务投影置"已取消"，新建任务投影逐条插入（监听 activity created/cancelled 事件兜底） |
| 转交 | 更新 assignee + `wf_task_transfer` 审计 |
| 加签 | 新增加签实例任务投影 + `wf_task_add_sign` 关联 |
| 撤回/取消 | 全部活动任务投影置"已取消" |
| 完成 | 投影置"已办"，写 `completed_at` |

- 保障手段：事件监听（Flowable 的 task/activity 事件）+ 后台一致性扫描（PRD 16.4：定期比对 ACT_RU_TASK 与 `wf_approval_task`）双保险。历史级别至少 `audit`，审计要求高则 `full`。

---

# M0 实测修订（Spike 集成测试 S0–S5，2026-10-28；源码位置 `workflow-server/workflow-spike`，22/22 全绿）

对正文 B0–B6 各节的**运行时实证复核**与新增铁律，BPMN 建模约定、API 包名移位如下。所有结论
标注了对应的哨兵/集成测试，Flowable 升级后先重跑测试再沿用。

## 1. BPMN 建模铁律（写法层面，直接影响部署成功率）

| # | 铁律 | 实测依据 |
|---|------|----------|
| W1 | MI collection 必须用**标准 BPMN 形态** `<loopDataInputRef>` + `<inputDataItem name>`；Flowable 私货 `flowable:collection` **会被 XSD 校验拒部署** | S2 实测（spike-addsign-process 部署被拒） |
| W2 | MI 实例数必须**节点专属独立变量** cardinality：`<loopCardinality>${wf_n_<activityId>}</loopCardinality>`。同构 cardinality（两节点同字符串）互退时引擎产出**空壳 MI 根**卡死（flowable issue #3944，7.1.0 未修） | S1 哨兵 spike-return-sameconfig；S5 Normalizer N2 规则 |
| W3 | MI 完成条件 ALL 规则：`<completionCondition>${nrOfCompletedInstances == nrOfInstances}</completionCondition>`；v1 仅**并行 ALL** 规则成立（前/后加签同一 API 温床） | S2 T03/T04/T13 |
| W4 | MI 成员赋值用 `flowable:assignee="${<inputDataItem.name>}"` 逐实例落人，与 collection 变量名呼应 | S2 实测 |
| W5 | 审批节点统一多实例建模（D1）：`BpmnNormalizer` 把无 MI 的 UserTask 注入并行 MI + 节点专属 cardinality（申请节点实例数=1 退化），保证全部审批/待办节点同构、退回加签同一 API | S5 T20a/T20b（快照+可部署可执行双锁） |

## 2. 退回口径速查（谁退谁、怎么退）

| 场景 | 合法姿势 | 实测 |
|------|----------|------|
| 串行链路互退（含退回申请人） | `moveActivityIdTo(cur, target)` | S1 T05/T06 |
| 会签（并行 MI）部分完成态整区收回 | **节点级** `moveActivityIdTo(miActivityId, 上游)` | S5 T22 |
| 普通并行网关区块整体收回 | `moveExecutionsToSingleActivityId(全部子执行含 join 挂起执行, 上游)` | S3 合法正例 |
| MI 根执行收进 moveExecutions 集合 | ❌ **禁止**（`act_ru_task` FK 删序缺陷，引擎抛 SQL 而非业务错） | S5 实测 |
| 退回目标=网关 | ❌ 服务层前置拦截（引擎不报错、条件重走重开任务） | S3 T15 |
| 跨支退回 | ❌ 禁止（幽灵复制 + join 永久挂起） | S3 T14b |

## 3. 撤回与投影（T07/T12 实测锚点）

- 撤回五项核对全绿：运行实例/任务/执行清零、`HistoricProcessInstance` 带 endTime+deleteReason、
  历史任务全部留痕、旧任务句柄续操作抛 `FlowableObjectNotFoundException`（S4 T07）。
- 投影雏形可行：引擎级事件监听（`TASK_CREATED/TASK_COMPLETED/ENTITY_DELETED` + `PROCESS_COMPLETED/PROCESS_CANCELLED`）
  → 内存投影与引擎全程对账一致；**ENTITY_DELETED 对已完成任务不折叠 REMOVED**（保历史语义）。
  实现已抽为 `InMemoryTaskProjection`（S4/S5 共用），M3 平移 MySQL `wf_approval_task` 时沿用同一事件语义（S4 T12、S5 T21–T23）。

## 4. Flowable 7.1.0 API 包名移位备忘（6.x 写法编译不过）

| 类型 | 6.x 位置 | 7.1.0 位置 |
|------|----------|-----------|
| `HistoryLevel` | `org.flowable.engine.history` | `org.flowable.common.engine.impl.history` |
| 事件体系（`FlowableEvent/FlowableEngineEntityEvent/FlowableEngineEventType/FlowableEventListener`） | `org.flowable.engine.delegate.event` | `org.flowable.common.engine.api.delegate.event` |
| `FlowableEventListener` 契约 | onEvent + isFailOnException 两个方法 | **新增抽象方法** `isFireOnTransactionLifecycleEvent()` / `getOnTransaction()`（无横切需求时返回 false/null） |
| `BpmnXMLConverter` | `org.flowable.bpmn` | `org.flowable.bpmn.converter` |
| `HistoricTaskInstance` | — | `org.flowable.task.api.history`（不在 engine 包） |

## 5. 测试基线

`mvn -pl workflow-spike test`：22/22 全绿（S0 冒烟 3 + S1 退回 4 + S2 加签 3 + S3 边界 4 + S4 撤回/投影 3 +
S5 规一化 2 + 采购贯穿 3）。数据库 `workflow_spike`（本地 MySQL80），凭证走 `WF_DB_PASSWORD` 环境变量。

---

# 四、需要挡住/注意的事项清单（汇总）

以下两类**不要在任何实现里硬调 API 尝试**，统一在流程配置/接口校验层禁止：

1. ⛔ 并行分支中**单执行跨分支退回**（孤立执行/错误汇合风险；如需回退：全分支收敛后整体退，且必须过 T10/T14b 测试）；
2. ⛔ change-state **目标直接设为网关元素本身**（网关非等待状态；目标白名单仅 UserTask，含多实例 UserTask）。

另有两点**语义边界**要写进设计文档，避免误用：

3. ⚠️ change-state 可进入/退出多实例节点（官方测试覆盖），但目标实例如会签节点会**按集合整组重建实例**，RFC 语义上等价"退回重议该节点"，不是"保留部分已办、重办一部分"（后者无引擎支持）；
4. ⚠️ `ProcessInstanceMigrationService`（版本升级迁移）对多实例/CallActivity 内部移动仍未支持（upcoming）——本项目设计使运行中实例固定在旧版本流程，正常用不到；若将来启用"运行中升级"必须先验证。

附：两个**依赖业务层补齐**的点（引擎不负责）：转交不清除候选链接（§3.1.2-1）；转交后的完整人员审计历史（§3.1.2-2）。

---

# 五、对 PRD 第一版落地建议

| 能力 | 第一版范围 | 引擎入口 |
|------|-----------|----------|
| 转交 | 全量支持 | `setAssignee`（+候选清理、转交审计） |
| 加签 | 节点统一多实例建模；单人/多人并行加签（ALL 规则）；或签、串行加签暂缓 | `addMultiInstanceExecution` |
| 退回 | 串行链路白名单互退 + 退回申请人 + 会签节点（退前置/整组重议）；并行分支内节点拒绝 | `moveActivityIdTo` |
| 会签 | ALL/ANY/比例；人员集合由解析服务产出 | multi-instance + completionCondition |
| 会签节点的退回 | 首选"退前置节点 + 重置集合变量再进入"（人员可重建）；快捷路径为引擎直退（整组重议） | change-state + 变量重置 |
| 撤回/取消 | 全量支持 | `deleteProcessInstance` |
| 并行整体回退 | 暂缓或仅收敛后整体退（强烈建议配套 T10 测试） | `moveExecutionsToSingleActivityId` |

BPMN 生成器对称约束（发布时校验，形成硬约束而非口头约定）：审批节点一律生成多实例 userTask + collection 变量 + completionCondition；进入节点的辅助节点（若有）禁止出现在流程设计器可选节点类型中；退回白名单随版本快照落库。

---

# 六、与《Flowable高级审批能力验证.md》结论的对照

| 原文档结论 | 本文核对结果 |
|-----------|--------------|
| 转交：引擎具备基础能力，区分永久转交与委托 | ✅ 一致；补充"候选链接不清除""历史只剩最终 assignee"两点 |
| 加签：无通用 addSign API，需业务层实现 | ✅ 一致；进一步给出具体路径（统一多实例 + addMultiInstanceExecution，官方测试背书）与边界（或签/串行不可，普通单人节点不可改造） |
| 退回：基础能力已有测试，不能断言任意结构安全 | ✅ 一致；结构化为 B0–B5 精确边界（多实例进出测试覆盖，"未支持"仅属版本升级迁移 API）+ 白名单管控 |
| 撤回：deleteProcessInstance 具备，业务层补权限/审计 | ✅ 一致 |
| 并行/会签：有测试覆盖但需独立验证汇合与会签完成条件 | ✅ 一致；补充"满足条件后引擎自动取消剩余实例"的确定性行为 |
| 历史/待办一致性：需补充集成测试 | ✅ 一致；T12 必做 |
| 文档 T01–T12 测试清单 | ✅ 保留，并补充 T13–T16 + T14b（见下） |

---

# 七、自动化测试清单（Flowable 7.x + 同一 MySQL/H2 环境执行）

复用《验证文档》T01–T12，另加：

| 编号 | 场景 | 核心断言 |
|------|------|----------|
| T13 | 多实例节点运行中 `addMultiInstanceExecution` 加人（ALL 规则） | 新增任务可见；原任务不提前推进；全部完成后才离开节点；中途或签不受影响 |
| T14 | change-state **直接退回多实例（会签）节点** | ✅ 预期成功：当前执行取消、目标多实例**按集合整组重建任务**；核对五项数据（runtime/历史任务/历史活动/流程实例/业务投影） |
| T14b | change-state 目标设为**网关元素本身** / 并行分支单执行跨分支退回 | 预期被引擎拒绝或产生错误结构 → 验证白名单拦截的必要性；接口层返回 `WORKFLOW_OPERATION_FAILED` |
| T15 | 会签节点"退回"：退前置节点 → 重置集合 → 重新进入 | 任务集合按新配置重建；旧实例历史保留且带结束原因；无孤立执行 |
| T16 | 转交"角色/组"型任务（候选组） | 目标用户可办理；组内其他成员不再可见/认领（业务清理候选链接生效）；`wf_task_transfer` 记录完整链路 |

T05–T12（退回核验五项数据、撤回一致性、并行会签、投影一致性）全部保留原断言口径。所有测试在目标 Flowable 版本、MySQL、历史级别下跑通后再进入业务开发。

---

# 八、术语备注

- **转交（transfer）**：永久变更办理人（`setAssignee`）。**委托（delegate）**：临时委托处理、结果交回 owner（`delegateTask`/`resolveTask`），两者引擎语义不同，接口与表均分设。
- **会签**：多人全部/按比例通过；**或签**：任一人通过。均由同一多实例机制 + 不同 completionCondition 实现。
- **前加签/后加签**：在原审批人之前/之后临时插入审批人，通过在同一多实例节点动态追加实例实现。
- **change-state（活动状态变更）**：`RuntimeService.createChangeActivityStateBuilder()`，Flowable 受支持的流程状态迁移 API，本文"退回"的引擎入口。
