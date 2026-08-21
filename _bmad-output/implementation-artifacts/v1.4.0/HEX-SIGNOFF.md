---
title: "需要 Hex 确认的事项清单"
type: signoff-checklist
created: 2026-08-17
context: "V1.4.0 精选自营电商（分支 shawn/oneline-ecommerce）与 Hex 的工作线并行"
---

# 需要 Hex 确认的事项

> **分类依据不是「碰了哪个文件」，而是「撞了会不会被发现」。**
> 大部分共享文件两边都往末尾追加时会产生**可见的 git 冲突**——烦，但拦得住。
> 真正需要事先约定的，是那些**合并干净、编译通过、测试全绿，但语义已经坏掉**的。

---

## 🔴 A 类 · 必须事先约定（撞了不会被发现）

### A-1 · Flyway 号段划分

**现状：** 所有分支的迁移号都停在 **V100**。**两边下一个迁移都会自然地想用 V101。**

**我方已占用：**

| 号 | 内容 | 状态 |
|---|---|---|
| **V101** | `shop_products` + `shop_skus` | ✅ 已提交 |
| **V102** | `sku_inventory` | ✅ 已提交 |
| V103–V139 | 电商后续（地址/购物车/订单/支付扩展/物流/退货/评价/复购） | 预留 |

**请 Hex 确认：** 你的工作线用 **V140 起**（或你指定另一段，只要不与 V101–V139 重叠）。

**为什么必须事先说：**
- 两人各建一个 `V101__xxx.sql`（文件名不同）→ **git 合并干净，无冲突** → Flyway 启动时报「Found more than one migration with version 101」**直接起不来**。这条与任何配置无关，重号就是硬失败。
- 2026-07-11 已经因此全量重排过一次（原文见 `V60__init_payment_intents.sql` 文件头）

> ✏️ **2026-08-17 更正：** 本文件原先还写了第二条论据——「你先把 V105 应用到 stag、我的 V101–V104 后到 → Flyway 默认 `outOfOrder=false` 会拒绝启动」。**这条在本仓库不成立，已删。**
> `petgo-backend/src/main/resources/application.yml:171` **显式设了 `out-of-order: true`**，并附了理由注释（集成分支模型下按「谁测好谁先上」上生产，迁移号无法保证单调应用）；`V87` 迁移的文件头也写了「out-of-order 已开」。本地起库实测，Flyway 自己打的是 `outOfOrder mode is active` 的 WARN，不是拒绝启动。
> **A-1 的结论不变**（重号仍是硬失败，号段仍要划分），但别拿这条去说服 Hex——他 grep 一下 `application.yml` 就能反驳，反而折损整份清单的可信度。

**配套一条：🔴 合并时不重排号。** 这跟决策 E2 的「merge 时号继续单调顺延」相反——重排会让已在 stag 应用过的迁移校验和失配。独占号段的全部意义就是让重排永远不必发生。

---

### A-2 · 两个 CHECK 约束的独占改动权

**这一条是 2026-07-30 事故的同一个剧本**（原文见 `V97__union_notification_types_two_lines.sql` 文件头）：
两条工作线各自 `DROP + ADD` 同一个 `ck_notifications_type`，合并后**审核通知整类失效**——
两边测试全绿、git 无冲突、编译不报错，只在真发那类通知时才炸。

**请 Hex 确认这两个约束在本周期归我改：**

| 约束 | 我要做什么 | 何时 |
|---|---|---|
| `ck_payment_intents_channel` | 放宽为 `('QRIS','PAWCOIN','MIXED')`，支持混合支付 | Story 3.3 |
| `ck_notifications_type` | 末尾追加电商相关通知类型（当前 19 值） | Story 6.3 |

**如果你同期也要改其中任何一个**，请提前说——那就得约定**后合并方取并集重建**，并照 `V97` 的写法在迁移文件头写清两边各加了什么。

---

## 🟡 B 类 · 只需知会（撞了会有可见冲突，拦得住）

以下文件两人都可能改，但**只要各自追加到末尾**，冲突就是可见的 git 冲突，解掉即可。不需要你审批，只需要你知道我动过：

| 文件 | 我改了什么 | 约定 |
|---|---|---|
| `shared/security/SecurityConfig.java` | ✅ **已改**：追加商品 GET 对游客放行（Story 1.1） | 🔴 **只追加，绝不重排既有 `requestMatchers` 顺序**——Spring Security 按声明顺序匹配，重排会静默改鉴权语义 |
| `admin/account/domain/AdminPermissions.java` | 即将追加 `shop.*` 权限码（Story 1.3–1.5） | 常量与 `GROUPS` 的两个 `List.of` **都只在末尾追加**。`ALL` 由 `GROUPS` 派生且是权限码校验白名单，**删/改一个码 = 已授权账号静默失权** |
| `templates/admin/layout.html` | 即将追加「电商」导航块 | 在导航末尾追加 `<details class="nav-section">`，不动既有块顺序 |
| `i18n/messages_{id,en,zh_CN}.properties` | 即将追加电商文案 key | **三份必须同步加**，只追加到末尾。漏一份 → 该语种显示 raw key |
| `order/service/OrderCenterService.java` | 将追加 `ECOMMERCE` 分支（Story 3.9） | 既有 3 个分支与 4 个映射器一行不改；🔴 **不重构成 switch 或策略模式** |
| `pay/domain/PayChannel.java` | 将末尾追加 `MIXED` | 只追加，不重排、不删、不改既有值拼写 |

> ⚠️ **既成事实：** `SecurityConfig` 我已经改并提交了（commit `9e9cd79b`），**当时契约还没定**。改法是纯追加、未重排，但确实没提前打招呼。

---

## 🟢 C 类 · 我不碰，但你若要动请告诉我

| 对象 | 状态 |
|---|---|
| `petgo_app/lib/shared/widgets/bottom_tab_bar.dart` 的 `AppTab` | 4 值无空位。电商 Tab 归 **DEP-1** 未拍板，**本版本我不碰**。你若要动 Tab，我这边的 Toko 入口方案要跟着改 |
| `consult_orders` / `ai_consult_orders` / `id_card_hd_purchases` 三张表的 `pay_channel` CHECK | 我**刻意不放宽**它们（虚拟商品恒单渠道，窄 CHECK 是纵深防御）。**请不要以「统一口径」为由顺手对齐** |

---

## 一句话版（可以直接发给 Hex）

> V1.4.0 电商跟你并行，有两件事想先跟你对一下：
> **① Flyway 号段**——我占 V101–V139（V101/V102/V103 已提交），你从 V140 起可以吗？两边都从 V101 开始的话 git 不会冲突（文件名不同），但 Flyway 会报「Found more than one migration with version 101」直接起不来，07-11 已经因此全量重排过一次。
> **② 两个 CHECK 约束**——`ck_payment_intents_channel`（加 MIXED）和 `ck_notifications_type`（加电商通知类型）本周期归我改，你同期不动它们。这俩要是两边各改各的，就是 07-30 审核通知全断那次的同一个剧本：合并干净、测试全绿、只在真发通知时才炸。
> 另外我会陆续动 `SecurityConfig` / `AdminPermissions` / admin 导航 / 三份 i18n，都只追加到末尾，冲突会是可见的，先跟你说一声。

---

## ⏸️ 签字前的临时授权（2026-08-17，产品口头决定）

**在 Hex 确认之前，A 类两条由产品负责人临时拍板生效**，开发照此推进：
- A-1：V101–V139 归电商线（已按此提交 V101/V102）
- A-2：两个 CHECK 归电商线改（Story 3.3 / 6.3 执行时生效）

🔴 **若 Hex 事后提出异议，代价：**
- A-1 冲突 → 需重排迁移号，且**若已应用到 stag 则必须先清库**（迁移冻结规则下不能改已提交的迁移）
- A-2 冲突 → 需照 `V97` 写法取并集重建约束，并回归验证两边的通知/支付类型全部可用
