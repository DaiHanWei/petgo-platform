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

Status: ready-for-dev

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
