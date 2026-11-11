# PRD 对账：决策 / 缺口 / 研究约束 × prd.md

> 2026-11-11 · 只读对账，未改 prd.md
> 输入：decisions.md（D1–D9、不做清单、G1–G6、待定）、.decision-log.md（PRD 阶段决定）、research/technical-analytics-pipeline-research-2026-11-11.md（技术概况）
> 代码核实：`petgo_app/lib/core/analytics/analytics.dart`、`core/router/app_router.dart`、`shared/widgets/app_shell.dart`、`core/push/push_service.dart`、`features/ops/presentation/ops_popup_coordinator.dart`；PostHog 原生 SDK（iOS Pods `PostHogAppLifeCycleIntegration.swift`，Android `posthog-android-3.76.2` 反编译）

## 一、结论速览（需要改 PRD 或补决定的）

| # | 级别 | 问题 | 涉及 |
|---|---|---|---|
| 1 | 高 | **老用户升级到 V1.5.0 首启会被全部算成「新安装」**：FR-3 以「本地无自建安装标记」判定 `Application Installed`，而存量用户本地没有这个标记，发版后安装数会暴涨，D1/D7 留存的分组也全错 | FR-3、G1、S2、市场 #1–#3 |
| 2 | 高 | **跨库查询在 Postgres 里做不到**：埋点库与业务库分库（D4）后，C 类指标和 AI 查数的即席关联都需要跨库；PG 原生不支持跨 database 关联（要 `dblink` / `postgres_fdw` 扩展，与「不装扩展」相冲），AI 查数现有账号也是「单库 + 连接上限 3 + 语句超时 30 秒」 | FR-19、FR-23、D4、D8 |
| 3 | 高 | **磁盘按 12 个月明细层累计会逼近上限**：研究估算原始事件 1.5–7.5GB/月；明细层字段「尽量保留全」保留 12 个月，上限情形约 90GB + 原始 35 天约 9GB，再加 stag 埋点库，占掉余量 131G 的大部分；SM-4 只约束「每月增长」，没有总量上限、磁盘告警或「到线即切三方」的触发条件 | D5、D6、SM-4、定位 |
| 4 | 中 | **推送打开按推送 id 关联对不上**：`toko_coupon_expiry_push_opened` 只带 `coupon_count`，没有任何推送 / 批次 id；券过期推送也不走 `ops_notification_campaigns`，FR-20 的「按推送 id 关联」只对运营通知成立 | FR-20、G3、S5 |
| 5 | 中 | **「以登录凭证为准」会把离线攒的事件记到错的人头上**：FR-2 允许事件在本地留 7 天、换账号后再补发，此时请求带的是新账号凭证；FR-8 规定以凭证为准，会把 A 账号的旧事件归给 B | FR-2、FR-5、FR-8 |
| 6 | 中 | **「后端服务端事件不改发自建库」是 PRD 新做的决定，未进决策日志**：decisions.md 把它列为「待定」，PRD §5 / §6.2 直接定为不做，.decision-log.md 无对应条目 | decisions 待定第 2 条、§5 |
| 7 | 低 | **D8「固定看板等指标收齐再定」被写成「不做可视化看板」**：语义从「待定」收紧为「不做」；作为 MVP 范围合理，但应记一条日志说明 | D8、§5 |
| 8 | 低 | **UJ-3 写「Dai 每天收到对账结果」**，而决策日志是「对账结果不进飞书日报，只存库供 AI 查数」，「收到」的说法与之矛盾；UJ-3 是「连续多日超阈值标红」，FR-22 是「超阈值即标红」，两处不一致 | UJ-3、FR-22 |

## 二、逐条对照

### 2.1 大方向决策 D1–D9

| # | 决策 | PRD 体现 | 判定 |
|---|---|---|---|
| D1 | 接收做在现有 Spring Boot 内、独立模块、不加中间件 | §4.2 描述、FR-7 | 一致 |
| D2 | 两端批量（端 20 条 / 30 秒、切后台与网络恢复补发；后端内存队列立即回成功、500 条 / 数秒写库） | FR-2、FR-10 | 一致；参数标了 ASSUMPTION |
| D3 | 崩溃丢数秒可接受；正常发版先停接收、排空再退 | FR-10 | 一致 |
| D4 | 同实例独立埋点库、独立账号与连接数、不建外键、复制业务字段 | §4.3 描述、FR-11、FR-19 | 一致；但「复制业务字段」与跨库读取的取舍留给架构，见问题 2 |
| D5 | 原始层按天分区、35 天、整分区删除、不归档 | FR-12 | 一致 |
| D6 | 明细层保留 12 个月、字段尽量全 | FR-13 | 一致；磁盘影响见问题 3 |
| D7 | 汇总层只做团队要看的指标、长期保留 | §3 术语、FR-18、SM-C1 | 一致 |
| D8 | 优先 AI 查数；固定看板指标收齐再定 | FR-23、§5「不做可视化看板」 | 轻微走样，见问题 7 |
| D9 | 日活以 GA4 为准；并行算含游客日活、每日核对；阈值观察期 PRD 定 | FR-22、SM-2 | 一致；阈值已按日志落定 |

「定位」三条（过渡方案、门面单入口、事件格式对齐通用结构、口径写文档）：§1、FR-1、§3 术语、FR-21 均已体现。但「体量涨到约 10 倍时考虑切回」**没有落成可观测的触发条件**（日活、月事件量或磁盘占用到多少就启动迁移评估），见问题 3。

### 2.2 不做清单

| 不做项 | PRD | 判定 |
|---|---|---|
| 不用 Firebase / GA4 + BigQuery 存原始事件 | §5 | 一致 |
| 不引入 TimescaleDB / ClickHouse / 列存扩展 / pg_partman / pg_cron | §5、§4.3 描述「不装扩展」 | 一致；注意问题 2 若选 `postgres_fdw` 即违反此条 |
| 不做原始数据 OSS 归档 | §5、FR-12 | 一致 |

### 2.3 现状缺口 G1–G6

| # | PRD | 判定与代码核实 |
|---|---|---|
| G1 生命周期事件 | FR-3 | 方向对；**安装判定有升级误判（问题 1）**。另：PostHog 原生 SDK 还会发 `Application Updated` 和 `Application Backgrounded`，Opened 带 `from_background` 属性，FR-3 未提，建议保留 `from_background` 以便区分冷启动和回前台 |
| G2 页面浏览 | FR-4 | 一致。代码核实：路由挂 `PosthogObserver()`（`app_router.dart:487`），Tab 切换由 `app_shell.dart:_reportTabEntered` 调 `Analytics.screen('${tab.analyticsName}_page')` 补报，冷启动落地页在 `app_router.dart:480/585` 补报，`toko_page` 即来自 `AppTab.shop` 的 `analyticsName='toko'`。补充：`PosthogObserver` 默认取 `RouteSettings.name` 作页面名、只追踪 `PageRoute`、根路由 `/` 改写为 `root ('/')`；自有观察者要复刻这三点，「页面名与 PostHog 时期相同」才成立 |
| G3 推送接收人数 | FR-20 | 口径与日志一致；**券过期推送无 id 可关联（问题 4）** |
| G4 运营位曝光 | FR-15 | PRD 比 decisions.md 更准：代码里 `ops_popup_shown` / `ops_popup_tapped` / `ops_popup_closed`（带 `popup_id`）已存在（`ops_popup_coordinator.dart:263-275`），decisions.md G4「只有 toko_banner_shown、toko_newbie_coupon_popup_shown 有曝光」已过时。建议回改 decisions.md 或在日志记一句，避免下游按旧表重复补埋点 |
| G5 购物车移除 | FR-16 | 与日志一致（物理删除，补事件） |
| G6 生产 / 测试区分 | FR-6、FR-13 | 一致；侧载 / TestFlight 可识别性已列开放问题 |

### 2.4 PRD 阶段决定（.decision-log.md）

| 决定 | PRD | 判定 |
|---|---|---|
| 范围 = B + C + G1–G6，A 类另做口径 | §0、§5 | 一致 |
| 产物目录不挂版本 | frontmatter / 路径 | 一致 |
| 快速路径、内部工具分量 | 篇幅符合 | 一致 |
| G5 物理删除 → 补事件 | FR-16 | 一致 |
| 新版直接切、不双写 | §4.1 描述、§6.2 | 一致 |
| 汇总 T+1（WIB 凌晨）；明细数分钟可查 | FR-18、FR-13 | 一致 |
| 匿名 id + 关联表、不回写 | FR-5、FR-14 | 一致；归属判定有离线补发问题（问题 5） |
| 推送口径 | FR-20 | 一致；券过期推送缺 id（问题 4） |
| 对账阈值：覆盖 ≥ 90% 后连续 4 周日差 ≤ 10%，观察期 4 周 | FR-22、SM-2 | 一致；FR-22 没写「连续 4 周」，只写在 SM-2，可接受 |
| 对账不进飞书 | FR-22、§6.2 | FR 一致；UJ-3 措辞矛盾（问题 8） |
| 随 V1.5.0 切换 | §4.1 | 一致 |
| （未记录）服务端事件不改发 | §5、§6.2 | 缺日志（问题 6） |

## 三、研究约束是否被 PRD 忽略

| 约束（研究 §0） | PRD 处理 | 影响 |
|---|---|---|
| 同机 8 核 / 23G / 十余容器 | FR-8「业务接口不受影响」、SM-4「P95 无显著变化」 | 基本覆盖；内存队列上限（FR-10）应给出量级，避免与其他容器争内存 |
| `max_connections=100`（默认），生产 + stag 后端 + 只读账号 + 埋点共用 | FR-11 只写「有上限」 | **未给数字**。实例级 100 由生产、stag 两套后端连接池、`claude_bot` / `claude_bot_stag` / `ops_readonly` 共享，再加生产、stag 两个埋点写入池，建议 PRD 写明「埋点写入 + 汇总任务合计连接上限」或至少要求架构列连接预算 |
| `shared_buffers=128MB` 默认 | 未提 | 汇总任务大扫描会挤掉业务库的缓存命中；建议 FR-18 加「汇总任务在低峰跑、限并发」 |
| 磁盘余 131G、原始层不能无限保留 | FR-12 / FR-13 / SM-4 | **12 个月明细层的总量没算（问题 3）**；另缺「磁盘占用告警」需求 |
| 护栏：禁止 MQ / 通用缓存 / 新中间件；异步只用 `@Async` + DB 状态机 | §5、D1 | 内存队列本身不是中间件，但与「异步只用 `@Async` + DB 状态机」字面上有张力；D2 是 Dai 定的，建议在架构里显式记为护栏例外，免得评审时被当违规返工 |
| AI 查数用只读账号直连 Postgres | FR-23 | **只写了授权，没写接入**：现有 agent 每个账号只绑一个 database（如 `claude_bot_stag` 只能读 `petgo_stag`、连接上限 3、语句超时 30 秒），读埋点库需要新连接配置 / 新账号；30 秒超时下查 12 个月明细会大量超时，所以 SM-1「100% 可答」实际依赖汇总层覆盖完整（问题 2） |
| 已有 `user_active_days` 自算日活 | 未提 | 可以作为登录用户日活的第三方对照（自建登录日活 vs `user_active_days`），比 GA4 对账更能隔离「游客」与「覆盖率」两种差异来源；也可用于 SM-3 抽检。PRD 没用到，属遗漏而非冲突 |
| GA4 已接入日报（服务账号、`GA4_PROPERTY_ID`） | FR-22 未说数据从哪取 | 建议注明复用现有 GA4 拉取能力，不新建凭证 |
| 单条大小需 `pg_column_size` 实测 | §8 开放问题 4 | 一致 |
| 客户端已有 PII / 自由文本剥离 | FR-1 | 一致。代码另有「金额键只对 PostHog 剥离」（`posthogMoneyKeys`，2026-11-08 拍板）——这条规则是为 PostHog 定的，换自建后金额要不要进自己的库，PRD 没说，建议补一句 |

## 四、代码事实核实

| PRD 陈述 | 核实结果 |
|---|---|
| FR-1：门面 `Analytics` 统一上报、PII / 自由文本 / 长度上限规则 | 属实：`analytics.dart` 有 `_freeTextKeys`、`_maxStringValueLen = 64`、PII 键判定，上报失败只 `debugPrint` 不抛 |
| FR-2 / D2：PostHog 批量 20 | 属实：`flushAt = kDebugMode ? 1 : 20`；`captureApplicationLifecycleEvents = true`（`analytics.dart:69-72`） |
| FR-4：路由挂 `PosthogObserver`，`goBranch` 补报 screen | 属实：`app_router.dart:487` `observers: [PosthogObserver()]`；补报在 `shared/widgets/app_shell.dart:117`（Tab 切换，且只在真切过去后才报）和 `app_router.dart:480/585`（冷启动落地）。PRD 说「沿用现有补报逻辑」对 |
| FR-5：用户标识不可枚举、哈希还是 token 待定 | 现状 `distinctIdFor` 是**无盐 sha256**，代码注释自承可被反推回自增 id，并且 PostHog 侧还**明文带内部数字 id**（「PostHog 专项豁免」）。自建库是自家库、由后端从凭证解析用户，可以直接存内部 id，客户端是否还需要带用户标识值得在架构里重新判断 |
| FR-20：端上打开事件 `ops_notification_opened`、`toko_coupon_expiry_push_opened` | 名称属实（`push_service.dart:55/68`）。前者带 `campaign_id`（活动 `public_token`）+ `source=push`；**后者只带 `coupon_count`，无推送 id**（问题 4） |
| FR-15：运营弹窗已有曝光 / 点击 | 属实，见 G4 一行；decisions.md 的说法过时 |
| FR-3 假设：PostHog 的 `Application Opened` 含从后台恢复 | **属实，可去掉 ASSUMPTION**。iOS（`PostHogAppLifeCycleIntegration.swift`）：订阅 `didBecomeActive`，以「此前处于后台」为条件发 `Application Opened`，属性 `from_background = !isFreshAppLaunch`；另发 `Application Installed` / `Application Updated`（按存储的版本号判断）/ `Application Backgrounded`。Android（`PostHogLifecycleObserverIntegration`，`ProcessLifecycleOwner` 的 `onStart`）：每次进程回到前台发 `Application Opened`，带 `from_background`、`version`、`build`；`onStop` 发 `Application Backgrounded`。即：冷启动和每次从后台回前台都会计一次 Opened，同一前台期间不重复 |

## 五、建议改动（供 Dai 拍板后改 PRD）

1. FR-3 增加「升级用户不算新安装」的判定规则（例如存在任一旧本地数据 / 已登录态 / PostHog 本地存储即视为老用户，只写安装标记不发 Installed），并在口径文档注明 V1.5.0 切换当周的安装数口径。
2. FR-19 / FR-23 明确：C 类关联只能「复制业务字段进埋点库」或「汇总任务在应用层分别读两库再拼」，排除跨库 SQL；AI 查数需新增埋点库连接，并以汇总层为主要查询面。
3. FR-12 / FR-13 / SM-4 补：埋点库总量上限与磁盘告警；把「体量约 10 倍」落成可观测的迁移评估触发线（日活、月事件量、磁盘占用三选一或组合）。必要时把明细层 12 个月拆成「核心字段 12 个月 + 宽字段更短」。
4. FR-20 写明券过期推送的发送记录来源，并要求打开事件补带推送批次标识（或限定该推送只算总打开数）。
5. FR-8 改为「事件归属以事件携带的、上报时签名或记录的会话身份为准；凭证只用于防伪」，或要求端上换账号前先发完 / 分开旧账号队列。
6. .decision-log.md 补两条：服务端事件不改发；看板本期不做（D8 收紧）。decisions.md G4 回改。
7. UJ-3 改为「Dai 在 AI 查数里查对账结果」，与 FR-22 的标红条件统一。
8. FR-11 给出连接预算；FR-18 要求汇总低峰运行、限并发；FR-1 补一句金额属性在自建库的处理。
