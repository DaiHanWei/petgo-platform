---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-11-12'
inputDocuments:
  - _bmad-output/planning-artifacts/analytics-pipeline/prd.md
  - _bmad-output/planning-artifacts/analytics-pipeline/decisions.md
  - _bmad-output/planning-artifacts/analytics-pipeline/.decision-log.md
  - _bmad-output/planning-artifacts/analytics-pipeline/research/technical-analytics-pipeline-research-2026-11-11.md
  - _bmad-output/planning-artifacts/analytics-pipeline/reconcile-decisions-research.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-11-11'
scope: 'analytics-pipeline 主题架构 delta（基线 _bmad-output/planning-artifacts/architecture.md）'
---

# 架构决策文档 · 自建埋点（analytics-pipeline）

_对跨版本基线 `_bmad-output/planning-artifacts/architecture.md` 的 delta。按步骤协作逐节追加。_

## 项目上下文分析

### 需求概览

**功能需求（27 条 FR，分两批）**

| 组件 | 涉及 FR | 架构含义 |
|---|---|---|
| ① App 采集端 | FR-1–6 | 替换 PostHog SDK：本地队列、攒批上报、离线补发、匿名 id；脱敏规则沿用现有的 |
| ② 接收模块 | FR-7–10 | 放在现有 Spring Boot 内部；鉴权或匿名都能写入；限流；按 `messageId` 去重；立即返回成功，进内存队列后每 500 条批量写库 |
| ③ 存储分层 | FR-11–16 | 独立的埋点库，三层：原始 35 天、明细 3 个月、汇总长期保留；按天分区；注销时匿名化；磁盘水位告警 |
| ④ 汇总任务 | FR-21–26 | 每天 WIB 凌晨跑前一天（T+1）；按进度续算，可重复跑；C 类指标在任务里分别读两个库，结果写回埋点库；GA4 日活每天落库用于对账 |
| ⑤ AI 查数接入 | FR-27 | 只读账号；只能连 1 个库，最多 3 个连接，查询 30 秒超时 |

FR-17–20（补埋点缺口）跨①和④两个组件。

**非功能需求**：业务接口 P95 增幅 ≤10%；量级按 10 倍余量设计；不新增中间件；埋点数据和业务数据隔离。

**规模与复杂度**

- 主领域：后端数据管道，加 Flutter 采集端
- 复杂度：中。现量每月 300–750 万条，按每月 3,000–7,500 万条设计
- 架构组件：5 个

### 技术约束与依赖

- **同一台机器、同一个 PG 实例**：`petgo-postgres` 是 PG16，没有任何扩展，`max_connections=100`、`shared_buffers=128MB`，生产和 stag 共用。磁盘余 131 GB，同机其他服务也在用这块盘。
- **护栏**：
  - 禁止 MQ、缓存层、新中间件和 PG 扩展。
  - 异步只用 `@Async` + DB 状态机。内存队列算破例，按 D2 记录在案。
  - Flyway 用时间戳版本号，`ddl-auto=validate`。
- **需要第二个数据源**，并且有独立的 Flyway 历史。CI 的 `flyway-guard` 要能覆盖第二个迁移目录。
- **可以复用的现有组件**：`RedisRateLimiter`、advisory lock 的定时任务写法、`Ga4ActiveUsersClient`、注销级联服务、`AnonymousVisitorId`。
- **服务端事件**：现在经 `AnalyticsClient`（目前只有 PostHog 一个实现）发出，约 12 处调用，包括支付、优惠券推送、分享页 / 名片页等。新增一个自建实现，直接写进接收队列。
- **后端是单实例部署**：内存队列和 `@Scheduled` 都依赖这一点。
- **内存队列在部署或重启时有丢失窗口**：App 已经收到成功，不会重发。处理方式见核心决策。
- **H5 匿名访客**（`AnonymousVisitorId`）不是 App 设备：不进匿名 id 关联表，不算日活。
- **PostHog 已停用**（PRD §1）：V1.5.0 之前没有行为事件数据可用，这段空窗不补（PRD §5 不迁移历史）。旧版 App 和后端仍带着 PostHog 代码，发出的请求不再有人接收。

### 横切关注点

1. **身份**：匿名 id 和用户的关联表；身份在事件发生时绑定；处理登出和跨账号补发（A3、A4、A7）。
2. **只算生产数据**：stag 包、模拟器、侧载 / TestFlight、内部员工都要排除；判定放在哪一层（A5、Q5）。
3. **时间边界**：时间一律存 UTC，分区按 UTC 天切，口径按 WIB 天算。汇总读相邻两个分区；删分区必须等对应那天的汇总完成之后。
4. **资源隔离**：独立的小连接池（A8）、账号级别的 `statement_timeout`、汇总按天分块并放在低峰期跑。
5. **生产和 stag 两套**：stag 用独立的埋点库，配额更低。
6. **故障隔离（只丢埋点，不伤业务）**：
   - 磁盘水位过低时，接收端主动停写。
   - 内存队列设上限，满了返回 429。
   - 提前建好 7 天的分区，再加一个 DEFAULT 分区兜底。
7. **数据新鲜度与可观测**：汇总进度表；查数时能看到「数据截至哪天」；汇总失败要告警。
8. **事件清单作为唯一来源**：事件名和属性只维护一份清单，App 上报和汇总 SQL 都对照它，防止改名后数据悄悄归零。

### 上线前验证清单

- 按 App 现有事件代码构造典型事件，在 stag 写入后用 `pg_column_size` 测单条大小，对应 A11。
- 事件量没有历史数据可以校准（PostHog 已停用）：先按研究估算上线，上线一周后用实测量回看水位和告警阈值。
- 核实 `flyway-guard` 能覆盖第二个迁移目录（已核实：`MIG_DIR` 写死，需扩展）。
- iOS TestFlight 真包验证 `installerStore`。
- stag 用 10 倍量压测接收端，对比业务接口 P95（NFR ≤10%）。

## 底座评估（存量项目，不引入脚手架）

### 主技术域

后端数据管道（Spring Boot，放在现有服务内部），加 Flutter 采集端。版本以基线为准：Spring Boot 4.0.6 / Java 21，Flutter 3.44。

### 选项

- **新建独立服务或现成产品**（PostHog 自托管、Plausible 等）：不采用，违反「不加新中间件 / 新组件」的护栏，技术调研已排除。
- **在现有工程内扩展**：采用。

### 落点

**后端 `petgo-backend`**

- 新模块 `com.tailtopia.analytics`，分接收、存储、汇总、身份四块。
- `shared/analytics/AnalyticsClient` 的实现直接换成自建，删除 `PostHogAnalyticsClient` 和 `analytics.posthog.*` 配置。
- **第二数据源**：配置项 `analytics.datasource.*`，用 `JdbcTemplate` 批量写，**不走 JPA**，埋点表不受业务库 `ddl-auto=validate` 实体扫描牵连。
- **第二套 Flyway**：迁移目录 `classpath:db/analytics-migration`，历史表在埋点库内，版本号同样用时间戳。
- 复用现有组件：`shared/ratelimit/RedisRateLimiter`、`admin/dailyreport/Ga4ActiveUsersClient`、advisory lock 定时任务写法、注销级联服务。
- **CI**：`scripts/ci/check-flyway-versions.sh` 现在只扫一个目录（`MIG_DIR` 写死），要扩成同时扫两个迁移目录。

**App `petgo_app`**

- 门面 `lib/core/analytics/analytics.dart` 保持不变，业务调用方零改动；只把底层的 `Posthog()` 换成自建上报器。V1.5.0 移除 `posthog_flutter`。
- 不加新依赖：
  - 本地队列用 `path_provider` 写文件。
  - 设备和版本信息用现有的 `device_info_plus` / `package_info_plus`。
  - 「上次版本号」存 `shared_preferences`，用来判断安装还是升级。
- PostHog SDK 原来自动发的 `Application Opened / Installed / Updated` 三个生命周期事件，改为自己实现。

**第一个实现 story** 是搭骨架：第二数据源、第二套 Flyway、CI 守门扩展，再加 App 门面换底。

## 核心架构决策

### 决策优先级

- **阻塞实现（已全部关闭）**：A3、A4、A5/Q1、A7、A8、A9、A10、A12/Q3、Q5。
- **塑造架构**：分区键、明细去重、晚到数据、事件清单、故障隔离、PostHog 代码清理。
- **沿用默认值**：A1（20 条 / 30 秒）、A2（1,000 条或 7 天）、A6（32 KB / 500 KB）。
- **延后**：A11 / Q2 实测大小（上线前）、A13 / Q4 系统推送发送记录（批次 ②）、A14、Q6。

### 一、数据架构

| # | 决策 | 理由 |
|---|---|---|
| D-1 | 埋点库：生产 `petgo_analytics`、stag `petgo_analytics_stag`，都在 `petgo-postgres` 实例上。**不加 env 列**：进哪个库取决于哪个后端收到（stag 包连 stag 后端） | 环境天然隔离，口径不用再过滤环境 |
| D-2 | **原始层按 `received_at`（UTC）每天一个分区**，只追加，允许重复 | 离线补发的旧事件永远写进当天分区，不会写进已删除或已汇总的分区 |
| D-3 | **明细层按 `event_date`（WIB）每天一个分区**。`event_date` 由**校正后**的发生时间算出（FR-9：发生时间 +（收到时间 − 发送时间））。去重两层：`DetailBuilder` 写入前查算出日期**前后各 1 天**分区内是否已有同一 `message_id`；唯一键（`event_date`, `message_id`）+ `ON CONFLICT DO NOTHING` 兜底 | 唯一约束必须含分区键；重试时网络延迟不同，校正时间可能跨过午夜，单靠唯一键拦不住 |
| D-4 | **A9 明细瘦身**：只留解析列，即事件名、`client_ts`（原始发生时间）、`event_time`（校正后）、`event_date`、`is_suspicious`（FR-9 可疑判定，汇总排除）、匿名 id、`user_id`、`user_key`、平台、App 版本、系统版本、机型、生产标记。属性 JSONB **只留事件清单登记过的字段**。完整原始报文只在原始层 | 明细体积可控；口径只依赖登记字段 |
| D-5 | 汇总表按指标族分（`agg_daily_*`），写入时 upsert；**`agg_progress` 记录每个任务已完成的日期**，既用于续算，也是新鲜度标记 | 一张表同时解决续算和「数据截至哪天」 |
| D-6 | **晚到数据**：每次汇总重算最近 3 天（WIB）。更晚到的进明细层但不回算，写进口径文档作为已知偏差 | 离线补发多数在 3 天内到达；重算幂等 |
| D-7 | **事件清单唯一来源**：`petgo-backend/src/main/resources/analytics/event-catalog.yaml`。后端启动时加载，用于过滤明细属性，并同步到埋点库 `event_catalog` 表供 AI 查。App 加 L0 测试，比对 Dart 事件常量与这份 yaml | 改名在 CI 就红，不会悄悄归零 |
| D-9 | **口径文档唯一来源**（FR-26）：`petgo-backend/src/main/resources/analytics/metric-definitions.yaml`，每个指标写编号、定义、来源表 / 事件、排除规则、已知偏差、SQL 文件位置、变更记录。后端启动时同步进埋点库 `metric_definitions` 表，机制同 D-7 | AI 查数只能读库 |
| D-10 | **身份关联按时间判定**（FR-14）：`identity_links` 每条关联记录建立时间；汇总时取「事件时间之前最近一次关联」 | 一个匿名 id 换过账号时归属正确 |
| D-11 | **新版覆盖率**（FR-24）：GA4 `activeUsers` 按 `appVersion` 拆，与 GA4 日活一起每天落库 | 复用现有 GA4 接入 |
| D-8 | **分区维护**：每天跑一次。提前建好 7 天分区；DEFAULT 分区兜底，非空即告警。原始层删超过 35 天的分区，**前提是那一天已汇总完成**；明细层删超过 3 个月的分区 | 落实故障隔离与时间边界 |

### 二、身份与安全

| # | 决策 |
|---|---|
| **A3** | 匿名 id 是 App 首次启动时生成的 UUIDv4，存 `shared_preferences`，**不放 secure storage**（避开 BAD_DECRYPT 坑）。卸载后重新生成，登出不重置 |
| **A4** | App 上报的用户标识沿用 `AnalyticsDistinctId`，即 `sha256('tailtopia-user-'+id)`，前后端已逐字对齐。埋点库内部另存 `user_id`（bigint）供 C 类指标关联业务库；它不对外暴露，不违反「对外标识不可枚举」 |
| **A7** | 带 JWT 的请求以 token 用户为准，逐条事件判断：<br>• 事件里没有用户标识：按匿名事件处理。<br>• 用户标识等于 token 用户：通过。<br>• 用户标识不等于 token 用户（跨账号补发）：查关联表，看（匿名 id，该用户）之前登录时是否建立过关联。有就通过；没有就去掉用户标识，标记 `identity_unverified`。<br>不带 JWT 但带了用户标识的请求，同样走关联表校验 |
| 接口 | `POST /api/v1/analytics/batch`，可不带 JWT。用 `RedisRateLimiter` 按 IP + 匿名 id 限流；体积限制按 A6；匿名 id 必须是合法 UUID |
| **A10** | `AccountDeletionService`（DB 状态机）新增一个参与者：往埋点库 `erasure_requests` 写一行，写失败抛异常，由状态机现有重试兜住。**每晚在汇总之前**批量处理：删关联表，把明细层和原始层中这些用户的 `user_id` / `user_key` 置空。汇总层不动。最晚 24 小时内生效 |

### 三、接收与资源

| # | 决策 |
|---|---|
| 队列 | 有界内存队列，容量 50,000 条（D2 破例，记录在案）。**满了返回 429**，App 保留事件稍后重试。写库线程每 1 秒或攒够 500 条，用 `JdbcTemplate` 批量写入原始层 |
| 部署丢失 | 优雅停机时先把队列写完再退出。只有进程被强杀或 OOM 时才会丢，作为可接受风险记录 |
| **A8** | 埋点写入连接池最多 3 个（接收 1 + 汇总 1 + 分区和擦除 1）；写入账号设 `CONNECTION LIMIT 5`、`statement_timeout=120s`。stag 连接池最多 2 个。AI 查数用**独立账号 `analytics_reader`**（FR-27，与业务库 `claude_bot` 分开），`CONNECTION LIMIT 3`，只读明细层、汇总层、关联表、对账结果、口径表；**原始层不开放**（完整报文、无业务索引，扫一次即全表扫描）。**合计最多占 100 个连接中的 10 个** |
| 磁盘熔断 | 埋点库超过 40 GB 告警（FR-16）；**超过 50 GB 接收端停写**（返回 429）。主机整盘余量后端容器看不到，另在主机上加 cron 检查（运维项） |
| 汇总 | WIB 03:00 运行，`@Scheduled` + advisory lock，按天分块；C 类指标对业务库只做窄查询 |
| 告警 | 沿用 `OpsAlertService` 的 TECH 渠道：汇总失败、DEFAULT 分区非空、容量水位、接收端熔断 |

### 四、App 端

| # | 决策 |
|---|---|
| **A5** | 生产标记由 App 判定，作为上下文字段写在每条事件里；明细层保留，**汇总时排除**，便于审计。规则：<br>• debug 包不上报。<br>• 模拟器：`isPhysicalDevice=false`。<br>• iOS：`installerStore` 为 `com.apple.testflight` 判为 TestFlight，为 `com.apple.simulator` 判为模拟器。<br>• Android：安装来源**只认 `com.android.vending`（Google Play）**，其他一律判为侧载。<br>• `installerStore` 为 null：算生产数据，写进口径文档作为已知偏差。<br>TestFlight 判定需用真包验证一次 |
| 生命周期事件 | 自己实现：<br>• `Application Installed`：没有「上次版本号」记录时发。<br>• `Application Updated`：版本号变了时发。<br>• `Application Opened`：冷启动和每次回到前台时发 |

### 五、口径排除与运营位

| # | 决策 |
|---|---|
| **Q5** | 不建员工名单。统计只排除 `users.role != 'USER'`（官方号）和 `account_type = 'VIRTUAL'`（虚拟号 / 种子号）。员工用真实账号产生的数据算正常用户，写进口径文档作为已知偏差（Dai 2026-11-12 拍板：影响不大） |
| **A12** | 运营位按 3 类：弹窗（`ops_popups`，已有展示 / 点击事件）、Toko 横幅（`shop_banners`）、运营推送（`ops_notification_campaigns`）。后续发现遗漏，补进事件清单即可，不影响架构 |

### 六、过渡与收尾

| # | 决策 |
|---|---|
| 服务端事件 | `AnalyticsClient` 的实现直接换成自建，**不双写**（PostHog 已停用）。上线后用业务库核对，比如每天的 `shop_payment_paid` 事件数对照当天 `PAID` 订单数。PRD §5「不迁移服务端事件」改为迁移 |
| PostHog 清理 | PostHog 已停用。V1.5.0 移除 App 的 `posthog_flutter` 和 `POSTHOG_*` dart-define，后端删除 `PostHogAnalyticsClient` 和 `POSTHOG_*` 环境变量。旧版 App 发往 PostHog 的请求无人接收，没有影响 |

### 实现顺序与依赖

1. 骨架：第二数据源、第二套 Flyway、CI 守门扩展、事件清单。
2. 原始层、明细层，加分区维护。依赖 1。
3. 接收端点，加队列、熔断、身份校验。依赖 2。
4. 擦除参与者。依赖 2。
5. 服务端 `AnalyticsClient` 自建实现。依赖 3。
6. App 换底，加生命周期事件。依赖 3；后端要先于 App 上生产。
7. 补埋点缺口 FR-17–20。依赖 6 和 D-7。
8. 批次 ②：汇总、GA4 对账、AI 账号。依赖 D-5 和 D-6。

## 实现规范与一致性规则

通用规范（命名映射链、ProblemDetail、UTC 存储等）以基线为准。本节只定埋点特有、不写明就会各写各的地方。

### 1. 命名

| 对象 | 规则 |
|---|---|
| 埋点库表 | `events_raw`、`events_detail`、`identity_links`、`erasure_requests`、`agg_progress`、`event_catalog`、`agg_daily_<指标族>` |
| 分区 | `events_raw_p20261112`、`events_detail_p20261112`；DEFAULT 分区叫 `*_pdefault` |
| 时间列 | `*_at` 用 `timestamptz`，存 UTC；只有 `event_date`（date）是 **WIB 日期**，在列注释里写明 |
| 事件名 | 只有 3 个生命周期事件用 Segment 写法：`Application Installed`、`Application Updated`、`Application Opened`。其余沿用现有 snake_case，格式 `<领域>_<对象>_<动作过去式>`，如 `toko_add_to_cart_tapped` |
| 推送打开 | 统一事件 `push_opened`，属性 `push_type`；运营推送另带 `campaign_token`（FR-18） |
| 页面浏览 | PostHog 的 `$screen` 改为 `screen_viewed`，页面名放在属性 `screen` 里（如 `toko_page`）。映射写进事件清单，AI 查数按新名查 |
| 字段命名 | 请求外层字段按基线用 camelCase（`messageId`、`sentAt`、`anonymousId`）；**事件属性一律 snake_case**，与事件清单一致。两层写法不同是有意为之 |

### 2. 结构

- 后端包按职责拆：`com.tailtopia.analytics.{ingest, store, identity, erasure, rollup, catalog}`。业务模块不直接依赖这些包，只通过 `AnalyticsClient` 发事件。
- 埋点库迁移只放在 `db/analytics-migration/V<yyyyMMdd_HHmm>__*.sql`，**绝不混进业务迁移目录**，反过来也一样。
- 汇总 SQL 每个指标族一个文件：`resources/analytics/rollup/<指标族>.sql`。写成纯文本文件，不拼在 Java 字符串里，人和 AI 都能直接读口径。
- 集成测试用本地 postgres 上的独立测试库 `petgo_analytics_test`，L1 跑法与现有一致。

### 3. 接口格式

| 情况 | 返回 | App 怎么处理 |
|---|---|---|
| 正常 | `202 {accepted, rejected}`；单条不合格的事件直接丢弃，只计入 `rejected` | 从本地队列删掉这一批 |
| 队列满 / 磁盘熔断 | `429` + `Retry-After` | 保留，到时间再发 |
| 5xx / 网络错误 | — | 保留，指数退避后重发 |
| 400 / 413（整批格式错或超大） | ProblemDetail | **整批丢弃**，防止坏批次永远卡在队头 |

### 4. 处理规则

- **所有定时任务**：
  - 拿 advisory lock，锁键统一登记在一个常量类里。
  - 必须可以重复跑。
  - **写数据和写 `agg_progress` 放在同一事务里**。
- **WIB 日期只在一处计算**：明细入库时用 `AT TIME ZONE 'Asia/Jakarta'` 算出 `event_date`。汇总只按 `event_date` 过滤；SQL 里不准用 `now()` 或 `CURRENT_DATE` 推算日期，日期一律由任务作为参数传入。
- **日志**：
  - 埋点接口从全接口请求日志里排除。
  - 任何日志都不打事件内容，只打条数和 `messageId`。
- **App 端**：
  - 上报是发出去就不管的，UI 代码不准 `await` 埋点调用。
  - 本地队列文件的读写串行执行。
  - 继续走现有的白名单加脱敏。
- **新增事件的固定三步**：在 `event-catalog.yaml` 登记 → 加 Dart 事件常量 → 比对测试变绿。少一步 CI 就红。

### 5. 反例

- ❌ 用 JPA 实体映射埋点表。规定是 `JdbcTemplate`。
- ❌ 汇总时 `GROUP BY date(event_time)`。这会按 UTC 切天，必须用 `event_date`。
- ❌ 在明细层用 `received_at` 判断事件属于哪一天。
- ❌ 业务模块直接注入 `ingest` 包里的队列。只能走 `AnalyticsClient`。
- ❌ 收到 429 后立刻重试。必须遵守 `Retry-After`。

## 目录结构与边界

### 目录结构

```
petgo-backend/
├── src/main/java/com/tailtopia/
│   ├── analytics/                          # 新模块（批次 ①；rollup 属批次 ②）
│   │   ├── config/AnalyticsDataSourceConfig.java   # 第二数据源 + 第二套 Flyway + JdbcTemplate（FR-11）
│   │   ├── ingest/
│   │   │   ├── AnalyticsBatchController.java       # POST /api/v1/analytics/batch（FR-7）
│   │   │   ├── IngestRequest.java / IngestEvent.java
│   │   │   ├── IngestValidator.java                # 体积、UUID、时间可疑判定（FR-9、A6）
│   │   │   ├── IngestQueue.java                    # 有界队列 + 429（FR-10）
│   │   │   ├── IngestFlusher.java                  # 批量写原始层 + 优雅停机写完
│   │   │   └── IngestCircuitBreaker.java           # 50 GB 停写
│   │   ├── identity/
│   │   │   ├── IdentityResolver.java               # A7 逐条校验
│   │   │   └── IdentityLinkRepository.java         # identity_links
│   │   ├── store/
│   │   │   ├── DetailBuilder.java                  # 原始→明细：去重、瘦身、event_date（FR-13、A9）
│   │   │   ├── PartitionMaintenanceJob.java        # 预建 7 天 / 条件删除 / DEFAULT 非空告警（FR-12、FR-13）
│   │   │   └── StorageWatermarkJob.java            # 40 GB 告警（FR-16）
│   │   ├── erasure/
│   │   │   ├── AnalyticsErasureParticipant.java    # 由 AccountDeletionService 调用（FR-15）
│   │   │   └── ErasureJob.java                     # 每晚、在汇总之前
│   │   ├── catalog/EventCatalog.java               # 加载 yaml、同步 event_catalog 表
│   │   ├── catalog/MetricDefinitions.java          # 加载口径 yaml、同步 metric_definitions 表（FR-26）
│   │   ├── rollup/                                 # 批次 ②（FR-21–26）
│   │   │   ├── RollupJob.java                      # WIB 03:00、重算最近 3 天
│   │   │   ├── RollupProgressRepository.java       # agg_progress
│   │   │   └── Ga4DailySnapshotJob.java            # 复用 Ga4ActiveUsersClient（FR-24）
│   │   └── AnalyticsLockKeys.java                  # advisory lock 锁键登记
│   ├── shared/analytics/
│   │   ├── AnalyticsClient.java                    # 接口不改
│   │   ├── SelfHostedAnalyticsClient.java          # 新增，写入 IngestQueue
│   │   └── PostHogAnalyticsClient.java             # 删除
│   ├── account/service/AccountDeletionService.java # 改：加擦除参与者
│   └── shared/logging/…                            # 改：埋点接口不进全接口请求日志
├── src/main/resources/
│   ├── db/analytics-migration/V<ts>__*.sql         # 埋点库专用迁移
│   ├── analytics/event-catalog.yaml                # 事件清单唯一来源
│   ├── analytics/metric-definitions.yaml           # 口径文档唯一来源（FR-26）
│   └── analytics/rollup/<指标族>.sql               # 批次 ②
└── src/test/java/com/tailtopia/analytics/…         # 与源码同构

petgo_app/
├── lib/core/analytics/
│   ├── analytics.dart                 # 门面签名不变；底层换成自建上报器
│   ├── self_hosted_sink.dart          # 新：攒批 / 上报 / 退避（FR-1–3）
│   ├── event_queue_file.dart          # 新：本地队列文件，串行读写（A2）
│   ├── anonymous_id.dart              # 新：UUIDv4，存 shared_preferences（A3）
│   ├── lifecycle_events.dart          # 新：Installed / Updated / Opened
│   ├── production_flags.dart          # 新：模拟器 / TestFlight / 侧载判定（A5）
│   └── event_names.dart               # 新：事件常量（与 yaml 比对）
└── test/core/analytics/event_catalog_sync_test.dart   # 读 ../petgo-backend/…/event-catalog.yaml 比对

scripts/
├── ci/check-flyway-versions.sh        # 改：同时扫两个迁移目录
└── ops/create-analytics-db.sql        # 新：建库、建账号、CONNECTION LIMIT、statement_timeout、`analytics_reader` 只读账号（手动执行一次）
```

### 边界

- **API**：只新增一个对外端点 `POST /api/v1/analytics/batch`。不新增读接口，查数一律走 AI 只读账号直连。
- **模块**：
  - 业务模块只认 `AnalyticsClient`。
  - `analytics` 模块对业务的依赖只有三处：读用户 `role` / `account_type`（汇总排除）、被注销流程调用、读业务库做 C 类汇总。
- **数据**：
  - 埋点库只通过 `analytics` 模块的 JdbcTemplate 写入。
  - 业务库的 JPA 和 Flyway 都不知道埋点库的存在。
  - 汇总读业务库用现有数据源，只做窄查询；**两个库之间从不做跨库 SQL**。
- **环境**：
  - 新增环境变量 `ANALYTICS_DB_NAME / USER / PASSWORD`，`.env.example` 只放占位。
  - 部署脚本不改；**建库要在部署之前，手动执行 `create-analytics-db.sql`**，stag 和生产各一次。

### 数据流

```
App 门面 ─┐
          ├→ batch 端点 → 校验/身份 → 有界队列 → 原始层(UTC 日分区)
服务端 AnalyticsClient ─┘                                   │ DetailBuilder（每分钟增量）
                                                           ↓
                                    擦除任务 → 明细层(WIB 日分区) → 汇总任务(03:00) → agg_* ← AI 只读
                                                                       ↑
                                                         业务库窄查询 + GA4 快照
```

**原始层到明细层每分钟增量转换**（`DetailBuilder`），明细层基本实时，AI 当天即可查明细；汇总仍为 T+1。

## 架构校验结果

### 一致性 ✅

- 技术栈沿用基线（Spring Boot 4 / Java 21 / Flutter 3.44 / PG16），无新增依赖、中间件或扩展。
- 决策、规范、目录三处无矛盾。校验中发现「FR-9 时钟校正 × D-3 去重」的冲突，已改为前后各 1 天查重 + 唯一键兜底。

### 需求覆盖 ✅

- FR-1–27 全部有架构落点。FR-23 依赖 A13（系统推送发送记录现状），按 PRD 属批次 ②。
- NFR：不拖累业务（独立连接池、熔断、低峰汇总、压测验证）；隔离（独立库与账号）；10 倍余量（日分区 + 水位）；合规（注销 24 小时内擦除、日志不打事件内容）。

### 校验中补上的缺口

| 级别 | 缺口 | 处理 |
|---|---|---|
| 关键 | FR-9 校正时间跨午夜导致去重失效 | D-3、D-4 |
| 重要 | FR-26 口径文档位置未定 | D-9 |
| 重要 | FR-27 要求 AI 独立账号 | A8 |
| 次要 | FR-14 按时间判定归属、FR-24 覆盖率来源、FR-18 事件名、P95 验证方式 | D-10、D-11、命名规范、上线前验证清单 |

### 完整性检查

**需求分析**
- [x] 项目上下文已分析
- [x] 规模与复杂度已评估
- [x] 技术约束已识别
- [x] 横切关注点已映射

**架构决策**
- [x] 关键决策已记录（版本沿用基线）
- [x] 技术栈完整
- [x] 集成方式已定义
- [x] 性能已考虑

**实现规范**
- [x] 命名规范
- [x] 结构规范
- [x] 通信格式
- [x] 处理规则

**目录结构**
- [x] 完整目录树
- [x] 组件边界
- [x] 集成点
- [x] 需求到目录的映射

### 就绪度

**结论：可以进入实现**。信心：高。

**延后项（不阻塞）**：
- 上线前实测：A11 单条大小、事件量校准、TestFlight 真包、10 倍压测。
- 批次 ②：A13 / Q4。
- Dai 决定：A14、Q6。

**优势**：零新组件；埋点故障与业务隔离；事件清单和口径各只有一个来源，改名在 CI 就能发现；注销擦除复用现有状态机。

**以后可以改进**：量级再涨一个数量级，或需要复杂即席分析时，评估列存或 ClickHouse（研究报告里的升级路径）。

### 交接

- 实现严格按本文档的决策、规范和目录执行；有冲突时以本文档为准，基线管通用规范。
- **第一个 story：骨架**。包括第二数据源、第二套 Flyway、`check-flyway-versions.sh` 扩展、`create-analytics-db.sql`（含写入账号和 `analytics_reader`），以及 `event-catalog.yaml`、`metric-definitions.yaml` 两份 yaml 和它们的同步机制。
