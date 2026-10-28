# 低代码审批流平台 · 后端 SOP（标准作业程序）

**适用：** 后端（`workflow-server/`）的环境搭建、构建测试、启动运维、数据迁移、后台任务、安全基线、故障排查与发布收口。
**配套文档：** 《低代码审批流平台PRD.md》《开发计划.md》《Flowable高级审批能力可行性分析.md》
**代码落位：** `workflow-server/`（后端）、`doc/`（设计文档）；前端在 `workflow-web/`（不在本 SOP 范围）

> 本文档把后端从"零搭建"到"日常运维"拆成可照做的步骤，每条给出验收判据。
> 与文档冲突时以实际代码/测试结论为准，并回写本文档。

---

## 1. 系统定位与版本基线（动手前必读）

模块化单体：**若依 3.9.2（springboot3 分支）基座 + 自定义 `ruoyi-workflow` 模块**。流程引擎是事实源，MySQL 存业务/投影/审计，MongoDB 存表单 JSON，MySQL Outbox 做可靠通知。

| 层 | 选型 | 版本 | 备注 |
|---|------|------|------|
| JDK | Java | **17**（本机 `D:\Java\Server\jdk\jdk-17.0.17`） | Flowable 7 要求 |
| 后端框架 | Spring Boot（若依 3.9.2 基座） | **3.5.16** | `spring.profiles.active=druid` |
| 流程引擎 | Flowable | **7.1.0**（`flowable-spring-boot-starter-process`） | `database-schema-update:true`、`async-executor-activate:false`、`history-level:full` |
| 关系库 | MySQL | 8.x | 库 `workflow_dev`；`wf_*` 业务表 + `ACT_*` 引擎表（约 39 张）共用实例统一事务 |
| 文档库 | MongoDB | 6/7/8 | 库 `workflow`；集合 `form_schema_versions` / `approval_form_data` |
| 迁移 | Flyway | 10.x（Spring Boot 内嵌） | `baseline-version:0` + `baseline-on-migrate:true`，只迁 `wf_*`/`approval_*` |
| 连接池 | druid | 1.2.28 | 控制台 `/druid/*`（登录 `ruoyi/123456`） |
| API 文档 | springdoc-openapi | 2.9.0 | 契约先行（前端按 OpenAPI 生成 TS 类型） |
| 鉴权 | JWT（若依默认） | — | token 有效期默认 30min；第一版不引 Redis |

**模块清单：** `ruoyi-admin`（启动/控制器/测试）、`ruoyi-common`、`ruoyi-framework`、`ruoyi-system`、`ruoyi-workflow`（流程/表单/审批/outbox/恢复/安全守卫）、`workflow-spike`（引擎验证，保留）；`ruoyi-generator`/`ruoyi-quartz` 已物理删除。

**红线：** 凭证不进 git（druid `${WF_DB_*}`、Mongo `${WF_MONGO_URI}` 占位符已生效）；流程状态/待办/权限的事实源永远是 Flowable/MySQL。

---

## 2. 环境准备 SOP

### 2.1 前置依赖

| 依赖 | 就绪判据 |
|------|---------|
| JDK 17 | `java -version` 输出 17.x |
| Maven | `mvn -v` 可解析插件（本环境 `dependency:build-classpath` 不可用，属已知限制） |
| MySQL 服务 | `Get-Service MySQL80` = Running；`3306` 监听 |
| MongoDB 服务 | `27017` 监听（见 2.3 权限坑） |
| Redis | 可选，第一版不启用（接口留桩，见《开发计划》§八） |

### 2.2 MySQL 准备

1. 建库与用户（本地值可参考 `workflow-server/.env`，**该文件不进 git**）：
   ```sql
   create database workflow_dev default character set utf8mb4;
   create user 'workflow'@'127.0.0.1' identified by '123456';
   grant all privileges on workflow_dev.* to 'workflow'@'127.0.0.1';
   grant select on performance_schema.* to 'workflow'@'127.0.0.1';  -- 缺这步 druid 连接会被 disabled（见 §10）
   ```
2. 执行若依基线脚本（含 M1 的 `sys_user/sys_dept` 扩展字段）：
   `sql/ry_20260417.sql` → `sql/update/m1_sys_ext.sql`（幂等，可重跑）。
3. `wf_*` 表由 Flyway 自动建（V1–V4），`ACT_*` 表由 Flowable 首次启动自建 —— 两者都**不需要手动执行**。

### 2.3 MongoDB 准备（本机已踩坑，照做）

- 服务启动失败排查顺序：
  1. `Get-Service MongoDB` 状态；停/崩则查 `C:\Program Files\MongoDB\Server\8.3\log\mongod.log` 尾部（常见：`out of memory`）。
  2. **Program Files 权限坑**：当前用户对 `Server\8.3\data` 与 `log` 目录无写权限（服务以 NetworkService 运行可写，手动拉起或 `icacls` 授权会被拒）。**不要浪费时间改 ACL**。
  3. 临时目录全新 dbPath 无认证启动（绑定 127.0.0.1，与应用默认 URI 一致）：
     ```
     mongod.exe --dbpath <temp>\mongo-data --port 27017 --wiredTigerCacheSizeGB 0.25 --logpath <temp>\mongod.log
     ```
     - `--dbpath` 目录必须**先建好**（mongod 不自动创建）；
     - 命令行参数是 `--dbpath`/`--logpath`，**没有** `--bindIp`（那是 cfg 里的 camelCase 写法）；
     - 无认证模式：**连接 URI 不要带凭证**（带 `user:pass` 会触发 SCRAM 校验失败）。
- 就绪判据：`Get-NetTCPConnection -State Listen -LocalPort 27017` 有记录。

### 2.4 凭证注入

应用运行时需要以下环境变量（IDE 运行配置或 shell 注入，不进 git）：

```
WF_DB_HOST=127.0.0.1   WF_DB_PORT=3306   WF_DB_NAME=workflow_dev
WF_DB_USERNAME=workflow   WF_DB_PASSWORD=123456
WF_MONGO_URI=mongodb://127.0.0.1:27017/workflow     # 无认证时不带 user:pass
```

---

## 3. 构建与测试 SOP

> 本机内存紧张（曾仅剩 ~600MB），**所有 Maven 命令固定小堆**，否则 OOM：
> `$env:MAVEN_OPTS='-Xms128m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC'`
> 并设 `$env:JAVA_HOME='D:\Java\Server\jdk\jdk-17.0.17'`、`$env:Path` 前缀、`WF_DB_*`/`WF_MONGO_URI`。

| 步骤 | 命令（workdir=`workflow-server`） | 验收判据 |
|------|------|---------|
| 改 `ruoyi-workflow` 源码后增量编译 | `mvn install -pl ruoyi-workflow -DskipTests -q` | 无输出即成功 |
| 编译测试代码 | `mvn test-compile -pl ruoyi-admin -DskipTests -q` | 无输出即成功 |
| 单类验收 | `mvn test -pl ruoyi-admin -Dtest='M5ReliabilitySafetyAcceptanceTest' -DfailIfNoTests=false` | `Tests run: 11, Failures: 0, Errors: 0` |
| 全量回归（提交前必跑） | `mvn test -pl ruoyi-admin -Dtest='M1EngineAcceptanceTest,M2FormFlowAcceptanceTest,M3ApprovalFlowAcceptanceTest,M4ApprovalFlowAcceptanceTest,M5ReliabilitySafetyAcceptanceTest' -DfailIfNoTests=false` | **33/33 绿**（2+4+6+10+11） |

测试前置条件：MySQL + MongoDB 在线、凭证注入、`sys_user` 3/4/5 已存在（各测试 Order(0) 自动最小插入并自清理，可连续重跑）。

---

## 4. 启动 SOP

1. 运行主类：`com.ruoyi.RuoYiApplication`（模块 `ruoyi-admin`），端口 **8080**，context-path `/`。
2. 启动自检（按日志顺序核对）：
   - Flyway 迁移成功（`Migrating schema ... to version "4"`），`fail-on-error:true` 迁移失败直接拒启；
   - Flowable 建齐 `ACT_*` 表（首启约 39 张），`database-schema: workflow_dev` 钉住元数据探测库；
   - MongoDB 连接成功（`MongoTemplate` bean 就绪）；
   - `@Scheduled` 注册 outbox 投递与一致性恢复（初始延迟 60s，不误触发）。
3. 冒烟检查：
   - 登录页 `GET /` 200；
   - druid 控制台 `GET /druid/*`（登录 `ruoyi/123456`）；
   - 登录接口拿 token：`POST /login`（若依验证码 `captchaType=math`）。
4. 停服：Ctrl+C / 结束进程即可；Flowable 无异步 Job（`async-executor-activate:false`），无需优雅停机等待。

---

## 5. 数据迁移 SOP（Flyway）

| 版本 | 脚本 | 内容 |
|------|------|------|
| V1 | `V1__wf_form_definition.sql` | 表单定义/版本、审批单骨架、外部接口（5 表） |
| V2 | `V2__approval_flow.sql` | 审批流：流程定义/版本、审批类型、任务投影、操作幂等 |
| V3 | `V3__advanced_approval.sql` | 高级审批：转交/加签/退回三表 |
| V4 | `V4__reliability_safety.sql` | 可靠安全：`wf_outbox_event` |

新增变更 SOP：
1. 新建 `V{n+1}__描述.sql`，只动 `wf_*`/`approval_*` 表（`sys_*` 扩展走 `sql/update/` 手动脚本）；
2. **禁止修改已发布脚本**（Flyway 按 checksum 校验，改了会启动失败）；
3. 提交后由应用启动自动执行；验证 `flyway_schema_history` 最新记录。

---

## 6. 后台任务 SOP（M5）

| 任务 | 调度 | 配置键（默认值） | 行为与观察点 |
|------|------|------|------|
| Outbox 投递 `OutboxEventService.dispatchDueEvents` | `@Scheduled fixedDelay` | `workflow.outbox.poll-ms=60000` / `initial-delay-ms=60000` / `backoff-base-ms=1000` | 每事件独立事务；成功 `PROCESSED`；失败指数退避 `base·2^(n-1)` 封顶 30min；超 `maxRetries=5` 标 **FAILED 死信**（不阻塞批次其他事件） |
| 一致性恢复 `ConsistencyRecoveryService.recoverAll` | `@Scheduled fixedDelay` | `workflow.recovery.poll-ms=60000` / `initial-delay-ms=60000` | 四步：结束漂移→APPROVED+投影兜底；投影缺/幻影修复；>24h 孤儿 Mongo 文档清理（单次≤500）；SUBMITTING 卡死→FAILED |

**日常巡检 SQL：**
```sql
-- 死信告警（FAILED 需查 last_error 人工介入）
select event_type, count(*), max(last_error) from wf_outbox_event
where status='FAILED' group by event_type;
-- 卡死提交
select id, approval_no, business_status, created_at from wf_approval_instance
where business_status='SUBMITTING' and created_at < now() - interval 30 minute;
```

---

## 7. 关键 REST 接口速查

| 分组 | 路径 | 说明 |
|------|------|------|
| 审批类型 | `/workflow/approval-definitions` | CRUD + publish（表单+流程综合校验） |
| 流程 | `/workflow/workflows` | 定义 + 发布（BPMN 校验规一化门禁 + 版本快照） |
| 表单 | `/workflow/forms` `/workflow/form-data` | 定义/草稿/发布；表单数据双写（MySQL 投影 + Mongo JSON） |
| 外部接口 | `/workflow/external-apis` | 登记/白名单/测试调用（SSRF 守卫生效） |
| 审批单 | `/workflow/approvals` | 提交/详情/时间线/撤回/取消；**detail/timeline 走资源级鉴权** |
| 任务 | `/workflow/tasks` | 待办/已办/同意/驳回/转交/加签/退回/退回白名单 |

**资源级鉴权（M5，防 ID 遍历）：** `detail`/`timeline` 传入当前用户；仅 **admin（"1"）/ 申请人 / 该单任务办理人（投影 assignee）** 可访问，其他用户一律 `ServiceException("无权查看该审批单")`。

---

## 8. 安全作业 SOP（M5）

1. **凭证纪律：** 数据库/ MongoDB 凭证只经环境变量注入；`.env`、IDE 运行配置不提交；`git grep` 出现明文密码即视为事故。
2. **外部接口调用（SSRF）：** 每个 `wf_external_api` 必须配 `allowed_hosts_json` 白名单；目标命中内网/保留/环回/链路本地/云元数据黑名单即拒绝；HttpClient **不跟随重定向**；响应**有界读取 1MB**。新增接口默认不信任任何"内网可达"地址。
3. **已知遗留：** GitHub fine-grained PAT 曾在对话中明文暴露，**尚未轮换** —— 尽快生成新 PAT 替换，并从本地任何文件中清除旧值。
4. **提交纪律：** 提交前跑 `gitnexus detect_changes`（M5 收口实测 risk low）；`workflow-web/auto-imports.d.ts`、`pnpm-lock.yaml`/`pnpm-workspace.yaml` 是用户进行中变更**不提交**；`figma-local-project/` 不提交。

---

## 9. 发布/收口 SOP

里程碑收口固定流程（M1–M5 已按此执行，M6 待做）：

1. 全量回归 33/33 绿（§3）；
2. `doc/开发计划.md` 追加"**MX 已收口**"段（含验收数、踩坑记录）；
3. `gitnexus detect_changes` 确认无 HIGH/CRITICAL；
4. 显式 `git add`（只加 doc + workflow-server 变更），避开用户进行中文件；
5. commit → `git tag m{n}-complete` → `git push origin main` + `git push origin <tag>`（代理 127.0.0.1:7890 已配）。

当前进度：**M1–M5 已收口打 tag**（m1-complete … m5-complete，最新 `742a358`）；下一卡 **M6 整体验收**：采购审批贯穿案例 + PRD 19 十八条清单逐项打勾。

---

## 10. 故障排查 SOP（踩坑表）

| 症状 | 根因 | 处置 |
|------|------|------|
| SSRF 漏拦 172.16/192.168/169.254/198.18/203.0.113 段 | Java byte 有符号：`b[0]==172` 恒 false（172 存为 -84） | 八位组先 `& 0xFF` 再比较（已修，GuardCheck 31 例全过） |
| MongoDB Connection refused / 30s 超时 | 服务停/崩（曾 OOM） | §2.3：临时 dbPath 无认证拉起，URI 不带凭证 |
| 认证 `Exception authenticating MongoCredential` | 无认证服务 + URI 带 `user:pass` | URI 去掉凭证（`mongodb://127.0.0.1:27017/workflow`） |
| druid 连接 `disabled` | `performance_schema` 无查询权限 | `grant select on performance_schema.*` |
| V1 迁移被跳过、wf_* 表没建 | baseline 版本号与 V1 冲突 | `baseline-version:0`（必须低于首个脚本号） |
| 测试启动报 Mongo SCRAM/无 bean | 嵌套 `@TestConfiguration` 自动检测失效 | `@SpringBootTest(classes={...TestConfig.class})` 显式注册；`@Primary` 方法返回**具体类型** |
| `createTaskQuery().list()` 查不到新任务 | 引擎返回快照 | 完成节点后**两轮查询**（M3/M4 均有此坑） |
| Flowable 误判"表已存在"走 upgrade 崩溃 | 服务器多库（workflow_spike 残留 ACT 表）跨库误判 | `flowable.database-schema: workflow_dev` 钉住探测库 |
| 退回 collection-MI 节点卡死（act_ru_task 空） | Flowable 直退多实例只造空壳 | 走"退目标前置串行节点 + 重写集合变量"方式二（M4） |
| Maven `dependency:build-classpath` 报 NoPluginFound | 插件不可解析（本环境限制） | 手拼 classpath（target/classes + spring-context jar）复现验证 |

---

*版本 V1.0 · 2026-10-28 · M5 收口后整理。环境侧细节（临时 mongod、小堆 Maven）为 Windows 本机实测结论。*
