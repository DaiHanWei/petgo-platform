---
title: "V1.4.0 三人并行开发契约"
type: dev-contract
status: draft-pending-signoff
created: 2026-08-15
authority: "签字后与 CROSS-STORY-DECISIONS.md 同级；本文件是决策 E2 的并行版本"
---

# V1.4.0 三人并行开发契约

`CLAUDE.md` 与决策 **E2** 的「Flyway 按执行顺序单调分配」是**单人串行**前提。本版本三条工作线并行，同一套规则下会撞——**已经撞过两次**：

- **2026-07-11 · Flyway 撞号** → V1.1 资金地基迁移号全量 +13 重排（原文见 `V60__init_payment_intents.sql` 文件头）
- **2026-07-30 · 两条线各自 `DROP+ADD` 同一个 `ck_notifications_type`** → 合并后审核通知整类失效。**两边测试全绿、合并无冲突、编译不报错**（原文见 `V97__union_notification_types_two_lines.sql` 文件头）

本版本要给 `PayChannel` 加 `MIXED`，是第二次事故的同一个剧本。以下三条约束需三方确认。

---

## 一 · Flyway 独占号段

**基线（2026-08-15 实测）：** 最高 **V100**，`main`/`stag`/`hex/v1.1.4`/`hex/v1.1.2` 四条分支全部停在这里；**V86 是空洞**。

| 号段 | 工作线 | 负责人 |
|---|---|---|
| **V101 – V139** | V1.4.0 电商（`shawn/oneline-ecommerce`） | 本人 |
| **V140 – V169** | *（待填）* | *（待填）* |
| **V170 – V199** | *（待填）* | *（待填）* |
| V200+ | 保留 | — |

1. 只在自己号段内建迁移；段内自己递增，**段与段之间不必连续**（V86 空洞即证明 Flyway 不要求连号）
2. 🔴 **合并时不重排号。** 这是与 E2 最大的差异——重排会让已在 stag/测试库应用过的迁移校验和失配，Flyway 直接启动失败
3. 已提交的迁移一律冻结，修正一律新起 `ALTER`（继承 E2）

## 二 · 共享枚举与 CHECK 约束

**本版本唯一要改的是 `PayChannel`（加 `MIXED`）。** 它被 4 个实体映射，取值被 4 个 CHECK 钉在 4 张表上：

| 表.列 | 约束名 | 本版本 |
|---|---|---|
| `payment_intents.channel` | `ck_payment_intents_channel` | ✅ **放宽**，加 `MIXED` |
| `consult_orders.pay_channel` | `ck_consult_orders_channel` | ❌ 不放宽 |
| `ai_consult_orders.pay_channel` | `ck_ai_consult_orders_channel` | ❌ 不放宽 |
| `id_card_hd_purchases.pay_channel` | `ck_id_card_hd_purchases_channel` | ❌ 不放宽 |

1. 🔴 **枚举只在末尾追加**，不重排、不删除、不改拼写。落库枚举都是 `varchar` + CHECK，**重排不报错，但静默改变全部历史行的语义**
2. 🔴 **同一个 CHECK 同期只允许一条线 `DROP+ADD`**（第二次事故的直接教训）。要动上表任一约束**先在群里认领**；本版本我认领 `ck_payment_intents_channel`
3. 若两线确实都要加值，**后合并方取并集重建**，照 `V97` 的写法在文件头写清两边各加了什么
4. **后三个 CHECK 不放宽是刻意的**，不是遗漏——混合支付只发生在电商订单，保持窄 CHECK 让 `MIXED` 误写进虚拟商品表时 DB 当场拒绝。**不要以「统一口径」为由顺手对齐**
5. 加枚举值的 Java 提交与放宽 CHECK 的迁移**必须同 PR**，否则会有「枚举有值但 DB 拒绝」的中间态

> 另：`notifications.type`（`ck_notifications_type`，**19 值**）本版本也要加值，同样需认领——它正是第二次事故的那个约束。
> `OrderType` **不落库**（纯 DTO 枚举，全仓无 `order_type` 列），改它只有编译期风险。

## 三 · `OrderCenterService` 与 App 共享壳

`order/service/OrderCenterService.java`，**275 行**，订单中心唯一 fan-in 聚合器，三线共享且无法拆分。

1. 加订单类型 = **既有 if 链末尾追加分支 + 新增独立 private 方法**。既有三个分支与四个映射器**一行不改**
2. 🔴 **不重构。** 不改成 `switch`、不抽策略模式、不提公共接口——任何结构性重构都会制造无法自动合并的冲突
3. `ID_HD` 没有分支是刻意的（javadoc 明写「ID_HD 无源→空」），**不要顺手补**
4. **改这个文件前先在群里说一声**
5. App 侧 `app_en.arb` / `app_id.arb` / `app_router.dart` **只追加不重排**；`bottom_tab_bar.dart` 的 `AppTab` **4 值无空位，动它须三人同意**（归 DEP-1，未拍板前不碰）

---

## 签字

| 确认人 | 号段 | 日期 | 确认 |
|---|---|---|---|
| 本人（电商线） | V101–V139 | | ☐ |
| *（待填）* | *（待填）* | | ☐ |
| *（待填）* | *（待填）* | | ☐ |

**当场要敲定的三件事：**
1. **号段边界** —— 上表两格由你们自己填，用量只有你们知道
2. **`ck_payment_intents_channel` 与 `ck_notifications_type` 归我改**，这期间别人不碰
3. **`OrderCenterService` 的改动先后** —— 若你们本周期也要加订单类型，排个顺序

签字后追加一条摘要到 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md`。
