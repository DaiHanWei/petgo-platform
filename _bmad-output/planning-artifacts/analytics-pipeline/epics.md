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

（2026-11-12 就绪度评审后修订；以 architecture.md 为准）

**骨架**
- 存量项目，不用脚手架。骨架分两个 story：数据源 / Flyway / CI / 建库（1.1），事件清单盘点与口径骨架（1.2）。
- 第二数据源 `analytics.datasource.*`（`JdbcTemplate`，不走 JPA）；业务数据源与 `JdbcTemplate` 显式 `@Primary`；埋点库连不上或第二套 Flyway 失败时后端照常启动，管道停用并告警。
- 第二套 Flyway `db/analytics-migration`（时间戳版本号）；`check-flyway-versions.sh` 扫两个目录；`flyway-guard.yml` 的 paths 加入新目录；CI 测试建第二个库。
- `scripts/ops/create-analytics-db.sql`：生产与 stag 各用独立角色；写入账号 `CONNECTION LIMIT 5`、`statement_timeout=120s`；`analytics_reader` `CONNECTION LIMIT 3`，按白名单逐表授权，不用全局默认授权。
- 环境变量 `ANALYTICS_DB_NAME / USER / PASSWORD`，`.env.example` 只放占位；建库在部署前手动执行。

**数据与存储**
- 原始层按 `received_at`（UTC）日分区，有 DEFAULT 兜底（建分区前先搬走 DEFAULT 里对应区间的行）；带 `source`（APP / SERVER / H5）与 `unknown_event` 标记。
- 明细层按 `event_date`（WIB）日分区，**不设 DEFAULT**；可疑事件和超出分区范围的事件 `event_date` 取 `received_at` 的 WIB 日期；批内 `DISTINCT ON` + 前后各 1 天查重 + 唯一键兜底。
- 明细进度存 `pipeline_progress`，只推进到 `now() − 2 分钟`，避免与写入提交竞态。
- 原始层删除前提：那一天已转进明细层；明细层保留 3 个月。
- 汇总：`agg_daily_*` / `agg_weekly_*` / `agg_monthly_*` / `agg_cohort_*`；`agg_progress` 续算；每次重算最近 3 天；周期去重指标入周表、月表。
- 事件清单不拒收：未登记事件进原始层、标 `unknown_event`、不进明细、按天告警，补登记后可重算。

**身份与安全**
- 匿名 id = UUIDv4，存 `shared_preferences`，卸载重生成，登出不重置。
- `identity_links` 只追加，每次登录一行；归属规则：事件前有关联 → 最近一次；之前没有、之后有 → 第一个；从未关联 → 匿名 id。
- 服务端事件用 `AnalyticsClient.captureForUser(userId, …)` / `captureForVisitor(visitorId, …)`。
- 限流以匿名 id 为主，IP 只做宽兜底；429 带 `Retry-After`。
- 注销擦除：清 `user_id` / `user_key` 和清单里标为用户标识的属性键；原始层按分区逐个处理。
- 统计只算 `role = 'USER'` 且 `account_type = 'REAL'`（排除官方号、兽医、虚拟号）；员工与测试账号为已知偏差。

**接收与资源**
- 有界内存队列按字节上限 64 MB，满了 429；服务端事件队列满或熔断时丢弃计数。
- 连接配额合计 ≤10；汇总和擦除在任务内 `SET LOCAL statement_timeout` 放宽。
- 埋点库超 40 GB 告警、超 50 GB 停写；主机整盘余量另加 cron（运维项）。
- 告警走 `OpsAlertService` TECH 渠道；埋点接口排除在全接口请求日志之外。

**App 端**
- 门面签名不变；新增上报器、本地队列文件、匿名 id、生命周期、生产标记、事件名常量；移除 `posthog_flutter`。
- 生产标记：debug 不上报；模拟器；iOS TestFlight / 模拟器，iOS null 算生产；Android 只认 Google Play，null 也算侧载。
- 生命周期判定与 PRD FR-3 一致：有旧版本地数据、无安装标记 → Updated，不报 Installed。
- 返回码：202 删批；429 按 `Retry-After`（缺失时按默认退避）；5xx / 断网指数退避；400 / 413 整批丢弃。
- 联调（L1）用 profile / release 包连 stag。

**业务库判据**
- Toko 已付款 = `shop_orders.paid_at IS NOT NULL`，按 `paid_at` WIB 日期；退款 / 取消仍算。业务库关联一律用 public token。

**上线前验证**
- stag 实测单条事件大小；上线一周后回看水位；TestFlight 真包验证；10 倍压测在本地按生产参数起的实例上做（不在共用实例上压）。

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

后端能接收、校验、存储并查询埋点；上生产后服务端事件立即入库，不必等 App 发版；注销擦除同步落实。本 epic 只改后端（加 CI 和运维 SQL），没有前端段。L0 = 编译 / 单测；L1 = 本地 postgres + redis 真跑；L2 = stag / 生产实测。

### Story 1.1：埋点库骨架

As a 开发者,
I want 埋点库有独立的数据源、迁移历史和建库脚本，且出问题不影响业务,
So that 后续 story 只管写管道逻辑，不会碰到业务库。

**Acceptance Criteria:**

**Given** 新增 `analytics.datasource.*` 与 `AnalyticsDataSourceConfig` **Then** 业务数据源和 `JdbcTemplate` 显式 `@Primary`，埋点的 `DataSource` / `JdbcTemplate` 用 `@Qualifier`；现有全部测试保持绿〔L1〕
**And** 埋点连接池最多 3 个（stag 由 env 配为 2）〔L1〕
**And** 第二套 Flyway 只读 `classpath:db/analytics-migration`，历史表在埋点库内；首条迁移只建 `event_catalog`、`metric_definitions`〔L1〕
**And** 埋点库连不上或埋点 Flyway 失败时，后端照常启动、业务接口可用，埋点管道标记为停用并经 TECH 渠道告警〔L1〕
**And** `scripts/ci/check-flyway-versions.sh` 分别检查两个迁移目录（同号、非时间戳、改动 main 已有文件），跨目录撞号不算冲突；`.github/workflows/flyway-guard.yml` 的 paths 加入 `db/analytics-migration/**`〔L0〕
**And** 后端 CI 的测试环境建出埋点测试库，L1 测试能连上〔L0〕
**And** `scripts/ops/create-analytics-db.sql` 按参数分别建生产（`petgo_analytics` / `analytics_writer` / `analytics_reader`）和 stag（`petgo_analytics_stag` / `analytics_writer_stag` / `analytics_reader_stag`）；写入账号 `CONNECTION LIMIT 5`、`statement_timeout=120s`；只读账号 `CONNECTION LIMIT 3`、只有 CONNECT 权限，**不设全局默认授权**（表权限由各 story 的迁移逐表 `GRANT`）；可重复执行〔L1〕
**And** `.env.example` 加 `ANALYTICS_DB_NAME / ANALYTICS_DB_USER / ANALYTICS_DB_PASSWORD` 占位；docs/runbooks 补「部署前先建库」〔L0〕
**And** `AnalyticsLockKeys` 常量类建好，后续任务的 advisory lock 锁键只从这里取〔L0〕

### Story 1.2：事件清单盘点与口径骨架

As a 开发者和 AI 查数,
I want 现有全部事件和后续要新增的事件都登记在一份清单里,
So that 明细层知道保留哪些属性，擦除知道清哪些键，AI 知道每个事件的含义。

**Acceptance Criteria:**

**Given** 新增 `analytics/event-catalog.yaml` **Then** 登记 App 现有全部事件（扫描 `Analytics.capture` / `capturePurchase` / `buttonTapped` 等调用点）及其属性，以及服务端 `AnalyticsClient` 的全部事件〔L0〕
**And** 预先登记 Epic 2、3 将要新增的事件：`user_identified`、`screen_viewed`、`Application Installed / Updated / Opened`、`push_opened`、`toko_cart_item_removed`、`toko_cart_quantity_changed`〔L0〕
**And** 每个属性标出是否为用户标识（如 `internal_user_id`、对方用户 id），供擦除使用〔L0〕
**And** 新增 `analytics/metric-definitions.yaml`，先放结构和 S1 示例条目〔L0〕
**And** 启动时加载两份 yaml 并 upsert 进 `event_catalog` / `metric_definitions`；yaml 格式错误时埋点管道停用并告警、指出出错位置，不阻断后端启动〔L1〕
**And** 迁移给 `analytics_reader` 授权这两张表〔L1〕

### Story 1.3：原始层与分区维护

As a 开发者,
I want 原始层按天分区并自动预建,
So that 写入永远有分区可落。

**Acceptance Criteria:**

**Given** 迁移建 `events_raw`（按 `received_at` UTC 日分区，字段对齐 Segment：`message_id`、`event`、`client_ts`、`sent_at`、`received_at`、`anonymous_id`、`user_key`、`user_id`、`source`、`properties`、`context`、`identity_unverified`、`unknown_event`）与 `events_raw_pdefault` **Then** 只在 `received_at` 上建 BRIN；**不授权**给 `analytics_reader`〔L1〕
**And** `PartitionMaintenanceJob` 每天运行（advisory lock），保证今天起未来 7 天的分区都存在；分区边界用带 `+00` 的时间字面量；重复运行不报错〔L1〕
**And** 建分区前若 DEFAULT 里有落在该区间的行：DETACH DEFAULT → 建分区 → 搬行 → ATTACH DEFAULT，同一事务内完成〔L1〕
**And** DEFAULT 分区非空时经 TECH 渠道告警；任务失败只告警，不影响接收（FR-12）〔L1〕
**And** 单测覆盖分区日期计算：跨月、跨年、UTC 与 WIB 跨日〔L0〕

### Story 1.4：批量接收接口

As a App 和服务端,
I want 把事件批量交给后端并立即拿到结果,
So that 上报不阻塞主流程，后端被刷也不拖垮业务。

**Acceptance Criteria:**

**Given** `POST /api/v1/analytics/batch`，可不带 JWT **When** 批次合法 **Then** 返回 `202 {accepted, rejected}`，事件进入有界内存队列〔L1〕
**And** 必填字段 `messageId`、`event`、`timestamp`、`sentAt`、`anonymousId`（UUID）；缺任一或格式非法只拒该条并计入 `rejected`；其余字段缺失不拒收〔L0〕
**And** 单条 32 KB、请求 500 KB 超限返回 413，整批格式错误返回 400（ProblemDetail）〔L0〕
**And** 事件名不在清单里的**照样接收**，`unknown_event=true`；按事件名计数，每天经 TECH 渠道告警一次〔L1〕
**And** 限流以匿名 id 为主，IP 只设很宽的兜底阈值（取配置）；429 响应带 `Retry-After`；IP 不写进任何表或日志〔L1〕
**And** 队列按估算字节数设上限（默认 64 MB，取配置），满了返回 429 + `Retry-After`；管道停用时返回 503〔L1〕
**And** `IngestFlusher` 每 1 秒或攒够 500 条批量写 `events_raw`；`received_at` 取服务端时间；`source=APP`〔L1〕
**And** 优雅停机时先停接收、写完队列再退出；集成测试验证停机前已 202 的事件全部落库〔L1〕
**And** 本接口从全接口请求日志里排除；任何日志只打条数和 `messageId`〔L1〕
**And** 本 story 内用户标识原样入库并标 `identity_unverified=true`，身份校验由 1.5 收紧〔L1〕
**And** 在本地按生产参数（`shared_buffers=128MB`、`max_connections=100`）起的 postgres 上以 10 倍现量压测，业务接口 P95 增幅 ≤10%（NFR-1），同时观察队列满载时的堆内存；结果记进 Completion Notes。**不在 stag 压**（与生产共用实例）〔L2〕

### Story 1.5：身份校验与关联表

As a 数据使用者,
I want 每条事件的用户归属可信，且留下完整的登录历史,
So that 按「人」统计时不被冒充，也能还原「当时是谁」。

**Acceptance Criteria:**

**Given** 迁移建 `identity_links`（只追加；匿名 id、`user_id`、`user_key`、`linked_at`；主键（匿名 id, `linked_at`））**When** 带 JWT 的请求里有 `user_identified` **Then** 追加一行，同一设备反复登录同一账号也每次记一行〔L1〕
**And** `IdentityResolver` 逐条判定：没有用户标识的按匿名处理；等于 token 用户的通过；不等于 token 用户的查关联表，有关联则通过，否则去掉用户标识并标 `identity_unverified`；不带 JWT 但带用户标识的同样走关联表〔L0〕
**And** 通过校验的 App 事件，`user_id` 取 token 用户或关联表里对应的用户〔L1〕
**And** 校验不通过的事件计数并打一条汇总日志（不含标识）〔L0〕
**And** 同一设备先登 A、再登 B、再登回 A：关联表三行都在；A 在登出前产生、换号后才补发的事件仍归 A〔L1〕
**And** 迁移给 `analytics_reader` 授权 `identity_links`〔L1〕

### Story 1.6：明细层转换

As a 数据使用者,
I want 收到的事件在 10 分钟内去重、校正后进入可查的明细层,
So that AI 当天就能查到干净的明细。

**Acceptance Criteria:**

**Given** 迁移建 `pipeline_progress` 与 `events_detail`（按 `event_date` WIB 日分区，**无 DEFAULT**），唯一键（`event_date`, `message_id`），在 `user_id`、`anonymous_id`、`event` 上建索引；`events_detail` 授权给 `analytics_reader`，`pipeline_progress` 不授权 **Then** 列只含架构 D-4 规定的解析列，属性 JSONB 只保留清单登记的字段〔L1〕
**And** 初始建好今天前后各 7 天的分区，再由每日维护任务滚动预建〔L1〕
**And** `DetailBuilder` 每分钟运行（advisory lock），按 `received_at` 增量处理，进度只推进到 `now() − 2 分钟`，进度与数据在同一事务提交；收到后 10 分钟内可查（FR-13）〔L1〕
**And** `unknown_event=true` 的行不转入明细〔L1〕
**And** `event_time` = `client_ts` +（`received_at` − `sent_at`）；`event_date` 由 `event_time` 换成 WIB 日期；`client_ts` 原样保留〔L0〕
**And** 校正后晚于 `received_at` 超过 5 秒、或早于 `received_at` 超过 7 天的，标 `is_suspicious`；可疑事件和日期超出已建分区的事件，`event_date` 取 `received_at` 的 WIB 日期〔L0〕
**And** 批内先按 `message_id` `DISTINCT ON`；写入前查 `event_date` 前后各 1 天分区里是否已有同一 `message_id`；唯一键 `ON CONFLICT DO NOTHING` 兜底；集成测试覆盖「重试跨午夜」「同批重复」两种场景〔L1〕
**And** 生产标记：上下文里的模拟器 / TestFlight / 侧载标记，以及 `role != 'USER'` 或 `account_type != 'REAL'` 的用户，都记为非生产（用户角色按批查业务库，只读窄查询）〔L1〕

### Story 1.7：明细分区、原始层清理与重算

As a Dai,
I want 过期数据按期删除，口径修正后能从原始层重算明细,
So that 磁盘可控，算错了也能补救。

**Acceptance Criteria:**

**Given** `PartitionMaintenanceJob` 扩展到明细层 **Then** 按 WIB 日期预建未来 7 天分区，删除超过 3 个月的分区〔L1〕
**And** 原始层删除超过 35 天的分区，前提是 `pipeline_progress` 已越过该日；进度落后时不删并告警〔L1〕
**And** 提供按日期区间从原始层重算明细的入口（只限原始层保留期内；对补登记的事件同样适用），重算结果与首次处理一致〔L1〕
**And** 单测覆盖「哪些分区该删、哪些不该删」的判定〔L0〕

### Story 1.8：注销擦除

As a 注销的用户,
I want 我的埋点数据在 24 小时内与我脱钩,
So that 注销承诺在埋点库里也成立。

**Acceptance Criteria:**

**Given** **业务库**迁移建 `analytics_erasure_outbox`（`user_id`、`user_key`、`requested_at`、`done_at`）**When** `AccountDeletionService` 状态机执行注销 **Then** 新增的 `AnalyticsErasureParticipant` 在注销同一事务内写入一行，不访问埋点库；埋点库故障不影响注销完成（Dai 2026-11-12 拍板）〔L1〕
**And** `ErasureJob` 每晚固定时刻运行（早于汇总，advisory lock，任务内 `SET LOCAL statement_timeout` 放宽）：从业务库读出未完成的行，在埋点库删除这些用户的 `identity_links`；把 `events_detail`、`events_raw` 中的 `user_id` / `user_key` 置空，并删掉清单里标为用户标识的属性键；原始层按分区逐个处理；完成后回写业务库 `done_at`；埋点库不可用时跳过并告警，下次再处理〔L1〕
**And** 重复运行安全；已处理的请求不再扫描〔L1〕
**And** 擦除后匿名 id 上的事件仍保留（变成匿名事件）；汇总层不动〔L1〕
**And** 集成测试：注销 → 跑擦除任务 → 埋点库里查不到该 `user_id` / `user_key`，属性里也没有该用户的标识〔L1〕

### Story 1.9：服务端事件改走自建

As a 运营和产品,
I want 支付、优惠券推送、分享页这些服务端事件进自建库,
So that 后端一上线就有服务端数据。

**Acceptance Criteria:**

**Given** `AnalyticsClient` 新增 `captureForUser(long userId, String event, Map props)` 与 `captureForVisitor(String visitorId, String event, Map props)`，原 `capture(distinctId, …)` 标为废弃 **Then** 10 个类 17 处调用点改用新方法（机械改动）〔L0〕
**And** 新增 `SelfHostedAnalyticsClient`：`captureForUser` 写 `source=SERVER`、`user_id` 直接给出并视为已校验；`captureForVisitor` 写 `source=H5`、匿名 id 用访客 id，不进关联表〔L1〕
**And** 队列满、熔断或管道停用时，服务端事件丢弃并计数，不抛异常、不影响业务〔L1〕
**And** 删除 `PostHogAnalyticsClient`、`analytics.posthog.*` 配置和 `POSTHOG_*` 环境变量占位〔L0〕
**And** `AnalyticsEventGuard` 等现有防护照常生效〔L0〕
**And** stag 上触发一次测试支付，`shop_payment_paid` 进入明细层，与当天 `paid_at` 不为空的订单数一致〔L2〕

### Story 1.10：存储水位与停写熔断

As a Dai,
I want 埋点库占用每天可见、超线告警、再超线自动停写,
So that 埋点永远不会把共用的数据库磁盘写满。

**Acceptance Criteria:**

**Given** 迁移建 `storage_watermarks`，`StorageWatermarkJob` 每天运行 **Then** 记录本环境埋点库占用、月事件量和月增长〔L1〕
**And** 超过 40 GB 经 TECH 渠道告警；月事件量 ≥ 6,000 万条（研究估算 10 倍区间 3,000–7,500 万条的中位）或占用增速按月外推将在 3 个月内触及 40 GB 时，告警文案提示「评估切回第三方」（FR-16）〔L1〕
**And** `IngestCircuitBreaker` 每 5 分钟检查一次，超过 50 GB 后接收端返回 429，降回阈值以下自动恢复；熔断和恢复都告警〔L1〕
**And** 阈值取配置，单测覆盖告警 / 熔断 / 恢复三种状态切换〔L0〕
**And** docs/runbooks 补一段主机整盘余量 cron 检查的说明（运维项，不在后端实现）〔L0〕

---

## Epic 2：App 切到自建上报（随 V1.5.0）

新版 App 的全部行为事件进入自建库，带身份和生产标记，生命周期与页面浏览口径不变。本 epic 只改 App；联调依赖 Epic 1 已上 stag，发版依赖 Epic 1 已上生产。L0 = `flutter analyze` / `flutter test`；L1 = Android 模拟器装 **profile 或 release 包**连 stag 后端（debug 包不上报），查 stag 埋点库；L2 = 真机 / TestFlight / 商店包。

### Story 2.1：自建上报器、本地队列与匿名 id

As a 产品和运营,
I want App 的事件可靠地送到自建后端，断网和被杀都不丢,
So that 埋点数据完整，又不拖慢 App。

**Acceptance Criteria:**

**Given** 新增 `anonymous_id.dart` **Then** 首次启动生成 UUIDv4，存在应用支持目录的 `analytics/state.json`（不放 secure storage，也不放 `shared_preferences`）；卸载前不变；读取早于任何事件入队〔L0〕
**And** 本地队列文件也放 `analytics/` 目录；Android 备份规则（`fullBackupContent` 与 `dataExtractionRules`）排除 `analytics/` 目录，其余数据照常备份（Dai 2026-11-12 拍板）〔L0〕
**And** 新增 `self_hosted_sink.dart`、`event_queue_file.dart`；业务代码调用 `Analytics.capture` / `capturePurchase` / `buttonTapped` 等时，门面签名不变，事件经现有白名单和 `scrub()` 后进本地队列；金额属性允许保留〔L0〕
**And** 每条事件带 `messageId`（UUIDv4）、`timestamp`、`anonymousId`；发送时填 `sentAt`；请求体外层 camelCase、属性 snake_case〔L0〕
**And** 每 20 条或 30 秒发一批（先到者为准）；切到后台立即发；网络恢复后补发；冷启动时补发上次未发完的〔L0〕
**And** 队列存成 `path_provider` 下的文件，读写串行；上限 1,000 条或 7 天，超出丢最旧〔L0〕
**And** 返回码处理：202 删批次；429 按 `Retry-After`，缺失时按默认退避；503 / 5xx / 断网指数退避；400 / 413 整批丢弃〔L0〕
**And** debug 包不发送，`debugCaptureSink` 测试钩子照常可用；上报异常一律吞掉，UI 代码不 `await` 埋点调用〔L0〕
**And** 模拟器上产生 50 条事件 → 断网 → 杀进程 → 重启联网，stag 明细层能查到全部 50 条且无重复〔L1〕

### Story 2.2：身份绑定

As a 数据使用者,
I want 每条事件带上发生时的身份，登录前后能连成同一个人,
So that 按「人」统计准确，换号补发也不会记错人。

**Acceptance Criteria:**

**Given** 事件入队 **Then** 写入当时的匿名 id 和用户标识（`distinctIdFor`）；之后登出或换号，已入队事件的身份不变〔L0〕
**And** `identifyUser` 上报一条 `user_identified`，**不带** `internal_user_id` 等明文标识；`reset()` 只清用户标识，不重置匿名 id〔L0〕
**And** 切换账号（`app.dart` 的 reset → identify 序列）前，先尝试把队列里上一账号的事件发出去，失败不阻塞切换〔L0〕
**And** 请求有 JWT 时带上〔L0〕
**And** 模拟器上先登 A 再登 B，stag 关联表有两行，A 的事件不记到 B 头上〔L1〕

### Story 2.3：生命周期事件

As a 市场和产品,
I want 新装、升级、打开三个事件与以前口径一致,
So that 安装量、留存、日活能接着算。

**Acceptance Criteria:**

**Given** 新增 `lifecycle_events.dart` **When** 设备上既没有自建安装标记、也没有旧版本地数据 **Then** 首次启动报 `Application Installed` 并把安装标记与当前版本号写进 `analytics/state.json`（不进备份）〔L0〕
**And** 有旧版本地数据但没有安装标记的设备，首次启动报 `Application Updated`，**不报 Installed**；之后版本号每次变化也报 Updated（带前后版本）〔L0〕
**And** 「旧版本地数据」的判据键在 story 内核实并写明：必须是旧版每台设备首启必写、未登录和未完成引导的用户也有的键〔L0〕
**And** 每次冷启动和每次从后台回到前台都报 `Application Opened`，属性 `from_background` 区分；静默推送唤醒不报〔L0〕
**And** 判定逻辑抽成纯函数，单测覆盖新装、升级、同版本重启、版本再变四种情况〔L0〕
**And** 模拟器上：全新安装 → Installed + Opened；先装旧版 APK 再覆盖装新版 → 只有 Updated + Opened〔L1〕

### Story 2.4：页面浏览事件

As a 电商和产品,
I want 页面浏览照旧记录，页面名不变,
So that Toko 浏览、漏斗第一步等指标不断档。

**Acceptance Criteria:**

**Given** 自有路由观察者取代 `app_router.dart` 里的 `PosthogObserver()` **Then** 路由 push 产生 `screen_viewed`（属性 `screen` = 现有页面名，如 `toko_page`）〔L0〕
**And** `Analytics.screen()` 现有调用（底部 Tab `goBranch` 切换、冷启动落地页补报）同样产生 `screen_viewed`〔L0〕
**And** 同一页面不因路由重建重复上报（沿用现有去重逻辑）〔L0〕
**And** 模拟器上依次切四个底部 Tab 并进入商品详情，stag 明细层的页面名与 PostHog 时期一致〔L1〕

### Story 2.5：环境与生产标记

As a 数据使用者,
I want 每条事件带足以判定「是否生产数据」的上下文,
So that 模拟器、TestFlight、侧载的数据不混进统计。

**Acceptance Criteria:**

**Given** 新增 `production_flags.dart` **Then** 每条事件的上下文带 App 版本、构建号、平台、系统版本、机型〔L0〕
**And** `isPhysicalDevice=false` 标模拟器；iOS `installerStore` 为 `com.apple.testflight` 标 TestFlight、`com.apple.simulator` 标模拟器、null 算生产；Android 只有 `com.android.vending` 算商店，其余（含 null）标侧载〔L0〕
**And** 标记在启动时算一次并缓存，单测覆盖每种组合〔L0〕
**And** stag 包（`PETGO_API_BASE_URL` 指向 stag）的事件只发往 stag 后端，生产埋点库查不到（FR-6）〔L1〕
**And** 模拟器事件在 stag 明细层被标为非生产〔L1〕
**And** TestFlight 包的 `installerStore` 实测为 `com.apple.testflight`，结果记进 Completion Notes；不符合时记为已知偏差〔L2〕

### Story 2.6：移除 PostHog 与事件名比对

As a 开发者,
I want App 里不再有 PostHog，实际发出的事件名都在后端清单里,
So that 新版不发无用请求，改名在 CI 就能发现。

**Acceptance Criteria:**

**Given** 移除 `posthog_flutter` 依赖、`POSTHOG_KEY` / `POSTHOG_HOST` dart-define 和全部 `Posthog()` 调用 **Then** `flutter analyze` 干净，`main.dart` 里的 PostHog 启动注释同步清理〔L0〕
**And** Android 侧为 PostHog 加的构建覆盖项，确认无其他依赖需要后一并移除，否则保留并注明原因〔L0〕
**And** `test/core/analytics/event_catalog_sync_test.dart` 扫描 `lib/` 下 `Analytics.capture(` / `capturePurchase` / `buttonTapped` 等调用的事件名字符串字面量和事件名常量，与 `../petgo-backend/src/main/resources/analytics/event-catalog.yaml` 比对；三元表达式两侧都要识别；动态拼接的名字（如 `'${tabName}_page'`）列白名单；缺项时测试失败并列出〔L0〕
**And** 模拟器上走一遍主流程，抓包确认没有任何发往 posthog.com 的请求〔L1〕

---

## Epic 3：补齐团队指标需要的事件（随 V1.5.0）

运营位、推送打开、购物车、漏斗这几类团队指标都有可算的事件。改动以 App 为主；新事件已在 Story 1.2 预登记，若属性有变化同步改 `event-catalog.yaml`（后端段只改 yaml，需随后端部署到 stag 后 L1 才能在明细层看到属性）。与 Epic 2 改的是不同文件，可以并行；L1 需要 Epic 2 的上报器。

### Story 3.1：运营位曝光与点击核对

As a 电商和内容运营,
I want 每个弹窗和 banner 的曝光、点击都能按运营位统计,
So that 能算点击率和 7 天带单。

**Acceptance Criteria:**

**Given** 现有 `ops_popup_shown` / `ops_popup_tapped`（`ops_popup_coordinator.dart`）、`toko_banner_shown` / `toko_banner_tapped`（`shop_banner_carousel.dart`、`toko_page_v3.dart`）和新人券弹窗（`toko_newbie_coupon_popup_shown` 等）**Then** 三类事件统一带 `placement_type`（`ops_popup` / `toko_banner` / `newbie_coupon`）和 `placement_token`（运营位或活动的 public token），原有属性保留〔L0〕
**And** 曝光统一按「露出超过 50% 计一次」，与 Toko 横幅现有定义一致；不一致的改齐〔L0〕
**And** 核对结果（运营位清单、事件名、token 来源表）写进 `metric-definitions.yaml` 的 S5 条目〔L0〕
**And** 模拟器上展示并点击一次弹窗和横幅，stag 明细层能按 `placement_token` 查到曝光和点击各一条〔L1〕

### Story 3.2：推送打开统一事件

As a 内容运营,
I want 任何推送被点开都记成同一个事件并带类型,
So that 能按单条运营推送算打开率、按类型统计系统推送。

**Acceptance Criteria:**

**Given** `core/push/push_service.dart` 的推送点击处理 **When** 任一推送被点开 **Then** 上报 `push_opened`，`push_type` = 载荷里的 `type`〔L0〕
**And** 运营通知另带 `campaign_token` = 载荷 `targetRef`（活动 public_token）〔L0〕
**And** 现有 `ops_notification_opened`、`toko_coupon_expiry_push_opened` 保留；通知中心列表点击不报 `push_opened`〔L0〕
**And** 载荷缺 `type` 时 `push_type` 记为 `unknown`，不丢事件〔L0〕
**And** App 被杀后点推送冷启动时，`push_opened` 在匿名 id 就绪后入队，不丢、不抛异常〔L0〕
**And** 真机上分别点开一条运营推送和一条点赞推送（含一次冷启动），stag 明细层各有一条 `push_opened`，类型正确〔L2〕

### Story 3.3：购物车事件补全

As a 电商,
I want 加购、移出、改数量都有事件,
So that 能算商品在购物车里停留多久、为什么没转化（电商 #7）。

**Acceptance Criteria:**

**Given** `toko_add_to_cart_tapped`（`product_detail_page_v3.dart`、`sku_picker_sheet.dart`）**Then** 补齐属性：`product_token`、`sku_token`、数量、单价〔L0〕
**And** 新增 `toko_cart_item_removed`：手动删除或清空失效商品时按商品各报一次，带 `product_token`、`sku_token`、数量、`reason`（`manual` / `invalid_cleared`）〔L0〕
**And** 新增 `toko_cart_quantity_changed`：带 `product_token`、`sku_token`、修改前后数量〔L0〕
**And** 下单成功后商品离开购物车，不报 `toko_cart_item_removed`〔L0〕
**And** 模拟器上加购 → 改数量 → 删除，stag 明细层依次有三条事件，属性齐全〔L1〕

### Story 3.4：漏斗与引导事件核对

As a 电商和产品,
I want 团队指标引用的事件名都真实存在、含义一致,
So that 漏斗和留存不会因为事件名对不上而算错。

**Acceptance Criteria:**

**Given** 电商 #6 漏斗各步（`toko_page` 浏览、`toko_product_shown`、`toko_product_detail_viewed`、`toko_buy_now_tapped` / `toko_add_to_cart_tapped`、`toko_order_submitted`、`toko_order_pay_tapped`）、产品 #1 `pet_profile_create_submitted`、产品 #5 `onboarding_completed` **Then** 逐个核对触发时机与团队文档定义一致，结论写进 `metric-definitions.yaml` 对应条目〔L0〕
**And** 不一致的以代码为准修正口径条目并写变更记录；需告知团队的列进 Completion Notes〔L0〕
**And** 漏斗各步事件都带 `product_token`（商品维度能关联），缺的补上；订单详情的行由后端补 `productToken` / `skuToken` 字段（只增不改）〔L0 / L1〕
**And** `onboarding_completed` 改为引导请求成功后再上报（请求失败不报）（Dai 2026-11-12 拍板）〔L0〕
**And** 其余事件不改触发时机；确需改动的单列出来交 Dai 拍板〔L0〕

---

## Epic 4：指标汇总、日活对账与 AI 查数（批次 ②）

团队每天早上能向 AI 问到前一天的 B、C 类指标，口径一致；日活对账每天落库，作为切换日活来源的依据。本 epic 只改后端（加 AI 查数接入配置）。依赖 Epic 1 的明细层；Epic 2、3 上线后指标才完整。

**通用规则（每个 story 都适用）**
- 只算生产数据且 `is_suspicious=false`；用户维度只算 `role = 'USER'` 且 `account_type = 'REAL'`。
- 按「人」去重：带用户标识的事件归该用户；匿名事件按关联表归属（事件前有关联 → 最近一次；之前没有、之后有 → 第一个；从未关联 → 匿名 id）。
- WIB 自然日；周 = 周一至周日；月 = WIB 自然月。
- 每日去重人数不跨天相加：凡「统计周期内去重人数」的指标，同时写日表、周表、月表（周期结束次日计算并回补）；比率类月指标（取每日比率平均）由日表现算。
- Toko 已付款 = `shop_orders.paid_at IS NOT NULL`，按 `paid_at` 的 WIB 日期；退款 / 取消仍算。业务库关联一律用 public token，读业务库只做窄查询，不做跨库 SQL。
- 汇总表不含 `user_id`、匿名 id 等可识别个人的字段；每张新表在迁移里授权给 `analytics_reader`。

### Story 4.1：汇总框架与活跃、安装、注册

As a 运营和市场,
I want 每天早上查到前一天的日活、安装数和注册数,
So that 不用等人手工拉数。

**Acceptance Criteria:**

**Given** 迁移建 `agg_progress`、`agg_daily_activity`、`agg_daily_install` **When** `RollupJob` 在 WIB 03:00 运行（advisory lock，晚于 `ErasureJob`，任务内放宽 `statement_timeout`）**Then** 按各指标族的 `agg_progress` 续算，每次重算最近 3 个 WIB 日（upsert，幂等）〔L1〕
**And** 汇总 SQL 放在 `resources/analytics/rollup/<指标族>.sql`，日期作为参数传入〔L0〕
**And** 写数据和推进 `agg_progress` 在同一事务；失败经 TECH 渠道告警，下次运行自动补上缺的日期〔L1〕
**And** 提供按日期区间手动重跑的入口（只限明细层保留期内）〔L1〕
**And** 身份归属规则抽成一个共用的 SQL 视图或函数，供所有指标族复用〔L1〕
**And** `agg_daily_activity`：自建日活（含游客，按人）、登录用户日活，分平台〔L1〕
**And** `agg_daily_install`：首次 `Application Installed` 的新装数（不含升级，分平台）、首次 `signup_succeeded` 的注册数（S2、S3）〔L1〕
**And** 构造数据集测试：跨午夜事件归 WIB 日；「安装（未登录）→ 注册 → 次日打开」算同一个人；登出后的事件归上一个账号；非生产、可疑、兽医账号被排除〔L1〕

### Story 4.2：GA4 快照与日活对账

As a Dai,
I want 每天看到同口径的自建日活与 GA4 日活差值和新版覆盖率,
So that 有依据决定什么时候改用自建日活。

**Acceptance Criteria:**

**Given** `Ga4ActiveUsersClient` 新增按 `appVersion` 维度拉取的方法 **Then** `Ga4DailySnapshotJob` 每天把 GA4 全版本日活和 V1.5.0+ 日活写入 `ga4_daily_snapshot`，**每次回拉最近 3 天覆盖旧值**〔L1〕
**And** GA4 拉取失败告警，下次运行补拉；stag 无 GA4 凭证时跳过并记日志〔L1〕
**And** `agg_daily_dau_reconcile` 每天一行：自建对账日活（按设备 / 匿名 id 去重、不做生产排除、只含 App 来源）、GA4 V1.5.0+ 日活、差值百分比 =（自建对账 − GA4 V1.5.0+）÷ GA4 V1.5.0+、新版覆盖率（V1.5.0+ ÷ 全版本，注明近似）、自建日活（含游客，按人）、登录用户日活、状态〔L1〕
**And** 覆盖率首次 ≥90% 的那天为观察期起点，之后 4 周内 |差值| ≤10% 标「正常」，否则「异常」；观察期外为「未开始」或「已结束」（FR-25）〔L0〕
**And** 对账结果只存库，不推飞书〔L0〕
**And** 日活来源开关 `analytics.dau-source`（默认 `GA4`），供 4.7 使用〔L0〕

### Story 4.3：留存与引导转化

As a 市场和产品,
I want 查到 D1 / D3 / D7 留存、新用户 W1 留存和安装后建档比例,
So that 能评估投放质量和新手引导。

**Acceptance Criteria:**

**Given** 迁移建 `agg_cohort_retention` **Then** 以安装日为组（首次 `Application Installed`，按归属规则归到人），记组人数和第 X+1、X+3、X+7 天再次 `Application Opened` 的人数；未满 N 天的组该列为空（市场 #1–#3）〔L1〕
**And** 以 `onboarding_completed` 日为组，记组人数和其后第 7–13 天内任意一天打开过的人数（产品 #5）〔L1〕
**And** 按月（`agg_monthly_install_profile`）记当月新装人数中有 `pet_added`（建档成功）的人数（产品 #1，不用点击即报的 `pet_profile_create_submitted`）（Dai 2026-11-12 拍板）〔L1〕
**And** 每次运行回补仍在观察窗口内的组（最长 13 天）〔L1〕
**And** 按周加权的留存由组表现算，口径写进 `metric-definitions.yaml`〔L0〕

### Story 4.4：运营位与推送效果

As a 电商和内容运营,
I want 每个弹窗、横幅、推送的展示、点击、打开和带单都能查,
So that 知道哪个运营位有效。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_placement`、`agg_weekly_placement`、`agg_monthly_placement`、`agg_daily_push`、`agg_weekly_push`、`agg_monthly_push`（推送打开人数也是周期去重指标）**Then** 按 `placement_type` + `placement_token` 记曝光次数、点击次数、点击人数（周表、月表的点击人数为周期内去重）〔L1〕
**And** 每个运营位记「点击后 7 天内产生的已付款 Toko 订单数」：读业务库已付款订单（用户 + `paid_at`），与点击按用户和时间匹配，结果写回埋点库（S5 带单，FR-22）〔L1〕
**And** 运营位是否推广 Toko：读业务库运营位配置的跳转目标，作为维度写进汇总表（FR-17 第 3 条）〔L1〕
**And** 运营推送按 `campaign_token` 记 `ops_notification_campaigns` 的 `planned_count` / `recipient_count`（口径注明「接收 = 写入通知中心人数，近似」）和 `push_opened` 打开人数；券过期推送的发送数取服务端事件 `toko_coupon_expiry_push_sent`；其他系统推送按 `push_type` 只记打开人数，并在口径中注明（FR-23）〔L1〕
**And** 本 story 内勘察各系统推送是否有发送记录（A13 / Q4），结论写进口径条目〔L0〕
**And** 带单数与直接查业务库的已付款订单抽样一致〔L1〕

### Story 4.5：Toko 浏览、漏斗与加购转化

As a 电商和产品,
I want 查到 Toko 打开人数、漏斗各步人数和加购转化,
So that 知道用户在哪一步流失。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_toko`、`agg_weekly_toko`、`agg_monthly_toko` **Then** 记浏览 `toko_page` 的人数（日 / 周 / 月去重，电商 #4）〔L1〕
**And** 漏斗：以进入第一步的人为组，记 7 天内按顺序经过每一步的人数；最后一步「支付成功」取业务库已付款订单；日表、周表、月表各一份（电商 #6）〔L1〕
**And** 同时记已提交订单数与已付款订单数（产品 #2）〔L1〕
**And** 加购转化：周期内加购的人中，加购后 7 天内完成 Toko 付款的人数（日 / 周 / 月，电商 #5）〔L1〕
**And** 依赖 7 天窗口的指标，每次运行回补最近 7 天〔L1〕

### Story 4.6：购物车与商品

As a 电商,
I want 查到购物车停留情况和最有潜力、售罄但受关注的商品,
So that 知道哪些商品该推、哪些该补货。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_cart`、`agg_daily_product` **Then** 购物车**只存聚合**：按商品、按天记加购数、移出数、已购买数、仍在车里数、停留时长分布、购物车数、每车商品数、购物车总价值（单价 × 数量）；不存用户（电商 #7）〔L1〕
**And** 「已购买」以业务库订单行（按 `sku_token` 关联）为准；在其他设备或后台清空的购物车行无法观测，口径注明〔L1〕
**And** 「是哪个用户」的逐件明细只在明细层 3 个月内可查，口径条目写明并告知电商〔L0〕
**And** 商品维度每天记详情浏览次数、加购次数，以及读业务库得到的库存与近 30 天已付款件数（电商 #8、#9）〔L1〕

### Story 4.7：以日活为分母的比率指标

As a 电商、产品和内容运营,
I want 查到电商交易占日活、付费用户占日活、新用户与回访用户,
So that 这几项能和日活放在同一口径下比较。

**Acceptance Criteria:**

**Given** 迁移建 `agg_daily_ratio` **Then** 每天记有已付款 Toko 订单的人数（S4 分子）、有非 Toko 付费解锁的人数（产品 #3 分子，判据按各业务表逐项列出；不含 PawCoin 充值和送礼，另存两者人数作参照（Dai 2026-11-12 拍板））、当天注册人数（内容 #1），分子均读业务库〔L1〕
**And** 分母日活按 `analytics.dau-source` 取：`GA4` 时取 GA4 全版本日活，`SELF` 时取自建日活（含游客，按人）；表里同时存分母来源〔L1〕
**And** 切到 `SELF` 时在 `metric-definitions.yaml` 的 S1 条目写切换日期〔L0〕
**And** 回访用户 = 当天日活 − 当天新用户；月比率取每日比率的平均，写进口径〔L0〕

### Story 4.8：口径文档补全与 AI 查数接入

As a 团队成员,
I want 向 AI 问任何 B、C 类指标都能按统一口径得到答案,
So that 不同人问同一个数，得到同一个结果。

**Acceptance Criteria:**

**Given** `metric-definitions.yaml` **Then** B、C 类每个指标都有条目：团队编号、定义、来源表、排除规则、已知偏差（卸载重装算新装、iOS `installerStore` 为 null、员工与测试账号、晚到超过 3 天不回算、V1.5.0 之前无数据、接收数为近似、购物车跨设备不可观测等）、SQL 文件位置、变更记录（FR-26）〔L0〕
**And** CI 校验每个汇总表都至少被一个条目引用〔L0〕
**And** 启动时同步进 `metric_definitions`〔L1〕
**And** AI 查数服务新增埋点库连接，使用 `analytics_reader`（只读、3 个连接、30 秒超时）；用该账号查 `events_raw`、`events_raw_p<今天>`、`erasure_requests`、`pipeline_progress` 都返回 permission denied（FR-27）〔L1〕
**And** AI 查数的说明文档写明：先查 `metric_definitions`，按口径计算；周期去重用周表 / 月表，不把日表相加；跨库问题只用预拼好的汇总表〔L0〕
**And** 用 5 个典型问题（昨日日活、上周 D1 留存、某弹窗上周点击人数、Toko 漏斗、某推送打开率）在 stag 实测，答案与直接查表一致，记进 Completion Notes〔L2〕

### Story 4.9：埋点管道加固（2026-11-13 追加）

As a 后端负责人,
I want 写库线程意外终止、埋点库卡住、配置写错时都能被发现且不拖垮主业务,
So that 埋点不会静默丢数据，也不会让后端起不来或让 App 请求挂住。

来源：云端 28 个 story 完成后报告的已知风险。须在合入 dev 前完成。

**Given** 写库线程遇到任意 Throwable **Then** 不退出，告警后继续；看门狗发现线程死掉就重启并告警，发现停滞就告警〔L0〕
**And** 请求线程上的埋点库查询用单独的 JdbcTemplate，超时 2 秒，超时按「查不到」处理；后台 job 不受影响〔L0 / L1〕
**And** 4.1 汇总配置、1.10 存储配置以及所有 analytics 配置项非法时，后端照常启动，只停用对应功能并告警（D-12）〔L0〕

详细验收标准见 `_bmad-output/implementation-artifacts/analytics-pipeline/4-9-埋点管道加固.md`
