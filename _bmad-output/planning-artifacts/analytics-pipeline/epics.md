---
stepsCompleted: [1, 2, 3, 4]
status: 'complete'
completedAt: '2026-11-12'
inputDocuments:
  - _bmad-output/planning-artifacts/analytics-pipeline/prd.md
  - _bmad-output/planning-artifacts/analytics-pipeline/architecture.md
  - _bmad-output/planning-artifacts/analytics-pipeline/decisions.md
  - _bmad-output/planning-artifacts/analytics-pipeline/.decision-log.md
scope: 'analytics-pipeline（不挂版本；App 侧随 V1.5.0 切换）'
---

# 自建埋点（analytics-pipeline）· Epic 与 Story 拆分

## 概述

本文把 PRD（27 条 FR）和架构决策拆成可实现的 epic 与 story。没有 UX 文档：这是纯数据管道，没有界面。

## 需求清单

### 功能需求

**批次 ①（随 V1.5.0 必须就绪，后端先上生产）**

- FR-1：App 全部埋点经 `Analytics` 门面发往自建后端，不再初始化 PostHog SDK；门面签名不变；PII / 自由文本剥离规则继续生效；金额属性允许上报；上报失败不抛异常。
- FR-2：本地队列 + 攒批上报（20 条 / 30 秒，先到者为准）；切后台立即发、断网恢复后补发、被杀重启后补发；队列上限 1,000 条或 7 天，超出丢最旧；后端成功前不删；身份在事件发生时绑定。
- FR-3：自实现 `Application Installed` / `Application Updated` / `Application Opened`；从旧版升级的设备只报 Updated 不报 Installed；冷启动和回前台都报 Opened，带「是否从后台恢复」。
- FR-4：自有路由观察者取代 `PosthogObserver`，页面名沿用现有命名；底部 Tab 切换和冷启动落地页也要报。
- FR-5：每条事件带匿名 id，已登录时带用户标识；登录 / 切换账号时上报一次身份关联；切换账号前先尝试发完上一账号的事件。
- FR-6：事件带 App 版本、构建号、平台、系统版本，以及区分生产 / stag 包、真机 / 模拟器、商店 / TestFlight / 侧载的渠道标记；stag 包发往 stag 后端。
- FR-7：批量接收接口；单批条数、单条大小、请求大小有上限，超限 400；单条格式错误只拒该条，响应说明被拒条数；已登录和未登录都能上报。
- FR-8：按设备 / IP 限流，超限 429 + `Retry-After`；IP 只在内存用于限流不落库；校验事件声明的用户标识，不能冒充他人，不通过的丢弃计数；被刷时不拖累业务。
- FR-9：按事件 id 去重；用「收到时间 − 发送时间」校正时钟偏差，明细保留原始与校正后两个时间；校正后晚于收到时间或早于收到时间超过队列时限的事件标为可疑，不进汇总。
- FR-10：进内存队列后立即返回成功，批量写原始层；队列有上限，满了让 App 稍后重试；正常停机先停接收、写完队列再退出；崩溃丢失数秒数据可接受。
- FR-11：独立埋点库，生产和 stag 各一个；埋点相关连接总数有配额，不挤占业务库连接；不与业务表建外键。
- FR-12：原始层按天分区、保留 35 天；提前建好分区；过期分区整块删除；分区任务失败时记录并告警，不影响接收。
- FR-13：明细层保留 3 个月，由原始层清洗而来（去重、时间校正、身份补全、生产数据标记）；收到后 10 分钟内可查；可从原始层重算保留期内数据；单行体积受控。
- FR-14：保存全部「匿名 id ↔ 用户」关联及关联时间；按人计数时登录前的匿名事件通过关联表归到用户，不回写；一个匿名 id 关联多个用户时按事件时间判定归属。
- FR-15：注销时删除该用户的身份关联，清空明细层和原始层中的用户标识；汇总不变；24 小时内生效，失败可重试。
- FR-17：S5 涉及的每个弹窗和 banner 都有带运营位 id 的曝光和点击事件；曝光按「露出超过 50% 计一次」。
- FR-18：任何推送被点开都报同一个打开事件，带推送类型；运营通知另带推送 id。
- FR-19：加购事件带商品 id、数量、单价；新增「移出购物车」事件（带原因）和「修改数量」事件；下单离开购物车不算移出。
- FR-20：核对电商 #6 漏斗、产品 #1、产品 #5 引用的事件名都在代码中存在且口径一致，不一致以代码为准修正口径文档。

**批次 ②（发版后可补）**

- FR-16：每天记录生产与 stag 埋点库占用和月增长；超过上限（40 GB）告警；接近研究估算 10 倍时提示「评估切回第三方」。
- FR-21：每天 WIB 凌晨计算前一个 WIB 自然日的 B、C 类全部指标，遵守 §4.5.1 口径要点；幂等、按进度续算、失败告警；只算生产数据、按人去重；汇总层长期保留且不含可识别个人的字段。
- FR-22：C 类指标（S4、内容 #1、电商 #5、#6 最后一步、#7、#9，产品 #2、#3，S5 的 7 天内订单）由汇总任务分别读两库、拼好写入埋点库；结果与业务库 `PAID` 订单数一致。
- FR-23：推送发送 / 接收数取后端发送记录，打开数取 FR-18 统一事件；没有发送记录的系统推送只统计打开数并在口径中注明。
- FR-24：每天把 GA4 日活（含游客）和新版覆盖率写入埋点库；观察期内日活类指标一律用 GA4；对账通过后由 Dai 决定是否切换，口径记录切换日期。
- FR-25：每天产出自建日活、GA4 日活、差值百分比（(自建 − GA4) ÷ GA4）、新版覆盖率和登录用户日活；覆盖率 ≥90% 后开始 4 周观察期，|差值| ≤10% 为正常；结果存库，不推飞书。
- FR-26：B、C 类每个指标都有口径条目（编号、定义、来源、排除规则、已知偏差、SQL 位置）；AI 查数能读到并按口径计算；变更有记录。
- FR-27：AI 查数用独立的埋点库只读账号，能读明细、汇总、关联表、对账结果和口径，不能写；跨库问题由 FR-22 预拼汇总回答。

### 非功能需求

- NFR-1 不拖累业务：埋点接收、写库、汇总上线后，业务接口 P95 增幅 ≤10%；汇总在 WIB 凌晨低峰运行。
- NFR-2 零新组件：不引入 Firebase / BigQuery、TimescaleDB、ClickHouse、pg_partman、pg_cron、dblink / postgres_fdw 或任何新中间件、数据库扩展；异步只用 `@Async` + DB 状态机（内存队列为记录在案的破例）。
- NFR-3 隐私：不存 PII、健康数据和 IP；用户标识不可枚举；注销后匿名化；日志不记录事件里的用户标识。
- NFR-4 可搬走：事件字段语义对齐 Segment（事件名、事件 id、三种时间、匿名 id、用户标识、属性、环境上下文），可整体导出。
- NFR-5 容量：按现量 10 倍（每月 3,000–7,500 万条）设计。

### 架构带来的附加要求

**骨架（Epic 1 第一个 story）**
- 存量项目，不用脚手架。第一个 story 搭骨架：第二数据源 `analytics.datasource.*`（`JdbcTemplate`，不走 JPA）、第二套 Flyway（`db/analytics-migration`，时间戳版本号）、`scripts/ci/check-flyway-versions.sh` 扩展为扫两个迁移目录、`scripts/ops/create-analytics-db.sql`（建库、写入账号、`analytics_reader`、连接上限、`statement_timeout`）、`event-catalog.yaml` 与 `metric-definitions.yaml` 两份 yaml 的加载与同步。
- 新增环境变量 `ANALYTICS_DB_NAME / USER / PASSWORD`，`.env.example` 只放占位；建库要在部署前手动执行，stag 和生产各一次。

**数据与存储**
- 原始层按 `received_at`（UTC）日分区；明细层按校正后时间算出的 `event_date`（WIB）日分区，前后各 1 天查重 + 唯一键（`event_date`, `message_id`）兜底。
- 明细瘦身（A9）：只留解析列（`client_ts`、`event_time`、`event_date`、`is_suspicious`、匿名 id、`user_id`、`user_key`、平台、版本、机型、生产标记等），属性只留事件清单登记的字段。
- `DetailBuilder` 每分钟增量把原始层转进明细层。
- 分区维护：提前建 7 天、DEFAULT 分区兜底并告警；原始层只删已汇总完成的日期；明细层删超过 3 个月的分区。
- 汇总表 `agg_daily_*` upsert；`agg_progress` 记录完成日期（续算 + 新鲜度）；每次重算最近 3 天。
- 身份关联表记录关联时间，汇总取「事件时间之前最近一次关联」。

**身份与安全**
- 匿名 id = UUIDv4，存 `shared_preferences`（不放 secure storage），卸载重生成，登出不重置。
- 用户标识沿用 `AnalyticsDistinctId`（`sha256('tailtopia-user-'+id)`），库内另存 `user_id`。
- 身份校验规则（A7）：token 用户优先；跨账号补发查关联表，查不到则去掉用户标识并标 `identity_unverified`。
- 接口 `POST /api/v1/analytics/batch`，可不带 JWT，`RedisRateLimiter` 按 IP + 匿名 id 限流。
- 注销：`AccountDeletionService` 新增参与者写 `erasure_requests`，每晚在汇总前批量擦除。

**接收与资源**
- 有界内存队列 50,000 条，**满了返回 429**（覆盖 PRD FR-10 的 503，按架构为准）；每 1 秒或 500 条批量写；优雅停机写完队列。
- 连接配额：写入池 3（账号上限 5，`statement_timeout=120s`），stag 池 2，`analytics_reader` 3；合计 ≤10。
- 埋点库超 40 GB 告警、超 50 GB 停写；主机整盘余量另在主机加 cron 检查（运维项）。
- 告警走 `OpsAlertService` 的 TECH 渠道（汇总失败、DEFAULT 分区非空、水位、熔断）。
- 埋点接口排除在全接口请求日志之外；日志不打事件内容。

**App 端**
- 门面 `lib/core/analytics/analytics.dart` 签名不变，新增 `self_hosted_sink`、`event_queue_file`（`path_provider` 文件，串行读写）、`anonymous_id`、`lifecycle_events`、`production_flags`、`event_names`；不加新依赖；移除 `posthog_flutter` 和 `POSTHOG_*` dart-define。
- 生产标记（A5）：debug 包不上报；`isPhysicalDevice=false` 为模拟器；iOS `installerStore` 为 `com.apple.testflight` / `com.apple.simulator` 判非生产；Android 只认 `com.android.vending`；null 算生产（已知偏差）。
- 返回码处理：202 删批次；429 按 `Retry-After`；5xx / 断网指数退避；400 / 413 整批丢弃。
- `$screen` 改为 `screen_viewed`（属性 `screen`）；推送打开统一事件 `push_opened`（`push_type`，运营推送带 `campaign_token`）。
- 新增事件三步：登记 yaml → 加 Dart 常量 → 比对测试变绿。

**服务端事件与清理**
- `AnalyticsClient` 直接换成自建实现，删除 `PostHogAnalyticsClient` 与 `POSTHOG_*` 环境变量；PostHog 已停用，不双写；上线后用业务库核对（如 `shop_payment_paid` 对 `PAID` 订单数）。
- H5 匿名访客（`AnonymousVisitorId`）不进匿名 id 关联表，不算日活。

**口径排除**
- 不建员工名单（覆盖 PRD FR-13「内部账号名单」）：统计只排除 `role != 'USER'` 和 `account_type = 'VIRTUAL'`。
- 运营位按弹窗、Toko 横幅、运营推送 3 类（A12）。

**上线前验证**
- stag 实测单条事件大小（A11）；上线一周后用实测量回看水位；TestFlight 真包验证 `installerStore`；stag 10 倍量压测验证 NFR-1。

### UX 设计要求

无（纯数据管道，没有界面）。

### FR 覆盖映射

| FR | Epic | 内容 |
|---|---|---|
| FR-1 | Epic 2 | 门面换发送端 |
| FR-2 | Epic 2 | 本地攒批与补发 |
| FR-3 | Epic 2 | 生命周期事件 |
| FR-4 | Epic 2 | 页面浏览 |
| FR-5 | Epic 2 | 身份携带 |
| FR-6 | Epic 2 | 环境标记 |
| FR-7 | Epic 1 | 批量接收接口 |
| FR-8 | Epic 1 | 防刷与身份校验 |
| FR-9 | Epic 1 | 去重与时间校正 |
| FR-10 | Epic 1 | 内存队列与批量写库 |
| FR-11 | Epic 1 | 独立埋点库 |
| FR-12 | Epic 1 | 原始层 |
| FR-13 | Epic 1 | 明细层 |
| FR-14 | Epic 1 | 身份关联表 |
| FR-15 | Epic 1 | 注销匿名化 |
| FR-16 | Epic 1 | 存储水位（从批次 ② 提前，与停写熔断共用占用检测） |
| FR-17 | Epic 3 | 运营位曝光与点击 |
| FR-18 | Epic 3 | 推送打开统一事件 |
| FR-19 | Epic 3 | 购物车事件 |
| FR-20 | Epic 3 | 漏斗与引导事件核对 |
| FR-21 | Epic 4 | T+1 汇总 |
| FR-22 | Epic 4 | C 类指标关联业务数据 |
| FR-23 | Epic 4 | 推送触达口径 |
| FR-24 | Epic 4 | 日活来源 |
| FR-25 | Epic 4 | 每日对账 |
| FR-26 | Epic 4 | 口径文档 |
| FR-27 | Epic 4 | AI 只读访问 |

## Epic 列表

依赖链：Epic 1 → Epic 2 与 Epic 3 并行 → Epic 4。批次 ① = Epic 1–3（随 V1.5.0 必须就绪，后端先上生产）；批次 ② = Epic 4。

### Epic 1：后端埋点管道上线
后端能接收、校验、存储并查询埋点；上生产后服务端事件（支付、优惠券推送、分享页等）立即入库，不必等 App 发版；注销擦除同步落实。
**覆盖 FR：** FR-7、FR-8、FR-9、FR-10、FR-11、FR-12、FR-13、FR-14、FR-15、FR-16，以及骨架与服务端 `AnalyticsClient` 替换。

### Epic 2：App 切到自建上报（随 V1.5.0）
新版 App 的全部行为事件进入自建库，带身份和生产标记，生命周期与页面浏览口径不变。
**覆盖 FR：** FR-1、FR-2、FR-3、FR-4、FR-5、FR-6。

### Epic 3：补齐团队指标需要的事件（随 V1.5.0）
运营位、推送打开、购物车、漏斗这几类团队指标都有可算的事件。
**覆盖 FR：** FR-17、FR-18、FR-19、FR-20。

### Epic 4：指标汇总、日活对账与 AI 查数（批次 ②）
团队每天早上能向 AI 问到前一天的 B、C 类指标，口径一致；日活对账每天落库，作为切换日活来源的依据。
**覆盖 FR：** FR-21、FR-22、FR-23、FR-24、FR-25、FR-26、FR-27。

---

## Epic 1：后端埋点管道上线

后端能接收、校验、存储并查询埋点；上生产后服务端事件立即入库，不必等 App 发版；注销擦除同步落实。本 epic 只改后端（加 CI 脚本和运维 SQL），没有前端段。

### Story 1.1：埋点库骨架与清单同步

As a 开发者,
I want 埋点库有独立的数据源、迁移历史、建库脚本和两份清单,
So that 后续 story 只管写业务逻辑，不会碰到业务库。

**Acceptance Criteria:**

**Given** 新增 `analytics.datasource.*` 配置与 `AnalyticsDataSourceConfig` **Then** 埋点库用独立的 Hikari 池（最多 3 个连接，stag 由 env 配为 2）和 `JdbcTemplate`，业务库的 JPA 实体扫描与 `ddl-auto=validate` 不受影响〔L1〕
**And** 第二套 Flyway 只读 `classpath:db/analytics-migration`，历史表在埋点库内；首条迁移 `V<ts>__init_analytics_meta.sql` 只建 `event_catalog`、`metric_definitions` 两张表〔L1〕
**And** 业务迁移目录里出现埋点表、或埋点迁移目录里出现业务表，任一情况都会让启动或测试失败〔L1〕
**And** `scripts/ci/check-flyway-versions.sh` 同时扫 `db/migration` 和 `db/analytics-migration`：两目录各自查同号、非时间戳、改动 main 已有文件；跨目录撞号不算冲突〔L0〕
**And** 新增 `scripts/ops/create-analytics-db.sql`：建库（生产 `petgo_analytics`、stag `petgo_analytics_stag`），建写入账号（`CONNECTION LIMIT 5`、`statement_timeout=120s`），建 `analytics_reader`（`CONNECTION LIMIT 3`，只读，默认授权覆盖之后新建的表）；可重复执行〔L1〕
**And** `.env.example` 加 `ANALYTICS_DB_NAME / ANALYTICS_DB_USER / ANALYTICS_DB_PASSWORD` 占位；docs/runbooks 补一段「部署前先建库」〔L0〕
**And** 新增 `analytics/event-catalog.yaml`（先登记现有 App 事件与服务端事件）和 `analytics/metric-definitions.yaml`（先放结构与示例条目）；后端启动时加载并 upsert 进两张表，yaml 格式错误时启动失败并指出位置〔L0 / L1〕
**And** `AnalyticsLockKeys` 常量类建好，后续任务的 advisory lock 锁键只从这里取〔L0〕

### Story 1.2：原始层与分区维护

As a 开发者,
I want 原始层按天分区并自动建删,
So that 写入永远有分区可落、磁盘不会无限增长。

**Acceptance Criteria:**

**Given** 迁移建 `events_raw`（按 `received_at` UTC 日分区，只追加，字段对齐 Segment：`message_id`、`event`、`client_ts`、`sent_at`、`received_at`、`anonymous_id`、`user_key`、`user_id`、`properties`、`context`、`identity_unverified`）与 `events_raw_pdefault` **Then** 不建业务索引，只在 `received_at` 上建 BRIN〔L1〕
**And** `PartitionMaintenanceJob` 每天运行（advisory lock），保证今天起未来 7 天的分区都存在；重复运行不报错〔L1〕
**And** 本 story 只建不删；原始层删除放在 1.5，以明细层处理进度为前提〔L0〕
**And** DEFAULT 分区非空时经 `OpsAlertService`（TECH）告警〔L1〕
**And** 任务失败只记错误日志并告警，不影响接收（FR-12）〔L1〕
**And** 分区名、保留天数取常量 / 配置，单测覆盖「哪些分区该建、哪些该删」的日期计算（含跨月、跨年）〔L0〕

### Story 1.3：批量接收接口

As a App 和服务端,
I want 把事件批量交给后端并立即拿到结果,
So that 上报不阻塞主流程、后端被刷也不拖垮业务。

**Acceptance Criteria:**

**Given** `POST /api/v1/analytics/batch`，可不带 JWT **When** 批次合法 **Then** 返回 `202 {accepted, rejected}`，事件进入有界内存队列（容量 50,000）〔L1〕
**And** 单批条数、单条 32 KB、请求 500 KB 超限返回 400 / 413（ProblemDetail）；单条字段缺失、`anonymousId` 不是 UUID、事件名不在事件清单里的，只拒该条并计入 `rejected`〔L0 / L1〕
**And** `RedisRateLimiter` 按 IP 和匿名 id 限流，超限 429 + `Retry-After`；IP 不写进任何表或日志〔L1〕
**And** 队列满返回 429 + `Retry-After`（覆盖 PRD FR-10 的 503）〔L1〕
**And** `IngestFlusher` 每 1 秒或攒够 500 条批量写 `events_raw`；`received_at` 取服务端时间〔L1〕
**And** 优雅停机时先停接收、写完队列再退出；集成测试验证停机前已 202 的事件全部落库〔L1〕
**And** 本接口从全接口请求日志里排除；任何日志只打条数和 `messageId`，不打事件内容和用户标识〔L0 / L1〕
**And** 本 story 内用户标识先原样入库并标 `identity_unverified=true`，身份校验由 1.4 收紧〔L1〕
**And** stag 以 10 倍现量压测接收端，业务接口 P95 增幅 ≤10%（NFR-1），结果记进 Completion Notes〔L2〕

### Story 1.4：身份校验与关联表

As a 数据使用者,
I want 每条事件的用户归属可信，且能把登录前的匿名行为归到人,
So that 按「人」统计的指标不被冒充或错绑。

**Acceptance Criteria:**

**Given** 迁移建 `identity_links`（匿名 id、`user_key`、`user_id`、`linked_at`；唯一键为匿名 id + `user_id`）**When** 带 JWT 的请求里有身份关联事件 `user_identified` **Then** 写入或保留一条关联，`linked_at` 取首次关联时间〔L1〕
**And** `IdentityResolver` 逐条判定：没有用户标识的按匿名处理；等于 token 用户的通过；不等于 token 用户的查关联表，有关联则通过，否则去掉用户标识并标 `identity_unverified`；不带 JWT 但带用户标识的同样走关联表〔L0〕
**And** 校验不通过的事件计数并打一条汇总日志（不含标识）〔L0〕
**And** 通过校验的事件，用 `AnalyticsDistinctId` 反查出 `user_id` 一并落库〔L1〕
**And** H5 匿名访客（`AnonymousVisitorId`）发来的事件不写关联表〔L0〕
**And** 同一设备先后登录 A、B：两条关联都在，A 在登出前产生、换号后才补发的事件仍归 A〔L1〕

### Story 1.5：明细层

As a 数据使用者,
I want 收到的事件在 10 分钟内去重、校正后进入可查的明细层,
So that AI 当天就能查到干净的明细。

**Acceptance Criteria:**

**Given** 迁移建 `events_detail`（按 `event_date` WIB 日分区）与 DEFAULT 分区，唯一键（`event_date`, `message_id`），在 `user_id`、`anonymous_id`、`event` 上建索引 **Then** 列只含 D-4 规定的解析列，属性 JSONB 只保留事件清单登记的字段〔L1〕
**And** `DetailBuilder` 每分钟按 `received_at` 进度增量处理（advisory lock，进度写在同一事务里），收到后 10 分钟内可查（FR-13）〔L1〕
**And** `event_time` = `client_ts` +（`received_at` − `sent_at`）；`event_date` 由 `event_time` 换算成 WIB 日期；`client_ts` 原样保留〔L0〕
**And** 校正后晚于 `received_at`，或早于 `received_at` 超过 7 天的，标 `is_suspicious`〔L0〕
**And** 写入前查 `event_date` 前后各 1 天的分区里是否已有同一 `message_id`，有则跳过；唯一键 `ON CONFLICT DO NOTHING` 兜底；集成测试覆盖「重试跨午夜」场景〔L1〕
**And** 生产标记：上下文里的模拟器 / TestFlight / 侧载标记，以及 `role != 'USER'` 或 `account_type = 'VIRTUAL'` 的用户，都记为非生产〔L1〕
**And** `PartitionMaintenanceJob` 扩展到明细层：预建 7 天，删除超过 3 个月的分区〔L1〕
**And** 原始层删除超过 35 天的分区，前提是 `DetailBuilder` 的进度已越过该日；进度落后时不删并告警〔L1〕
**And** 提供按日期区间从原始层重算明细的入口（只限原始层保留期内），重算结果与首次处理一致〔L1〕

### Story 1.6：注销擦除

As a 注销的用户,
I want 我的埋点数据在 24 小时内与我脱钩,
So that 注销承诺在埋点库里也成立。

**Acceptance Criteria:**

**Given** 迁移建 `erasure_requests`（`user_id`、`user_key`、`requested_at`、`done_at`）**When** `AccountDeletionService` 状态机执行注销 **Then** 新增的 `AnalyticsErasureParticipant` 写入一行；写失败抛异常，由状态机现有重试兜住〔L1〕
**And** `ErasureJob` 每晚在汇总之前运行（advisory lock）：删除这些用户的 `identity_links`，并把 `events_detail`、`events_raw` 中的 `user_id` / `user_key` 置空，然后写 `done_at`〔L1〕
**And** 重复运行安全；已处理的请求不再扫描〔L1〕
**And** 擦除后，匿名 id 上的事件仍保留（变成匿名事件）；汇总层不动〔L1〕
**And** 集成测试：注销 → 跑擦除任务 → 埋点库里查不到该 `user_id` / `user_key`〔L1〕

### Story 1.7：服务端事件改走自建

As a 运营和产品,
I want 支付、优惠券推送、分享页这些服务端事件进自建库,
So that 后端一上线就有服务端数据，不再依赖已停用的 PostHog。

**Acceptance Criteria:**

**Given** 新增 `SelfHostedAnalyticsClient` 实现 `AnalyticsClient` **Then** 现有约 12 处调用点无需改动，事件经内存队列写入原始层（用户标识由服务端直接给出，按已校验处理）〔L1〕
**And** 删除 `PostHogAnalyticsClient`、`analytics.posthog.*` 配置和 `POSTHOG_*` 环境变量占位〔L0〕
**And** 服务端事件名全部登记进事件清单；`AnalyticsEventGuard` 等现有防护照常生效〔L0〕
**And** 匿名访客事件（分享页、名片页）带 `AnonymousVisitorId`，标为 H5 来源，不进身份关联〔L1〕
**And** stag 上触发一次测试支付，`shop_payment_paid` 进入明细层，与当天 `PAID` 订单数一致〔L2〕

### Story 1.8：存储水位与停写熔断

As a Dai,
I want 埋点库占用每天可见、超线告警、再超线自动停写,
So that 埋点永远不会把共用的数据库磁盘写满。

**Acceptance Criteria:**

**Given** 迁移建 `storage_watermarks`，`StorageWatermarkJob` 每天运行 **Then** 记录生产 / stag 埋点库占用和月增长〔L1〕
**And** 超过 40 GB 经 TECH 渠道告警；月事件量或占用接近研究估算的 10 倍时，告警文案提示「评估切回第三方」（FR-16）〔L1〕
**And** `IngestCircuitBreaker` 每 5 分钟检查一次，超过 50 GB 后接收端对所有请求返回 429，降回阈值以下自动恢复；熔断和恢复都告警〔L1〕
**And** 阈值取配置，单测覆盖告警 / 熔断 / 恢复三种状态切换〔L0〕
**And** docs/runbooks 补一段主机整盘余量 cron 检查的说明（运维项，不在后端实现）〔L0〕

---

## Epic 2：App 切到自建上报（随 V1.5.0）

新版 App 的全部行为事件进入自建库，带身份和生产标记，生命周期与页面浏览口径不变。本 epic 只改 App；依赖 Epic 1 已上 stag（联调）和生产（发版前）。验收层级：L0 = `flutter analyze` / `flutter test`；L1 = Android 模拟器连 stag 后端，查 stag 埋点库；L2 = 真机 / TestFlight / 商店包。

### Story 2.1：自建上报器与本地队列

As a 产品和运营,
I want App 的事件可靠地送到自建后端，断网和被杀都不丢,
So that 埋点数据完整，又不拖慢 App。

**Acceptance Criteria:**

**Given** 新增 `self_hosted_sink.dart`、`event_queue_file.dart` **When** 业务代码调用 `Analytics.capture` / `capturePurchase` / `buttonTapped` 等 **Then** 门面签名不变，事件经现有白名单和 `scrub()` 后进本地队列；金额属性允许保留〔L0〕
**And** 每条事件生成 UUIDv4 `messageId`，记下 `timestamp`；发送时填 `sentAt`；请求体外层 camelCase、属性 snake_case〔L0〕
**And** 每 20 条或 30 秒发一批（先到者为准）；切到后台立即发；网络恢复后补发；冷启动时补发上次未发完的〔L0 / L1〕
**And** 队列存成 `path_provider` 下的文件，读写串行；上限 1,000 条或 7 天，超出丢最旧〔L0〕
**And** 返回码处理：202 删批次；429 按 `Retry-After` 延后；5xx / 断网指数退避；400 / 413 整批丢弃并打一条 debug 日志〔L0〕
**And** debug 包不发送（沿用 `kDebugMode` 约定），`debugCaptureSink` 测试钩子照常可用〔L0〕
**And** 上报异常一律吞掉，UI 代码不 `await` 埋点调用〔L0〕
**And** 模拟器上产生 50 条事件 → 断网 → 杀进程 → 重启联网，stag 明细层能查到全部 50 条且无重复〔L1〕

### Story 2.2：匿名 id 与身份绑定

As a 数据使用者,
I want 每条事件带上发生时的身份，登录前后能连成同一个人,
So that 按「人」统计准确，换号补发也不会记错人。

**Acceptance Criteria:**

**Given** 新增 `anonymous_id.dart` **Then** 首次启动生成 UUIDv4 存 `shared_preferences`（不放 secure storage）；卸载前不变，`Analytics.reset()` 不重置它〔L0〕
**And** 每条事件入队时写入当时的匿名 id 和用户标识（`distinctIdFor`）；之后登出或换号，已入队事件的身份不变〔L0〕
**And** `identifyUser` 上报一条 `user_identified` 事件；`reset()` 只清用户标识〔L0〕
**And** 切换账号（`app.dart` 的 reset → identify 序列）前，先尝试把队列里上一账号的事件发出去，失败不阻塞切换〔L0〕
**And** 请求带当前 JWT（有则带）；模拟器上先登 A 再登 B，stag 关联表有两条，A 的事件不记到 B 头上〔L1〕

### Story 2.3：生命周期事件

As a 市场和产品,
I want 新装、升级、打开三个事件与以前口径一致,
So that 安装量、留存、日活能接着算。

**Acceptance Criteria:**

**Given** 新增 `lifecycle_events.dart` **When** 设备上没有自建安装标记、也没有旧版本地数据 **Then** 首次启动报 `Application Installed` 并写安装标记与当前版本号〔L0〕
**And** 有旧版本地数据（`shared_preferences` 中存在旧版已知键）但没有安装标记的设备，首次启动报 `Application Updated`，不报 Installed；之后版本号每次变化也报 Updated（带前后版本）〔L0〕
**And** 每次冷启动和每次从后台回到前台都报 `Application Opened`，属性 `from_background` 区分〔L0〕
**And** 判定逻辑抽成纯函数，单测覆盖新装、升级、同版本重启、版本再变四种情况〔L0〕
**And** 模拟器上：全新安装 → Installed + Opened；覆盖安装旧版 APK 再装新版 → 只有 Updated + Opened〔L1〕

### Story 2.4：页面浏览事件

As a 电商和产品,
I want 页面浏览照旧记录，页面名不变,
So that Toko 浏览、漏斗第一步等指标不断档。

**Acceptance Criteria:**

**Given** 自有路由观察者取代 `app_router.dart` 里的 `PosthogObserver()` **Then** 路由 push 产生 `screen_viewed`（属性 `screen` = 现有页面名，如 `toko_page`）〔L0〕
**And** `Analytics.screen()` 现有调用（底部 Tab `goBranch` 切换、冷启动落地页补报）同样产生 `screen_viewed`〔L0〕
**And** 同一页面不因路由重建重复上报（沿用现有去重逻辑）〔L0〕
**And** 模拟器上依次切四个底部 Tab 和进入商品详情，stag 明细层的页面名与 PostHog 时期一致〔L1〕

### Story 2.5：环境与生产标记

As a 数据使用者,
I want 每条事件带足以判定「是否生产数据」的上下文,
So that 模拟器、TestFlight、侧载的数据不混进统计。

**Acceptance Criteria:**

**Given** 新增 `production_flags.dart` **Then** 每条事件的上下文带 App 版本、构建号、平台、系统版本、机型〔L0〕
**And** `isPhysicalDevice=false` 标模拟器；iOS `installerStore` 为 `com.apple.testflight` 标 TestFlight、`com.apple.simulator` 标模拟器；Android 只有 `com.android.vending` 算商店，其余标侧载；null 算生产〔L0〕
**And** 标记在启动时算一次并缓存，单测覆盖每种组合〔L0〕
**And** stag 包（`PETGO_API_BASE_URL` 指向 stag）的事件只发往 stag 后端，生产埋点库查不到（FR-6）〔L1〕
**And** 模拟器事件在 stag 明细层被标为非生产〔L1〕
**And** TestFlight 包的 `installerStore` 实测为 `com.apple.testflight`，结果记进 Completion Notes；不符合时记为已知偏差〔L2〕

### Story 2.6：移除 PostHog 与事件清单比对

As a 开发者,
I want App 里不再有 PostHog，事件名与后端清单对得上,
So that 新版不发无用请求，改名在 CI 就能发现。

**Acceptance Criteria:**

**Given** 移除 `posthog_flutter` 依赖、`POSTHOG_KEY` / `POSTHOG_HOST` dart-define 和全部 `Posthog()` 调用 **Then** `flutter analyze` 干净，`main.dart` 里的 PostHog 启动注释同步清理〔L0〕
**And** Android 侧为 PostHog 加的构建覆盖项，确认无其他依赖需要后一并移除，否则保留并注明原因〔L0〕
**And** 新增 `event_names.dart` 收拢 App 上报的事件名常量〔L0〕
**And** `test/core/analytics/event_catalog_sync_test.dart` 读取 `../petgo-backend/src/main/resources/analytics/event-catalog.yaml`，App 的每个事件名都必须已登记，否则失败并列出缺项〔L0〕
**And** 模拟器上走一遍主流程，抓包确认没有任何发往 posthog.com 的请求〔L1〕

---

## Epic 3：补齐团队指标需要的事件（随 V1.5.0）

运营位、推送打开、购物车、漏斗这几类团队指标都有可算的事件。改动以 App 为主，每个新事件同步登记进后端 `event-catalog.yaml`（后端段只改 yaml，无代码）。与 Epic 2 改的是不同文件，可以并行；联调（L1）需要 Epic 2 的上报器。

### Story 3.1：运营位曝光与点击核对

As a 电商和内容运营,
I want 每个弹窗和 banner 的曝光、点击都能按运营位 id 统计,
So that 能算点击率和 7 天带单。

**Acceptance Criteria:**

**Given** 现有 `ops_popup_shown` / `ops_popup_tapped`（`ops_popup_coordinator.dart`）、`toko_banner_shown` / `toko_banner_tapped`（`shop_banner_carousel.dart`、`toko_page_v3.dart`）和新人券弹窗 **Then** 逐一核对都带运营位 id（弹窗 = `ops_popups` 的 token，横幅 = `shop_banners` 的 token），缺的补上〔L0〕
**And** 曝光统一按「露出超过 50% 计一次」，与 Toko 横幅现有定义一致；不一致的改齐〔L0〕
**And** 核对结果（运营位清单、事件名、id 字段）写进 `metric-definitions.yaml` 的 S5 条目〔L0〕
**And** 后端段：上述事件及属性在 `event-catalog.yaml` 登记齐全〔L0〕
**And** 模拟器上展示并点击一次弹窗和横幅，stag 明细层能按运营位 id 查到曝光和点击各一条〔L1〕

### Story 3.2：推送打开统一事件

As a 内容运营,
I want 任何推送被点开都记成同一个事件并带类型,
So that 能按单条运营推送算打开率、按类型统计系统推送。

**Acceptance Criteria:**

**Given** `core/push/push_service.dart` 的推送点击处理 **When** 任一推送被点开 **Then** 上报 `push_opened`，`push_type` = 载荷里的 `type`〔L0〕
**And** 运营通知另带 `campaign_token` = 载荷 `targetRef`（活动 public_token）〔L0〕
**And** 现有 `ops_notification_opened`、`toko_coupon_expiry_push_opened` 保留不动；通知中心列表点击不报 `push_opened`（只算推送点击）〔L0〕
**And** 载荷缺 `type` 时 `push_type` 记为 `unknown`，不丢事件〔L0〕
**And** 后端段：`push_opened` 在 `event-catalog.yaml` 登记，列出已知的 `push_type` 取值〔L0〕
**And** 真机上分别点开一条运营推送和一条点赞推送，stag 明细层各有一条 `push_opened`，类型正确〔L2〕

### Story 3.3：购物车事件补全

As a 电商,
I want 加购、移出、改数量都有事件,
So that 能算商品在购物车里停留多久、为什么没转化（电商 #7）。

**Acceptance Criteria:**

**Given** `toko_add_to_cart_tapped`（`product_detail_page_v3.dart`、`sku_picker_sheet.dart`）**Then** 补齐属性：商品 id、SKU id、数量、单价〔L0〕
**And** 新增 `toko_cart_item_removed`：手动删除或清空失效商品时按商品各报一次，带商品 id、SKU id、数量、`reason`（`manual` / `invalid_cleared`）〔L0〕
**And** 新增 `toko_cart_quantity_changed`：带商品 id、SKU id、修改前后数量〔L0〕
**And** 下单成功后商品离开购物车，不报 `toko_cart_item_removed`〔L0〕
**And** 后端段：两个新事件在 `event-catalog.yaml` 登记〔L0〕
**And** 模拟器上加购 → 改数量 → 删除，stag 明细层依次有三条事件，属性齐全〔L1〕

### Story 3.4：漏斗与引导事件核对

As a 电商和产品,
I want 团队指标引用的事件名都真实存在、含义一致,
So that 漏斗和留存不会因为事件名对不上而算错。

**Acceptance Criteria:**

**Given** 电商 #6 漏斗各步（`toko_page` 浏览、`toko_product_shown`、`toko_product_detail_viewed`、`toko_buy_now_tapped` / `toko_add_to_cart_tapped`、`toko_order_submitted`、`toko_order_pay_tapped`）、产品 #1 `pet_profile_create_submitted`、产品 #5 `onboarding_completed` **Then** 逐个核对触发时机与团队文档定义一致，结论写进 `metric-definitions.yaml` 对应条目〔L0〕
**And** 不一致的以代码为准修正口径条目，并在条目的变更记录里写明；需告知团队的列进 Completion Notes〔L0〕
**And** 这些事件在 `event-catalog.yaml` 中都已登记，属性与代码一致〔L0〕
**And** 本 story 不改事件的触发逻辑；确需改动的单列出来交 Dai 拍板〔L0〕

---

## Epic 4：指标汇总、日活对账与 AI 查数（批次 ②）

团队每天早上能向 AI 问到前一天的 B、C 类指标，口径一致；日活对账每天落库，作为切换日活来源的依据。本 epic 只改后端（加 AI 查数接入配置）。依赖 Epic 1 的明细层；Epic 2、3 上线后指标才完整。通用规则（每个 story 都适用）：只算生产数据且 `is_suspicious=false`；按「人」去重，「人」= 事件时间点能关联到的 `user_id`，否则为匿名 id；WIB 自然日；每日去重人数不跨天相加，周、月统计由 AI 按口径从日表现算，不另存。

### Story 4.1：汇总框架与活跃、安装、注册

As a 运营和市场,
I want 每天早上查到前一天的日活、安装数和注册数,
So that 不用等人手工拉数。

**Acceptance Criteria:**

**Given** 迁移建 `agg_progress` 与 `agg_daily_activity`、`agg_daily_install` **When** `RollupJob` 在 WIB 03:00 运行（advisory lock，排在 `ErasureJob` 之后）**Then** 按各指标族的 `agg_progress` 续算，每次重算最近 3 个 WIB 日（upsert，幂等）〔L1〕
**And** 汇总 SQL 放在 `resources/analytics/rollup/<指标族>.sql`，日期作为参数传入，不用 `now()` / `CURRENT_DATE`〔L0〕
**And** 写数据和推进 `agg_progress` 在同一事务；失败经 TECH 渠道告警，下次运行自动补上缺的日期〔L1〕
**And** 提供按日期区间手动重跑的入口（只限明细层保留期内）〔L1〕
**And** `agg_daily_activity`：自建日活（含游客）、登录用户日活，分平台（S1 自建口径，只用于对账）〔L1〕
**And** `agg_daily_install`：首次 `Application Installed` 的新装数（不含升级，分平台），首次 `signup_succeeded` 的注册数（S2、S3）〔L1〕
**And** 汇总表不含 `user_id`、匿名 id 等可识别个人的字段〔L0〕
**And** 用构造好的明细数据集测试：跨午夜事件归 WIB 日、匿名转登录算一个人、非生产与可疑事件被排除〔L1〕

### Story 4.2：GA4 日活快照与每日对账

As a Dai,
I want 每天看到自建日活和 GA4 日活的差值和新版覆盖率,
So that 有依据决定什么时候改用自建日活。

**Acceptance Criteria:**

**Given** `Ga4DailySnapshotJob` 复用 `Ga4ActiveUsersClient` **Then** 每天把前一天的 GA4 日活（含游客）和按 `appVersion` 拆分的活跃数写入 `ga4_daily_snapshot`，算出新版覆盖率（V1.5.0 及以上占比）〔L1〕
**And** GA4 拉取失败告警，下次运行补拉；只在 prod 运行（stag 无 GA4 凭证时跳过并记日志）〔L1〕
**And** 对账表 `agg_daily_dau_reconcile` 每天一行：自建日活（含游客）、GA4 日活（含游客）、差值百分比 =（自建 − GA4）÷ GA4、新版覆盖率、登录用户日活、状态〔L1〕
**And** 覆盖率首次 ≥90% 的那天记为观察期起点，之后 4 周内 |差值| ≤10% 标「正常」，否则标「异常」；观察期外状态为「未开始」或「已结束」（FR-25）〔L0〕
**And** 对账结果只存库，不推飞书〔L0〕
**And** 日活来源开关 `analytics.dau-source`（默认 `GA4`）；切到 `SELF` 时在 `metric-definitions.yaml` 的 S1 条目记录切换日期〔L0〕

### Story 4.3：留存与引导转化

As a 市场和产品,
I want 查到 D1 / D3 / D7 留存、新用户 W1 留存和安装后建档比例,
So that 能评估投放质量和新手引导。

**Acceptance Criteria:**

**Given** 迁移建 `agg_cohort_retention` **Then** 以安装日为组（首次 `Application Installed`），记组人数和第 X+1、X+3、X+7 天再次 `Application Opened` 的人数；未满 N 天的组该列为空（市场 #1–#3）〔L1〕
**And** 以 `onboarding_completed` 日为组，记组人数和其后第 7–13 天内任意一天打开过的人数（产品 #5）〔L1〕
**And** 按月记有 `pet_profile_create_submitted` 的人数和当月新装数（产品 #1）〔L1〕
**And** 每次运行回补仍在观察窗口内的组（最长 13 天），不只算最近 3 天〔L1〕
**And** 周加权、月比率不入表，口径写进 `metric-definitions.yaml`〔L0〕

### Story 4.4：运营位与推送效果

As a 电商和内容运营,
I want 每个弹窗、横幅、推送的展示、点击、打开和带单都能查,
So that 知道哪个运营位有效。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_placement`、`agg_daily_push` **Then** 按运营位 id 每天记曝光次数、点击次数、点击人数（S5 曝光 / 点击）〔L1〕
**And** 每个运营位每天记「点击后 7 天内产生的已支付 Toko 订单数」：读业务库 `shop_orders`（只计 `PAID`）的窄查询，按用户和时间与点击匹配；结果写进埋点库，不做跨库 SQL（S5 带单，FR-22）〔L1〕
**And** 运营推送按 `campaign_token` 每天记发送数、下发成功数（取 `ops_notification_campaign_targets` 等发送记录）和 `push_opened` 打开人数；系统推送按 `push_type` 记打开人数，有发送记录的类型补发送数（FR-23）〔L1〕
**And** 没有发送记录的推送类型，在口径条目里注明「只有打开数」〔L0〕
**And** 带单数与直接查业务库的 `PAID` 订单数一致（抽样校验）〔L1〕

### Story 4.5：Toko 浏览、漏斗、转化与购物车

As a 电商和产品,
I want 查到 Toko 打开人数、漏斗各步人数、加购转化、购物车停留和潜力商品,
So that 知道用户在哪一步流失、哪些商品值得补货。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_toko`、`agg_daily_toko_funnel`、`agg_daily_cart_item`、`agg_daily_product` **Then** 每天记浏览 `toko_page` 的人数（电商 #4）〔L1〕
**And** 漏斗：以每天进入第一步的人为组，记 7 天内按顺序经过每一步的人数；最后一步「支付成功」读业务库 `PAID` 订单（电商 #6）；同时记已提交订单数与已支付订单数（产品 #2）〔L1〕
**And** 加购转化：每天加购的人中，7 天内完成 Toko 支付的人数（电商 #5）〔L1〕
**And** 购物车：每件被加购的商品记加购时间、结束方式（已购买 / 已移出 / 仍在车里）和停留时长、单价 × 数量；已购买以业务库订单为准（电商 #7）〔L1〕
**And** 商品维度每天记详情浏览次数、加购次数，以及读业务库得到的库存与近 30 天已支付件数（电商 #8、#9）〔L1〕
**And** 依赖 7 天窗口的指标，每次运行回补最近 7 天〔L1〕

### Story 4.6：以日活为分母的比率指标

As a 电商、产品和内容运营,
I want 查到电商交易占日活、付费用户占日活、新用户与回访用户,
So that 这几项能和日活放在同一口径下比较。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_ratio` **Then** 每天记有已支付 Toko 订单的人数（S4 分子）、有非 Toko 付费解锁的人数（产品 #3 分子）、当天注册人数（内容 #1），分子均读业务库〔L1〕
**And** 分母日活按 `analytics.dau-source` 取：`GA4` 时取 `ga4_daily_snapshot`，`SELF` 时取 `agg_daily_activity`；表里同时存分母的来源〔L1〕
**And** 回访用户 = 当天日活 − 当天新用户；月比率取每日比率的平均，写进口径，不入表〔L0〕
**And** 业务库读取排除 `role != 'USER'` 与 `account_type = 'VIRTUAL'`〔L1〕

### Story 4.7：口径文档补全与 AI 查数接入

As a 团队成员,
I want 向 AI 问任何 B、C 类指标都能按统一口径得到答案,
So that 不同人问同一个数，得到同一个结果。

**Acceptance Criteria:**

**Given** `metric-definitions.yaml` **Then** B、C 类每个指标都有条目：团队编号、定义、来源表、排除规则、已知偏差（卸载重装算新装、`installerStore` 为 null、员工真实账号、晚到超过 3 天不回算、V1.5.0 之前无数据等）、SQL 文件位置、变更记录（FR-26）〔L0〕
**And** 启动时同步进 `metric_definitions`，CI 校验每个汇总表都至少被一个条目引用〔L0〕
**And** AI 查数服务新增埋点库连接，使用 `analytics_reader`（只读、3 个连接、30 秒超时），可读明细层、汇总层、关联表、对账表和口径表，不可读原始层（FR-27）〔L1〕
**And** AI 查数的说明文档写明：先查 `metric_definitions`，按口径计算；跨库问题只用预拼好的汇总表，不自行跨库〔L0〕
**And** 用 5 个典型问题（昨日日活、上周 D1 留存、某弹窗点击率、Toko 漏斗、某推送打开率）在 stag 实测，答案与直接查表一致，记进 Completion Notes〔L2〕
