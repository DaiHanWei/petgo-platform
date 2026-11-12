# 自建埋点（analytics-pipeline）实施就绪度 · 独立审查

- 审查日期：2026-11-12
- 审查对象：`_bmad-output/planning-artifacts/analytics-pipeline/` 下的 prd.md（final）、architecture.md（complete）、epics.md（4 epic / 25 story）；参考 .decision-log.md、decisions.md、department-data-summary.zh.md
- 方法：逐条核对 FR 的可验证结果与 story AC；三份文档互相对照；对文档引用的类、表、文件、事件名 grep 代码核实
- 审查人没有参与这些文档的写作

## 结论

**修完再实现。** 批次 ① 的骨架（Story 1.1–1.3）方向没有问题。但有 3 条关键问题会让核心指标（留存、周期去重人数）从设计上就算错或算不出来，或者让分区维护整体失效。另有十余条重要问题会在实现时直接踩坑：数据源接线、`PAID` 状态不存在、服务端事件无法反查 `user_id`、只读账号越权、对账口径不可比等。它们大多只需要改文档、补 AC，不需要推翻架构。

---

## 🔴 关键

### C-1 身份归属规则把「登录前的匿名事件」排除在外，安装留存和注册转化会系统性失真

- **位置**：architecture.md D-10；epics.md Epic 4 开头的「通用规则」；Story 4.1 最后一条 AC；与 PRD FR-14、UJ-2 冲突
- **问题**：D-10 规定「汇总时取事件时间**之前**最近一次关联」，Epic 4 通用规则写「人 = 事件时间点能关联到的 `user_id`，否则为匿名 id」。按这条规则，首次登录之前的事件在事件时间点之前**没有任何关联**，于是永远算作匿名 id。这正是 FR-14 要解决的情形：「登录前的匿名事件通过关联表归到对应用户」。UJ-2 也要求「未登录期间的事件通过匿名 id 关联到注册后的用户」。
- **后果**：`Application Installed` 几乎都发生在登录前，算作「匿名 id 这个人」；第 X+1 天的 `Application Opened` 已登录，算作「`user_id` 这个人」。两者对不上，D1/D3/D7 留存、产品 #1（安装后建档）、S3 注册率都会被严重低估。Story 4.1 的测试「匿名转登录算一个人」与通用规则本身矛盾，开发照哪条做都会有一处不过。
- **证据**：architecture.md 第 140 行「汇总时取『事件时间之前最近一次关联』」；epics.md 第 457 行「『人』= 事件时间点能关联到的 user_id，否则为匿名 id」；prd.md 第 261 行。
- **建议**：把归属规则改成分段式：①事件时间之后存在关联、之前没有 → 归到该匿名 id 的**第一个**关联用户；②之前有关联 → 归最近一次；③已登出的匿名事件，按②归上一个账号，还是保持匿名？需要明确拍板。规则写进 D-10 和 Epic 4 通用规则，并在 Story 4.1 写成 SQL 级的验收用例（安装 → 匿名打开 → 注册 → 次日打开，D1 = 1/1）。

### C-2 汇总层只存「每日去重人数」，周期去重类指标从日表推不出来，明细过期后永久无法回答

- **位置**：epics.md Epic 4 通用规则（「周、月统计由 AI 按口径从日表现算，不另存」）；Story 4.4、4.5；PRD FR-21（汇总层长期保留）、§4.5.1「日去重不跨天相加」、§8 风险 4、SM-1
- **问题**：团队定义里有大量「统计周期内的去重用户数」，例如电商 #4「周期内浏览过 Toko 的去重用户」、电商 #5「周期内加购的去重用户」及其中 7 天内支付的人数、电商 #7「加购的用户数」、S5「点击人数 / 打开人数」、电商 #6 的周漏斗。PRD 自己也规定「每日去重人数不能相加」，所以这些数**不能**由日表推出。Epic 4 又明确「周、月不另存」。在 3 个月内，AI 只能回到明细层现算（口径一致性无保障）；超过 3 个月，明细已删，这些指标**永久无法回答**，与 FR-21「汇总层长期保留」、§8「汇总层上线时就覆盖全部 B、C 类指标」、SM-1「100% 可答」直接冲突。
- **补充**：4.3 对产品 #1 存了「按月」数，与通用规则「月不入表」不一致，说明作者其实意识到了这个问题，但只补了一项。
- **建议**：给每个「周期去重」指标加周表、月表（`agg_weekly_*` / `agg_monthly_*`，按 ISO 周一至周日、WIB 自然月）。周期结束后的次日计算，并按 D-6 的晚到窗口回补。或者在 PRD 里明确降级：周期去重只保证 3 个月内可答，写进口径已知偏差。二选一，并修改 SM-1。

### C-3 明细层 DEFAULT 分区会接住时钟异常的事件，导致后续建分区失败，且这些数据永不过期

- **位置**：architecture.md D-3、D-8；epics.md Story 1.5（`events_detail` 按 `event_date` 分区 + DEFAULT 分区；可疑事件只打标、仍写入）、Story 1.2（预建 7 天）
- **问题**：`event_date` 由校正后时间算出。设备在事件发生和发送之间改过时钟（手动改时间、NTP 跳变、恢复出厂后补发）时，`event_time` 可能落在任意日期，FR-9 只把它标为可疑，仍然写入明细层。日期超出已建分区范围的行会落进 `events_detail_pdefault`，带来三个后果：
  1. PostgreSQL 规定：DEFAULT 分区里已有某日期的行时，再 `CREATE TABLE … PARTITION OF … FOR VALUES FROM (该日)` 会报错（updated partition constraint for default partition would be violated）。只要有一条事件的日期落在「今天 + 8 天以后」，几天后 `PartitionMaintenanceJob` 预建到那一天就会**失败**，之后每天都失败，新数据全部涌进 DEFAULT。
  2. DEFAULT 分区不会被「删超过 3 个月的分区」逻辑删除，里面的行**永远不过期**，违反 D6 的 3 个月保留期。
  3. 「DEFAULT 非空即告警」会被这类正常噪声持续触发，真告警被淹没。
- **建议**：可疑事件不按校正时间入分区。可以把 `event_date` 夹到 `received_at` 的 WIB 日期，同时保留原始的 `event_time`；也可以把可疑事件写进单独的小表。预建分区前先检查 DEFAULT 中是否有落在该区间的行，有就先搬走。分区维护单测加「DEFAULT 中已有未来日期行」的用例。原始层按服务端 `received_at` 分区，不受影响。

---

## 🟠 重要

### I-1 架构的「安装 / 升级」判定与 PRD、epics 矛盾，而架构声明「有冲突以本文档为准」

- **位置**：architecture.md「四、App 端 · 生命周期事件」（`Application Installed`：没有「上次版本号」记录时发）、§交接（「有冲突时以本文档为准」）；PRD FR-3；epics Story 2.3
- **问题**：「上次版本号」是 V1.5.0 新写的键，所有从旧版升级的设备第一次启动时都没有它。按架构实现，**所有升级用户都会被报成 `Application Installed`**，S2 安装数和留存组在发版当周暴涨。PRD 与 Story 2.3 写的是对的（「有旧版本地数据、没有安装标记 → Updated」），但架构又规定冲突时以架构为准，开发拿到的是两份相反的指令。决策日志「升级判定」一条已按 PRD 拍板，架构没有同步。
- **建议**：改架构的这一行，和 PRD / Story 2.3 对齐。另外，Story 2.3 只说「旧版已知键」，要明确列出是哪些键：必须是旧版**每台设备首次启动必写**的键，并核实未登录、从未完成引导的老用户也有。

### I-2 业务库里没有 `PAID` 状态

- **位置**：PRD §4.5.1、FR-22；epics Story 4.4（「`shop_orders`（只计 `PAID`）」）、4.5、4.6
- **证据**：`V20260817_2308__init_shop_orders.sql` 中 `ck_shop_orders_status` 只有 `PENDING_PAYMENT / PENDING_SHIPMENT / SHIPPED / DELIVERED / COMPLETED / CANCELLED / REFUNDING / REFUNDED`。「已付款」的判据是 `V20261024_2131__shop_orders_merchant_columns.sql` 新加的 `paid_at IS NOT NULL`；那份迁移的注释写明，已付款后被取消的单也算已付款。
- **问题**：照文档写 `status = 'PAID'` 会恒为 0；如果开发自己猜成 `status IN (...)`，又会漏掉「付后取消」「退款中」等情况。另外「付款后又退款」的订单算不算带单 / 转化，文档没有说。
- **建议**：口径统一成「`paid_at IS NOT NULL`，按 `paid_at` 的 WIB 日期归日」。退款单是否扣除，单独拍板并写进 `metric-definitions.yaml`。非 Toko 付费（产品 #3）的判据也要逐一列出（`payment_intents.status='PAID'`、KTP / Tailsonality / 名额等各表）。

### I-3 第二数据源接线的三个坑，Story 1.1 都没写

- **位置**：Story 1.1；architecture.md「落点 · 第二数据源」
- **问题**：
  1. Spring Boot 的 `DataSourceAutoConfiguration` 在容器里出现任何 `DataSource` bean 时就会退让。新增埋点 `DataSource` 后，业务数据源不再自动创建，除非显式声明 `@Primary` 业务数据源，或把埋点 bean 标为 `defaultCandidate = false`。代码里有 225 处按类型注入 `JdbcTemplate`（包括 `OpsAlertService`），多出一个 `JdbcTemplate` bean 会报注入歧义，启动失败。
  2. 启动容错：埋点库不可达、或第二套 Flyway 迁移失败时，按默认行为（Hikari 快速失败 + Flyway 启动期迁移）**整个后端起不来**，与架构「只丢埋点，不伤业务」矛盾。
  3. CI：`.github/workflows/backend-ci.yml` 的 postgres service 只建 `petgo` 库，所有 `@SpringBootTest` 都会因为连不上埋点库而失败。`flyway-guard.yml` 的 `paths` 过滤只含 `db/migration/**`，只改埋点迁移的 PR 根本不会触发守门。
- **建议**：Story 1.1 补三条 AC：业务数据源显式 `@Primary`（或埋点 bean 用 `defaultCandidate=false`），全量现有测试绿〔L1〕；埋点库宕机时后端仍能启动、业务接口可用、接收端返回 429 或 503〔L1〕；CI 建第二个库，flyway-guard 的 paths 加入新目录〔L0〕。

### I-4 服务端事件只带哈希，「反查 `user_id`」没有实现路径

- **位置**：Story 1.4（「用 `AnalyticsDistinctId` 反查出 `user_id`」）、Story 1.7（「现有约 12 处调用点无需改动」「带 `AnonymousVisitorId`，标为 H5 来源」）；architecture.md A4
- **证据**：`AnalyticsClient.capture(String distinctId, String event, Map props)` 只收哈希。调用方是 `AnalyticsDistinctId.of(userId)`（如 `ShopPaymentAnalyticsListener`），或者 H5 访客 id（`PostSharePageAnalytics`、`CardPageAnalytics`、`CardTrackController`，32 位 hex）。sha256 无盐，但不能「反查」，只能全表枚举 users 计算后比对。
- **问题**：
  1. 服务端事件进不了 `user_id`，Story 1.5 按 `role` / `account_type` 判定生产数据时对它们失效，管理员和虚拟号的服务端事件会混进统计。
  2. `SelfHostedAnalyticsClient` 只能靠字符串长度（64 hex 还是 32 hex）猜测是用户还是 H5 访客，文档没有写。
  3. `CardTrackController` 把 H5 前端传来的 `body.event()` 原样上报，事件名不在清单里会被拒（见 I-10）。
- **建议**：给 `AnalyticsClient` 增加 `captureForUser(long userId, …)` / `captureForVisitor(String visitorId, …)` 两个重载，原方法标为废弃；12 处调用点做机械改动，并在 1.7 写明「调用点需改动」。App 侧只依赖 JWT 和关联表得出 `user_id`，与 1.4 的规则一致。

### I-5 `analytics_reader` 的默认授权会覆盖原始层和擦除表

- **位置**：Story 1.1（「`analytics_reader`…只读，默认授权覆盖之后新建的表」）；architecture.md A8（「原始层不开放」）；Story 4.7
- **问题**：`ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES` 会对之后新建的**所有**表生效，包括 `events_raw`、它每天新建的分区（分区可以直接查询）、`erasure_requests`（列出已注销用户的 `user_id`）和 `storage_watermarks` 等内部表。这与「原始层不开放」直接矛盾，也让 AI 能看到「谁注销了」。
- **建议**：不用全局默认授权。改成显式授权白名单表和视图，新建汇总表时在迁移里逐条 `GRANT`；分区维护任务建原始层分区时显式 `REVOKE`。Story 1.1 / 4.7 加 L1 验收：用 `analytics_reader` 查 `events_raw`、`events_raw_p<今天>`、`erasure_requests` 都返回 permission denied。

### I-6 DAU 对账两边口径天然不同，观察期几乎必然「异常」

- **位置**：PRD FR-25、SM-2；architecture.md D-11；Story 4.2
- **问题**：自建日活和 GA4 日活至少有四处系统性差异：
  1. 自建只有 V1.5.0+；GA4 是全版本（`Ga4ActiveUsersClient` 取的是全量 `activeUsers`，没有版本过滤）。覆盖率刚到 90% 时，仅这一项差值就 ≈ −10%，正好卡在阈值上。
  2. 自建按「人」（多设备合一、一台设备换账号算两人），GA4 按设备（类注释写明「按设备」）。
  3. 自建排除模拟器、侧载、`role != 'USER'`（含 VET，兽医在用 App），GA4 不排除。
  4. GA4 App 数据有 24–48 小时处理延迟，WIB 03:00 拉前一天的数通常还没定稿。Story 4.2 只在「失败」时补拉。
- **建议**：对账改为「GA4 只取 appVersion ≥ 1.5.0 的 `activeUsers`」与「自建按设备（匿名 id）去重、不排除非生产」对比，这样才同口径。GA4 快照每次回拉最近 3 天覆盖旧值。差值阈值是否保留 10%，在同口径之后重新确认。这是 PRD 层面的口径问题，需要 Dai 拍板。

### I-7 Epic 2 内部往后依赖，事件名和匿名 id 还没就位就要求 L1 验收

- **位置**：Story 2.1 L1（「stag 明细层能查到全部 50 条」）、2.2、2.3、2.4；Story 1.3（「`anonymousId` 不是 UUID、事件名不在清单里的，只拒该条」）
- **问题**：
  1. 2.1 的事件还没有匿名 id（2.2 才建），按 1.3 的规则会被逐条拒收，2.1 的 L1 无法通过。
  2. `user_identified`（2.2、1.4）、`screen_viewed`（2.4）、`Application Installed/Updated/Opened`（2.3）都是新事件名。1.1 只登记「现有 App 事件与服务端事件」，2.6 才做比对测试，于是 2.2–2.4 的 L1 都会因「不在清单」被拒。
  3. 1.3 的「单条字段缺失」到底哪些字段必填，没有定义。如果平台、版本号必填，2.1 还依赖 2.5。
- **建议**：把匿名 id 并入 2.1，或把 2.2 移到 2.1 前面。1.1 的 yaml 初始清单显式包含上述新事件名。1.3 列出必填字段表。

### I-8 事件清单严格拒收，任何清单漂移都会永久丢数

- **位置**：Story 1.3（事件名不在清单里的只拒该条并计入 `rejected`）；architecture.md D-7（清单只用于「过滤明细属性」）、§3 接口格式（202 后 App 删除整批）
- **问题**：epics 比架构更严：架构只说清单用来过滤明细属性，没有说拒收事件。拒收的事件**连原始层都不进**，App 收到 202 后删除本地队列，数据无法找回。会触发漂移的情形包括：新版 App 先于后端清单发布、热修补埋点、H5 的 `CardTrackController` 透传事件名、服务端新事件忘了登记。比对测试（2.6）只能防 App 常量，防不了这些。
- **建议**：事件名不在清单的照样写进原始层，打 `unknown_event` 标记；明细层不转入，并经 TECH 告警计数。原始层保留 35 天，补登记后可以用 1.5 的重算入口恢复。只对结构非法（缺 `messageId`、超大）的事件拒收。

### I-9 `DetailBuilder` 按 `received_at` 推进进度，与写入提交顺序有竞态，进度表也没定义

- **位置**：Story 1.5（「每分钟按 `received_at` 进度增量处理，进度写在同一事务里」「原始层删除前提是 DetailBuilder 的进度已越过该日」）；Story 1.3（`received_at` 取服务端时间）；Story 4.1（`agg_progress` 在这里才建）
- **问题**：
  1. `received_at` 在接收时打上，最多 1 秒后才由 `IngestFlusher` 批量提交。`DetailBuilder` 读到「`received_at` ≤ 当前最大值」并推进进度后，才提交的、`received_at` 更早的行会被**永久跳过**。重启、停机写完队列、服务端事件同时写入时都会出现。
  2. 进度存在哪张表没有写。如果复用 `agg_progress`，就是往后依赖 Epic 4 的 4.1；如果不复用，就要新建一张表，但 1.5 没有列出。
  3. 同一批里同一个 `message_id` 出现两次、且两次的校正日期不同（跨午夜）时，「写入前查前后各 1 天」查不到彼此，两条都会写入。
- **建议**：进度只推进到 `now() - 安全延迟`（例如 2 分钟）为止，或者给原始层加 `bigserial` 序号、按序号推进。1.5 自建 `pipeline_progress` 表。批内先按 `message_id` 做 `DISTINCT ON`。

### I-10 `identity_links` 的唯一键加「首次关联时间」，会丢掉重新登录的历史

- **位置**：Story 1.4（唯一键为匿名 id + `user_id`，`linked_at` 取首次关联时间）；architecture.md D-10
- **问题**：同一台设备先登 A（t1），再登 B（t2），又登回 A（t3）。唯一键只保留 A 的 t1，t3 之后登出期间的匿名事件按「之前最近一次关联」会归给 B。家庭共用手机、测试号切换时会频繁出现。
- **建议**：关联表改为只追加的事件表（每次登录一行，主键用自增或 `(anonymous_id, linked_at)`），汇总时取区间。或者至少维护 `last_linked_at`，并按区间判定归属。

### I-11 按 IP 限流在印尼移动网络下会误伤正常用户

- **位置**：Story 1.3（`RedisRateLimiter` 按 IP 和匿名 id 限流）；PRD FR-8
- **问题**：印尼的移动运营商普遍使用 CGNAT，大量用户共用一个出口 IP，按 IP 设阈值很容易把正常用户整体打成 429。App 侧本地队列只有 1,000 条或 7 天上限，长时间 429 会导致丢数。另外，代码库里没有任何 `Retry-After` 实现（`AppException.rateLimited` 只返回 ProblemDetail），「复用 `RedisRateLimiter`」并不能直接满足「429 + `Retry-After`」。
- **建议**：以匿名 id 为主维度限流，IP 只做很宽的兜底阈值（压测后定）。在 1.3 里写明 `Retry-After` 由谁加、取什么值。App 收到不带 `Retry-After` 的 429 时，按默认退避处理（2.1 补一条 AC）。

### I-12 内存队列按条数设上限，最坏情况占用远超堆内存

- **位置**：architecture.md「三、接收与资源 · 队列」（50,000 条）；Story 1.3（单条最大 32 KB）
- **问题**：50,000 × 32 KB ≈ 1.6 GB。被刷或写库变慢时队列会被填满，足以把同进程的业务服务 OOM 掉，违反 NFR-1。
- **建议**：按估算字节数设上限（例如 64 MB），或把单条上限降到 8 KB 并按 50,000 × 8 KB 核算。1.3 加一条队列满载时的堆内存观测 AC。

### I-13 写入账号的 `statement_timeout=120s` 也会卡住汇总和擦除

- **位置**：architecture.md A8（写入账号 `statement_timeout=120s`，池 3 个连接分给接收、汇总、分区和擦除）；Story 1.6、4.5
- **问题**：
  1. `ErasureJob` 要对 35 天原始层做 `UPDATE … WHERE user_id = ANY(...)`，原始层除 BRIN(`received_at`) 外没有索引，按 10 倍设计量是全表扫描几千万行，很可能超过 120 秒并反复失败，24 小时生效的承诺落空。
  2. 4.5 的 7 天顺序漏斗、购物车生命周期汇总在 10 倍量下也可能超时。
  3. 连接池只有 3 个，`DetailBuilder`（每分钟）、熔断检查（每 5 分钟）、水位、重算入口都没有算进去。
- **建议**：汇总和擦除用会话级 `SET statement_timeout` 单独放宽。原始层擦除改成按分区逐个处理，或者干脆不擦原始层：原始层 35 天自然过期，PRD 允许的 24 小时承诺需要据此重新评估，这要拍板。重新核算连接预算。

### I-14 注销擦除会漏掉属性 JSON 里的用户标识

- **位置**：Story 1.6（只把 `user_id` / `user_key` 两列置空）、Story 2.2（`identifyUser` 上报 `user_identified`）
- **证据**：现有 `Analytics.identifyUser` 会把 `personProperties`（含明文 `internal_user_id`）发给 PostHog（`petgo_app/lib/core/analytics/analytics.dart` 第 85–121 行）。
- **问题**：如果 `user_identified` 沿用这份属性，或者其他事件属性里带了作者、对方用户等标识，原始层的 `properties` 和明细层中登记过的属性都会留下可识别个人的数据。擦除只清两列，不满足 FR-15。
- **建议**：2.2 明确 `user_identified` 不带 `internal_user_id`。`event-catalog.yaml` 给属性加「是否用户标识」标记，擦除时一并清理这些键；原始层用 `jsonb - key`。1.6 加 L1 用例。

### I-15 L2 压测在与生产共用的 PostgreSQL 实例上做

- **位置**：Story 1.3 最后一条 AC（stag 以 10 倍现量压测，看业务接口 P95）；architecture.md「技术约束」（`petgo-postgres` 生产与 stag 共用，`max_connections=100`、`shared_buffers=128MB`，同机还有其他服务）
- **问题**：在 stag 打 10 倍量，压的是和生产同一个 PG 实例、同一块磁盘、同一个 Redis，会直接影响生产 P95。项目约定生产基建动作要先确认，这条 AC 没有写任何防护。
- **建议**：压测改在低峰时段、预先告知、设好中止阈值，或改在本地 / 临时实例做。AC 写明前置审批和回滚手段。

### I-16 事件清单比对测试只查常量，查不到真正的调用点

- **位置**：Story 2.6（`event_names.dart` 收拢常量 + `event_catalog_sync_test.dart`）；PRD FR-1（调用点无需改动）
- **证据**：App 里 `Analytics.capture('…')` 字面量有 191 种，另有常量、三元表达式、多行调用（如 `sku_picker_sheet.dart` 的 `isBuy ? 'toko_buy_now_tapped' : 'toko_add_to_cart_tapped'`、`kKeepsakeCardGeneratedEvent`），页面名由 `'${tabName}_page'` 动态拼接。
- **问题**：调用点不改为引用常量的话，测试只能保证 `event_names.dart` 和 yaml 一致，实际发出的事件名并没有被校验，「改名在 CI 就红」不成立。全部改成常量，又与「调用点无需改动」矛盾。
- **建议**：二选一。一是改调用点（2.6 的规模要据此上调）；二是测试改为扫描 `lib/` 下 `Analytics.capture(` 的字符串字面量并与 yaml 比对，动态事件名列白名单。

### I-17 汇总层「不含个人字段」与电商 #7 购物车明细冲突

- **位置**：Story 4.5（`agg_daily_cart_item`：每件被加购商品的加购时间、结束方式、停留时长）；PRD FR-21（汇总不含可识别个人字段）、FR-15（汇总层不动）；department-data-summary 电商 #7（「是哪件商品、**哪个用户**、什么时间」）
- **问题**：要算「每个购物车的商品数」，就必须按用户分组；团队还明确要「哪个用户」。表里不放用户就算不出来，放了就违反 FR-21，注销擦除也不覆盖汇总层。逐件、逐次的行长期保留，本身就是个人行为记录。
- **建议**：`agg_daily_cart_item` 只存聚合（按商品、按天：加购数、移出数、停留时长分布、购物车数、购物车价值）。「哪个用户」明确只在明细层 3 个月内可查，写进口径已知偏差，并告知电商。

### I-18 运营推送的「发送 / 接收数」取错了表

- **位置**：Story 4.4（「取 `ops_notification_campaign_targets` 等发送记录」）；PRD FR-23
- **证据**：`ops_notification_campaign_targets` 只是「IDS 模式的指定用户名单」（`V20261012_1107` 第 54–70 行），不是发送日志。发送结果在 `ops_notification_campaigns.recipient_count / failed_count / skipped_count / planned_count`。`recipient_count` 的含义是「成功写入通知中心的人数」，不是推送下发成功数。
- **问题**：按文档取数会得到错误的分母；「接收数 = 下发成功数」在现有数据里没有对应字段。
- **建议**：4.4 改为取 `ops_notification_campaigns` 的计数列，口径写明「接收 = 写入通知中心人数（近似）」。A13 / Q4（系统推送发送记录现状）在 4.4 里加一条勘察子任务，已知服务端事件 `toko_coupon_expiry_push_sent` 可以作为券过期推送的发送记录。

### I-19 Story 规模过大

- **1.1**：第二数据源、第二 Flyway、CI 脚本、建库 SQL（两个库、多个账号）、env、runbook、两份 yaml 的加载、同步、校验，`AnalyticsLockKeys`，**外加登记全部约 191 个 App 事件和服务端事件的属性清单**。光清单就是一个独立的盘点工作。建议拆成「1.1a 数据源 + Flyway + CI + 建库」和「1.1b 事件清单盘点 + 口径 yaml 骨架 + 同步」。
- **1.5**：明细表、`DetailBuilder`、双层去重、时间校正、跨库读 `users` 判定生产标记、明细分区维护、原始层条件删除、重算入口。建议拆出「明细分区与原始层删除」和「重算入口」。
- **4.5**：4 张表、7 天顺序漏斗、购物车生命周期（要和业务库订单逐商品对账，见 I-20）、商品维度、跨库。建议拆成漏斗 / 转化与购物车 / 商品两个 story。

### I-20 购物车、带单与业务库关联时，id 形式没有定义

- **位置**：Story 3.3、4.4、4.5
- **证据**：App 事件带的是 `product_token` / `sku_token`（`sku_picker_sheet.dart` 第 69–72 行）；业务库订单行用的是 bigint 外键。
- **问题**：判断「已购买」、匹配「点击后 7 天的订单」、按商品统计近 30 天销量时，都需要 token 和 id 互相映射。文档只写「读业务库窄查询」，没有说按什么键关联；跨账号、跨设备在别处删车的情况也没有覆盖。
- **建议**：明确所有关联一律用 public token（业务库窄查询返回 token），口径写明「在其他设备或后台清空的购物车行无法观测」。

---

## 🟡 次要

1. **PRD FR-8 与架构 A7 的分歧没有记入决策日志**：PRD 写「校验不通过的事件丢弃并计数」，架构写「去掉用户标识、标 `identity_unverified`、保留事件」。架构的做法更合理，但需要在 .decision-log.md 补一条。
2. **FR-16 从批次 ② 提前到 Epic 1**：只在 epics 映射表里说明了，决策日志没有记录。
3. **epics「附加要求 · 数据与存储」第 74 行**写「原始层只删已汇总完成的日期」，与 architecture D-8（2026-11-12 更正为「已转进明细层」）和 Story 1.5 不一致，是没改干净的旧文。
4. **PRD FR-13「设备上下文按设备只存一份」(A9)** 被架构 D-4「只留解析列」替代，PRD 正文没有更新。
5. **命名规范清单不全**：architecture §实现规范 1 的表名清单缺 `metric_definitions`、`storage_watermarks`、`ga4_daily_snapshot`、`agg_daily_dau_reconcile`；`agg_cohort_retention` 不符合 `agg_daily_<指标族>` 规则。
6. **H5 来源标记没有落点**：Story 1.7 要求「标为 H5 来源」，但 `events_raw`（1.2）和明细层（D-4）都没有 `source` 列。
7. **`role != 'USER'` 会同时排除兽医（VET）**：`ck_users_role` 是 `USER/VET/ADMIN`。架构把这条描述成「官方号」，与实际效果不符。是否排除兽医需要确认，口径要写准。此外，团队通用规则要求排除「测试账号」，Q5 的规则没有覆盖。
8. **Android 的 `installerStore` 为 null 时算生产**：Android 上 null 多半是 adb 或侧载安装，与「只认 `com.android.vending`」自相矛盾。建议只对 iOS 适用 null 算生产。另外，项目记忆里 V1.3.3 出过「小米 APK」包，建议再确认目前确实没有非 Play 的分发渠道（A5 的前提）。
9. **分区边界的时区**：原始层按 UTC 日分区，建分区 DDL 必须写成 `'2026-11-12 00:00:00+00'`，否则会按会话时区解释；明细层的「今天」要按 WIB 算。Story 1.2 的日期单测应覆盖这两点。
10. **可疑判定没有容差**：「校正后晚于 `received_at`」的判定会把毫秒级抖动误判为可疑，建议留几秒容差。
11. **新人券弹窗没有运营位 id 来源**：现有 `toko_newbie_coupon_popup_shown` 带的是 `coupon_id`（活动 token）。3.1 只定义了弹窗和横幅的 id 来源。三类事件的 id 属性名不统一（`popup_id` / `banner_id` / `coupon_id`），4.4 应定义映射。另外，「该运营位是否推广 Toko」（PRD FR-17 第 3 条、S5 电商口径）需要把后台配置的目标类型复制进埋点库，没有任何 story 覆盖。
12. **推送冷启动时序**：App 被杀后点推送冷启动，`push_opened` 可能在匿名 id 或上报器初始化之前触发。3.2 没有覆盖这种情况。iOS 静默推送唤醒是否会误报 `Application Opened`，2.3 也没有说明。
13. **服务端事件在队列满或熔断时的行为没有定义**：服务端无法重试，应丢弃并计数。1.7 没写。
14. **验收标准不可测**：1.1「业务迁移目录出现埋点表会让启动或测试失败」、1.2「本 story 只建不删〔L0〕」、4.2「切到 SELF 时在 yaml 记录切换日期〔L0〕」都是流程约定，不是可测的 AC。4.7「启动时同步进 `metric_definitions`」标的是〔L0〕，实际需要数据库，应为 L1。FR-16「接近研究估算 10 倍」没有给出数值定义。
15. **L1 用例要求非 debug 包**：2.1 规定 debug 包不发送，但 Epic 2 的 L1 用例要在模拟器上发数据，应写明用 profile 或 release 包连 stag。
16. **角色是集群级的**：如果 prod 和 stag 的埋点库共用同一个写入角色，`CONNECTION LIMIT 5` 由两边共享（3 + 2 = 5，没有余量）。部署时新旧容器重叠就会连不上。`analytics_reader` 如果同名同角色，生产的 AI 也能连上 stag 库。建议 stag 用独立角色。
17. **GA4 新版覆盖率的分母**：GA4 `activeUsers` 按 `appVersion` 拆分时，同一用户跨版本会被重复计数，覆盖率需注明是近似值。`Ga4ActiveUsersClient` 目前只支持总数，要新增带维度的方法，4.2 的「复用」需要改代码。
18. **擦除与「原始层只追加」冲突**：擦除要 UPDATE 原始层，与「只追加」的表述冲突，且会在 35 个分区产生膨胀。
19. **24 小时承诺依赖注销作业本身**：注销作业是异步的，失败重试可能超过 24 小时；埋点库不可用时也会阻塞业务侧注销。这是可以接受的取舍，但应写明。

---

## 三份文档之间的矛盾汇总

| # | 说法 A | 说法 B | 性质 |
|---|---|---|---|
| 1 | PRD FR-10：队列满返回 503 | 架构 / epics：429 | 有意推翻（决策日志、epics 均注明） |
| 2 | PRD §5：不迁移服务端事件 | 架构：迁移 | 有意推翻（决策日志、PRD 删除线） |
| 3 | PRD FR-13：后端维护内部账号名单 | 架构 Q5：不建名单，只排除 role / VIRTUAL | 有意推翻（决策日志） |
| 4 | PRD：FR-16 属批次 ② | epics：放进 Epic 1 | 有意调整，仅 epics 注明，决策日志未记（🟡-2） |
| 5 | PRD FR-8：不通过的事件丢弃 | 架构 A7：去掉标识后保留 | **未记录**（🟡-1） |
| 6 | PRD FR-3 / Story 2.3：靠旧版数据区分升级 | 架构：没有上次版本号就报 Installed | **真矛盾**，且架构声明自己优先（I-1） |
| 7 | PRD FR-14 / UJ-2：登录前的匿名事件归到用户 | 架构 D-10 / Epic 4 通用规则：取事件之前的关联 | **真矛盾**（C-1） |
| 8 | 架构 D-8 / Story 1.5：原始层在转入明细后才删 | epics 附加要求：已汇总后才删 | **epics 内部旧文**（🟡-3） |
| 9 | 架构 D-7：清单只过滤明细属性 | Story 1.3：不在清单的事件拒收 | **未经讨论的收紧**（I-8） |
| 10 | Story 1.1：只读账号默认授权所有新表 | 架构 A8：原始层不开放 | **真矛盾**（I-5） |
| 11 | PRD FR-21：汇总不含个人字段 | 团队定义电商 #7「哪个用户」、Story 4.5 逐件记录 | **未处理**（I-17） |
| 12 | Epic 4 通用规则：周、月不入表 | Story 4.3：按月记产品 #1 | epics 内部不一致（C-2） |
| 13 | PRD / epics：业务库 `PAID` | 代码：没有这个状态 | **事实错误**（I-2） |
| 14 | PRD FR-13 A9：按设备存一份上下文 | 架构 D-4：只留解析列 | 架构替代，PRD 未更新（🟡-4） |
| 15 | Story 1.7：调用点无需改动 | Story 1.4、1.7：服务端事件要反查 `user_id`、标 H5 | **做不到**（I-4） |

## 往后依赖与建表时机

| 项 | 问题 |
|---|---|
| Story 1.5 的进度表 | 没有定义；复用 `agg_progress`（4.1 才建）就是跨 Epic 往后依赖（I-9） |
| Story 2.1 → 2.2 | 2.1 的 L1 依赖 2.2 才生成的匿名 id（I-7） |
| Story 2.2 / 2.3 / 2.4 → 2.6 | 新事件名要到 2.6 才系统登记，1.1 的清单不含它们（I-7） |
| Epic 3 → Epic 2 | L1 依赖 2.1 的上报器；epics 已说明，属可接受的 epic 间依赖（Epic 3 依赖的是前一个 Epic） |
| Epic 4 → Epic 2 / 3 | 指标完整依赖前面的 epic，方向正确 |
| 其余表 | `events_raw`(1.2)、`identity_links`(1.4)、`events_detail`(1.5)、`erasure_requests`(1.6)、`storage_watermarks`(1.8)、`agg_*`(4.x) 都由第一个用到它的 story 建，没问题；4.2 的 `ga4_daily_snapshot` / `agg_daily_dau_reconcile` 没写「迁移建」，应补上 |

## 代码引用核实

| 文档引用 | 核实结果 |
|---|---|
| `AnalyticsClient`、`PostHogAnalyticsClient`、`AnalyticsDistinctId`、`AnonymousVisitorId`、`AnalyticsEventGuard`（shared/analytics） | 存在。`AnonymousVisitorId` 是 32 位 hex，不是 UUID |
| `RedisRateLimiter` | 存在；**不支持 `Retry-After`**（全库没有该头） |
| `Ga4ActiveUsersClient` | 存在；只取全量 `activeUsers`，**没有 `appVersion` 维度**，需要扩展 |
| `AccountDeletionService` | 存在；是直接注入各服务的编排，不是「参与者」接口，加一项依赖即可 |
| `OpsAlertService` / `OpsChannel.TECH` | 存在；按类型注入 `JdbcTemplate`（I-3 的歧义受害者之一） |
| `ApiAccessLoggingFilter`（shared/logging） | 存在 |
| `shop_orders` 的 `PAID` | **不存在**（I-2），应改用 `paid_at` |
| `ops_notification_campaign_targets` 作发送记录 | 表存在，但**不是发送记录**（I-18） |
| `ops_popups.public_token`、`shop_banners.public_token`、`ops_notification_campaigns.public_token` | 存在 |
| `users.role`（USER/VET/ADMIN）、`users.account_type`（REAL/VIRTUAL） | 存在 |
| `scripts/ci/check-flyway-versions.sh` 中写死的 `MIG_DIR` | 存在，属实；另外 `flyway-guard.yml` 的 paths 也要改（I-3） |
| App `analytics.dart` 的 `capture` / `capturePurchase` / `buttonTapped` / `screen` / `scrub` / `debugCaptureSink` / `distinctIdFor` / `identifyUser` / `reset` | 都存在 |
| `app_router.dart` 的 `PosthogObserver()` | 存在（第 487 行） |
| `push_service.dart` 载荷 `type` / `targetRef` | 存在；券过期推送的 `targetRef` 是张数，不是活动 token（3.2 只对运营通知取 `campaign_token`，正确） |
| 漏斗与引导事件 `toko_product_shown` … `toko_order_pay_tapped`、`pet_profile_create_submitted`、`onboarding_completed`、`signup_succeeded` | 都存在。`toko_page` 由 `'${tabName}_page'` 动态拼接 |
| `ops_popup_coordinator.dart`、`shop_banner_carousel.dart`、`toko_page_v3.dart`、`product_detail_page_v3.dart`、`sku_picker_sheet.dart` | 都存在 |
| `PETGO_API_BASE_URL` | 存在（`dio_client.dart`） |
| `path_provider`、`shared_preferences`、`device_info_plus`、`package_info_plus` 8.3.1（含 `installerStore`） | 都在依赖里；iOS 返回 `com.apple.testflight` / `com.apple.simulator` / `com.apple`，与 A5 一致 |
| UUID 生成、网络恢复检测 | `uuid` 只是传递依赖，没有 `connectivity_plus`；「不加新依赖」意味着 UUID 要自己写，「网络恢复补发」只能靠定时重试，2.1 应写明 |

---

## FR 覆盖矩阵

图例：✅ AC 有具体覆盖；◐ 部分覆盖或有缺陷；❌ 没有覆盖；⇄ 已有意推翻或替代

| FR | 可验证结果（PRD 原文摘要） | Story / AC | 状态 | 缺口 |
|---|---|---|---|---|
| FR-1 | 不再向 PostHog 发请求 | 2.6（L1 抓包） | ✅ | |
| FR-1 | 调用点无需改动 | 2.1 | ◐ | 与 2.6 的常量收拢、1.7 的服务端改造冲突（I-4、I-16） |
| FR-1 | PII、自由文本黑名单和长度上限继续生效 | 2.1（经 `scrub()`） | ✅ | |
| FR-1 | 允许上报金额 | 2.1 | ✅ | 需同时去掉 `posthogMoneyKeys` 的剥离，可补一句 |
| FR-1 | 失败不抛异常 | 2.1 | ✅ | |
| FR-2 | 20 条 / 30 秒 | 2.1 | ✅ | |
| FR-2 | 切后台立即发 | 2.1 | ✅ | |
| FR-2 | 断网补发、被杀重启补发 | 2.1（L1） | ◐ | L1 依赖 2.2 的匿名 id（I-7）；没有网络检测依赖 |
| FR-2 | 上限 1,000 条 / 7 天，丢最旧 | 2.1 | ✅ | |
| FR-2 | 后端成功前不删 | 2.1（返回码表） | ✅ | |
| FR-2 | 身份在事件发生时绑定 | 2.2 | ✅ | |
| FR-3 | 新装判定 | 2.3 | ✅ | 与架构矛盾（I-1） |
| FR-3 | 升级只报 Updated | 2.3 | ◐ | 「旧版已知键」没有列明 |
| FR-3 | 冷启动和回前台都报 Opened，带属性 | 2.3 | ✅ | |
| FR-3 | 口径文档注明卸载重装算新装 | 4.7 | ✅ | |
| FR-4 | `screen()` 与路由切换产生页面浏览，页面名不变 | 2.4 | ✅ | |
| FR-4 | 底部 Tab 和冷启动落地页 | 2.4 | ✅ | |
| FR-5 | 匿名 id 卸载前不变，登出不重置 | 2.2 | ✅ | |
| FR-5 | 用户标识不可枚举 | 2.2（`distinctIdFor`） | ✅ | |
| FR-5 | A/B 两条关联；登出后只带匿名 id | 2.2、1.4 | ◐ | 重新登录的历史会丢（I-10） |
| FR-5 | 切换前先发上一账号的事件 | 2.2 | ✅ | |
| FR-6 | 版本、构建号、平台、系统版本 | 2.5 | ✅ | |
| FR-6 | 区分生产、stag、模拟器、TestFlight、侧载 | 2.5 | ◐ | Android null 的处理（🟡-8） |
| FR-6 | stag 包发往 stag | 2.5 | ✅ | |
| FR-7 | 超限返回 400 | 1.3 | ✅ | |
| FR-7 | 单条拒收，同批照收 | 1.3 | ◐ | 必填字段没定义；拒收范围比架构严（I-7、I-8） |
| FR-7 | 登录和未登录都能报 | 1.3 | ✅ | |
| FR-8 | 设备 / IP 限流，429 + `Retry-After`，IP 不落库 | 1.3 | ◐ | CGNAT 误伤；`Retry-After` 没有现成实现（I-11） |
| FR-8 | 不能冒充他人，失败丢弃并计数 | 1.4 | ⇄ / ◐ | 架构改为去标识后保留，未记录；`user_identified` 自身校验没写 |
| FR-8 | 不拖累业务 | 1.3（L2 压测） | ◐ | 压测会打到共用的生产 PG（I-15）；队列内存（I-12） |
| FR-9 | 按事件 id 去重 | 1.5 | ◐ | 批内跨日重复（I-9） |
| FR-9 | 时间校正，保留两个时间 | 1.5 | ✅ | |
| FR-9 | 可疑事件不进汇总 | 1.5 + Epic 4 通用规则 | ◐ | 可疑事件进 DEFAULT 分区（C-3）；没有容差 |
| FR-10 | 立即返回，批量写库 | 1.3 | ✅ | |
| FR-10 | 队列有上限，满返回 503 | 1.3（429） | ⇄ | 按条数设上限的内存风险（I-12） |
| FR-10 | 优雅停机写完队列 | 1.3 | ✅ | |
| FR-11 | 不写业务库，prod / stag 各一个 | 1.1 | ✅ | |
| FR-11 | 连接配额，不挤占业务连接 | 1.1 | ◐ | 没有满载验证；角色共享（🟡-16）；连接预算没算全（I-13） |
| FR-11 | 不建外键 | 隐含（分库） | ✅ | |
| FR-12 | 预建分区 | 1.2 | ✅ | |
| FR-12 | 超期整块删除 | 1.5 | ✅ | |
| FR-12 | 失败告警，不影响接收 | 1.2 | ✅ | |
| FR-13 | 10 分钟内可查 | 1.5 | ◐ | 水位竞态（I-9） |
| FR-13 | 生产标记（环境 + 内部名单） | 1.5 | ⇄ / ◐ | 名单改为 role 判定；服务端事件没有 `user_id`（I-4） |
| FR-13 | 超期按分区删除 | 1.5 | ◐ | DEFAULT 分区永不过期（C-3） |
| FR-13 | 可从原始层重算 | 1.5 | ✅ | |
| FR-13 | 单行瘦身（A9） | 1.5（D-4） | ⇄ | |
| FR-14 | 保存全部关联及首次时间 | 1.4 | ◐ | 唯一键丢重登（I-10） |
| FR-14 | 登录前匿名事件归到用户，不回写 | Epic 4 通用规则、4.1 | ❌ | 规则写反了（C-1） |
| FR-14 | 多用户按事件时间判定 | D-10 | ◐ | 同上 |
| FR-15 | 删关联，清空明细和原始层的用户标识 | 1.6 | ◐ | 属性 JSON 漏清（I-14）；原始层擦除超时（I-13） |
| FR-15 | 汇总不变 | 1.6 | ◐ | 汇总里有个人粒度的表（I-17） |
| FR-15 | 24 小时生效，可重试，有日志 | 1.6 | ✅ | |
| FR-16 | 每天记录占用和月增长 | 1.8 | ✅ | |
| FR-16 | 超 40 GB 告警 | 1.8 | ✅ | |
| FR-16 | 接近 10 倍时提示切回第三方 | 1.8 | ◐ | 阈值没有数值定义 |
| FR-17 | 核对已覆盖的运营位 | 3.1 | ✅ | |
| FR-17 | 补齐其余运营位，曝光按 50% | 3.1 | ◐ | 新人券弹窗的 id 没定义 |
| FR-17 | 带 id，能与后台关联判断是否推广 Toko | 3.1、4.4 | ❌ | 没有 story 把「是否推广 Toko」带进埋点库（🟡-11） |
| FR-18 | 全部推送统一事件，带类型 | 3.2 | ◐ | 冷启动时序（🟡-12） |
| FR-18 | 运营通知带 id，按单条或按类型统计 | 3.2、4.4 | ✅ | |
| FR-18 | 旧事件保留，以统一事件为准 | 3.2 | ✅ | |
| FR-19 | 加购带商品 id、数量、单价 | 3.3 | ✅ | id 形式与业务库关联没定义（I-20） |
| FR-19 | 移出事件带原因 | 3.3 | ✅ | |
| FR-19 | 改数量事件 | 3.3 | ✅ | |
| FR-19 | 下单不算移出 | 3.3 | ✅ | |
| FR-20 | 漏斗与引导事件名核对，以代码为准修正口径 | 3.4 | ✅ | |
| FR-21 | WIB 凌晨覆盖全部 B、C 类，遵守 §4.5.1 | 4.1–4.6 | ◐ | 周期去重指标缺表（C-2） |
| FR-21 | 幂等、续算、告警 | 4.1 | ✅ | |
| FR-21 | 只算生产，按人去重 | Epic 4 通用规则 | ◐ | 「人」的规则写反（C-1） |
| FR-21 | 长期保留，不含个人字段 | 4.1 | ◐ | 与 I-17、C-2 冲突 |
| FR-22 | 分读两库、不建外键 | 4.4–4.6 | ✅ | |
| FR-22 | 与业务库已支付订单一致 | 4.4（抽样） | ◐ | 4.5、4.6 没有一致性 AC；`PAID` 不存在（I-2） |
| FR-23 | 运营推送发送 / 接收取发送记录，注明近似 | 4.4 | ◐ | 表取错（I-18） |
| FR-23 | 系统推送有记录的取发送数，没有的注明 | 4.4 | ◐ | A13 没有勘察任务 |
| FR-23 | 打开数取统一事件 | 4.4 | ✅ | |
| FR-24 | 每天写入 GA4 日活和覆盖率 | 4.2 | ◐ | 需要扩展 GA4 客户端；GA4 有延迟（I-6） |
| FR-24 | 观察期内日活类指标用 GA4 | 4.6 | ✅ | |
| FR-24 | 切换后记录日期 | 4.2 | ◐ | 是流程项，不可测 |
| FR-25 | 每天产出五项 | 4.2 | ✅ | |
| FR-25 | 覆盖率 ≥90% 后 4 周，差值 ≤10% | 4.2 | ◐ | 两边口径不可比（I-6） |
| FR-25 | 存库，不推飞书 | 4.2 | ✅ | |
| FR-26 | 每个指标都有完整条目 | 4.7 | ✅ | |
| FR-26 | AI 能读到并按口径计算 | 4.7（L2） | ✅ | |
| FR-26 | 变更有记录 | 4.7 | ✅ | |
| FR-27 | 独立只读账号，能读指定表，不能写 | 1.1、4.7 | ◐ | 默认授权越权（I-5） |
| FR-27 | 原始层是否开放由架构定 | A8（不开放） | ◐ | 同上，实际会被开放 |
| FR-27 | 跨库问题用预拼的汇总回答 | 4.7 | ◐ | 依赖 C-2 补齐周期表 |

### NFR 覆盖

| NFR | Story / AC | 状态 | 缺口 |
|---|---|---|---|
| 不拖累业务：P95 增幅 ≤10%，低峰汇总 | 1.3（L2）、4.1（03:00） | ◐ | 压测打到共用的生产 PG（I-15）；没有验证 `DetailBuilder` 每分钟跨库读 `users` 和汇总读业务库的负载；队列内存（I-12）；埋点库故障会阻塞启动（I-3） |
| 零新组件 | 全部 | ✅ | 内存队列破例已记录 |
| 隐私：不存 PII / IP；标识不可枚举；注销匿名化；日志不记标识 | 1.3、1.6 | ◐ | 属性 JSON 漏擦（I-14）；`analytics_reader` 越权（I-5）；汇总里有个人粒度的表（I-17） |
| 可搬走：Segment 语义 | 1.2（字段） | ✅ | |
| 容量：10 倍 | 1.3 压测、1.8 水位 | ◐ | 擦除和汇总在 10 倍量下会触发 `statement_timeout`（I-13） |
