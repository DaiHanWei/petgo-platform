---
baseline_commit: c307613
---

# Story 9.2: 定价与 PawCoin 配置

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **所属**：V1.1 Epic 9 第二个 Story（**后端 + admin 模板**，**触资金子系统**）。承接 9-1 权限码（`config.view`/`config.edit`）。交付：把现散落 env（`petgo.consult.unit-price`/`vet-share-rate`、`petgo.triage.ai-unlock-price`/`default-free-quota`、`petgo.id-hd.download-price`、`petgo.pay.topup-paused`、`TopupTierProvider.Default` 4 档）**外化为 DB 可配**（4 表 + 变更日志 + 审计哈希链），并把运行时读取切到 DB。**成交价/分成/免费额度判定已在各订单落快照** → 改配置**只影响后续**，历史不变。
> **默认值 = 现网 env 默认**（迁移种子照抄，行为零变化）：兽医单价 50000 / 兽医分成 60% / AI 解锁 10000 / HD 下载 5000 / 月免费额度 1 / PawCoin 溢价 0% / 充值暂停 false / 4 档 10k·25k·50k·100k 全启用。**若 prod 曾用自定义 env，部署后由运营在后台改回**（迁移已注记）。
> **资金护栏（不可埋违反点）**：premium_rate ∈ [0,50]；vet_share_rate ∈ [0,100]；monthly_free_quota ∈ [0,35]（红色态零变现不受额度影响，安全规则层「只升不降」）；充值档位**保底 ≥1 启用**（禁全禁）；所有写操作记 `config_change_logs` + `AdminAuditService` 哈希链（§7，append-only）。**禁 MQ/缓存**——config 读走 JPA 单行查询（≤500 DAU 直读即可，护栏禁 Caffeine）。

## Story

As a **运营**,
I want **配置定价、分成、免费额度、PawCoin 溢价与充值档位**,
so that **收费参数可运营（AB-8A/8F/6A/6B）**。

## Acceptance Criteria

> **验证层**：**L0**（config 校验/换算/变更日志组装单测、`mvn compile`）· **L1**（4 表 schema validate、读切 DB 生效、变更日志 + 审计落库、档位保底 ≥1）。无 L2。

### AC1 — 4 表 + schema validate 一致（Flyway V78）

**Given** `pricing_config`（单行 id=1）/`pawcoin_config`（单行 id=1）/`pawcoin_topup_tiers`（多行）/`config_change_logs`（append-only）（**Flyway V78**，非架构旧稿 V51）
**When** 上下文启动
**Then** `ddl-auto=validate` 与实体一致；种子行 = 现网 env 默认
> 验证层：**L0**（迁移 DDL + 实体编译）+ **L1**（validate 过 + 种子值正确）。

### AC2 — 运营读改配置，只影响后续

**Given** `config.view`（读）/`config.edit`（改）门控
**When** 改单价/分成/额度/溢价/暂停/档位
**Then** 后续新成交按新值；**历史订单按已落快照不变**（`consult_orders`/`ai_consult_orders`/`id_card_hd_purchases` 已存快照）；运行时读取（consult 计费 / AI 解锁 / HD 下载 / 免费额度 / 充值档位·暂停）切 DB 生效
> 验证层：**L1**（改 unit-price → 新订单用新价、旧订单快照不变；改 topup-paused → 下单被拒；改档位 enabled → 选项变）+ **L0**（`vetPayout=price*rate/100` 换算单测）。

### AC3 — 变更日志 + 审计哈希链

**Given** 每次配置写
**When** 保存
**Then** 每字段变更（old→new）落 `config_change_logs`（changed_by/at）；同调 `AdminAuditService.record` 入哈希链；无变更字段不记
> 验证层：**L1**（改 2 字段 → 2 条 change log + 审计链各 1 条；未变字段不记）+ **L0**（diff 组装单测）。

### AC4 — 校验护栏

**Given** 非法输入
**When** 保存
**Then** premium_rate>50 / share>100 / quota>35 / 负值 → 422；**禁用最后一个启用档位 → 422（保底 ≥1）**；合法值落库
> 验证层：**L0**（各边界校验分支）+ **L1**（禁最后档位 422、库不变）。

---

## Tasks / Subtasks

> 顺序：迁移 → 域/repo → PlatformConfigService（读/改/日志/审计/校验）→ 运行时读切 DB（consult/triage/id-hd/topup/tier）→ admin UI → 测试。

### 🟦 后端子任务（petgo-backend）

- [ ] **B1. Flyway `V78__init_platform_config.sql`**（AC: 1）——4 表 + 单行种子（照抄 env 默认）+ 4 档种子。号 **V78**（当前 max V77）。
- [ ] **B2. 域 + repo**：`PricingConfig`/`PawCoinConfig`（单行 id=1）/`PawCoinTopupTier`/`ConfigChangeLog` + repositories。
- [ ] **B3. `PlatformConfigService`**（AC: 2,3,4）：`pricing()`/`pawcoin()`/`enabledTiers()`/`allTiers()` 读；`updatePricing`/`updatePawCoin`/`updateTiers` 改（字段 diff → change log + `AdminAuditService.record`；校验护栏；档位保底 ≥1）。
- [ ] **B4. 运行时读切 DB**（AC: 2）：
  - [ ] `FreeQuotaService`（月免费额度）、`AiUnlockService`（AI 解锁价）、`ConsultBillingService`（单价/分成）、`IdCardHdService`（HD 价）改读 `PlatformConfigService`（clamp 保留）。
  - [ ] `TopupTierProvider` 加 **DB 实现**（`@Primary`，读 enabled 档位；空则回退 `Default`）；`PawCoinTopupService` 的 topup-paused 改读 DB。
  - [ ] Properties 字段保留为**回退默认/文档**（不删，避免 env 绑定破坏）；消费点改走 config service。
- [ ] **B5. Admin UI**（AC: 2,3,4）：`AdminConfigController`（`config.view` 看 / `config.edit` 改，`@PreAuthorize`）+ `templates/admin/config.html`（定价/PawCoin/档位三区表单）+ `layout.html` 加「配置」导航（config.view 门控）+ i18n zh/en。
- [ ] **B6. 测试**（AC: 1-4）：L0 `PlatformConfigServiceTest`（换算/diff/校验/保底）；L1 `PlatformConfigIntegrationTest`（读改生效 + 变更日志 + 审计 + 保底 422 + schema validate）。

### 🔗 联调 / 验证

- [ ] **V1. L0**：`mvn -B test-compile` + config 单测。
- [ ] **V2. L1（本地 Docker，可留验收）**：schema validate、读改生效、变更日志/审计、保底。

## Dev Notes

- **Flyway 号权威**：V78（架构旧稿 V51 作废，决策 E2）。当前 max V77（7-3）。
- **快照已在**：consult_orders 存 unit_price/vet_share_rate/vet_payout；ai_consult_orders/triage 存成交价；id_card_hd_purchases 一次性永久。故改配置天然「只影响后续」。
- **红色态零变现**：monthly_free_quota 是「免费额度」非红色态开关；红色态永不加付费墙由安全规则层保证（本 story 不碰）。
- **溢价（premium_rate）**：仅「系统故障未交付 + 转 PawCoin」分支用（4-5 落 `pawcoin_premium_rate_snapshot`）；本 story 只提供可配值，退款分支消费在既有 refund 路径（如未接则读值待接，不倒退护栏 C-1）。
- **无缓存**：config 读走 repo 单行查询；禁 Caffeine/MQ（护栏）。

## Completion Notes

**实现完成（2026-07-14）· L0 全绿；L1 待本地 Docker。触资金子系统,已守护栏。**

### 数据 + 中立读服务
- **Flyway `V78__init_platform_config.sql`**（实际号 V78,非旧稿 V51）：`pricing_config`/`pawcoin_config`（单行 id=1）/`pawcoin_topup_tiers`（4 档种子）/`config_change_logs`。表级 CHECK 兜底（share 0-100、quota 0-35、premium 0-50、amount>0）。种子=现网 env 默认（行为零变化）。
  - ⚠️ **L1 修正(2026-07-15)**：`vet_share_rate`/`monthly_free_quota`/`premium_rate` 初写为 `SMALLINT`,但实体字段是 Java `int` → Hibernate validate 报 `found int2, expecting int4`。改为 `INTEGER`(项目惯例:INT 列配 int,如 ConsultOrder.vetShareRateSnapshot)。V78 未应用真库,直接改。**L1 本地 scratch 库跑通(56+47=103 集成测试全绿,含资金路径回归)。**
- 域 `PricingConfig`/`PawCoinConfig`/`PawCoinTopupTier`/`ConfigChangeLog` + repos（`com.tailtopia.config`,中立模块）。
- `PlatformConfigService`（只读）：`pricing()`/`pawcoin()`/`enabledTiers()`/`allTiers()`。**无缓存**(护栏)。供资金模块消费。

### 写服务（admin slice,校验+日志+审计）
- `AdminConfigService`：`updatePricing`/`updatePawCoin`/`setTierEnabled`。逐字段 diff → `config_change_logs` + `AdminAuditService.record`（哈希链）；无变更不写不审计。护栏校验（premium/share/quota/nonneg）；**充值档位保底 ≥1 启用**（禁停最后一个 → 422）。

### 运行时读切 DB（改配置只影响后续,历史落快照）
- `FreeQuotaService`（月免费额度,clamp 保留）、`AiUnlockService`（AI 解锁价）、`IdCardHdService`（HD 价）、`ConsultPayService`（兽医单价/分成/到手,建单快照读同一行内部一致）改读 `PlatformConfigService`。
- 充值档位：`TopupTierProvider` 返回类型 `TopupTier`→`TopupTierDto`；新增 `DbTopupTierProvider`(`@Primary`,读 enabled 档位,DB 空回退内置默认);`PawCoinTopupService` 暂停态改读 `pawcoin_config`。
- `application.yml` env 降为 V78 种子默认(注释已更新);Properties 类保留不删(避免破坏 env 绑定;consult 超时字段仍用 props)。

### Admin UI
- `AdminConfigController`（`config.view` 看 / `config.edit` 改,`@PreAuthorize`）+ `templates/admin/config.html`（定价/PawCoin/档位三区,edit 无权只读）+ `layout.html` 加「运营配置」导航 + i18n `admin.nav.config`/`group.ops`（zh/en,parity 绿）。

### 测试
- L0：`AdminConfigServiceTest`(8:校验/diff/保底/无变更不写) + 既有受影响单测同步(`FreeQuotaServiceTest`/`AiUnlockServiceTest`/`PawCoinTopupServiceTest` 改 mock 定价链,全绿)。
- L1（待本地）：`PlatformConfigIntegrationTest`(种子默认/读改生效+变更日志/保底 422/schema validate)。
- `mvn -B test-compile` 全通过。

### 待办
- **L1** 本地 Docker 跑 `PlatformConfigIntegrationTest` + consult/ai/id-hd 改价后新单用新价·旧单快照不变（已在各 story L1 覆盖快照,本次改动不改快照语义）。
- **premium_rate 消费**：退款转币 bonus 分支（4-5）若已接则读本值;架构 §119 `pawcoin_premium_rate_snapshot` 落快照。本 story 只提供可配值。
- 未提交（等用户）。
