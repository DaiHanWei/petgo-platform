# 待发 · Flyway 号段撞车与改号方案（V1.4.0 电商 ↔ V1.1.4 社区管控）

> **发给：** Dai + 何鑫（Hex）
> **紧急度：** 🔴 阻塞「两天后电商合回 stag」，但**不影响何鑫当前的验收**
> **一句话：** 两条线各建了一份 `V101`~`V104`。**git 不会报冲突，Flyway 启动时才炸。**
> 建议**电商这边改号**（我的迁移从没上过任何服务器，改号零风险）。

---

## 一 · 撞了什么

| 号 | V1.1.4 社区管控（已在 stag / staging 跑着） | V1.4.0 电商（我的分支，未上线） |
|---|---|---|
| **V101** | `init_user_hide_relations` | `init_shop_products_and_skus` |
| **V102** | `init_account_reports` | `init_sku_inventory` |
| **V103** | `init_account_disposals` | `add_shop_sku_cost_price` |
| **V104** | `extend_notification_types_account_disposal` | `init_inventory_movements` |

## 二 · 为什么必须现在处理

🔴 **这是「测试全绿也拦不住」的那一类问题。**

两份文件名不同（`V101__init_user_hide_relations.sql` vs `V101__init_shop_products_and_skus.sql`），
所以：**git merge 零冲突 · 编译通过 · 单测可能全绿** —— 一直到服务启动那一刻，Flyway 抛：

```
Found more than one migration with version 101
```

**这条与配置无关**，`out-of-order: true` 也救不了（那个开关管的是乱序应用，不是重号）。
我们自己的 `sprint-status-v1.4.0.yaml:55` 就写着这句警告，2026-07-30 那次
`ck_notifications_type` 事故也是同一个形态：**两边都绿，合起来才坏**。

## 三 · 建议：电商这边改号（V101–V125 → V140–V164）

**为什么该我们改，不是何鑫改：**

| | V1.1.4 社区管控 | V1.4.0 电商 |
|---|---|---|
| 迁移是否已应用到服务器 | ✅ **已经跑在 `petgo_stag` 上** | ❌ 从没上过任何服务器，只在本地库跑过 |
| 改号的代价 | 🔴 要重建 staging 库（已应用的迁移改号 = 校验和失配） | ✅ 改文件名 + 改文档，零风险 |

> ⚠️ 契约上电商线的独占号段是 **V101–V139**（`sprint-status-v1.4.0.yaml:54`），
> 按约定是 V1.1.4 越了界。但**现在追责没有意义** —— 谁改代价小就谁改，
> 而代价小的明显是我们。写在这里只是为了下一轮分号段时把这条口子堵上。

### 新号段：V140–V164

**为什么跳到 140 而不是接着 107：** 现在活着的分支里，`hex/v1.1.6` 已经用到 **V106**
（`V105__add_pet_profile_sex`、`V106__add_content_post_image_sizes`）。
接在 107 后面等于赌「V1.1.6 之后不会再加迁移」—— 这个赌注两周前刚输过一次。
**留 33 个号的缓冲**，把下一次撞车的概率压到接近零。

### 映射表（24 个）

| 旧 | 新 | 内容 |
|---|---|---|
| V101 | **V140** | shop_products + shop_skus |
| V102 | **V141** | sku_inventory |
| V103 | **V142** | shop_skus 加 cost_price |
| V104 | **V143** | inventory_movements |
| V106 | **V144** | shipping_addresses |
| V107 | **V145** | shipping_zones |
| V108 | **V146** | shop_carts |
| V109 | **V147** | shop_orders |
| V110 | **V148** | ⚠️ payment_intents 混合支付（**共享表**） |
| V111 | **V149** | 订单归因 + pawcoin 规则 |
| V112 | **V150** | pawcoin_config 补偿溢价 |
| V113 | **V151** | ⚠️ payment_intents 支付窗（**共享表**） |
| V114 | **V152** | 购物车行归因 |
| V115 | **V153** | shipments |
| V116 | **V154** | ⚠️ `ck_notifications_type` += SHOP_ORDER_SHIPPED（**共享 CHECK**） |
| V117 | **V155** | ⚠️ `ck_notifications_type` += SHOP_ORDER_EXCEPTION（**共享 CHECK**） |
| V118 | **V156** | return_requests |
| V119 | **V157** | opened_precedents |
| V120 | **V158** | ⚠️ `ck_notifications_type` += SHOP_RETURN_UPDATED（**共享 CHECK**） |
| V121 | **V159** | 🔴 `pet_profiles` 加体重/绝育（**共享表，认领待补**） |
| V122 | **V160** | repurchase_triggers + `ck_notifications_type` += REPURCHASE_FOOD_LOW |
| V123 | **V161** | shop_reviews |
| V124 | **V162** | pawcoin_wallets 预留 batch_type |
| V125 | **V163** | notifications 游标索引（NOTIFY-CURSOR-TIE 的配套） |

（V105 本来就是空洞，改号后不再保留空洞；**顺序一律不变**，只整体平移。）

## 四 · 🔴 改号之后必须重跑的三件事

改文件名只是第一步，下面三条不做等于没改：

1. **那 4 个共享 CHECK 迁移（V154/V155/V158/V160）要重新按新顺序核对** ——
   它们是「DROP + ADD `ck_notifications_type`」的接力，每一个都必须包含**前面所有**的值。
   平移后顺序不变，理论上安全，但**必须重跑一次全量验证**，不能靠推理。
2. **重跑升级路径演练** —— 我之前做过一次（main 的 99 个迁移 → 塞存量行 → 我的 24 个 →
   `ddl-auto=validate` 启动），改号后要在**含 V1.1.4 的新基线**上再跑一次，
   因为这次基线里多了 `user_hide_relations` / `account_reports` 等表。
3. **回写 `sprint-status-v1.4.0.yaml` 的号段表** —— 契约要求「取号后立刻回写」。
   上一次没回写就已经造成过一次记录与实际脱节。

## 五 · 要你们拍板的两件事

1. **同意由电商这边改号吗？**（我认为代价明显更小，但这是三条线共用的约定，不该我单方面定）
2. **新号段 V140–V164 会不会又踩到第三条线？** —— 我只能看到 git 上的分支，
   看不到还没推的本地工作。**请确认没有别人正在写 V107–V164 之间的迁移。**

拍板后我这边的工作量约 30 分钟（改名 + 改 30 份文档里的引用 + 重跑演练 + 全量回归），
**全部在 `shawn/oneline-ecommerce` 上做，不碰 stag。**

## 六 · 顺带一提：本次事故暴露的流程缺口

号段冲突**没有任何自动化在看**。建议加一条 CI 检查（我可以写）：

```
扫 db/migration 下所有文件，若出现重复的版本号前缀 → 直接 fail
```

它拦不住「两条分支各自绿、合并才撞」的情况（那要在 merge 后才跑），
但至少能在**合并后的第一次 CI** 就炸出来，而不是等到部署时服务起不来。
