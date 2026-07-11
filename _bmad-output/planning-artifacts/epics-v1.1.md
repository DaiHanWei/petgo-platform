---
stepsCompleted: [1, 2, 3, 4]
status: 'complete'
completedAt: '2026-07-11'
docType: 'epics-delta'
baseline: '_bmad-output/planning-artifacts/epics.md'   # V1.0 冻结基线；本文件只写 V1.1 增量
inputDocuments:
  - /Users/dai/work/petGo/V1.1.0/v1-1-0PRD.md
  - /Users/dai/work/petGo/V1.1.0/v1-1-0后台prd.md
  - /Users/dai/work/petGo/V1.1.0/ux-delta/EXPERIENCE.delta.md
  - /Users/dai/work/petGo/V1.1.0/ux-delta/DESIGN.delta.md
  - _bmad-output/planning-artifacts/architecture-v1.1-delta.md
project_name: 'TailTopia V1.1.0'
date: '2026-07-11'
scope: 'V1.1 增量；brownfield；Flyway V47 起顺延'
---

# TailTopia V1.1.0 - Epic Breakdown（Delta）

## Overview

本文件是 V1.1.0 **增量** epic/story 分解，承接 PRD（客户端 + 后台）、UX delta、架构 delta。V1.0 已上线冻结；未提及处继承 V1.0。核心主题 = **引入资金子系统**（支付/PawCoin/退款/兽医分成）+ 配套档案/身份证/健康记录/后台运营增强。

## Requirements Inventory

### Functional Requirements（客户端，FR-43 起延续 V1.0 的 FR-42）

**资金 / 支付主线**
- FR-43：统一支付组件（QRIS 二维码轮询 + DANA，App 内嵌入式，承载三收费场景）
- FR-43A：AI 问诊「先出结果、后付费解锁详情」（Rp10,000）；等级图标/颜色/黄色时效提示/红色强提醒**始终免费**
- FR-43B：每月免费额度（默认 1 次/账号，WIB 每月 1 日重置，后台可调 0-35）
- FR-43C：红色评级安全护栏（零变现、始终免费、仅埋点不限速，「只升不降」不可绕过）
- FR-43D：兽医咨询免费入队→接单→**1.5 分钟限时支付**→会话；已交付服务退款（仅当天、客服批准、PawCoin 退币/真钱退真钱、无转 PawCoin 溢价）
- FR-43F：支付失败/取消通用回退（余额不变、不落流水）
- FR-43G：Konsultasi UI 文案定稿 + 移除用户端「结束会话」按钮 + 封禁挂起 15 分钟逃生通道

**PawCoin 虚拟币**
- FR-50：PawCoin 体系（1 koin=Rp1，「我的」页余额/获取/消费明细）
- FR-50A：充值（QRIS/DANA，档位 10k/25k/50k/100k，浮存门槛监控）
- FR-50C：消费范围 + 退款方向跟随支付方式
- FR-50D：不提现/不转赠/注销余额作废

**订单中心**
- FR-54 / 54A/B/C/D：订单页（支付凭证中心），四类卡片（兽医咨询 6 态 / PawCoin 充值 / AI 解锁 / 高清图），类型筛选 + 分页；宠物已删失效态

**兽医端计费**
- FR-53A：接单队列「等待用户支付（剩余 X 秒）」中间态 + 倒计时
- FR-53B：取消/超时/未支付 3 秒 Toast 通知兽医
- FR-53C：会话完成显示到手金额（Rp30,000 = 50k×分成快照）
- FR-53D：兽医月度收入汇总页

**档案 / 健康 / 里程碑 / 增长**
- FR-45 / 45A/45B/45C：成长档案结构化健康记录录入（疫苗/驱虫/月经/绝育/自定义）+ 问诊存档只读混排 + 里程碑第四触发路径 + 级联删除
- FR-47：6 个新手任务（独立计数）+ 聚合里程碑「Lulus Pemula」（S 级 15→16，总 30→31）+ 老用户批量回溯
- FR-48 / 48A~D：首页情绪彩蛋便签（100% 本地文案，无后端）
- FR-49 / 49A/49B/49C/49D：宠物身份证（全平台自增流水号、三风格证件卡 1600×900、三级水印、分享落地页 + deferred deep link 拉新、高清图付费下载 Rp5,000 一次性永久）

**通知 / 客服 / 举报**
- FR-51：举报处理结果通知举报人（覆盖 V1.0.0 不通知行为）
- FR-52 / 52A/52B：客服投诉升级（图片附件 ≤5、自填联系方式、结案通知、CSAT 满意度调查）
- FR-22*/34/38/40/41：推送与通知中心（从 V1.0.0 延续：兽医回复/互动推送、版本更新、权限申请时机、深链接、生日/纪念日、通知中心铃铛）

### NonFunctional Requirements（资金跨切面 + 延续）

- FR-NFR-1：单一双分录总账（所有资金变动落一套平衡分录，PawCoin/订单/月结/收入均为只读视图）
- FR-NFR-2：支付/退款/发币全链路幂等（回调 + 轮询双通道去重）
- FR-NFR-3：PawCoin 并发非负不变式（余额 CHECK≥0 + 行锁 + 同事务）
- FR-NFR-4：资金审计留痕（接入 AdminAuditService 哈希链；收款账号 PII 加密脱敏）
- FR-NFR-5：多角色资金 UJ（已在 UX 阶段落两条：兽医限时支付、退款审批到账）
- 性能：支付轮询超时可配；AI ≤15s（延续）；H5 ≤3s（延续）
- 安全/合规：红色零变现；KTP 娱乐仿制 + 免责；PawCoin 不可提现（印尼 e-money 合规）；浮存 ~10 亿 IDR 软监控

### Additional Requirements（架构 delta）

- **新增 `pay` 后端模块 + `shared/pay` 基础设施**（`PaymentGateway` 接口抽象）
- **Midtrans 网关集成**：收款 Snap/Core API（QRIS+DANA）+ 出款 Iris/Disbursement；聚合商**无 Cancel/Void**（免费入队后付费设计根因）；回调验签 + 双通道幂等
- **`ledger_entries` 双分录总账**（append-only）+ `pawcoin_wallets`（非负 CHECK）+ `payment_intents`（幂等锚）
- **兽医咨询两表 CAS 状态机**：`consult_requests`（付费前临时、单列 CAS、取消/超时即删）+ `consult_orders`（支付成功才建、进订单中心）
- **`@Scheduled` 限时支付倒计时扫描**（DB `pay_deadline_at` + 每 5-10s 扫描 + 暂停顺延，**无延迟队列/调度中间件**）
- **退款职责分离**：`REFUND_SUBMIT`/`REFUND_APPROVE`/`REFUND_PAYOUT` 三权限点，服务层强制同单互斥（SUPER_ADMIN 不豁免）
- **Flyway V47-V59 迁移**（加列一律 ALTER，勿动旧迁移，序号顺延）
- **虚拟账号复用 `users.account_type`**（REAL/VIRTUAL，无登录）
- 收款账号/证件 PII 加密脱敏、日志红线；AdminAuditService 哈希链接入资金/配置/处置写操作

### UX Design Requirements

**6 个新组件（DESIGN.delta）**
- UX-DR1：订单卡片组件（4 订单类型图标 + 3 状态色 warn/info/success + `amount` 待接单可 null→"Belum ada pembayaran"）
- UX-DR2：退款状态 chip/badge（4 态；**退款处理中→info 蓝非 error 红**；纯展示不可点）
- UX-DR3：PawCoin 余额块（koin 1:1、只读 ledger 流水、**无转账字段**）
- UX-DR4：退款渠道选择行（渠道 + 手续费 BCA=0/OVO=2500/GoPay=2500，**净额=核心正确性**，与后端一致）
- UX-DR5：身份证 3 风格变体（KTP 正反/Paspor/Pelajar；编辑仅作用当前预览会话不写档案；输出 1600×900）
- UX-DR6：情绪彩蛋气泡（**100% 本地 12 条定稿文案，无后端**；aku/kamu 人称；23:00–04:00 高概率；离线不留白）

**新增页/屏**
- UX-DR7：订单中心 `p-orders` 列表（泛化 4 类订单）+ 详情 `p-order-detail` 6 态
- UX-DR8：退款流程 3 页（退款方式选择 sheet / 填收款账户 / 账户核实 3 步进度）
- UX-DR9：PawCoin 页（余额 `p-pawcoin-balance` / 充值档位 / 充值失败 / 暂停态）
- UX-DR10：健康记录列表 `p-health-list`（结构化可编辑 + 🏥问诊存档只读混排，`editable` 标志区分）
- UX-DR11：兽医下单链路（确认 `p-vet-request-confirm` / 等待 `p-vet-waiting` 1min / 限时支付 `p-vet-timed-pay` 1.5min）
- UX-DR12：**兽医端进行中会话 `p-vet-active`**（补原型死链）+ 兽医收入页
- UX-DR13：身份证详情 3 风格 + HD paywall + 分享落地页（含目标档案已删/token 失效态）
- UX-DR14：全屏扣钱流程返回键/中断语义（待接单=可丢弃无痕 / 待支付=服务端权威中断即重播 / 填收款=提交前可退提交后不可逆）

### 后台 Admin Requirements（v1-1-0后台prd.md）

- AB-5A~5H：客服工单系统（工单列表/详情/分类标签/状态/CSAT/产品反馈汇总），**含 AB-5E 退款两段审批工作流**（客服判定→主管审批→财务打款，三角色职责分离互斥）
- AB-6A~6C：PawCoin 管理（退款溢价比例 / 充值档位 / 浮存余额监控告警）
- AB-7A：红色评级超额使用只读监控（人工标记，无自动拦截）
- AB-8A/8F：收费定价配置（AI/兽医/HD 价、兽医分成、月免费额度）
- AB-8B：兽医咨询订单管理（只读，不提供退款入口）
- AB-8C：AI 问诊收入统计
- AB-8D：兽医分成月结对账（每月 1 日自动生成→财务确认→已付款→归档）
- AB-8E：支付记录通用查询（跨类型只读）
- AB-8G：AI 问诊订单查询（独立 ID 命名空间）
- AB-9A~9C：昵称/宠物名异步审核（先放行后审核，中/高风险入队，处置/要求修改/逾期限流）
- AB-1.1-01：运营数据概览看板（用户/订单/内容/兽医四模块）
- AB-1.1-02/02A：种子内容批量上传（zip+Excel）+ 虚拟账号机制
- AB-1.1-29：后台权限点扩充
- 后台 §7：封禁/SIPDH 到期/用户停用遇进行中付费会话→挂起 + 15min 强制结束 + 退款
- 后台 §7.5：评论纳入内容管理主动下架 + 帖子激活高风险提交拦截

### FR Coverage Map

| 需求 | Epic | 说明 |
|---|---|---|
| FR-43(支付基础)/50/50A/50D | E1 | PawCoin 钱包与充值（资金地基）|
| FR-NFR-1/2/3/4 | E1 | 总账/幂等/非负/审计随地基落地，贯穿全部 |
| FR-43A/43B/43C | E2 | AI 问诊付费解锁 + 免费额度 + 红色护栏 |
| FR-43D(计费段)/43G/53A/53B/53C/53D | E3 | 兽医咨询计费流 + 兽医端 |
| FR-43D(退款段)/50C/51/52/52A/52B | E4 | 退款 + 客服工单 |
| FR-54/54A/54B/54C/54D | E5 | 订单中心 |
| FR-49/49A/49B/49C/49D | E6 | 身份证与分享拉新 |
| FR-45/45A/45B/45C/47 | E7 | 健康记录与里程碑 |
| FR-48/48A~D | E8 | 情绪彩蛋（纯前端）|
| FR-22*/34/38/40/41 | — | V1.0.0 延续，仅回归验证，不重复出 story |
| FR-NFR-5 | E3/E4 | 多角色资金 UJ，作为验收视角不单独出 story |
| UX-DR1/7 | E5 · UX-DR2/4/8 | E4 · UX-DR3/9 → E1 · UX-DR5/13 → E6 · UX-DR6 → E8 · UX-DR10 → E7 · UX-DR11/12/14 → E3 |
| AB-5A~5H | E4 | 客服工单 + 退款两段审批 |
| AB-6/7/8/9 · AB-1.1-01/02/02A/29 · 后台§7/§7.5 | E9 | 后台资金与运营治理（退款审批除外，在 E4）|

### 架构附加映射
- pay 模块 + shared/pay + Midtrans + ledger_entries + payment_intents → E1
- ai_consult_orders + user_monthly_free_quota → E2
- consult 两表 CAS + @Scheduled 倒计时 + vet_settlements(建表+生成) → E3
- refund_requests + 退款职责分离权限点 → E4
- pet_profiles.serial_id + id_card_hd_purchases → E6
- health_records + 新手任务/里程碑 S 级 +1 → E7
- users.account_type + 财务对账 UI + pricing/pawcoin config + identity_moderation → E9
- Flyway V47-V59 按 story 顺延分配（架构 delta §11）

## Epic List

### Epic 1: PawCoin 钱包与充值（资金地基）
用户能通过 QRIS/DANA 充值获得 PawCoin、查看余额与消费明细。本 epic 建起整个资金地基（PaymentGateway/Midtrans 网关、双分录总账、payment_intents 幂等锚、PawCoin 非负钱包），以「充值」为用户价值载体。
**FRs covered:** FR-43(支付组件基础), FR-50, FR-50A, FR-50D, FR-NFR-1, FR-NFR-2, FR-NFR-3, FR-NFR-4

### Epic 2: AI 问诊付费解锁
用户能用每月免费额度或付费（Rp10,000/PawCoin）解锁 AI 问诊详细建议；等级图标/颜色/黄色时效免费，红色评级永不锁。
**FRs covered:** FR-43A, FR-43B, FR-43C

### Epic 3: 兽医咨询计费流（含兽医端）
用户免费入队→兽医接单→1.5 分钟限时支付→建会话；兽医端接单队列「等待支付」倒计时中间态、进行中会话、会话完成到手金额、月度收入汇总。
**FRs covered:** FR-43D(计费段), FR-43G, FR-53A, FR-53B, FR-53C, FR-53D

### Epic 4: 退款与客服工单
用户就已交付服务当天申请退款→客服判定→（App 解锁选退款方式+填收款）→主管审批→财务打款到账；客服投诉工单升级（图片附件/自填联系方式/结案通知/CSAT）；举报处理结果回告举报人。
**FRs covered:** FR-43D(退款段), FR-50C, FR-51, FR-52, FR-52A, FR-52B, AB-5A~5H

### Epic 5: 订单中心
用户在一处查看全部支付凭证——兽医咨询/PawCoin 充值/AI 解锁/高清图四类订单卡片，类型筛选 + 分页，含退款态与宠物已删失效态。
**FRs covered:** FR-54, FR-54A, FR-54B, FR-54C, FR-54D

### Epic 6: 宠物身份证与分享拉新
用户生成三风格证件卡（KTP/Paspor/Pelajar，1600×900）、付费下载高清图（Rp5,000 一次性永久）、分享落地页 + deferred deep link 拉新；全平台自增流水号。
**FRs covered:** FR-49, FR-49A, FR-49B, FR-49C, FR-49D

### Epic 7: 成长档案健康记录与里程碑
用户录入结构化健康记录（疫苗/驱虫/月经/绝育/自定义），与问诊存档只读混排；解锁 6 个新手任务与聚合里程碑「Lulus Pemula」，老用户批量回溯。
**FRs covered:** FR-45, FR-45A, FR-45B, FR-45C, FR-47

### Epic 8: 首页情绪彩蛋（纯前端）
用户在首页看到 100% 本地生成的情绪彩蛋便签（12 条定稿文案，aku/kamu 人称，深夜高概率），离线不留白。
**FRs covered:** FR-48, FR-48A, FR-48B, FR-48C, FR-48D

### Epic 9: 后台资金与运营治理
运营/财务/审核在后台完成：定价与 PawCoin 配置、兽医咨询订单只读管理、AI 问诊收入统计、兽医分成月结对账、支付记录查询、红色超额监控、昵称/宠物名异步审核、虚拟账号与种子批量上传、评论内容管理与帖子激活拦截、封禁挂起处置、运营概览看板、权限点扩充。（退款审批已在 E4。）
**FRs covered:** AB-6A~6C, AB-7A, AB-8A~8G, AB-9A~9C, AB-1.1-01, AB-1.1-02, AB-1.1-02A, AB-1.1-29, 后台§7, 后台§7.5

---

> **story 约定**：每个 story 标注**后端/前端/联调**分段（跨双代码库一次只碰一侧）；每条 AC 标 **L0 静态**（analyze/test/compile，无需 DB/凭证）· **L1 集成**（Docker postgres+redis 真跑）· **L2 端到端**（真实第三方凭证/真机/视觉）。Flyway 号按 story 执行顺序从 V47 顺延（占位号见括注，实际顺延，勿照搬 → 决策 E2）。

## Epic 1: PawCoin 钱包与充值（资金地基）

以「充值」为用户价值载体，建起整个资金子系统地基：支付网关抽象 + 幂等 + 双分录总账 + PawCoin 非负钱包。

### Story 1.1: 支付网关基础设施与幂等地基

As a 平台,
I want 一套抽象的支付网关（Midtrans 收款）+ 幂等锚 + 回调/轮询双通道去重,
So that 后续一切收费场景都建在同一个可靠、可替换、防重复入账的支付基座上。

**Acceptance Criteria:**

**Given** 新增 `shared/pay` 与 `pay` 模块
**When** 定义 `PaymentGateway` 接口 + `MidtransGateway` 实现 + `payment_intents` 表（Flyway V47）
**Then** 编译通过、单测覆盖幂等键唯一约束与状态迁移（**L0**）
**And** `mvn -B package` 绿灯，`payment_intents` schema 与实体 `validate` 一致（**L1**）

**Given** Midtrans 回调与 QRIS 轮询双通道
**When** 同一笔支付经两通道各回一次
**Then** 仅入账一次、二次 capture 被幂等拒绝（**L1**）
**And** 回调验签失败即拒、密钥全 env 注入不入库（**L1**）；真实 Midtrans sandbox 一笔 QRIS 走通（**L2**）

### Story 1.2: 双分录总账与 PawCoin 钱包

As a 平台,
I want append-only 双分录总账 + 非负 PawCoin 钱包,
So that 所有资金变动可勾稽对账、PawCoin 余额并发不双花（FR-NFR-1/3）。

**Acceptance Criteria:**

**Given** `ledger_entries`（append-only）+ `pawcoin_wallets`(CHECK balance≥0) + `pawcoin_transactions`（Flyway V47）
**When** 记一笔资金事件
**Then** 借贷分录金额平衡、`entry_group` 关联、无 update/delete 路径（**L0/L1**）

**Given** 并发扣减同一钱包
**When** 两请求同时扣至余额边界
**Then** 行锁 + CHECK 保证不越负、二者不双花（**L1**）
**And** 钱包余额可由 `ledger_entries` 全量重算对账一致（**L1**）

### Story 1.3: PawCoin 充值下单与到账

As a 用户,
I want 选固定档位用 QRIS/DANA 充值 PawCoin,
So that 我能获得可消费的 PawCoin 余额。

**Acceptance Criteria:**

**Given** 充值档位 10k/25k/50k/100k（后台可配，本 story 先内置默认）
**When** `POST /me/pawcoin/topups` 下单 → Midtrans 支付 → 回调
**Then** 成功后 1:1 入账钱包 + 写 `TOPUP` 流水 + 平账分录（**L1**）
**And** 幂等：回调重放不重复入账（**L1**）；sandbox 真充值一笔到账（**L2**）

### Story 1.4: PawCoin 余额与流水页

As a 用户,
I want 在「我的」看到 PawCoin 余额与消费明细,
So that 我清楚自己有多少、花在哪。

**Acceptance Criteria:**

**Given** PawCoin 余额块组件（UX-DR3，1 koin=Rp1）
**When** 进入余额页
**Then** 显示余额 + 只读 ledger 流水（+/−、类型、时间），**无转账入口**、流水不可点（**L0**）
**And** `flutter analyze`/`flutter test` 绿（**L0**）；模拟器真机连正式后端渲染正确（**L2**）

### Story 1.5: 充值失败/取消与暂停态

As a 用户,
I want 充值失败/取消时余额不变、清楚看到状态,
So that 我不会因失败被误扣或困惑（FR-43F）。

**Acceptance Criteria:**

**Given** 充值失败/取消
**When** 回调返回失败或用户取消
**Then** 余额不变、**不写 ledger 流水**、前端提示「Saldo tidak berubah」（**L1**）

**Given** 浮存门槛触发运营暂停（AB-6C，后端不做一键硬暂停）
**When** 充值入口被暂停标志置灰
**Then** 前端渲染暂停态、不发起下单（**L0**）

### Story 1.6: 注销时 PawCoin 余额作废

As a 用户,
I want 注销账号时被告知 PawCoin 余额作废,
So that 符合不可提现/不转赠的合规约定（FR-50D）。

**Acceptance Criteria:**

**Given** 账号注销流程（延续 FR-20 级联）
**When** 用户确认注销
**Then** 余额作废写终结分录、注销前二次告知（**L1**）
**And** 余额作废纳入注销级联删除路径（**L1**）

## Epic 2: AI 问诊付费解锁

用户用免费额度或付费解锁 AI 详情；红黄免费部分与详建锁定部分分离下发，红色永不锁。

### Story 2.1: 每月免费额度模型与判定

As a 用户,
I want 每月有免费的 AI 问诊额度,
So that 我能低门槛体验、超额再付费（FR-43B）。

**Acceptance Criteria:**

**Given** `user_monthly_free_quota`(唯一 `user_id,period`)（Flyway V48）
**When** 同账号本月并发发起
**Then** 加锁读写、`used_count` 原子递增、不超发（**L1**）
**And** period 按 WIB 每月 1 日切换、默认 1 次可后台调（**L0/L1**）

### Story 2.2: AI 结果分字段下发与锁定态

As a 用户,
I want 始终免费看到等级图标/颜色/黄色时效/红色强提醒，详细建议按解锁下发,
So that 安全信息永不被付费墙挡住（FR-43A/43C，C-7）。

**Acceptance Criteria:**

**Given** `triage_records` 加 `unlock_source`/`unlock_channel`（Flyway V48）
**When** 返回分诊结果
**Then** 红/黄免费部分与 `SARAN PERAWATAN` 详建**分字段**下发（**L0/L1**）
**And** **红色评级 `unlock_source` 恒不需付费、永不锁**（安全护栏，即使额度耗尽）（**L1**）
**And** `unlock_source` 一经写入不可覆盖、生成失败不建记录（**L1**）

### Story 2.3: 付费/额度解锁流

As a 用户,
I want 用免费额度或 PawCoin/现金解锁 AI 详细建议,
So that 我能看到完整养护建议。

**Acceptance Criteria:**

**Given** `POST /triage/{token}/unlock` + `ai_consult_orders`（独立命名空间，Flyway V48）
**When** 用额度或支付解锁
**Then** 「生成结果+额度扣减/支付+解锁来源写入」同一事务、幂等（**L1**）
**And** 解锁后可复看、二次解锁不重复扣费（**L1**）；sandbox 真付费解锁一次（**L2**）

### Story 2.4: 解锁前端与 paywall UI

As a 用户,
I want 清晰的锁定态与解锁 CTA,
So that 我知道免费看到什么、付费解锁什么。

**Acceptance Criteria:**

**Given** 结果锁定态（C-7 修订）
**When** 未解锁查看
**Then** 详建 blur + CTA「PawCoin atau Rp10.000」；黄色图标/颜色/时效免费可见；**红色无锁**（**L0**）
**And** 对读屏隐藏 blur 内容、`flutter analyze` 绿（**L0**）；模拟器视觉验收（**L2**）

## Epic 3: 兽医咨询计费流（含兽医端）

免费入队→接单→限时支付→会话；服务端权威计时 + 两表 CAS + @Scheduled，无中间件。

### Story 3.1: 咨询请求两表与状态机

As a 平台,
I want `consult_requests`（付费前临时 CAS）+ `consult_orders`（支付成功才建）两表,
So that 未扣费不建单、取消/超时不留痕、订单中心无「已取消」态（A-5）。

**Acceptance Criteria:**

**Given** 两表 + `consult_order_stage_events`（Flyway V49）
**When** accept 与 cancel 并发
**Then** `state` 单列 compare-and-set 先到先得、杜绝双写（H-4）（**L1**）
**And** schema `validate` 一致、状态枚举 UPPER_SNAKE（**L0/L1**）

### Story 3.2: 免费入队与广播

As a 用户,
I want 免费发起咨询进入排队并广播给在线兽医,
So that 我不用先付费就能求诊（FR-43D）。

**Acceptance Criteria:**

**Given** `POST /consultations`（不扣费、不建订单）
**When** 入队后 1 分钟无兽医接单
**Then** 请求静默消失、不生成订单、无痕（**L1**）
**And** `@Scheduled` 扫描入队超时、广播所有在线兽医（FR-22E），无 MQ/延迟队列（**L1**）

### Story 3.3: 兽医接单与限时支付窗

As a 兽医,
I want 接单后系统给用户开 1.5 分钟限时支付窗、我侧看到「等待支付」倒计时,
So that 我知道这单是否会成、不空等（FR-53A）。

**Acceptance Criteria:**

**Given** `POST /consultations/{token}/accept`（CAS）
**When** 接单成功
**Then** 置 `ACCEPTED_AWAIT_PAY`、`pay_deadline_at`=+1.5min、兽医侧展示同一倒计时中间态（**L1**）
**And** 服务端权威计时、`@Scheduled` 扫过期（**L1**）

### Story 3.4: 限时支付、建会话与超时重播

As a 用户,
I want 在限时窗内付费成功即建会话，超时/取消自动重播,
So that 付费即得服务、没付上也不吃亏（FR-43D）。

**Acceptance Criteria:**

**Given** `POST /consultations/{token}/pay`（QRIS/DANA/PawCoin）
**When** 支付成功
**Then** 建 `consult_orders`(IN_PROGRESS) + 建腾讯 IM 会话 + 记 `paid_at`（**L1**）

**Given** 支付超时/取消或跳去充值
**When** 到期未付
**Then** 作废本次接单、`rebroadcast_count++` 重播、表单不丢；跳充值时 `paused_at` 暂停返回顺延（A-4）（**L1**）
**And** 「支付成功但 IM 建会话失败」触发系统故障退款路径（**L1**）；sandbox 真支付建会话（**L2**）

### Story 3.5: 用户端下单链路 UI

As a 用户,
I want 确认→等待→限时支付三屏与明确的中断语义,
So that 全流程清楚、返回不误伤（UX-DR11/14）。

**Acceptance Criteria:**

**Given** `p-vet-request-confirm`/`p-vet-waiting`(1min)/`p-vet-timed-pay`(1.5min)
**When** 各态中断/返回
**Then** 待接单=可丢弃无痕 / 待支付=服务端权威中断即重播 / 支付按钮全程可用（**L0**）
**And** `flutter analyze` 绿（**L0**）；模拟器全链路视觉验收（**L2**）

### Story 3.6: 兽医端队列、进行中会话与 Toast

As a 兽医,
I want 接单队列、进行中会话页、取消/超时 Toast,
So that 我能管理我的问诊（补 p-vet-active 死链，FR-53B/UX-DR12）。

**Acceptance Criteria:**

**Given** `GET /vet/queue` + `GET /vet/active`
**When** 兽医进入工作台
**Then** 队列与「进行中会话」均有落地页、5 tab 无死链（**L0/L1**）
**And** 取消/超时/未支付 3 秒 Toast（**L0**）；真机双端验收（**L2**）

### Story 3.7: 兽医到手金额与月度收入

As a 兽医,
I want 会话完成看到到手金额、月度收入汇总,
So that 我清楚收益（FR-53C/53D）。

**Acceptance Criteria:**

**Given** `vet_settlements`（Flyway V49/顺延）+ 分成快照
**When** 会话完成
**Then** 显示到手 Rp30,000（=50k×分成快照）（**L1**）
**And** 每月 1 日 WIB `@Scheduled` 生成月结、收入页展示当月待结算+历史倒序（**L1**）；真机视觉（**L2**）

### Story 3.8: Konsultasi UI 定稿与封禁挂起逃生

As a 用户,
I want 会话文案定稿、移除「结束会话」按钮、被封兽医挂起时我有逃生入口,
So that 会话安全不被劫持（FR-43G/H-5）。

**Acceptance Criteria:**

**Given** 兽医「待封禁挂起」态
**When** 挂起 ≤15 分钟或会话超时
**Then** 系统强制结束 + 按支付方式退款 + 封禁生效、用户端恢复「结束/上报」入口（**L1**）
**And** 用户端移除「结束会话」按钮、文案定稿（**L0**）；真机验收逃生路径（**L2**）

## Epic 4: 退款与客服工单

用户申请退款→客服判定→选方式填收款→主管审批→财务打款；两段审核可区分、职责分离。

### Story 4.1: 客服工单模型升级

As a 平台,
I want 工单支持图片附件、自填联系方式、CSAT、内部备注、标签,
So that 客服能完整处理投诉（FR-52，AB-5A~5H）。

**Acceptance Criteria:**

**Given** 工单表 ALTER + attachments/labels/notes 子表 + `cs_rating` 预留（Flyway V53）
**When** 建工单带 ≤5 图（OSS 预签名）
**Then** 附件/联系方式/标签落库、内部备注用户不可见（**L1**）
**And** schema `validate` 一致（**L0/L1**）

### Story 4.2: 用户端投诉工单升级

As a 用户,
I want 提投诉时能附图并留联系方式,
So that 客服更快联系我（FR-52）。

**Acceptance Criteria:**

**Given** `POST /support-tickets`
**When** 提交带附件与联系方式
**Then** 上传成功、工单可查（**L1**）；`flutter analyze` 绿（**L0**）；模拟器验收（**L2**）

### Story 4.3: 退款申请与两段审批模型

As a 平台,
I want `refund_requests` 明确分离客服判定与主管审批两段、算净额、加密收款账号、职责分离,
So that 退款可审计、UI 不撒谎、内控不越界（A-1/A-2/FR-NFR-4）。

**Acceptance Criteria:**

**Given** `refund_requests`（两段字段 + `net_amount` + 加密 payout PII）（Flyway V50）
**When** 计算净额
**Then** `net = order_amount − channel_fee`（BCA0/OVO·GoPay2500）后端权威（**L1**）

**Given** 提交/审批/打款
**When** 同一 admin 试图兼任两职
**Then** 服务层拒绝（SUPER_ADMIN 不豁免）+ 记审计（**L1**）

### Story 4.4: 客服判定与订单解锁/回落

As a 客服,
I want 批准退款需求即解锁用户选方式、驳回即通知并回落订单,
So that 两段审核可区分、驳回后订单状态诚实（AB-5B/A-2）。

**Acceptance Criteria:**

**Given** 客服在后台判定
**When** 批准退款需求
**Then** 订单置 REFUNDING、App 解锁「选退款方式」入口、**不发通知**（**L1**）

**Given** 客服驳回退款需求
**When** 驳回
**Then** 发「退款申请未通过」通知 + 订单**回落 COMPLETED 置 `refund_rejected`**（**L1**）

### Story 4.5: 用户选退款方式与填收款

As a 用户,
I want 选退款方式（PawCoin 即时退/真钱填账户）,
So that 我按支付方式拿回钱（UX-DR4/8，C-1）。

**Acceptance Criteria:**

**Given** 退款方式选择/填收款/账户核实 3 屏
**When** PawCoin 订单
**Then** 即时退币、无手续费、跳过选账户（**L1**）

**Given** QRIS/DANA 订单
**When** 选真钱退
**Then** 填收款账户、显示净额（与后端一致）、**不加 bonus**；仅「系统故障未交付+转 PawCoin」才加 bonus（**L1**）
**And** 提交后不可逆、提交前可退回 rs-choose（UX-DR14）（**L0**）；模拟器验收（**L2**）

### Story 4.6: 主管审批与财务打款

As a 主管/财务,
I want 审批退款申请、财务打款到账并回撤兽医收入,
So that 退款闭环且账目平（AB-5E）。

**Acceptance Criteria:**

**Given** `approval_status` 流转 + Iris 出款
**When** 主管通过→财务标记打款
**Then** 订单置 REFUNDED、付款凭证留痕、审计哈希链记录（**L1**）
**And** 兽医应付/已付分录**反向冲销**、月结视图随动（**L1**）；sandbox 真出款一笔（**L2**）

### Story 4.7: 工单结案通知与 CSAT

As a 用户,
I want 工单结案收到通知并可评价满意度,
So that 我知道处理结果、平台能收集 CSAT（FR-52A/52B）。

**Acceptance Criteria:**

**Given** 客服勾「已联系+已解决」
**When** 结案
**Then** 发结案通知 + 触发 CSAT 调查（1-5 分 + 评论）（**L1**）；前端问卷（**L0/L2**）

### Story 4.8: 举报处理结果回告举报人

As a 举报人,
I want 举报被处理后收到通知,
So that 我知道平台已跟进（FR-51）。

**Acceptance Criteria:**

**Given** 举报工单处置完成
**When** 运营下架/保留
**Then** 通知举报人「已处理」、**不透露具体结果**（**L1**）
**And** 通知 INSERT 走 REQUIRES_NEW（避 AFTER_COMMIT 吞写）（**L1**）

## Epic 5: 订单中心

一处查看 4 类支付凭证；退款处理中映射 info 蓝而非 error 红。

### Story 5.1: 订单聚合接口

As a 平台,
I want `GET /orders` 泛化聚合 AI/充值/HD/兽医四类订单,
So that 前端一个接口渲染全部订单（FR-54，UX-DR7）。

**Acceptance Criteria:**

**Given** 四类订单来源
**When** 请求订单列表
**Then** 游标分页 + 类型筛选 + PawCoin 汇总 + 待接单 `amount` 返 null（**L1**）
**And** 退款处理中状态语义支持前端映射 info（非 error）（**L1**）

### Story 5.2: 订单卡片与列表页

As a 用户,
I want 订单列表卡片清晰区分类型与状态,
So that 我一眼看懂每笔（UX-DR1/2）。

**Acceptance Criteria:**

**Given** 订单卡片组件（4 类型图标 + 3 状态色 + 退款 chip）
**When** 渲染列表
**Then** 退款处理中→info 蓝、待接单显「Belum ada pembayaran」、空态友好（**L0**）
**And** `flutter analyze` 绿（**L0**）；模拟器验收（**L2**）

### Story 5.3: 订单详情各态与失效态

As a 用户,
I want 点进订单看到对应类型的详情与状态,
So that 我能查凭证、看退款进度（FR-54A~D）。

**Acceptance Criteria:**

**Given** 兽医 6 态 / AI / 充值 / HD 详情
**When** 进入详情
**Then** 各态字段正确、退款态展示两子阶段/子变体（**L0/L1**）
**And** 宠物已删→订单失效占位态（非裸失败，FR-54D）（**L1**）；模拟器验收（**L2**）

## Epic 6: 宠物身份证与分享拉新

### Story 6.1: 流水号与身份证数据

As a 平台,
I want 全平台自增流水号 + 身份证数据接口 + 老用户未生成引导态,
So that 身份证有唯一编号、老用户有引导（FR-49A）。

**Acceptance Criteria:**

**Given** `pet_profiles.serial_id` 唯一 + 号池回收（Flyway V55）
**When** 分配流水号
**Then** 从 1 自增、删除释放回收、并发不撞号（**L1**）
**And** 分享/快照绑内部 id 不绑 serial_id、老用户返「尚未生成」标志（**L1**）

### Story 6.2: 三风格证件卡渲染

As a 用户,
I want KTP/Paspor/Pelajar 三风格证件卡,
So that 我能给宠物做趣味身份证（FR-49B，UX-DR5）。

**Acceptance Criteria:**

**Given** 三风格 + 1600×900 + 三级水印
**When** 渲染/编辑
**Then** KTP 有正反面、背面含娱乐仿制免责、编辑**仅当前预览会话不写档案**（**L0**）
**And** 不渲染成可冒充真实政府证件（**L0**）；模拟器视觉验收（**L2**）

### Story 6.3: 高清图付费下载

As a 用户,
I want 付费下载身份证高清图,
So that 我能永久保存（FR-49D）。

**Acceptance Criteria:**

**Given** `id_card_hd_purchases`（一次性永久，Flyway V55）
**When** 付费 Rp5,000
**Then** 解锁后可无限复下载、幂等（**L1**）；sandbox 真购一次（**L2**）

### Story 6.4: 分享落地页与拉新

As a 用户,
I want 分享身份证链接、他人点开直达或走落地页拉新,
So that 传播带来新用户（FR-49C）。

**Acceptance Criteria:**

**Given** 分享链接 + Thymeleaf 落地页 + deferred deep link
**When** 已装/未装 App 点开
**Then** 已装直达 App 内页、未装走落地页引导下载（**L1**）
**And** 目标档案已删/token 失效→失效态（非 500）（**L1**）；真机拉新链路验收（**L2**）

## Epic 7: 成长档案健康记录与里程碑

### Story 7.1: 健康记录模型与 CRUD

As a 用户,
I want 录入结构化健康记录,
So that 我能系统记录宠物健康（FR-45/45A）。

**Acceptance Criteria:**

**Given** `health_records`（类型枚举 + event_date）（Flyway V56）
**When** 增删改查
**Then** event_date 不可未来、字段长度校验、档案删除级联硬删（**L1**）
**And** schema `validate` 一致（**L0/L1**）

### Story 7.2: 健康记录列表与混排

As a 用户,
I want 健康记录与问诊存档只读混排,
So that 我在一处看全宠物健康时间线（FR-45B/45C，UX-DR10）。

**Acceptance Criteria:**

**Given** 结构化记录（可编辑）+ 🏥问诊存档（只读，来自 FR-16）
**When** 渲染列表
**Then** `editable` 标志区分可点、问诊条目不入 health_records 表（**L0/L1**）
**And** 健康记录触发里程碑第四路径（FR-45C）（**L1**）；模拟器验收（**L2**）

### Story 7.3: 新手任务与聚合里程碑

As a 用户,
I want 6 个新手任务与聚合里程碑 Lulus Pemula,
So that 我有引导性成长目标（FR-47）。

**Acceptance Criteria:**

**Given** 6 任务独立计数 + Lulus Pemula（S 级 15→16，总 31）（Flyway V59）
**When** 完成任务/老用户回溯
**Then** 独立计数、聚合解锁、老用户批量回溯一次性作业（**L1**）
**And** `flutter analyze` 绿（**L0**）；模拟器视觉（**L2**）

## Epic 8: 首页情绪彩蛋（纯前端）

### Story 8.1: 情绪彩蛋气泡

As a 用户,
I want 首页看到贴心的情绪彩蛋便签,
So that 每次打开有小惊喜（FR-48/48A~D，UX-DR6）。

**Acceptance Criteria:**

**Given** 12 条本地定稿文案（aku/kamu 人称）
**When** 首页加载
**Then** 100% 本地生成、23:00–04:00 高概率专属条目、状态 A 用宠物头像/B·C 系统占位、**离线不留白不转圈**（**L0**）
**And** `flutter analyze`/`flutter test` 绿（**L0**）；模拟器视觉验收（**L2**）

## Epic 9: 后台资金与运营治理

合并同一 admin slice 的后台工作；所有资金/配置/处置写操作接入 AdminAuditService 哈希链。

### Story 9.1: 后台权限点扩充与导航整改

As a 超管,
I want 新增细粒度权限点、整改兽医相关导航,
So that 后台权限覆盖 V1.1 新功能、导航不重复（AB-1.1-29）。

**Acceptance Criteria:**

**Given** 新权限点（兽医/AI 订单查看导出、内容导出、举报者清单、虚拟账号、退款三权限点、配置类）
**When** STAFF 勾选/取消
**Then** 端点按权限门控、导航去重（问诊异常）、「会话查询」改「历史会话查询」（**L1**）

### Story 9.2: 定价与 PawCoin 配置

As a 运营,
I want 配置定价、分成、免费额度、PawCoin 溢价与充值档位,
So that 收费参数可运营（AB-8A/8F/6A/6B）。

**Acceptance Criteria:**

**Given** `pricing_config`/`pawcoin_config`/`pawcoin_topup_tiers` + `config_change_logs`（Flyway V51）
**When** 改配置
**Then** 仅影响后续（历史按成交快照）、变更日志 + 审计哈希链、档位保底 ≥1 启用（**L1**）

### Story 9.3: 兽医咨询订单只读管理

As a 运营,
I want 只读查看兽医咨询订单全流程,
So that 我能追踪订单、标记待核查（AB-8B）。

**Acceptance Criteria:**

**Given** 订单列表 + 6 态 + 重播计数 + 待核查标记 + 导出
**When** 查看
**Then** 只读、**无退款入口**（退款走工单）、可导出（**L1**）

### Story 9.4: AI 问诊收入统计与订单查询

As a 运营,
I want AI 问诊收入汇总与订单级查询,
So that 我掌握 AI 收入（AB-8C/8G）。

**Acceptance Criteria:**

**Given** AI 收入统计 + `ai_consult_orders`（独立命名空间）查询
**When** 查询
**Then** 汇总正确、订单级可查可导出、与兽医订单命名空间隔离（**L1**）

### Story 9.5: 兽医分成月结对账

As a 财务,
I want 月结对账→确认→已付款→归档,
So that 兽医分成可对账打款（AB-8D）。

**Acceptance Criteria:**

**Given** `vet_settlements`（月 1 日生成）
**When** 财务确认打款
**Then** 状态 PENDING_FINANCE→PAID→ARCHIVED、凭证 + 审计（**L1**）

### Story 9.6: 支付记录查询与红色超额监控

As a 客服/运营,
I want 跨类型支付记录查询 + 红色超额只读监控,
So that 我能排查与观测（AB-8E/7A）。

**Acceptance Criteria:**

**Given** 支付记录通用查询 + 红色超额监控页
**When** 查询/标记
**Then** 按用户跨类型只读查、超额人工标记待核查/已处理（无自动拦截）（**L1**）

### Story 9.7: 昵称/宠物名异步审核

As a 运营,
I want 先放行后审核昵称/宠物名、逾期限流,
So that 违规标识被治理不误伤体验（AB-9）。

**Acceptance Criteria:**

**Given** `identity_moderation_tickets` + 屏蔽态列（Flyway V54）
**When** 中/高风险入队处置
**Then** 先放行、要求修改默认 +7 天、逾期自动限流、他人见 `***` 本人见原文（**L1**）

### Story 9.8: 虚拟账号与种子批量上传

As a 运营,
I want 管理虚拟账号、批量上传种子内容,
So that 冷启动内容可运营（AB-1.1-02A/02）。

**Acceptance Criteria:**

**Given** `users.account_type`(REAL/VIRTUAL)（Flyway V57）+ zip+Excel 指纹
**When** 建虚拟账号/批量上传
**Then** 虚拟账号无登录能力、复用 author_id、指纹比对、图片命名规范（**L1**）
**And** 与已上线 id1-20 种子作者一致（**L1**）

### Story 9.9: 评论内容管理与封禁挂起处置

As a 运营,
I want 评论纳入内容管理主动下架、帖子高风险提交拦截、封禁挂起处置,
So that 内容与安全事件可治理（后台§7/§7.5）。

**Acceptance Criteria:**

**Given** 评论纳入全量内容管理 + 帖子激活拦截 + 封禁挂起
**When** 下架评论/拦截帖子/兽医封禁遇进行中付费会话
**Then** 评论可主动下架、高风险提交拦截、挂起 15min 强制结束+退款、全程审计（**L1**）

### Story 9.10: 运营概览看板

As a 运营,
I want 用户/订单/内容/兽医四模块指标概览,
So that 我快速掌握运营态势（AB-1.1-01）。

**Acceptance Criteria:**

**Given** 四模块指标聚合
**When** 打开看板
**Then** 指标展示正确、原发布入口升级为概览（**L1**）
