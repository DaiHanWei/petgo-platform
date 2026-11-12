# Story 4.5: Toko 浏览、漏斗与加购转化

Status: review

<!-- 主题 analytics-pipeline，不挂版本（App 侧随 V1.5.0 切换）。分支 feat/analytics-pipeline，基于 main。 -->
<!-- 批次 ②（Epic 4）。只改后端；云端只跑 L0，L1/L2 留本地。 -->

## Story

As a 电商和产品,
I want 查到 Toko 打开人数、漏斗各步人数和加购转化,
so that 知道用户在哪一步流失。

## Acceptance Criteria

> 原样取自 `_bmad-output/planning-artifacts/analytics-pipeline/epics.md` Story 4.5。L0 = 编译 / 单测；L1 = 本地 postgres + redis 真跑（埋点测试库 `petgo_analytics_test`）；L2 = stag / 生产实测。

**Epic 4 通用规则（本 story 全部适用，违反即返工）**

- 只算生产数据且 `is_suspicious=false`；用户维度只算 `role = 'USER'` 且 `account_type = 'REAL'`。
- 按「人」去重：带用户标识的事件归该用户；匿名事件按关联表归属（事件前有关联 → 最近一次；之前没有、之后有 → 第一个；从未关联 → 匿名 id）。
- WIB 自然日；周 = 周一至周日；月 = WIB 自然月。
- 每日去重人数不跨天相加：凡「统计周期内去重人数」的指标，同时写日表、周表、月表（周期结束次日计算并回补）；比率类月指标（取每日比率平均）由日表现算。
- Toko 已付款 = `shop_orders.paid_at IS NOT NULL`，按 `paid_at` 的 WIB 日期；退款 / 取消仍算。业务库关联一律用 public token，读业务库只做窄查询，不做跨库 SQL。
- 汇总表不含 `user_id`、匿名 id 等可识别个人的字段；每张新表在迁移里授权给 `analytics_reader`。

**本 story 验收**

1. **Given** 迁移建 `agg_daily_toko`、`agg_weekly_toko`、`agg_monthly_toko` **Then** 记浏览 `toko_page` 的人数（日 / 周 / 月去重，电商 #4）〔L1〕
2. **And** 漏斗：以进入第一步的人为组，记 7 天内按顺序经过每一步的人数；最后一步「支付成功」取业务库已付款订单；日表、周表、月表各一份（电商 #6）〔L1〕
3. **And** 同时记已提交订单数与已付款订单数（产品 #2）〔L1〕
4. **And** 加购转化：周期内加购的人中，加购后 7 天内完成 Toko 付款的人数（日 / 周 / 月，电商 #5）〔L1〕
5. **And** 依赖 7 天窗口的指标，每次运行回补最近 7 天〔L1〕

**本 story 补充的可验证细则**（从通用规则和架构推出，验收时一并检查）

6. 三张表的迁移在 `db/analytics-migration/V20261113_1400__agg_toko.sql`，三张表都 `GRANT SELECT` 给只读账号；表内没有 `user_id`、匿名 id、`person_key` 等列〔L1〕
7. 汇总 SQL 在 `src/main/resources/analytics/rollup/toko.sql`，日期区间作为参数传入，SQL 内不出现 `now()` / `CURRENT_DATE`〔L0〕
8. 构造数据集测试覆盖：跳步不算（如有第 3 步无第 2 步）、超过 7 天不算、顺序颠倒不算、跨午夜按 WIB 归日、非生产 / 可疑 / 兽医账号被排除、登录前匿名浏览与登录后付款归同一人、周表人数 ≠ 日表人数之和〔L1〕
9. 「支付成功」与业务库同一时段 `paid_at` 不为空的订单按用户抽样一致；`orders_paid` 等于直接查业务库的已付款订单数（只算 `role='USER'` 且 `account_type='REAL'` 的下单人）（FR-22）〔L1〕

## Tasks / Subtasks

- [x] **Task 1：开工前核对前置 story 的真实产物**（AC: 全部）
  - [x] 读 Story 1.1 / 1.2 / 1.6 / 4.1 / 4.4 的 story 文件 File List，确认这些名字的**实际**写法，本 story 全部沿用，不另造：
    - 埋点 `JdbcTemplate` 的 `@Qualifier` 名与 `AnalyticsDataSourceConfig`（1.1）
    - 迁移里授权只读账号的写法（1.2 首次 `GRANT`；stag 角色名是 `analytics_reader_stag`，见 Dev Notes「授权陷阱」）
    - `events_detail` 的生产标记列名（1.6，架构 D-4 只叫「生产标记」）
    - 4.1 的 `RollupJob` 指标族注册方式、`agg_progress` 结构、SQL 文件加载器、**身份归属共用视图 / 函数**的名字和返回列（下文用 `<归属视图>` 指代）
    - 4.4 建立的「读业务库已付款订单」读取器（带单匹配已经在用）——本 story **复用**，不写第二份
  - [x] 读 `petgo-backend/src/main/resources/analytics/event-catalog.yaml`，确认漏斗各步事件名、`screen_viewed` 的 `screen` 属性、`toko_order_pay_tapped` / `toko_order_payment_succeeded` 都已登记且属性被明细层保留；读 `metric-definitions.yaml` 里 Story 3.4 写下的电商 #6 / 产品 #2 核对结论
- [x] **Task 2：迁移 `V20261113_1400__agg_toko.sql`**（AC: 1, 2, 3, 4, 6）
  - [x] 建三张表（结构见 Dev Notes「表结构」），`stat_date` / `week_start` / `month_start` 列注释写明「WIB 日期」
  - [x] 三张表授权只读账号（沿用 1.2 的写法）
  - [x] 本地自查：`bash scripts/ci/check-flyway-versions.sh origin/main` 绿
- [x] **Task 3：汇总 SQL `analytics/rollup/toko.sql`**（AC: 1–5, 7）
  - [x] 一个查询，输入 `:period_start`、`:period_end`（WIB 日期，闭区间）、`:window_end`（= `:period_end` + 7 天），输出一行该周期的全部指标列；日 / 周 / 月三种粒度复用同一 SQL
  - [x] 只按 `event_date` 过滤明细（读取区间 `[:period_start, :window_end + 1]`，再用 `event_time` 精确比窗口），不按 `received_at`，不用 `date(event_time)`
  - [x] 业务库已付款订单由 Java 先读出、写进本事务的临时表 `tmp_toko_paid_orders(user_id bigint, paid_at timestamptz)`（`ON COMMIT DROP`），SQL 只 join 临时表——绝不跨库
  - [x] 漏斗各步、加购转化的算法按 Dev Notes「口径与算法」，不得自行简化
- [x] **Task 4：指标族 Java 实现**（AC: 2, 3, 4, 5, 9）
  - [x] 在 `com.tailtopia.analytics.rollup` 下新增 `TokoRollup`（或 4.1 约定的指标族接口实现），注册进 `RollupJob`
  - [x] 每次运行（运行日 R，`Y = R − 1`）重算：日 `[Y−7, Y]` 每一天；周表、月表中「周期结束日落在 `[Y−7, Y]`」的已结束周期；`agg_progress` 落后时从断点续算（取较早者）
  - [x] 每个周期：读业务库已付款订单（`paid_at ∈ [period_start 00:00 WIB, window_end + 1 00:00 WIB)`，下单人 `role='USER' AND account_type='REAL'`）→ 写临时表 → 跑 `toko.sql` → upsert 目标表 → 推进 `agg_progress`，**同一事务**
  - [x] 业务库另读两个计数：周期内 `paid_at` 落在周期的订单数（`orders_paid`）、`created_at` 落在周期的订单数（`orders_created_biz`，参照列），同样只算 USER + REAL
  - [x] `is_final` = 周期结束日 `<= Y − 7`（窗口已全部关闭）
  - [x] 失败经 TECH 渠道告警（复用 4.1 的失败处理，不另写）
- [x] **Task 5：口径条目**（AC: 1–4）
  - [x] `metric-definitions.yaml` 新增 / 补全 电商 #4、电商 #5、电商 #6、产品 #2 四个条目（字段按 1.2 的结构；4.8 会统一补齐），写明来源表、算法、已知偏差（见 Dev Notes）、SQL 位置 `analytics/rollup/toko.sql`
- [x] **Task 6：测试**（AC: 7, 8, 9）
  - [x] L0：`TokoRollupPeriodTest` —— 重算区间计算（跨月、跨年、周一起始、月末 31/30/28/29 日）、`is_final` 判定、WIB 边界时间戳换算
  - [x] L0：读取 `toko.sql` 断言不含 `now()`、`current_date`、`date(event_time` 等禁用写法（参照 4.1 是否已有同类守门测试，有就扩展）
  - [x] L1：`TokoRollupIT`（埋点测试库 + 业务测试库），按 AC 8 列出的场景构造数据集；AC 9 的一致性断言
  - [x] `mvn -B clean package` 绿（L0）；L1 在本地跑 `mvn -B verify`（按 4.1 的 L1 跑法）

## Dev Notes

### 依赖与范围

- 依赖 Epic 1（明细层、关联表）、Story 2.4（`screen_viewed`）、Story 3.4（漏斗事件核对）、Story 4.1（汇总框架、归属视图）、Story 4.4（业务库已付款订单读取器）。本 story **只做 Toko 指标族**，不改框架。
- 电商 #7 的「加购的用户数」就是本 story 的 `atc_users`（日 / 周 / 月去重），4.6 不再另算。
- 不改 App、不改业务库 schema、不加业务迁移。

### 口径与算法（实现必须逐条照做）

**人**：一律取 `<归属视图>` 给出的人标识（下称 `person_key`）及其解析出的 `user_id`（未关联过的匿名人为 NULL）。只取生产标记为生产、`is_suspicious = false` 的行；`role/account_type` 排除已由 1.6 写进生产标记，确认后不要重复查业务库。

**电商 #4 Toko 打开人数（`toko_viewers`）**：周期内有 `event = 'screen_viewed' AND properties->>'screen' = 'toko_page'` 的去重人数。页面名来源：`petgo_app/lib/core/router/app_router.dart` 的 `Analytics.screen('${tabName}_page')`，Story 2.4 把 `$screen` 改名为 `screen_viewed`、页面名放 `screen` 属性。

**电商 #6 漏斗（`funnel_s1` … `funnel_s7`）**：

| 步 | 判定 | 代码出处 |
|---|---|---|
| s1 | `screen_viewed` 且 `screen='toko_page'` | 同上 |
| s2 | `toko_product_shown` | `shop_product_card.dart:133`、`shop_product_masonry.dart:104` |
| s3 | `toko_product_detail_viewed` | `product_detail_page_v3.dart:118` |
| s4 | `toko_buy_now_tapped` 或 `toko_add_to_cart_tapped` | `product_detail_page_v3.dart:1024/1029`、`sku_picker_sheet.dart:69` |
| s5 | `toko_order_submitted` | `checkout_page_v3.dart:1182`（建单成功后才发） |
| s6 | `toko_order_pay_tapped`，**或** `toko_order_payment_succeeded` | `shop_payment_launcher.dart:118`；`checkout_page_v3.dart:1186` |
| s7 | 业务库已付款订单：同一 `user_id`、`paid_at` 在窗口内 | `shop_orders.paid_at` |

- 组 = 周期内**首次**触发 s1 的人，`t1` = 其在周期内最早的 s1 时间；窗口 = `[t1, t1 + 7 天]`。
- 按顺序：`t_k` = 窗口内、`>= t_{k-1}` 的第一条 s_k 事件时间；没有则该人止步于 k−1。s7 = 存在 `paid_at ∈ [t6, t1 + 7 天]` 的已付款订单。
- 周表 / 月表的组 = 该周 / 该月内首次 s1 的人（**不是**日组相加）。
- 🔴 **s6 为什么也认 `toko_order_payment_succeeded`**：纯 PawCoin / 零元单在提交事务内直接成交（`order.settled`），App 不调 `/pay`、不发 `toko_order_pay_tapped`，直接发 `toko_order_payment_succeeded`（`checkout_page_v3.dart:1183-1187`）。只认 pay_tapped 会让这些人在 s6、s7 漏计。QRIS 路径上 payment_succeeded 必在 pay_tapped 之后，合并不会多算。这是对团队定义的口径补充：写进电商 #6 条目的「已知偏差 / 变更记录」，并列进 Completion Notes 告知团队。若 Story 3.4 已在 `metric-definitions.yaml` 对 s6 给出不同结论，**以 3.4 为准**并在 Completion Notes 说明。
- 匿名人（`user_id IS NULL`）到 s7 一律不通过（Toko 下单必须登录，`shop_carts` 一人一车、游客无车）。

**产品 #2（`orders_submitted`、`orders_paid`）**：
- `orders_submitted` = 周期内 `toko_order_submitted` 事件**条数**（不去重人；事件在建单成功后才发，一次建单一条）。
- `orders_paid` = 业务库 `paid_at` 的 WIB 日期落在周期内的订单数（USER + REAL 下单人）。
- `orders_created_biz`（参照列）= 业务库 `created_at` 落在周期内的订单数。**已知偏差**：`orders_submitted` 只来自 V1.5.0+ 且生产标记的设备，而 `orders_paid` 覆盖所有版本，新版覆盖率低时比值偏高；口径条目写明，并提示 AI「覆盖率未达 90% 前可用 `orders_paid ÷ orders_created_biz` 作参照」。

**电商 #5 加购转化（`atc_users`、`atc_converted_users`）**：
- `atc_users` = 周期内有 `toko_add_to_cart_tapped` 的去重人数。
- `atc_converted_users` = 其中存在某条周期内加购（时间 `t`）且有已付款订单 `paid_at ∈ [t, t + 7 天]` 的人数（不要求订单含该商品，按团队定义）。
- 转化率不落表，由 AI 用两列现算（口径条目写明）。

### 表结构（迁移 `V20261113_1400__agg_toko.sql`）

```sql
CREATE TABLE agg_daily_toko (
    stat_date             DATE        PRIMARY KEY,      -- WIB 日期
    toko_viewers          INTEGER     NOT NULL,         -- 电商 #4
    funnel_s1_toko_page   INTEGER     NOT NULL,         -- 电商 #6，组人数
    funnel_s2_product_shown   INTEGER NOT NULL,
    funnel_s3_detail_viewed   INTEGER NOT NULL,
    funnel_s4_buy_or_cart     INTEGER NOT NULL,
    funnel_s5_order_submitted INTEGER NOT NULL,
    funnel_s6_pay_tapped      INTEGER NOT NULL,
    funnel_s7_paid            INTEGER NOT NULL,
    orders_submitted      INTEGER     NOT NULL,         -- 产品 #2 分母（事件条数）
    orders_paid           INTEGER     NOT NULL,         -- 产品 #2 分子 / 电商 #1（业务库）
    orders_created_biz    INTEGER     NOT NULL,         -- 参照列：业务库建单数
    atc_users             INTEGER     NOT NULL,         -- 电商 #5 分母 / 电商 #7 加购人数
    atc_converted_users   INTEGER     NOT NULL,         -- 电商 #5 分子
    is_final              BOOLEAN     NOT NULL,         -- 7 天窗口已全部关闭
    computed_at           TIMESTAMPTZ NOT NULL
);
-- agg_weekly_toko：主键 week_start DATE（周一，WIB），其余列相同
-- agg_monthly_toko：主键 month_start DATE（当月 1 日，WIB），其余列相同
```

- 计数列用 `INTEGER`，**禁用 `SMALLINT`**（Hibernate/JDBC 映射 int→int4 的历史坑，虽然本模块用 JdbcTemplate，也保持一致）。
- 不建外键、不存任何人标识。upsert：`INSERT … ON CONFLICT (主键) DO UPDATE SET …`，重跑结果相同。

### 授权陷阱（务必先看 1.2 的实际写法）

epics / 架构写「迁移里授权给 `analytics_reader`」，但 stag 库的只读角色叫 `analytics_reader_stag`（Story 1.1 建库脚本、架构 A8）。迁移里硬写 `analytics_reader` 会在 stag 启动时因角色不存在而失败。1.2 是第一个写 `GRANT` 的 story，它的解法（例如 Flyway 占位符）就是本 story 的写法；CI 测试库也需要这个角色存在（1.1 负责）。若 1.2 没处理，停下在 Completion Notes 标为阻塞，不要自己发明第三种写法。

### 业务库读取（窄查询）

- 用业务数据源（`@Primary` 的 `JdbcTemplate`），只读、带时间区间、只取需要的列：

```sql
-- 已付款订单（复用 4.4 的读取器；若其签名不同，按它的签名调用）
SELECT o.user_id, o.paid_at
FROM shop_orders o JOIN users u ON u.id = o.user_id
WHERE o.paid_at >= :from AND o.paid_at < :to
  AND u.role = 'USER' AND u.account_type = 'REAL';
```

- `shop_orders.paid_at`：`V20261024_2131__shop_orders_merchant_columns.sql`，「离开 PENDING_PAYMENT 的同一事务写入，之后不改」。业务库**没有** `PAID` 状态（状态 CHECK 见 `V20260817_2308__init_shop_orders.sql`）。
- `paid_at` 上只有带 `referrer_merchant_id IS NOT NULL` 条件的部分索引，按时间区间查全表会顺扫。订单量小、03:00 低峰跑，可接受；**不要**为此加业务迁移。若 L1 观察到慢于 1 秒，记进 Completion Notes。
- `:from / :to` 由 Java 用 `ZoneId.of("Asia/Jakarta")` 把 WIB 日期换成 `OffsetDateTime`/`Instant` 传入，SQL 内不做时区推算。
- 读出的 `user_id` 只进本事务临时表，用完即弃；日志只打条数。

### 性能

- 月表一次读一个月 + 7 天的明细；先按 `event_date` 区间（分区裁剪）和 `event IN (…)` 过滤进临时表，再做逐步 join。`events_detail` 在 `event` 上有索引（1.6）。
- 任务内 `SET LOCAL statement_timeout` 放宽沿用 4.1；写入账号默认 120s。
- 月表在月结束后 8 次运行内都会重算，属预期。

### 已知偏差（写进口径条目）

- V1.5.0 之前没有行为事件；新版覆盖率低时人数偏低。
- 晚到超过回补窗口（7 天）的事件不再回算。
- s6 合并 `toko_order_payment_succeeded`（见上）。
- 匿名人无法到达 s7。
- 退款 / 取消的订单仍算已付款（D-13）。

### Project Structure Notes

```
petgo-backend/src/main/resources/db/analytics-migration/V20261113_1400__agg_toko.sql   新
petgo-backend/src/main/resources/analytics/rollup/toko.sql                               新
petgo-backend/src/main/resources/analytics/metric-definitions.yaml                      改（4 个条目）
petgo-backend/src/main/java/com/tailtopia/analytics/rollup/TokoRollup.java               新（名字按 4.1 的指标族约定）
petgo-backend/src/test/java/com/tailtopia/analytics/rollup/TokoRollupPeriodTest.java     新（L0）
petgo-backend/src/test/java/com/tailtopia/analytics/rollup/TokoRollupIT.java             新（L1）
```

- 迁移只进 `db/analytics-migration/`，绝不进业务 `db/migration/`；版本号 `V20261113_1400`（本批 4.5–4.8 依次 1400 / 1410 / 1420 / 1430）。
- 埋点表一律 `JdbcTemplate`，不建 JPA 实体（架构「反例」）。
- 打包一律 `mvn -B clean package`（项目 CLAUDE.md 第 6 条）。

### Testing

- L0（云端可跑）：区间与 `is_final` 计算单测、SQL 禁用写法守门、`mvn -B clean package`。
- L1（本地）：埋点测试库 `petgo_analytics_test` + 业务测试库；flush Redis、用独立库，按 4.1 的 L1 跑法。构造数据集至少覆盖 AC 8 的 7 个场景和 AC 9。
- L2：本 story 无（4.8 统一在 stag 用 AI 问「Toko 漏斗」实测）。

### References

- [Source: _bmad-output/planning-artifacts/analytics-pipeline/epics.md#Epic 4 通用规则、Story 4.5]
- [Source: _bmad-output/planning-artifacts/analytics-pipeline/architecture.md#D-5、D-6、D-10、D-12、D-13、A8、实现规范 §2/§4/§5]
- [Source: _bmad-output/planning-artifacts/analytics-pipeline/prd.md#FR-21、§4.5.1（Toko 漏斗、加购转化）、FR-22]
- [Source: _bmad-output/planning-artifacts/analytics-pipeline/department-data-summary.zh.md#电商 #4、#5、#6；产品 #2]
- [Source: petgo-backend/src/main/resources/db/migration/V20260817_2308__init_shop_orders.sql、V20261024_2131__shop_orders_merchant_columns.sql]
- [Source: petgo_app/lib/features/shop/presentation/checkout_page_v3.dart:1170-1260、shop_payment_launcher.dart:118-145]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5（claude-opus-5-5），云端 headless session

### Debug Log References

- `bash scripts/ci/l0-backend.sh`（= `./mvnw -B clean package`，排除 425 个 Spring 上下文 / 真库测试类）→ **BUILD SUCCESS，Tests run: 6746, Failures: 0, Errors: 0, Skipped: 10**（新增 `TokoRollupPeriodTest` 9、`TokoFamilyTest` 8、`RollupMigrationTextTest` +1、`MetricDefinitionsTest` +1）；L1 测试类 `TokoRollupIntegrationTest` 随 clean package 编译通过。
- `bash scripts/ci/check-flyway-versions.sh origin/main` → `flyway-guard: OK（migration 289 个，analytics-migration 12 个…）`。
- 未起 Docker、未连任何库 / 第三方。

### Completion Notes List

- 云端只跑到 L0；**L1/L2 待本地验收**。需本地验收：
  - L1 `TokoRollupIntegrationTest`（埋点测试库 `petgo_analytics_test` + 业务测试库）：AC1–AC4、AC8 全部 7 个场景（跳步、超 7 天、顺序颠倒、跨午夜 WIB 归日、非生产 / 可疑 / 兽医及匿名归到兽医被排除、登录前匿名浏览与登录后付款归同一人、周表人数 ≠ 日表之和）、AC9（`orders_paid` 与直接查业务库一致，日 / 周 / 月）、AC5（`runScheduled` 回补 `[Y−7, Y]`、`is_final`）、幂等、月表。
  - L1 AC6：迁移 `V20261113_1400__agg_toko.sql` 在真库应用、stag 上 `${readerRole}` 授权生效（本地 / CI 无角色走 NOTICE 跳过）。
  - L1 性能观察：业务库 `paid_at` 区间计数会顺扫 `shop_orders`（只有带商家条件的部分索引），若慢于 1 秒记入此处；按 story 不加业务迁移。
  - L2：本 story 无（4.8 统一在 stag 实测）。
- 实现要点：
  - 指标族类名沿用 4.4 约定命名为 `TokoFamily`（story 写的 `TokoRollup` 为暂名）；L1 测试类按项目惯例命名 `TokoRollupIntegrationTest`（项目无 failsafe，`*IT` 不会被 surefire 运行）。
  - 业务库已付款订单**复用** 4.4 的 `BusinessPlacementReader.paidOrders`（按其签名：先在埋点库短查询本单元可能到达第 6 步 / 加购过的已归属用户，再按用户批量读）；`orders_paid` / `orders_created_biz` 两个计数在同一读取器上**新增**两个方法（窄查询 JOIN users 只算 USER + REAL），未改动 4.4 已有方法。
  - 业务库读取全部在 `prepareDay` / `preparePeriods`（埋点库事务之外），结果按 key 校验后在单元事务内写进临时表 `tmp_toko_paid_orders(user_id, paid_at)`（ON COMMIT DROP），SQL 只 join 临时表；两库不跨库。日志只打条数。
  - `toko.sql` 按 4.4 的 `-- @section` 分段（compute / daily / weekly / monthly），日 / 周 / 月复用同一段 compute：先按 `event_date ∈ [period_start, window_end + 1]` 与事件名过滤进 `tmp_toko_events`，再逐步相关子查询求 t2…t6；目标表用 `INSERT … ON CONFLICT DO UPDATE` upsert。SQL 内无 `now()` / `CURRENT_DATE`，`is_final`、业务库计数、`window_end` 全由 Java 传入。
  - 重算：`recomputeDays = 8`（日 `[Y−7, Y]`，进度落后由框架从断点续算）；周 / 月只算**已结束**且结束日落在本轮重算区间内的周期（D-12「周期结束次日计算」），首日早于汇总下限的跳过；`is_final` = 周期末日 ≤ Y − 7。
  - 表结构按 story：日表主键 `stat_date`，周表 `week_start`，月表 `month_start`，标志列 `is_final`（与 4.4 的 `period_start` / `is_complete` 命名不同，以本 story 表结构为准）；计数列一律 `integer`。
  - 失败处理复用 4.1 `RollupService`（进度不推进、事务外 TECH 告警），未另写。
- 口径（需告知团队）：
  - 电商 #6 第 6 步合并 `toko_order_payment_succeeded`（纯 PawCoin / 零元单不经 pay_tapped）—— 与 Story 3.4 在 `metric-definitions.yaml` 的结论一致，已写入电商 #6 已知偏差与变更记录。
  - 电商 #6 第 7 步按**用户**匹配业务库已付款订单（`paid_at ∈ [t6, t1 + 7 天]`），汇总表不分商品；3.4 条目「经订单行的商品 / SKU token 与埋点关联」指商品维度，本 story 表无商品维度，未使用 token 关联，已在条目中写明。
  - 产品 #2 在新版覆盖率低时比值偏高，覆盖率未达 90% 前可用 `orders_paid ÷ orders_created_biz` 作参照（已写入条目）。
  - 电商 #4 / #5 / 产品 #2 条目新增，电商 #6 补 `sql_path` 与汇总算法；Story 3.4 的 `MetricDefinitionsTest` 断言电商 #6 变更记录「仅一条」改为「第一条」（与 4.3 对产品 #1 的处理相同）。

### File List

- petgo-backend/src/main/resources/db/analytics-migration/V20261113_1400__agg_toko.sql（新）
- petgo-backend/src/main/resources/analytics/rollup/toko.sql（新）
- petgo-backend/src/main/java/com/tailtopia/analytics/rollup/TokoFamily.java（新）
- petgo-backend/src/main/java/com/tailtopia/analytics/rollup/BusinessPlacementReader.java（改：新增两个订单计数方法）
- petgo-backend/src/main/java/com/tailtopia/analytics/rollup/RollupConfig.java（改：注册 toko 指标族）
- petgo-backend/src/main/resources/analytics/metric-definitions.yaml（改：电商#4、电商#5、产品#2 新增；电商#6 补全）
- petgo-backend/src/test/java/com/tailtopia/analytics/rollup/TokoRollupPeriodTest.java（新，L0）
- petgo-backend/src/test/java/com/tailtopia/analytics/rollup/TokoFamilyTest.java（新，L0）
- petgo-backend/src/test/java/com/tailtopia/analytics/rollup/TokoRollupIntegrationTest.java（新，L1）
- petgo-backend/src/test/java/com/tailtopia/analytics/rollup/RollupMigrationTextTest.java（改）
- petgo-backend/src/test/java/com/tailtopia/analytics/rollup/RollupSqlFilesTest.java（改）
- petgo-backend/src/test/java/com/tailtopia/analytics/catalog/MetricDefinitionsTest.java（改）
- _bmad-output/implementation-artifacts/analytics-pipeline/4-5-toko-浏览-漏斗与加购转化.md（改）
- _bmad-output/implementation-artifacts/analytics-pipeline/sprint-status-analytics-pipeline.yaml（改）

### Review Findings（代码复审 2026-11-12）

已逐项核对：漏斗 7 步事件与口径条目一致（s1 只认 `screen='toko_page'`、`/shop` 不计；s6 合并 payment_succeeded；s7 `paid_at ∈ [t6, t1+7天]`、匿名不通过）；按序（`t_k ≥ t_{k-1}`、窗口 `[t1, t1+7天]`、跳步即止步）；按 `v_events_person` 归属后的 `person_key` 去重并按 `tmp_excluded_users` 排除；只按 `event_date` 过滤、日期全参数化；周 / 月从明细重算（D-12）、只算已结束且结束日在 `[Y−7, Y]` 内的周期、`is_final = 末日 ≤ Y−7`；业务库读取全在 prepare（事务外）、临时表注入、无跨库 SQL；`BusinessPlacementReader` 只新增方法，4.4 行为不变；迁移版本、表结构、`${readerRole}` 条件授权与 story / 1.1 写法一致。

| # | 严重度 | 判定 | 描述 | 处理 |
|---|---|---|---|---|
| R1 | 低 | CONFIRMED | `metric-definitions.yaml` 电商 #6 的 definition 仍写「第 7 步经订单行商品 / SKU token 与埋点关联」，与本 story 在 known_bias 补的「汇总表按用户匹配、不经 token」自相矛盾，AI 读口径会困惑 | 已修：definition 该句补「（商品维度）；按人汇总的 agg_*_toko.funnel_s7_paid 按同一用户匹配、不经 token」 |
| R2 | — | REJECTED | 偏离 4（s7 按 user_id 匹配而非 public token）是否违反 D-13 | D-13 的「一律用 public token」列举的是商品、SKU、运营位、活动的关联；用户维度按埋点库内部 `user_id` 关联业务库是 A4 明文允许的，且 story 正文 s7 写明「同一 `user_id`」。不违反 |
| R3 | 低 | PLAUSIBLE | `t1 + interval '7 days'` 的天加法依赖会话 TimeZone；若 JVM / 会话时区为有夏令时的区（如 Europe/Berlin），跨 DST 时窗口差 1 小时 | 未修：与 4.4 placement.sql 同写法，容器默认 UTC；如部署改了时区再统一改 `interval '168 hours'` |
| R4 | 低 | PLAUSIBLE | prepare 阶段 `SCAN_USERS_SQL`（月单元扫 ~38 天明细经归属视图）在单元事务外执行，只受写入账号默认 120s `statement_timeout`，未享受 `SET LOCAL` 放宽 | 未修：与 4.4 同模式，只取三类低频事件且有 `event` 索引；L1 / stag 观察耗时，超时再议 |

复审后 L0：`bash scripts/ci/l0-backend.sh` → BUILD SUCCESS，Tests run: 6746, Failures: 0, Errors: 0, Skipped: 10；`check-flyway-versions.sh origin/main` → OK。

### Change Log

- 2026-11-12：Story 4.5 实现（Toko 浏览、漏斗与加购转化指标族 `toko`：迁移、汇总 SQL、指标族、口径条目、L0 / L1 测试）；状态 → review。
- 2026-11-12：代码复审：修 R1（电商 #6 definition 与 known_bias 关于第 7 步关联方式的矛盾）；其余见 Review Findings。


## 跨 story 对齐（2026-11-12 汇总）

- **只读账号授权一律写成 `GRANT SELECT ON <表> TO ${readerRole};`**：`${readerRole}` 是 Story 1.1 在第二套 Flyway 里定义的占位符（环境变量 `ANALYTICS_DB_READER_ROLE`，生产 `analytics_reader`、stag `analytics_reader_stag`、CI 测试库按 1.1 的约定）。不要写死角色名。
- 前置 story 的类名、视图名、列名以已实现代码为准（例如 1.1 的 `AnalyticsPipelineState`、`analyticsTransactionTemplate`，1.6 的生产标记列）；有出入时按代码改本 story 的实现，不改前置 story。
