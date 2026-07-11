---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-07-11'
docType: 'architecture-delta'
baseline: '_bmad-output/planning-artifacts/architecture.md'   # V1.0 冻结基线，本文件只写增量/取代
sources:
  - _bmad-output/planning-artifacts/architecture.md            # V1.0 架构（被继承/被取代基准）
  - /Users/dai/work/petGo/V1.1.0/v1-1-0PRD.md                  # V1.1 客户端 PRD（正确性来源）
  - /Users/dai/work/petGo/V1.1.0/v1-1-0后台prd.md               # V1.1 管理后台 PRD
  - /Users/dai/work/petGo/V1.1.0/ux-delta/EXPERIENCE.delta.md  # V1.1 UX delta（体验/行为基准）
  - /Users/dai/work/petGo/V1.1.0/ux-delta/DESIGN.delta.md      # V1.1 UX delta（组件/token）
workflowType: 'architecture-delta'
project_name: 'TailTopia'
user_name: 'Dai'
date: '2026-07-11'
flywayBaseline: 'V46'      # 已上线冻结，本版新增从 V47 起单调顺延
scope: 'PRD→UX→架构 三步之第三步；到架构为止，不含 epics/stories'
---

# TailTopia V1.1.0 架构决策文档 —— Delta

> **形态说明**：V1.0 架构（`architecture.md`, `stepsCompleted:[1..8]`）**已上线冻结**。本文件是 brownfield **增量 delta**，只写 V1.1 的**新增 / 取代 / 补齐**；未提及处一律**继承 V1.0 基线**。冲突序：**本 delta > V1.0 基线被取代条 > 原型**。正确性以 V1.1 PRD 为准，体验/行为以 UX delta 为准。
>
> **底线继承（不重述、全部延续）**：Spring Boot 4 / Java 21 / PostgreSQL + Redis + Flyway；模块化单体；异步只用 `@Async`+DB 状态机、`@Scheduled` 定时；**禁 MQ / 禁调度中间件 / 禁通用缓存层**；DB snake_case ↔ Java/Dart camelCase ↔ JSON camelCase；RFC 9457 ProblemDetail；对外标识不可枚举 token；env 注入凭证不入库；日志禁 PII/健康/令牌/签名 URL；`ddl-auto=validate`，schema 归 Flyway。

---

## §0 决策日志（本 delta 拍板项）

| # | 决策 | 结论 | 来源 |
|---|------|------|------|
| **A-1** | 退款三级职责分离建模（§12 H-8，全系统最高危） | **细粒度权限点 + 同单互斥**：不新增账号角色类型，在既有 SUPER_ADMIN/STAFF 上加 `REFUND_SUBMIT`/`REFUND_APPROVE`/`REFUND_PAYOUT` 三权限点；服务层强制同一 `admin_id` 在同一 `refund_request` 上不可兼任两职（**含 SUPER_ADMIN 也不豁免**，违反即拦截 + 记审计） | 用户 2026-07-11 拍板 |
| **A-2** | 客服「驳回退款需求」后订单落脚态（UX P0#3） | **回落 `COMPLETED` + `refundRejected` 标记/历史**；不新增订单终态枚举；用户收「退款申请未通过」通知。订单状态不再假装在退款 | 用户 2026-07-11 拍板 |
| **A-3** | 支付聚合商选型 | **定向 Midtrans**（Snap/Core API 收款 + Iris/Disbursement 出款），但全部经 `shared/pay/PaymentGateway` 接口抽象，便于日后替换；商务合同另行确认 | 用户 2026-07-11 拍板 |
| **A-4** | 限时支付倒计时遇「跳去充值 PawCoin」 | **暂停顺延**：离开去充值时服务端记 `paused_at`，返回后按剩余时间续；服务端权威计时。（消解 PRD「暂停顺延」vs UX delta「无暂停」冲突，取 PRD） | 用户 2026-07-11 拍板 |
| **A-5** | 兽医咨询「待接单/待支付」是否落为订单 | **两表拆分**：`consult_requests`（付费前临时态，CAS 状态机，取消/超时即删、不留痕、不进订单中心）+ `consult_orders`（支付成功才建、持久、进订单中心）。天然实现「未扣费不建单」「订单中心无已取消态」 | 架构裁定 |
| **A-6** | 虚拟账号（第四类账号）落地 | **复用 `users` 表 + `account_type` 枚举（REAL/VIRTUAL）**；无 google_sub/无密码/无登录能力；复用现有 `content_posts.author_id` 外键（与已上线的 id 1-20 种子作者一致）。正式推翻 2026-06-29「否决 is_seed_account」旧决策 | 架构裁定 |
| **A-7** | ContentType 枚举 `DAILY` 是否改名 `MOMENT` | **不改**：冻结枚举，App 侧已按 code 本地化显示 MOMENT，改名=零用户价值的高风险数据迁移。DB 保留 `DAILY` | 架构裁定 |
| **A-8** | 兽医 ID 与用户 ID 编号重叠（均从 1 起） | **不加前缀**：分属不同表（users / vets），订单外键分列引用，对外一律 token，内部 id 重叠无害 | 架构裁定 |
| **A-9** | 资金账本形态 | **单一双分录 append-only `ledger_entries`**：所有资金变动（充值/消费/兽医应付计提/退款/补偿）落一套平衡分录；PawCoin 钱包余额为可对账派生列（非负 CHECK + 行锁）；订单/退款/月结表引用分录。满足 FR-NFR-1 | 架构裁定 |

**继承的护栏（V1.1 明确重申，写码勿埋违反点）**：红色评级零变现（**即使月度免费额度耗尽也永不加付费墙**，FR-43C，安全规则层「只升不降」）；封禁挂起 15 分钟硬上限逃生（FR-43G/H-5）；PawCoin 不可提现/转赠（印尼 e-money 合规红线）；注销级联删除延伸至健康记录/PawCoin 余额作废；已交付服务退款仅限当天+一律退真钱+禁转 PawCoin 溢价（防现金泵套利 C-1）。

---

## §1 架构姿态增量：资金子系统入场

V1.0 是**零资金**系统。V1.1 最大的架构变化 = **引入完整资金子系统**（支付、PawCoin 虚拟币、退款、兽医分成结算）。这是唯一突破 V1.0「单体 + 少中间件」形态的地方，因为：

- **新增唯一外部中间件 = 支付聚合商**（Midtrans）。这是不可避免的第三方，非自建中间件，不违反「禁 MQ / 禁调度中间件」护栏。
- **资金正确性 = 新的不可协商地基**（与规模无关，等同 V1.0 的「AI 安全规则层」）。四条资金 NFR（单一总账 / 幂等 / PawCoin 非负并发 / 审计留痕）是架构必需件，见 §4。
- 资金逻辑仍**归后端单体内的模块**（新增 `pay` 模块 + `shared/pay` 基础设施），不拆微服务，符合 ≤500 DAU 姿态。

**规模复杂度重估**：仍为 **medium**；后端模块由 8 → 9（新增 `pay`）；外部依赖由 3 → 4（+ 支付聚合商）；Deferred Deep Link（FR-49C 拉新）视为可选第 5 依赖（可后置）。

---

## §2 新外部依赖：支付聚合商（Midtrans）

| 维度 | 决策 |
|---|---|
| 选型 | **Midtrans**（A-3）：收款 Snap/Core API（QRIS 二维码 + DANA）；出款 Iris/Disbursement（退款可打任意银行/钱包，与收款侧独立） |
| 抽象 | 全部经 `shared/pay/PaymentGateway` 接口；`MidtransGateway` 为唯一实现；便于日后换聚合商 |
| **关键结构性限制** | 聚合商**无 Cancel/Void（未结算撤销）能力** —— 这是**平台级硬限制**，正是「兽医咨询免费入队→接单后才付费」整套设计的根因。架构不得假设可撤销未结算交易 |
| 对账去重 | 收款状态经**双通道**获取：① QRIS 轮询 `GET status` ② 回调 `POST /payments/callback`。两通道**幂等去重**（`payment_intent` 唯一 + idempotency key），拒绝二次 capture/入账/解锁（FR-NFR-2） |
| 凭证 | Server Key / Client Key / 回调签名密钥全部 env 注入，不入库；回调验签 |
| 不做 | 不直连 GoPay/OVO SDK；不支持银行 VA / 转账收款；无「记住上次支付渠道」（OQ-8） |
| 部署位面 | 印尼支付流量经 Midtrans；后端仍留德国；回调走 Cloudflare 白名单回源 |

**降级**：支付失败/取消统一走 FR-43F 回退（余额不变、不落 ledger 流水、订单不建/回退），前端 ProblemDetail → 本地化文案。

---

## §3 数据架构 Delta（新表 / 加列）

> **Flyway 从 V47 起单调顺延**（基线冻结在 V46）。下方给出**逻辑模型 + 迁移分配**；具体 DDL 在实现 story 阶段落。**加列一律 ALTER，绝不改旧迁移**（决策 E2）。枚举落库 `varchar` + UPPER_SNAKE；长度=1 的列**禁建 VARCHAR(1)**（Hibernate6 映 CHAR(1) → validate 全红，历史坑）。

### 3.1 资金核心（`pay` 模块）

**`ledger_entries`（双分录总账，append-only，A-9 / FR-NFR-1）**
- `id` bigint PK；`entry_group` uuid/token（一次资金事件的一组平衡分录共享）；`account` varchar 枚举（`CASH_IN` 现金流入 / `FLOAT_LIABILITY` PawCoin 浮存负债 / `VET_PAYABLE` 应付兽医 / `VET_PAID` 已付兽医 / `PLATFORM_REVENUE` 平台收入 / `REFUND_OUT` 退款流出）；`direction` varchar（`DEBIT`/`CREDIT`）；`amount` bigint（IDR，最小单位；PawCoin 1 koin=Rp1 同单位）；`ref_type`/`ref_id`；`idempotency_key` varchar **唯一**；`created_at` timestamptz。**append-only（无 update/delete）**；补偿走新分录不改旧行。

**`pawcoin_wallets`（余额，派生可对账）**
- `user_id` bigint PK/唯一 FK；`balance` bigint **CHECK(balance>=0)**（防双花 FR-NFR-3）；`updated_at`。**余额无有效期**；扣减/入账**行锁 + 同事务**；可由 `ledger_entries` 全量重算对账。

**`pawcoin_transactions`（钱包流水，用户可见）**
- `id`；`user_id` FK；`delta` bigint（+充值/退回、−消费）；`type` varchar（`TOPUP`/`SPEND`/`REFUND`/`BONUS`）；`ref_type`/`ref_id`；`entry_group`（关联总账）；`created_at`。**充值失败不写本表**（FR-43F）。

**`payment_intents`（支付意图 / 幂等锚，FR-43 统一支付）**
- `id`；`intent_token` 不可枚举；`user_id`；`purpose` varchar（`VET_CONSULT`/`PAWCOIN_TOPUP`/`AI_UNLOCK`/`ID_HD`）；`channel` varchar（`QRIS`/`DANA`/`PAWCOIN`）；`amount` bigint；`status` varchar（`CREATED`/`PAID`/`FAILED`/`EXPIRED`）；`provider_ref`（Midtrans 交易号）；`idempotency_key` 唯一；时间戳。回调/轮询双通道更新此表。

### 3.2 兽医咨询计费（`consult` 模块，A-5 两表）

**`consult_requests`（付费前临时态，CAS 状态机）**
- `id`；`request_token`；`user_id`/`pet_profile_id` FK；`vet_id`（接单前 null）；`state` varchar 单列 CAS（`QUEUEING`→`ACCEPTED_AWAIT_PAY`→（成功即转订单并删本行）/ 超时取消删行）；`queue_deadline_at`（入队 1min）；`pay_deadline_at`（接单后 1.5min）；`paused_at`（跳充值暂停锚，A-4）；`rebroadcast_count` int；`created_at`。**取消/超时即删或标记废弃、不进订单中心**。所有 accept/cancel 竞态经 `state` **compare-and-set 单列**（非时间戳比较，H-4）。

**`consult_orders`（支付成功才建，持久，进订单中心）**
- `id`；`order_token` 不可枚举；`user_id`/`vet_id`/`pet_profile_id` FK；`status` varchar（`IN_PROGRESS`→`COMPLETED`→`REFUNDING`→`REFUNDED`；**无 CANCELLED**——未扣费不建单）；`amount` bigint；`pay_channel`；`payment_intent_id` FK；`vet_payout` bigint（Rp30,000=50k×分成快照）；`vet_share_rate_snapshot`/`unit_price_snapshot`（成交时快照，后台改价不影响历史）；`refund_rejected` bool（A-2，驳回回落 COMPLETED 时置真）；`session_started_at`/`session_ended_at`/`paid_at` timestamptz。
- 子表 **`consult_order_stage_events`**：每次接单/支付尝试/会话起止/退款节点时间戳（**保留历史不覆盖**）。

### 3.3 AI 问诊付费解锁（`triage` 模块）

- ALTER `triage_records`：加 `unlock_source` varchar（`LOCKED`/`FREE_QUOTA`/`PAID`，**一经写入不可覆盖**）；`unlock_channel` varchar（`QRIS`/`DANA`/`PAWCOIN`，仅 PAID 有值）。**生成失败不建记录**。红/黄免费部分与锁定 `SARAN PERAWATAN` 详建**分字段下发**（FR-43A，C-7 修订：黄色图标/颜色/时效提示始终免费；**红色永不锁**）。
- 新表 **`user_monthly_free_quota`**：`user_id`（额度对象=账号）；`period` char/date（YYYY-MM，WIB）；`used_count` int；**唯一 `(user_id, period)`**；判定与扣减**加锁读写、同事务**（FR-43B/NFR-3）。红色评级计入消耗（但不因耗尽而拦红）。默认 1 次/月，后台可调（0-35）。
- 新表 **`ai_consult_orders`**（AB-8G，**独立 ID 命名空间**，与兽医订单隔离）：`order_token`/`user_id`/`amount`/`pay_channel`/`status`(`COMPLETED`/`ABNORMAL`)/`paid_at`。

### 3.4 退款（`pay` + `admin`，A-1/A-2）

**`refund_requests`（退款工单 / 两段审核可区分）**
- `id`；`refund_token`；`order_id` FK；`related_ticket_id` FK（FR-52 客服工单）；`user_id`。
- **两段分离字段**：
  - 第一段「退款需求判定」（客服，AB-5B）：`need_decision` varchar（`PENDING`/`APPROVED`/`REJECTED`）；`submitter_admin_id`（客服）。批准 → 解锁 App 选方式入口（**不发通知**）；驳回 → 发「退款申请未通过」通知 + **订单回落 COMPLETED 置 `refund_rejected`**（A-2）。
  - 第二段「退款申请审批」（主管，AB-5E）：`approval_status` varchar（`PENDING_APPROVAL`/`APPROVED`/`REJECTED`/`PROCESSING`/`DONE`）；`approver_admin_id`（主管）；`payer_admin_id`（财务）。
- `source` varchar（`CS_MANUAL` 方式A / `USER_SELF` 方式B）。
- 金额：`order_amount`/`channel_fee`/**`net_amount`**（BCA=0 / OVO=2500 / GoPay=2500；`net = order − fee`，**后端权威计算**，与前端实时联动须一致——跨文档校验项 FR-NFR-5）。
- 收款账户（**敏感 PII，加密/脱敏、日志红线**）：`payout_channel`/`payout_account`/`account_holder_name`。
- `pawcoin_premium_rate_snapshot` smallint（仅「未交付+转 PawCoin」分支落 bonus 比例快照）。
- 备注/凭证：`approval_note`（必填）/`reject_reason`（必填）/`payment_proof`。
- 时间戳：`submitted_at`/`approved_at`/`rejected_at`/`paid_at`。
- **职责分离约束（A-1，服务层强制）**：`submitter_admin_id` / `approver_admin_id` / `payer_admin_id` **两两不可相等**（含 SUPER_ADMIN 不豁免），违反即拒 + 记审计。

### 3.5 后台配置 / 订单管理 / 月结（`admin`）

- **`pricing_config`**（键值，成交时在订单/咨询上落快照）：`ai_unlock_price`/`vet_consult_price`/`id_hd_download_price`/`vet_share_rate`(默认 60)/`monthly_free_quota`(默认 1)。
- **`pawcoin_config`**：`premium_rate` smallint（0-50%，退款转币溢价）。
- **`pawcoin_topup_tiers`**：`id`/`amount_idr`/`coins`(1:1)/`enabled`/`display_order`；应用层保证 ≥1 启用档；初始 4 档 10k/25k/50k/100k。
- **`config_change_logs`**（通用变更日志，配置类共用）：`config_key`/`admin_id`/`old_value`/`new_value`/`reason`/`changed_at`。（配置写操作亦入 AdminAuditService 哈希链，§7。）
- **`vet_settlements`**（月结）：`vet_id`/`period_month`/`completed_count`/`total_payable_idr`/`status`(`PENDING_FINANCE`/`PAID`/`ARCHIVED`)/`payment_proof`/`generated_at`/`paid_at`。每月 1 日 00:00 WIB `@Scheduled` 生成。
- **`identity_moderation_tickets`**（AB-9 昵称/宠物名异步审核）：`user_id`/`field_type`(`USER_NICKNAME`/`PET_NAME`)/`content`/`risk_score`/`risk_level`(`HIGH`≥0.8/`MEDIUM`0.6-0.8)/`status`/`disposition`(`PASS`/`REQUIRE_MODIFY`)/`modify_deadline`(默认+7d)/`related_prev_ticket_id`。相关字段加**屏蔽态**（他人见 `***`、本人见原文）；逾期未改 → 复用既有限流机制。**先放行后审核**。

### 3.6 客服工单扩展（`admin`，FR-52）

- ALTER 既有客服工单表（或新 `feedback_tickets`）：加 `contact_type`(`EMAIL`/`WHATSAPP`)/`contact_value`/`need_contact_customer`/`contacted_customer`/`csat_score`(smallint 1-5)/`csat_comment`(≤100)/`csat_deadline`/**`cs_rating`（预留 null，AB-5G 后续版本用，避免二次迁移）**/`related_order_id`。
- 子表：`ticket_attachments`（≤5 图，OSS 预签名上传）；`ticket_labels`（多对多枚举 `BUG/FEATURE/CONSULT_COMPLAINT/REFUND/CONTENT/ACCOUNT/PRAISE/OTHER`）；`ticket_internal_notes`（用户不可见）。

### 3.7 档案 / 身份证 / 健康记录 / 里程碑（`profile`）

- ALTER `pet_profiles`：加 **`serial_id` bigint 唯一**（全平台自增流水号，从 1；删除释放回收 → 号池/序列机制）。**分享/快照绑内部 `id`，不绑 `serial_id`**。
- 新表 **`id_card_hd_purchases`**（FR-49D，一次性永久解锁）：`pet_profile_id` FK/`user_id`/`pay_channel`/`purchased_at`。
- 新表 **`health_records`**（FR-45A/B）：`pet_profile_id` FK；`type` varchar(`VACCINE`/`DEWORM`/`MENSTRUATION`/`NEUTER`/`CUSTOM`)；`custom_name`(≤20)/`vaccine_name`(≤30)/`event_date` date（**不可未来、无下限**）/`note`(≤100)/`created_at`。**问诊类条目来自 FR-16、不入本表，仅只读混排**。**档案删除级联硬删**（PDP）。
- 里程碑（FR-47）：新增 6 个新手任务独立计数 + 聚合里程碑「Lulus Pemula」**S 级枚举 +1（15→16，总 30→31）**；老用户批量回溯解锁作业（一次性）。

### 3.8 虚拟账号（`admin`，A-6）

- ALTER `users`：加 `account_type` varchar（`REAL`/`VIRTUAL`，默认 `REAL`）；虚拟账号 `nickname`/`avatar_url`(选填)/`created_by`/`enabled`/`published_count`；**无 google_sub / 无密码 / 无登录**。复用 `content_posts.author_id`。批量种子上传（AB-1.1-02）：zip+Excel 指纹比对；图片命名 `{产品编码}_{批次}_{序号}_{短哈希}.jpg`。

### 3.9 通知类型扩枚举（`notify`）

`notifications.type` 新增：`REFUND_REJECTED`（退款申请未通过）/`TICKET_RESOLVED`（工单结案）/`CSAT_SURVEY`/`REPORT_REVIEWED`（FR-51 举报回告，已在 V40 迁移预留则复用）/`IDENTITY_REQUIRE_MODIFY`（标识需修改）。**批准退款需求不发通知**（AB-5B）。注意 **AFTER_COMMIT 事务吞写坑**：通知 INSERT 走 `REQUIRES_NEW`（历史 bug 已修范式）。

---

## §4 资金正确性地基（不可协商，FR-NFR-1~4）

等同 V1.0「AI 安全规则层」地位，**与规模无关**：

1. **单一双分录总账（NFR-1）**：所有资金变动落 `ledger_entries` 平衡分录；PawCoin 账本 / 订单 / 兽医月结 / 收入统计均为其**只读视图**，可对账勾稽。客服补偿走**新分录**不改旧字段。
2. **全链路幂等（NFR-2）**：每笔支付/退款/发币强制 `idempotency_key`；Midtrans 回调 + QRIS 轮询**双通道去重**；拒绝二次 capture/入账/解锁。
3. **PawCoin 非负并发不变式（NFR-3）**：`balance` DB CHECK≥0 + 行锁；「成功生成结果 + 额度扣减 + 解锁来源写入」**同一事务**；免费额度判定加锁。
4. **资金审计留痕（NFR-4）**：退款全链路、配置变更、月结打款等写操作接入既有 **AdminAuditService 哈希链**（append-only，§7）；`payout_account` 等 PII **加密/脱敏**，绝不入日志。
5. **合规红线**：PawCoin 不可提现/转赠（印尼 PBI 20/6/2018）；浮存 ~10 亿 IDR 阈值**软监控告警**（V1.1 不做一键硬熔断/暂停，运营+工程手动，AB-6C，已评估接受 C-4）；注销余额作废须告知。
6. **反套利（C-1/C-2）**：已交付服务退款**仅当天 + 一律退真钱 + 禁转 PawCoin 溢价**；仅**系统故障未交付**才可转 PawCoin 附 bonus。

---

## §5 核心状态机与异步/定时（无中间件实现）

### 5.1 兽医咨询免费入队→限时支付（FR-43D，全版本最复杂）

```
[consult_requests]                                   [consult_orders]
QUEUEING ──(1min 无兽医)──> 静默消失(删行, 不建单, 无痕)
   │
   │(兽医 accept, CAS 单列)
   ▼
ACCEPTED_AWAIT_PAY ──(1.5min pay_deadline 到期 / 用户取消 / 未付)──> 作废本次接单
   │                                                      └─> 自动重播其他在线兽医(rebroadcast++，表单不丢，回 QUEUEING)
   │                                                          (上限 5 次或 30min → 彻底失败, 记 failed_consult_requests)
   │(支付成功)
   ▼
  ★建 consult_orders(status=IN_PROGRESS) + 删/归档 request ────> IN_PROGRESS → COMPLETED → REFUNDING → REFUNDED
                                                                              └(驳回退款需求)─> 回 COMPLETED + refund_rejected=true (A-2)
```

- **服务端权威计时**（A-4/A-5）：入队 1min、支付 1.5min 均以 DB `*_deadline_at` 时间戳为准；兽医工作台展示**同一倒计时**（`p-vet-queue-pay` 中间态）。
- **倒计时暂停（A-4）**：用户跳去充值 PawCoin 时记 `paused_at`，返回后按剩余续；非重置。
- **竞态 = 单列 CAS**（H-4）：accept 与 cancel 对 `state` compare-and-set，先到先得，杜绝「兽医接单同时用户取消」双写。
- **超时扫描 = `@Scheduled`**（每 ~5-10s 扫过期 `ACCEPTED_AWAIT_PAY`）+ 入队 1min 超时扫描；**不引入延迟队列/定时中间件**（延续「砍 RocketMQ→DB 状态机+@Async」范式）。
- **IM 会话在支付成功后才建**；「支付成功但 IM 建会话失败」= 已扣未交付 → 触发系统故障退款路径（可转 PawCoin 附 bonus）。

### 5.2 退款两段审批（A-1/A-2）

```
用户 App 走 FR-52 工单说明退款需求
   │
第一段：客服判定(AB-5B)  need_decision: PENDING
   ├─ APPROVED ─> 订单置 REFUNDING + App 解锁「选退款方式」入口 (不发通知)
   │              └─ 用户选方式(PawCoin订单→即时退币无手续费无选账户; QRIS/DANA→填收款账户)
   │                 └─ 提交 => 生成方式B refund_request => 第二段
   └─ REJECTED ─> 发「退款申请未通过」通知 + 订单回落 COMPLETED 置 refund_rejected (A-2)
第二段：主管审批(AB-5E) approval_status: PENDING_APPROVAL
   ├─ APPROVED ─> PROCESSING(待财务打款) ─(财务标记打款+凭证)─> DONE => 订单 REFUNDED
   └─ REJECTED ─> 方式A退回客服可改重提 / 方式B通知用户
```
- **净额权威在后端**（`net_amount = order_amount − channel_fee`），前端联动展示须一致（FR-NFR-5 跨文档校验）。
- **退款方向铁律**：PawCoin 付 → 永远退 PawCoin（无手续费、跳过选账户）；QRIS/DANA 付 → 退真钱；**bonus 只挂「未交付+转 PawCoin」单一分支**。
- 提交收款账户为**不可逆边界**（`p-refund-account` 提交后落 `p-refund-review`；提交前中断可安全退回 `rs-choose`）。

### 5.3 定时任务清单（全部 Spring `@Scheduled` + `@Async` + DB 去重，禁 Quartz/中间件）

① 限时支付 1.5min 超时扫描；② 入队 1min 无接单超时；③ 兽医月度结算（每月 1 日 WIB）；④ 免费额度每月 1 日 WIB 重置（可惰性判定，不必定时）；⑤ 生日前 1 天 / 陪伴纪念日 30·100·365 天推送（V1.0.0 已建，延续）；⑥ 封禁挂起 15min 强制结束 + 自动退款（FR-43G/H-5）；⑦ 工单已解决 7 天自动关闭；⑧ 标识审核逾期限流；⑨ PawCoin 浮存阈值监控告警。

---

## §6 API Delta（`/api/v1`，对外一律 token）

| 端点 | 方法 | 角色 | 说明 |
|---|---|---|---|
| `/consultations` | POST | user | 免费入队（不扣费、不建订单） |
| `/consultations/{token}/accept` | POST | vet | 接单（CAS，触发限时支付） |
| `/consultations/{token}/cancel` | POST | user | 取消（与 accept CAS 互斥） |
| `/consultations/{token}/pay` | POST | user | 限时支付确认（QRIS/DANA/PawCoin）→ 成功建订单 |
| `/consultations/{token}/pause` `/resume` | POST | user | 跳充值暂停/返回续（A-4） |
| `/triage/{token}/unlock` | POST | user | 付费/额度解锁详情（红色永不锁） |
| `/me/free-quota` | GET | user | 本月额度状态 |
| `/me/pawcoin` · `/me/pawcoin/topups` | GET·POST | user | 余额+流水 / 充值 |
| `/orders` | GET | user | **订单中心：泛化 4 类订单**（AI/充值/HD/兽医）+ 类型筛选 + 游标分页 + PawCoin 汇总 |
| `/orders/{token}` | GET | user | 订单详情（兽医订单 6 态；`amount` 待接单可为 null） |
| `/refund-requests` · `/{token}/payout-info` | POST | user | 发起（走工单）/ 批准后填收款 |
| `/me/pet-profiles/{token}/id-card` · `/hd-download` | GET·POST | user | 身份证 3 风格 / 高清付费下载 |
| `/me/pet-profiles/{token}/health-records` | GET·POST·PATCH·DELETE | user | 健康记录 CRUD（问诊条目只读混排） |
| `/me/newbie-tasks` | GET | user | 新手任务进度 |
| `/support-tickets` · `/{id}/csat` | POST | user | 客服工单（附件 OSS 预签名）/ CSAT |
| `/vet/queue` · `/vet/active` · `/vet/income` | GET | vet | 接单队列 / **进行中会话(补 p-vet-active 死链)** / 月度收入 |
| `/payments/callback` · `/payments/{intent}/status` | POST·GET | provider/user | Midtrans 回调 + 轮询，**双通道幂等去重** |
| `/p/{cardToken}` | GET | 公开 | 身份证分享落地页（Thymeleaf；处理**目标档案已删/token 失效**落地态） |
| admin：退款审批、财务打款、配置、月结、标识审核、虚拟账号、概览看板 | 见 §7 | admin 权限点 | AB-5x/6x/7x/8x/9x/1.1-xx |

**订单中心泛化契约**（承接 UX P0#2）：每行 `orderType`/`statusColor`(warn/info/success)/`typeIcon`/`title`/`subtitle`/`statusBadge`/`amount`(待接单 null→前端显 "Belum ada pembayaran")。**退款处理中映射 info(蓝) 非 error(红)** —— 后端状态语义须支持前端此映射。

---

## §7 后台 Admin Delta

**权限模型（AB-1.1-29，延续 SUPER_ADMIN 隐式全权 + STAFF 勾选）新增权限点**：`REFUND_SUBMIT`/`REFUND_APPROVE`/`REFUND_PAYOUT`（A-1，同单互斥）、查看/导出兽医咨询订单、查看/导出 AI 问诊订单、导出内容管理数据、查看举报者清单、管理/导出虚拟账号、配置类（定价/PawCoin/档位）。

**新增 admin 能力**（复用各模块 service，不建独立系统）：客服工单系统（含退款两段审批 UI）、PawCoin 管理（溢价/档位/浮存监控）、红色超额只读监控（无自动拦截，AB-7A）、定价配置、兽医咨询订单管理（**只读、不提供退款入口**，退款只走工单）、AI 问诊收入统计、兽医分成月结对账、支付记录通用查询、AI 问诊订单查询、昵称/宠物名异步审核、虚拟账号管理、运营概览看板。

**导航整改**：兽医「问诊异常」去重；「会话查询」→「历史会话查询」（仅收费上线前免费会话）；「在线状态」「评分查看」并入兽医账号列表。

**审计接入（AdminAuditService 哈希链，Epic2~6 写操作必调，延续）**：退款全链路（提交/判定/审批/驳回/打款+凭证，**最高危**）、所有配置变更、月结打款归档、内容处置、标识审核处置、兽医账号变更、虚拟账号增删启停、超额标记。

---

## §8 前端 Delta（Flutter）

**6 新组件数据契约**（DESIGN.delta）：①订单卡片（4 类型 + 3 状态色 + amount 可 null）②退款状态 chip（4 态；**退款中→info 蓝非 error 红**，纯展示不可点）③PawCoin 余额块（koin，1:1，只读流水，**无转账**）④退款渠道选择行（渠道+手续费，**净额=核心正确性**，与后端一致）⑤身份证 3 风格变体（KTP/Paspor/Pelajar；编辑**仅作用当前预览会话、不写档案**、后端不接收；输出 1600×900）⑥情绪彩蛋气泡（**100% 本地 12 条定稿文案，无后端**；`aku/kamu` 人称；23:00–04:00 高概率；**离线不留白不转圈**）。

**新增页/屏**（feature 归属）：订单中心 `p-orders`+详情 6 态、退款方式选择/填收款/账户核实 3 步（`consult`/`pay`）、PawCoin 余额/充值/失败/暂停（`pay`）、健康记录列表（`profile`）、兽医下单链路 `p-vet-request/waiting/timed-pay`（`consult`）、**兽医端进行中会话 `p-vet-active`（补死链）**（`vet`）、身份证详情/HD paywall/分享落地（`profile`）。

**新增 feature 模块**：`features/pay/`（统一支付/PawCoin/订单中心）。前端结构、Riverpod+AsyncValue+不可变、dio 拦截器+ProblemDetail 映射、go_router 深链均**继承 V1.0**。全屏扣钱流程返回键语义（承接 UX High）：**待接单=可丢弃无痕 / 待支付=服务端权威(中断即重播) / 填收款=提交前可退、提交后不可逆**。

---

## §9 安全 / 合规护栏 Delta

延续 V1.0 六地基，V1.1 新增/强化：

- **红色态零变现强化（FR-43C）**：付费墙**永不拦红**，即使月度免费额度耗尽；红/黄免费部分与锁定详建**分字段下发**，安全规则层「只升不降」不可绕过。
- **KTP/身份证 = 娱乐仿制**（FR-49B）：背面须含免责声明（非官方、无法律效力）；仅展示层、不写档案字段；**分享落地页不得渲染成可冒充真实政府证件**。
- **退款收款账号 = 敏感 PII**：加密/脱敏存储，日志红线（NFR-4）。
- **支付/PawCoin 幂等 + 非负**（NFR-2/3，见 §4）。
- **反现金泵套利**（C-1/C-2，见 §4）。
- **封禁挂起 15min 逃生**（FR-43G/H-5）：兽医「待封禁挂起」时用户端恢复「结束/上报」入口；挂起 ≤15min 或会话超时 → 系统强制结束 + 按支付方式退款 + 封禁生效。后端落「兽医挂起」态驱动。
- **注销级联延伸**：健康记录硬删、PawCoin 余额作废（告知）、问诊条目匿名化（沿用 FR-20）。

---

## §10 缺失态 / 边界补齐（承接 UX 校验 P0/High）

| 缺口 | 处置 |
|---|---|
| **P0 兽医 `p-vet-active` 死链** | 补 `GET /vet/active` 进行中会话接口 + 页；核对兽医 5 tab 均有落地页 |
| **P0 订单中心只为兽医设计** | 订单模型/接口**泛化 4 类**（AI/充值/HD/兽医），详情按 `orderType` 分支 |
| **P0 CS 驳回后订单无落脚态** | A-2：回落 COMPLETED + `refund_rejected` 标记 |
| **P0 订单卡金额对比 3.9:1** | 前端升 text-primary（a11y，UX 侧改） |
| **High 全屏扣钱返回键语义** | §8 三态语义 + §5.1 服务端权威计时落库 |
| **High PawCoin 退款误加 bonus** | §5.2：bonus 仅「未交付+转 PawCoin」单一分支 |
| 缺失态×N | 订单列表空态 / 身份证老用户「尚未生成」引导态（后端返「未生成」标志）/ 分享指向已删档案失效态（后端返失效非 500）/ 宠物已删订单失效态 |
| 兽医收入回撤时序 | 退款成功 → 兽医应付/已付分录冲销（`ledger_entries` 反向分录，月结视图随动） |

---

## §11 Flyway 迁移序号分配（V47 起，按执行顺序单调，占位号实际顺延）

> 顺序 = 实现序列（§13）。**占位 `V<n>__`，实际号顺延，勿照搬**（决策 E2）。加列 ALTER，勿动旧迁移。

| 顺序 | 迁移（占位） | 内容 |
|---|---|---|
| V47 | `init_ledger_and_pawcoin` | ledger_entries / pawcoin_wallets / pawcoin_transactions / payment_intents |
| V48 | `alter_triage_unlock` + `init_free_quota` | triage_records 加 unlock_source/channel；user_monthly_free_quota |
| V49 | `init_consult_billing` | consult_requests / consult_orders / consult_order_stage_events / ai_consult_orders |
| V50 | `init_refund_requests` | refund_requests（两段字段 + 净额 + 收款 PII） |
| V51 | `init_admin_pay_config` | pricing_config / pawcoin_config / pawcoin_topup_tiers / config_change_logs |
| V52 | `init_vet_settlements` | vet_settlements |
| V53 | `alter_feedback_tickets` + 子表 | 工单加 contact/csat/cs_rating 预留 + attachments/labels/notes |
| V54 | `init_identity_moderation` | identity_moderation_tickets + 字段屏蔽态列 |
| V55 | `alter_pet_profiles_serial` + `init_id_hd` | pet_profiles.serial_id + 号池 + id_card_hd_purchases |
| V56 | `init_health_records` | health_records |
| V57 | `alter_users_account_type` | users.account_type + 虚拟账号字段 |
| V58 | `extend_notification_types_v11` | REFUND_REJECTED/TICKET_RESOLVED/CSAT_SURVEY/IDENTITY_REQUIRE_MODIFY |
| V59 | `alter_milestone_newbie_tasks` | 新手任务 + Lulus Pemula S 级 +1 |

（实际实现时按 story 拆分可再细分，号顺延即可。）

---

## §12 决策裁定 & 遗留 Open Items

**已裁定**（§0 A-1~A-9）：退款职责分离 / 驳回落脚态 / 支付选型 / 倒计时暂停 / 两表拆分 / 虚拟账号 / DAILY 不改名 / ID 不加前缀 / 单一总账。

**仍需 PM/商务确认（不阻塞架构，标 `[OPEN]`，进 story 前收敛）**：
- `[OPEN]` Midtrans 商务合同 + QRIS 轮询超时时长（OQ-13）。
- `[OPEN]` Deferred Deep Link 服务选型（Firebase Dynamic Links 已宣布日落 / Branch.io）——FR-49C 拉新，可后置，不阻塞主线。
- `[OPEN]` 方式B（用户自助）驳回后能否 App 内重提（UX 待定）。
- `[OPEN]` 红色超额 / 接单未支付的限速阈值与处置（OQ-11/12，仅埋点，上线后定）。
- `[OPEN]` 浮存硬熔断（V1.1 软监控，牌照申请线下推进）。

**明确不在 V1.1 范围**（架构不预留过度）：月度回顾（原 FR-46，移出）、健康记录提醒（1.3.0）、多宠物（1.2.0）、兽医预约（1.2.0）、会员制（1.2.0）、PawCoin 任务赚取 FR-50B（1.2.0）、兽医 AI 起草（1.2.0）、评论举报 FR-57（1.2.0）、稀有号拍卖机制本体（远期）。**审核类**：内容举报运营审核 + 昵称审核在后台**属 V1.1 范围**（FR-51 回告 + AB-9）；客户端不新增审核判定。

---

## §13 实现序列建议（架构视角，供 sprint-planning 参考）

1. **资金地基**：`shared/pay`（PaymentGateway/Midtrans/幂等/回调）+ `ledger_entries` + PawCoin 钱包（V47）——一切收费前置。
2. **PawCoin 充值闭环**：档位配置 + 充值 + 余额/流水页（V47/V51）。
3. **AI 问诊付费解锁 + 免费额度**（V48）——最简收费场景先跑通幂等/解锁。
4. **兽医咨询计费**：两表状态机 + 限时支付倒计时 + CAS + `@Scheduled` 扫描（V49）——最复杂，压后。
5. **订单中心**（泛化 4 类，V47~V49 之上）。
6. **退款两段审批**：refund_requests + 后台职责分离 UI + 净额计算 + 收款 PII（V50）。
7. **兽医月结**（V52）+ **兽医端进行中会话/收入页**（补死链）。
8. **后台配置/审计/订单管理/支付查询**（V51 之上）。
9. **身份证 serial_id/HD/分享落地**（V55）+ **健康记录**（V56）+ **里程碑增量**（V59）+ **情绪彩蛋**（纯前端）。
10. **标识异步审核 + 虚拟账号 + 概览看板**（V54/V57）+ **通知类型扩枚举/客服工单**（V53/V58）。

**AI Agent 实现纪律（延续 V1.0 + V1.1 强化）**：异步只 `@Async`+DB 状态机+`@Scheduled`，**除 Midtrans 外禁任何新中间件**；资金四不变式（总账/幂等/非负/审计）等同安全规则层不可绕过；红色态永不拦付费墙；退款职责分离同单互斥；收款账号/证件 PII 加密脱敏、日志红线；对外标识 token；Flyway V47 起顺延、加列 ALTER。

---

> **交付边界**：本 delta 完成 PRD→UX→架构三步之**第三步**。下一步（epics/stories/sprint-planning/dev）不在本轮范围，按用户「做到 3」口径**在此停止**。
