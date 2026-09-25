---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 3
story: 3.4
ad: [AD-S9]
decisions: []
fr: [SHOP-FR-24, SHOP-NFR-03, SHOP-NFR-06]
---

# Story 3-4: 新订单 Lark 提醒

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。
> 本 story **纯后端**，无 App 端改动。主线 C（履约效率）在本版的唯一条目。

## Story

As a 运营同事，
I want 有新订单付款成功时 Lark 主动告诉我一声，
so that 我不用一直靠人工打开后台页面盯着，发货时效不再取决于有没有人想起来刷。

## Context（现状与根因）

**痛点原话**（UJ-5 ⑤）：运营接单靠「人工去打开页面盯」，**零通知**。这是运营的重复提报。

**触发点已就位**：`shop/order/service/ShopOrderPaidHandler.java:46` 的 `onPaid(PaymentIntentPaidEvent)`，只处理 `purpose == SHOP_ORDER`（`:48-50`），拿到 `ShopOrder` 后完成状态迁移与 PawCoin 段结算。

🔴 **注意它的事务语义**：`@Transactional(propagation = Propagation.MANDATORY)`（`:45`）—— 它必须挂在支付回调的事务里。**提醒的写入不能让这个事务变重、更不能让它失败**（`:52-57` 的注释写得很清楚：抛异常会把整个回调事务连同意图的 `markPaid` 一起回滚，"那才是真的丢账"）。

🔴 **Lark 发消息的能力目前不存在**，需要在本 story 新建：
- `content/larksync/LarkContentClient.java:38` 只封装了 **Sheets 与 Drive** 接口（`:106`/`:129`/`:155`/`:182` 表格读写、`:203` 文件下载），**没有发消息的方法**。
- `admin/account/service/LarkOAuthClient.java` 是后台登录用的 OAuth 客户端，也不发消息。
- 全仓 grep `open-apis/im` 无命中。
- ✅ **可复用的是 tenant_access_token 的获取与缓存范式**：`LarkContentClient.java:73`（`/open-apis/auth/v3/tenant_access_token/internal`）+ `:46-72` 的缓存（过期前 5 分钟刷新）。

**配置范式**已就位：`application.yml:111-122` 的 `lark-content` 段，`mode: ${LARK_CONTENT_MODE:off}`，**默认 off 时任务静默、不打任何 Lark API**，凭证全 env 注入。本 story 照抄这个形状。

**定时扫描范式**已就位：`notify/schedule/ScheduledPushJob.java:24` —— Spring 原生 `@Scheduled` + cron 可配 + `@Async` 逐条投递 + **DB 唯一约束去重**（不用分布式锁）。类注释明写「禁 Quartz / Kafka / 任何调度或消息中间件」。另一个同类范例是 `shop/order/service/ShopOrderExpiryScanner`。

## Acceptance Criteria

**AC1 · 订单落入待提醒队列，且绝不拖累支付事务**
**Given** 一笔电商订单支付到账
**When** `ShopOrderPaidHandler.onPaid` 完成状态迁移与 Coin 段结算
**Then** 该订单被登记为「待提醒」（新表一行，见 AC2）`[L1]`
**And** 🔴 登记失败**不得**让 `onPaid` 抛异常、不得回滚支付事务；失败只记 `warn` 日志 `[L1]`
**And** 登记是**幂等**的：同一订单重复到账事件只产生一行（唯一约束兜底）`[L1]`

**AC2 · 待提醒表**
**Given** 无任何提醒相关的表
**When** 执行迁移 `V<yyyyMMdd_HHmm>__init_shop_order_notify_queue.sql`（取创建时刻）
**Then** 新增表，至少含：`id` · `shop_order_id BIGINT NOT NULL`（唯一约束，幂等键）· `status VARCHAR(16) NOT NULL`（`PENDING` / `SENT` / `FAILED`）· `retry_count INT NOT NULL DEFAULT 0` · `sent_at TIMESTAMPTZ NULL` · `created_at` / `updated_at` `[L1]`
**And** 表与列有 `COMMENT`，说明它是「汇总提醒的待发队列」而非通知中心 `[L0]`
**And** 迁移用**时间戳版本号**，提交前 `bash scripts/ci/check-flyway-versions.sh origin/main` 通过 `[L0]`

**AC3 · 🔴 汇总节流，绝不每单一条**
**Given** 同一个时间窗内有 3 笔订单付款
**When** 扫描任务运行
**Then** **只发一条**消息，内容里列出这 3 笔 `[L1]`
**And** 跨窗口的订单分成两条消息发 `[L1]`
**And** 窗口长度可配（`petgo.shop.order-notify.window-minutes`，默认 10）`[L0]`
**And** 窗口内没有待发订单时**不发空消息** `[L1]`

**AC4 · 扫描与投递范式**
**Given** 待提醒队列
**When** 实现扫描器
**Then** 用 Spring 原生 `@Scheduled`（cron 可经配置覆盖）+ `@Async` 投递，照 `ScheduledPushJob` / `ShopOrderExpiryScanner` 范式 `[L0]`
**And** 🔴 **不得引入** Quartz / Kafka / Redis Stream / 任何队列或调度中间件（SHOP-NFR-06）`[L0]`
**And** 投递成功 → `status=SENT` + `sent_at`；失败 → `retry_count+1` 且保持 `PENDING`，下一轮重试；超过 3 次 → `FAILED` 并记 `error` 日志，不再重试 `[L1]`

**AC5 · 消息内容不含个人信息**
**Given** 一条汇总提醒
**When** 渲染消息文本
**Then** 每笔订单只含：订单号（`display_no`，依赖 Story 4-3；4-3 未完成时用现有展示号）、金额、商品件数 `[L0]`
**And** 🔴 **不含**收件人姓名、电话、地址、下单人昵称与邮箱（SHOP-NFR-01）`[L0]`
**And** 有单测逐字段断言消息文本里不出现上述任何一项 `[L0]`

**AC6 · Lark 客户端与开关**
**Given** 无发消息能力
**When** 新增 Lark 消息客户端
**Then** 复用 `LarkContentClient` 的 tenant_access_token 获取与缓存范式（不要复制第二份 token 缓存逻辑，优先抽取共用）`[L0]`
**And** 配置照 `lark-content` 形状：`mode`（默认 `off`）· `app-id` / `app-secret`（env 注入，绝不入库）· 目标（群或个人）· cron · 窗口长度 `[L0]`
**And** `mode=off` 时扫描任务**静默跳过，不产生任何出网请求**，队列行保持 `PENDING` `[L1]`
**And** `.env.example` 只放占位 `[L0]`

**AC7 · 发送失败不影响订单**
**Given** Lark 接口超时或返回错误
**When** 投递失败
**Then** 订单本身状态、库存、资金**全部不受影响** `[L1]`
**And** 失败只落队列行与日志，不写通知中心、不给用户任何感知 `[L0]`

**AC8 · 收件人不写死**
**Given** OD-5（发给谁 / 窗口多长 / 夜间是否静默）**尚未拍板**
**When** 实现
**Then** 收件人（群 ID 或个人 ID）**一律从配置读，代码里不出现任何具体 ID** `[L0]`
**And** 夜间静默做成可选开关（默认关闭），定值前不启用 `[L0]`
**And** story 完成时在 Completion Notes 记下当前默认值，供 OD-5 拍板后调整 `[L0]`

**AC9 · 时效**
**Given** 一笔订单付款成功
**Then** 提醒在 **10 分钟内**送达（SHOP-NFR-03）`[L2]`

**AC10 · 回归**
**Then** 既有 `ShopOrderPaidHandler` 相关测试全绿；`mvn -B clean package` 通过 `[L0/L1]`

---

## Tasks / Subtasks

- [ ] **T1 · 待提醒表与实体**（AC2）
  - [ ] 新建迁移（时间戳号）+ 实体 + repository
  - [ ] `shop_order_id` 唯一约束（幂等键）；`status` 用 `varchar` + UPPER_SNAKE（基线命名约定）
  - [ ] 🔴 `ddl-auto=validate`：列类型必须与实体对上；**小整数列别用 SMALLINT 配 int**（Hibernate 映 int 会 validate 失败，仓内踩过）
  - [ ] 跑 `bash scripts/ci/check-flyway-versions.sh origin/main`

- [ ] **T2 · 在 onPaid 里登记**（AC1）
  - [ ] `ShopOrderPaidHandler.onPaid` 末尾登记待提醒
  - [ ] 🔴 **整段包 try/catch**，异常只记 `warn`：这个方法是 `MANDATORY` 传播，抛出去会回滚支付回调事务
  - [ ] 幂等：先 `existsByShopOrderId` 再插，唯一约束兜底

- [ ] **T3 · Lark 消息客户端**（AC6）
  - [ ] 新建客户端；**优先把 `LarkContentClient:46-72` 的 token 缓存抽成共用**，抽不动再复制并注明原因
  - [ ] 配置类照 `LarkContentSyncProperties` 形状；`mode=off` 短路
  - [ ] 出网必须有超时（照 `PostHogAnalyticsClient` 的做法，无超时会把线程池挂满）

- [ ] **T4 · 扫描与汇总投递**（AC3、AC4、AC5、AC7）
  - [ ] `@Scheduled` 扫描器：取窗口内 `PENDING` 行 → 聚合成一条消息 → `@Async` 投递 → 回写状态
  - [ ] 🔴 消息文本只放订单号 / 金额 / 件数；写单测逐项断言无 PII
  - [ ] 重试与终止：`retry_count` 超 3 转 `FAILED`
  - [ ] 空窗口不发

- [ ] **T5 · 配置与文档**（AC6、AC8）
  - [ ] `application.yml` 新段 + `.env.example` 占位
  - [ ] 在 story 的 Completion Notes 记下默认 cron、窗口、收件人配置项名，供 OD-5 拍板

- [ ] **T6 · 测试**（AC1~AC10）
  - [ ] L1：登记幂等、登记失败不回滚支付、三笔合一条、跨窗口分两条、空窗口不发、`mode=off` 静默、失败重试与终止
  - [ ] L0：消息文本无 PII、配置默认值、收件人不硬编码

---

## Dev Notes

**相关既有代码**
| 用途 | 位置 |
|---|---|
| 触发点 | `shop/order/service/ShopOrderPaidHandler.java:46`（`MANDATORY` 事务，勿抛异常） |
| token 缓存范式 | `content/larksync/LarkContentClient.java:46-73` |
| 配置范式 | `application.yml:111-122`（`lark-content`，`mode` 默认 off） |
| 定时扫描范式 | `notify/schedule/ScheduledPushJob.java:24`、`shop/order/service/ShopOrderExpiryScanner` |
| 出网超时范式 | `shared/analytics/PostHogAnalyticsClient`（`SimpleClientHttpRequestFactory` + 超时） |

**与其它 story 的关系**
- **Story 4-3** 会把订单展示号改为不可推算的新号并落库。本 story 的消息里用 `display_no`；4-3 未完成时先用现有展示号，4-3 完成后**不需要改本 story 的代码**（读的是同一个字段）。
- 本 story 与 Epic 3 其余三条**无依赖**，可并行。

**不做什么**
- 不发给用户、不进通知中心、不做 App 推送 —— 这是给运营的运维信号。
- 不做「发货超时未处理」的二次提醒（本版不在范围）。
- 不引入任何中间件。

## 验收与交付

- **L0**：`mvn -B clean package`；`bash scripts/ci/check-flyway-versions.sh origin/main`
- **L1**：Docker postgres + redis 起本地库，跑本 story 新增的集成测试（登记幂等 / 不回滚支付 / 汇总 / 重试 / `mode=off` 静默）
- **L2**：配好 Lark 凭证与目标群，真实下一单，确认 10 分钟内群里收到一条汇总提醒且不含任何个人信息
- 云端执行时 Completion Notes 必须写「L1/L2 待本地验收」，并记下默认配置值供 OD-5 拍板

## Definition of Done

- [ ] AC1~AC10 全部满足，每条标注的层级都已验证（或注明待本地）
- [ ] 迁移用时间戳版本号且 CI 检查通过
- [ ] `mode=off` 时零出网（默认状态下 CI 与本地不打 Lark）
- [ ] 消息文本无 PII，有单测钉住
- [ ] 收件人不硬编码，配置项名与默认值写进 Completion Notes
- [ ] 既有测试全绿，`mvn -B clean package` 通过

---

## Dev Agent Record

**执行环境**：云端 headless session（claude.ai/code），仅跑 **L0**。
**状态**：`review`（未 done）。**L1 / L2 待本地验收**。

### 新增 / 修改文件

| 文件 | 说明 |
|---|---|
| `db/migration/V20260916_1035__init_shop_order_notify_queue.sql` | 新表（时间戳版本号，flyway-guard 通过） |
| `shop/order/notify/ShopOrderNotifyQueueEntry.java` | 队列实体（`retry_count` 用 `INTEGER`/`int` 配对，避开 SMALLINT-validate 坑） |
| `shop/order/notify/ShopOrderNotifyQueueRepository.java` | `existsByShopOrderId` + 按 `(status, created_at)` 升序取待发 |
| `shop/order/notify/ShopOrderNotifyProperties.java` | `petgo.shop.order-notify.*` |
| `shop/order/notify/ShopOrderNotifyConfig.java` | `@EnableConfigurationProperties` |
| `shop/order/notify/ShopOrderNotifyMessage.java` | 🔒 纯函数渲染，只收 `Line(displayNo, totalAmount, itemCount)` |
| `shop/order/notify/LarkMessageClient.java` | `/open-apis/im/v1/messages` + tenant token 缓存 |
| `shop/order/notify/ShopOrderNotifyService.java` | 登记（`REQUIRES_NEW`）+ 窗口收拢 + 回写，三个独立短事务 |
| `shop/order/notify/ShopOrderNotifyScanner.java` | 薄 `@Scheduled` 扫描器 |
| `shop/order/service/ShopOrderPaidHandler.java` | 末尾 try/catch 登记 |
| `application.yml` / `.env.example` | 配置段与占位 |

测试：`ShopOrderNotifyMessageTest`(5) · `ShopOrderNotifyPropertiesTest`(7) · `ShopOrderNotifyScannerTest`(10) · `LarkMessageClientTest`(4) · `ShopOrderNotifyIntegrationTest`(9, L1) · `ShopOrderPaymentIntegrationTest` +2(L1)。

### AC 完成情况

| AC | 层级 | 状态 |
|---|---|---|
| AC1 登记 + 不拖累支付事务 | L1 | 代码完成（`REQUIRES_NEW` + try/catch 双层）；**L1 待本地** |
| AC2 待提醒表 | L0/L1 | 迁移 + COMMENT + flyway-guard ✅；约束生效性 **L1 待本地** |
| AC3 汇总节流 | L0/L1 | 窗口可配 ✅ L0；三笔合一条 / 跨窗分两条 / 空窗不发 **L1 待本地**（L0 已用 mock 钉住「一批只调一次 `sendText`」「空批不调」） |
| AC4 扫描与投递范式 | L0/L1 | `@Scheduled` + cron 可配 ✅；无任何中间件 ✅；重试/放弃 **L1 待本地** |
| AC5 消息无 PII | L0 | ✅ **含变异验证，见下** |
| AC6 客户端与开关 | L0/L1 | 配置 ✅；`mode=off` 零出网 L0 已用 mock 钉住（AC 原标 L1），真实静默 **L1 待本地** |
| AC7 失败不影响订单 | L0/L1 | 扫描器只读订单只写队列 ✅ L0；**L1 待本地** |
| AC8 收件人不写死 | L0 | ✅ 默认值见下 |
| AC9 10 分钟内送达 | L2 | **待本地/线上验收**（需真实 Lark 凭证与目标群） |
| AC10 回归 | L0 | ✅ 1734 个单测全绿 |

### 🎯 变异验证（AC5 PII 红线）

- **变异内容**：在 `ShopOrderNotifyMessage.render()` 的行拼接末尾加上 `.append(" | Budi Santoso")`（模拟「顺手往提醒里加个收件人」）。
- **结果：2 条用例变红，且红得对**
  - `ShopOrderNotifyMessageTest.neverContainsAnyPersonalInformation` —— 报「消息里出现了 receiverName = "Budi Santoso"」
  - `ShopOrderNotifyMessageTest.eachLineHasExactlyThreeSegments` —— 报「Expected size: 3 but was: 4」
- 已还原，还原后全绿。
- 补充：`render` 的签名**只收三字段的 `Line`**，拿不到 `ShopOrder`，所以「加 PII」这件事在编译期就要先改签名 —— 签名本身是第一道护栏，上面两条断言是第二道。

### 🔴 OD-5 待拍板：当前默认配置值

配置前缀 `petgo.shop.order-notify`（全部可经 env 覆盖，**代码里没有任何具体收件人 id**）：

| 配置项 | env | 默认值 | 备注 |
|---|---|---|---|
| `mode` | `SHOP_ORDER_NOTIFY_MODE` | `off` | 🔴 默认关，合并进任何环境都不出网 |
| `receive-id` | `SHOP_ORDER_NOTIFY_RECEIVE_ID` | **空** | 🔴 OD-5「发给谁」未定；空 = 视同 off |
| `receive-id-type` | `SHOP_ORDER_NOTIFY_RECEIVE_ID_TYPE` | `chat_id` | 发个人改 `open_id` |
| `app-id` / `app-secret` | `SHOP_ORDER_NOTIFY_APP_ID` / `_SECRET` | 空 | env 注入，绝不入库 |
| `base-url` | `SHOP_ORDER_NOTIFY_BASE_URL` | `https://open.larksuite.com` | 国内租户换 `open.feishu.cn` |
| `window-minutes` | `SHOP_ORDER_NOTIFY_WINDOW_MINUTES` | `10` | 🔴 OD-5「窗口多长」未定 |
| `cron` | `SHOP_ORDER_NOTIFY_CRON` | `0 */5 * * * *` | 必须短于窗口 |
| `max-orders-per-message` | `SHOP_ORDER_NOTIFY_MAX_ORDERS` | `50` | |
| `max-retries` | `SHOP_ORDER_NOTIFY_MAX_RETRIES` | `3` | 超限转 `FAILED` |
| `timeout-seconds` | `SHOP_ORDER_NOTIFY_TIMEOUT_SECONDS` | `10` | |
| `quiet-hours-enabled` | `SHOP_ORDER_NOTIFY_QUIET_ENABLED` | `false` | 🔴 OD-5「夜间是否静默」未定，默认关 |
| `quiet-start-hour` / `quiet-end-hour` | `SHOP_ORDER_NOTIFY_QUIET_START` / `_END` | `22` / `8` | WIB，跨午夜已 L0 逐小时钉住 |

### 🔶 与 story 文字的偏离（各一行，均已在代码注释里写明理由）

1. **AC4 的「`@Async` 投递」未实现，扫描器是同步的。**
   AC4 同时点名了两个范式，其中 `ShopOrderExpiryScanner` 全篇没有 `@Async`；本 story 的形状也更像它。
   `ScheduledPushJob` 的 `@Async` 是**逐条**投递几百条互相独立的推送，异步化省的是串行等待；
   本 story 一个窗口**只发一条** HTTP，异步化省不到任何东西，却会引入一个真问题 ——
   `@Scheduled` 立刻返回后队列行仍是 `PENDING`，下一次 cron 唤醒把同一批再捞一次，**运营收到重复的汇总消息**。
   要修就得加 `SENDING` 中间态 + 崩溃后的回收扫描，比这条提醒本身重得多。
   **若 OD-5 或架构方坚持要 `@Async`，请连同 `SENDING` 中间态一起排，不要只加注解。**

2. **AC6 的「不要复制第二份 token 缓存逻辑，优先抽取共用」—— 复制了，未抽取。**
   `LarkContentClient` 的 token 缓存是它的私有字段，抽出来要动一条**跑在生产上**的定时发帖链路。
   T3 允许「抽不动再复制并注明原因」。两份的行为必须保持一致，`LarkMessageClient` 类注释里已写明交叉引用。

3. **AC5 的 `display_no` 依赖 Story 4-3，当前用 `OrderDisplayNo.of(ECOMMERCE, id, createdAt)` 计算。**
   `ShopOrderNotifyService.toLine()` 里留了 `TODO(Story 4-3)`。这是 3-2 / 3-3 之外的**第三处**同一次切换点。

### 🔍 自审发现并修复（本 session，无 bmad-code-review 技能可用）

1. **🔴 token 过期会让提醒永久静默（已修）**：`LarkMessageClient.sendText` 原本只在 `RestClientException`
   分支作废 token 缓存。但 Lark 对失效的 `tenant_access_token` 返回的是 **HTTP 200 + body `code` 非 0**
   （99991663 / 99991661 / 99991664），根本不走那个分支 —— 结果是拿着同一个坏 token 一直重试，
   `max-retries=3` 撑不过三轮，队列里的订单全部转 `FAILED`，**提醒从此静默直到进程重启**。
   已改为 `ensureOk` 失败时也作废缓存，并用 `LarkMessageClientTest.nonZeroBodyCodeInvalidatesTheCachedToken`
   （进程内 HTTP 桩，不出网）钉住「失败后必须重新取 token」。
2. **事务跨出网调用（已修）**：扫描器原本把 `@Transactional` 直接标在 `@Scheduled` 方法上，
   一个连接池连接会被一次 Lark 往返（默认超时 10s）占住整整 10 秒。
   已按 `ShopOrderExpiryScanner` 的形状重构成「薄扫描器 + 三个独立短事务」，出网在事务之外。
3. **跨午夜静默窗口是纯逻辑却测不到（已修）**：原 `isQuietNow()` 直接读时钟，测试只能断言
   `isIn(true,false)` —— 一条**永远不会红**的用例。已抽出纯函数 `isQuietAt(int hour)`，
   L0 逐小时钉死 22/23/0/3/7 静默、8/9/12/18/21 不静默。22:00–08:00 这种窗口若误写成「与」分支，
   开关打开了却永远不生效且不报任何错。
4. `ShopOrderNotifyProperties.isLive()` 对 `receiveId == null` 会 NPE（已加空值判断）。
5. `ShopOrderNotifyQueueRepository` 的 javadoc 原本写「上界是窗口右端」，与代码传 `now` 矛盾
   ——**以代码为准**，javadoc 已改写为「升序是窗口切分的前提」。
6. `application.yml` 一度插出**第二个 `petgo.shop:` 键**（YAML 后者覆盖前者，会静默丢掉整段既有电商配置）。
   已合并进既有块，并用 `yaml.safe_load` 验证。

### ⚠️ 待本地/线上验收清单

- **L1（需 Docker postgres + redis）**：`ShopOrderNotifyIntegrationTest`（9 条：登记幂等 / 唯一约束兜底 /
  status CHECK / 三笔合一批 / 跨窗分两批 / 空队列不发 / 孤儿行退队 / markSent 打戳 / 重试三次转 FAILED /
  失败不动订单）+ `ShopOrderPaymentIntegrationTest` 新增 2 条（到账后登记 / **登记炸了支付照样到账且库存照扣**）。
- **L2**：配好 Lark 凭证与目标群，真实下一单，确认 10 分钟内群里收到**一条**汇总提醒，
  且消息里**没有**收件人姓名 / 电话 / 地址（AC9 + AC5 的线上确认）。
- **OD-5**：上表的默认值需运营拍板后经 env 调整；拍板前保持 `mode=off`。
- **推送限制**：本分支**无 git remote**（云端 clone 未配置 origin），story 无法 push，仅本地提交。
