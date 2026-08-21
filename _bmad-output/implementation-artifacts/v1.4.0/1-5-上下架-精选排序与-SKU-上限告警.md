---
title: "Story 1.5: 上下架、精选排序与 SKU 上限告警（后台 AB-10D）"
epic: 1
story: 5
version: v1.4.0
created: 2026-08-17
flyway: 无（sort_weight / is_active 已由 V101 建好）
baseline_commit: e842753ceca750d39aaa422a24c6b50833dc38ab
---

# Story 1.5: 上下架、精选排序与 SKU 上限告警（后台 AB-10D）

Status: review

> ✅ **本 Story 不需要 Flyway 迁移。** `shop_products.sort_weight` 与 `is_active` **V101 就已建好**（列注释原文：「维护逻辑属 1.5」），并已有索引 `idx_shop_products_listing (category, sort_weight DESC, id DESC)`。
> **V105 保持空号**，勿因「每条 story 都该有迁移」的惯性去建表。
>
> ✅ **读侧也已经就位。** Story 1.1 的 `ShopProductQueryService` 已经全程走 `findByActiveTrue*`，
> 所以「下架商品不出现在 App 任何列表与推荐中」**当前就成立**。本 Story 缺的只是**写入口**与**上限守护**。

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

---

## Story

As a 运营,
I want 控制商品的上下架与首页精选排序,
so that Toko 首页呈现的是我想主推的商品，且商品总数不失控。

---

## Acceptance Criteria

> 验证层级：`[L0]` 静态 · `[L1]` 集成（Docker pg+redis，本机可跑）· `[L2]` 端到端

### AC1 — 上架 / 下架

**Given** 商品已录入（新建默认 `is_active = false`，见 V101 列默认值）
**When** 运营执行上架 / 下架
**Then** 下架商品**不出现在 App 的任何列表与推荐中**
**And** 下架状态可被后台查询到（供 FR-96「购物车失效分区」在 Epic 3 使用；**本 Story 只需保证状态可查，不实现购物车**）
**And** 每次上下架写一条审计

`[L1]` 集成：下架后 `GET /api/v1/shop/products` 与详情接口均查不到该商品
`[L0]` 单测：状态切换与审计动作码

### AC2 — 🔒 SPEC-7 闭合：下架只改可见性，不动已锁定库存

**Given** 某商品有 SKU 的 `locked > 0`（用户已下单未支付）
**When** 运营下架该商品
**Then** ✅ **下架成功**，且 `sku_inventory` 的 `actual` / `locked` **一个数都不动**
**And** 已下单的用户照常付款履约；锁定量随支付出库或 60 分钟超时（AD-8）自然释放
**And** 🔴 **下架不得触发任何库存操作、不得取消任何订单、不得发任何通知**

> 📌 **2026-08-17 产品拍板，闭合 SPEC-7 的「下架时已锁定库存归属」缺口。**
> epics 原文此处写的是「待补规格」——PRD 亦把 SPEC-7 列在**未闭合**的 SPEC 里，与 Story 1.4 的 SPEC-11（已由 S-9 闭合）不同。
>
> **口径：下架 = 只改可见性。** 三条理由：
> ① 与 Story 1.2 确立的「**售罄不下架**」同一口径 —— 库存状态与可见性是两件事，不该互相驱动；
> ② 取消别人已下的单是用户敌意行为，且会牵出退款与通知链路（Epic 3 尚不存在）；
> ③ **召回/食品安全这类需要立即止血的场景已有现成机制** —— 决策 **S-3** 定了「运营在 AB-11D 手工选单取消 + 全额退款含运费 + 站内信告知」。本 Story 零新机制。
>
> ⚠️ **已知代价并接受：下架 ≠ 立即停止发货。** 下架后最长 60 分钟内仍可能有已支付订单需要履约。
> 🔴 **页面必须把这句话显式写给运营看**（见 AC1 的页面提示），否则运营会默认「下架了就不会再发货」——这是个会造成真实误解的落差。

`[L1]` 集成：造 `actual=10 locked=7` 的 SKU → 下架 → 断言两个数都没变、无新增库存流水
`[L0]` 源码护栏：上下架的服务方法**不引用** `InventoryService` / `SkuInventoryRepository` / `InventoryMovementService`（能力缺席，而非靠「记得别调」）

### AC3 — 精选排序权重

**Given** 运营调整首页精选排序
**When** 保存排序权重
**Then** Toko 首页区域④「全部精选」按该权重排序（**读侧 Story 1.1 已实现** `ORDER BY sort_weight DESC, id DESC`，本 Story 只补写入口）
**And** 区域② 的档案推荐结果也按该权重做最终排序（FR-107 第 4 步，**本 Story 只需暴露权重字段**，排序逻辑属 Epic 6）
**And** 权重变更写一条审计

`[L1]` 集成：改权重后对外列表接口的返回顺序随之改变
`[L0]` 单测：权重边界与非法值

### AC4 — 在售 SKU 总数上限告警与阻止上架

**Given** 在售商品的 SKU 总数接近上限
**When** 总数达到配置上限（默认 **30**，C-7）
**Then** 后台**顶部展示告警条**并说明原因
**And** **阻止继续上架**并说明原因

🔴 **三个容易做错的口径，以后台 PRD §AB-10D 原文为准**（epics 的措辞较粗）：

| 项 | ✅ 正确 | ❌ 常见误解 |
|---|---|---|
| 计数对象 | **在售商品（`is_active = true`）的 SKU 总数** | 商品数；或全部 SKU（含未上架） |
| 上限来源 | **可配置**（`petgo.shop.sku-cap`，默认 30），照 `petgo.shop.low-stock-threshold` 的既有范式 | 硬编码 30 |
| 阻止时机 | 上架**会使总数超过上限**时阻止（该商品的 SKU 数一并计入） | 只看当前是否已达 30，不看本次上架会加几个 |

> 📌 后台 PRD 原文：「当**在售商品的 SKU 总数**超过**配置上限**（建议 30，对应用户端 OQ-25）时，后台顶部展示告警条。**这是把「精选不做货架」的战略边界做成产品约束，而不是只写在文档里**」。
> ⚠️ 上限是 C-7 的**战略边界**，不是性能限制 —— 它坐实了三处依赖：规则式推荐 FR-107、固定运费表 FR-99、不做搜索 FR-93。放宽它等于动这三处的前提。

**And** 🔴 **下架不受上限约束**（下架只会让总数变小，任何时候都应允许）

`[L0]` 单测边界值：**29 / 30 / 31**（epics 明确要求）+ 「一次上架多 SKU 商品导致跨越上限」的场景
`[L1]` 集成：达上限后上架被拒且给出明确原因

### AC5 — 权限与审计

**Given** 上下架与排序属商品维护
**When** 接入后台
**Then** 🔴 **复用既有 `shop.product_edit`，不新增权限码** —— 上下架/排序是商品属性的一部分，单开权限码会让「能编辑商品但不能上架」这种没有业务含义的组合变得可表达
**And** 三个动作各写一条审计：`SHOP_PRODUCT_LISTED` / `SHOP_PRODUCT_DELISTED` / `SHOP_PRODUCT_SORT_UPDATED`

`[L0]` 护栏：新增动作码已注册；**未新增权限码**（`AdminPermissionsTest.listStableSize` 应仍为 **49**，不该动）
`[L1]` 集成：无 `shop.product_edit` 的已登录账号执行上架 → 403

---

## Tasks / Subtasks

### 🟦 后端子任务

- [x] **T1 无 Flyway 迁移**（确认项）—— 核对 `sort_weight` / `is_active` 已存在于 V101，**不新建迁移**，并把 sprint 台账里 V105 的猜测行改为「空号」
- [x] **T2 `ShopProduct` 增加状态变更方法**（AC1/AC3）—— `list()` / `delist()` / `applySortWeight(int)`，🔴 **不提供公开 setter**（沿用该实体既有风格）
- [x] **T3 `AdminShopListingService`**（AC1/AC3/AC4/AC5）
  - [x] `list(productId, actor)` —— 上架前校验 SKU 上限；写审计 `SHOP_PRODUCT_LISTED`
  - [x] `delist(productId, actor)` —— 🔴 **不做任何上限校验、不碰库存**；写审计 `SHOP_PRODUCT_DELISTED`
  - [x] `updateSortWeight(productId, weight, actor)` —— 写审计 `SHOP_PRODUCT_SORT_UPDATED`
  - [x] `activeSkuCount()` / `skuCap()` —— 供告警条与阻止判定共用同一口径，**不得两处各算一遍**
- [x] **T4 ⚠️ `AuditActions` 追加 3 个动作码**（AC5）—— **共享文件**，只追加到末尾
- [x] **T5 配置项** `petgo.shop.sku-cap:30`（AC4）—— 照 `petgo.shop.low-stock-threshold:5` 的既有范式注入
- [x] **T6 `AdminShopProductController` 追加端点 + 模板**（AC1/AC3/AC4）
  - [x] `POST /admin/shop/products/{id}/list` · `/delist` · `/sort-weight`
  - [x] 🔴 **端点必须 `try/catch AppException` → `error` flash + redirect**，见 Dev Notes §陷阱 1
  - [x] 列表页顶部告警条（达上限时）+ 「下架 ≠ 立即停止发货」的说明文案
- [x] **T7 ⚠️ i18n 三份同步加**（AC4）—— 告警条、按钮、说明文案；**本 Story 无新权限码，故无 `perm.*` 需要加**
- [x] **T8 测试**
  - [x] `[L0]` `AdminShopListingServiceTest` —— 上限边界 29/30/31 · 多 SKU 跨越上限 · 下架不受限 · 权重校验
  - [x] `[L0]` 源码护栏：上下架服务不引用任何库存类型（AC2 的能力缺席证明）
  - [x] `[L1]` `AdminShopListingEndpointIntegrationTest` —— 下架后对外接口查不到 · **下架不动 locked** · 权重改变对外顺序 · 达上限拒绝上架 · 无权限 403
  - [x] 🔴 **变异验证**：删掉 SKU 上限校验 → 上限测试必须变红；删掉「下架不碰库存」→ AC2 的 L1 必须变红

### 🟨 联调验收子任务（L1）

- [x] 起 pg17 + redis7 容器 → 全流程：录商品 → 上架 → 对外接口可见 → 改权重 → 顺序变化 → 下架 → 对外不可见
- [x] 🔒 造 `locked > 0` 的商品下架，断言库存零变化
- [x] 🔴 **全量 `mvn -B test` 绿**（当前基线 **1642**）—— 不得用窄过滤器代替

### 🟩 前端子任务

- [x] **无。** 后台 Thymeleaf 服务端渲染；用户端读侧 Story 1.1 已实现，本 Story 零 Flutter 侧改动

---

## Dev Notes

### ⚠️ 共享文件（本 Story 仅 2 处，均只追加）

| 文件 | 改什么 | 🔴 约束 |
|---|---|---|
| `admin/audit/service/AuditActions.java` | 末尾追加 3 个动作码（当前 53 个） | 只追加 |
| `i18n/messages_{id,en,zh_CN}.properties` | 追加告警条与按钮文案 | **三份必须同步加** |

> ✅ **本 Story 不改 `AdminPermissions`** —— 复用 `shop.product_edit`。因此**不触发**「加权限码 4 处清单」，`listStableSize` 应保持 **49** 不动。若你发现自己想改这个数字，说明走错方向了。

### 🔴 三个陷阱（前四条 story 已踩过，别再踩）

**陷阱 1 —— 新 POST 端点必须本地 catch `AppException`。**
`GlobalExceptionHandler` 是 `@RestControllerAdvice`，不 catch 就会给运营吐 RFC 9457 裸 JSON。仓库里 17 个既有 admin 控制器全部本地 catch → `error` flash + redirect；Story 1.3/1.4 曾是唯二例外，1.4 已修。
成功用 `notice`、失败用 `error`（仓库主流键名）。
⚠️ **`AdminShopProductController` 是 Story 1.3 的文件，它现有的 `create`/`update`/`upsertSku` 三个端点至今没有 catch** —— 本 Story 在同文件加端点时**顺手把那三个也补上**，别只管自己新加的。

**陷阱 2 —— 上限判定与告警条必须共用同一口径。**
若「顶部告警条」和「阻止上架」各写一遍 count 查询，两处迟早漂移（一个算在售 SKU、一个算商品数），表现为「明明报警了却还能上架」。统一走 `activeSkuCount()`。

**陷阱 3 —— 窄过滤器不能代替全量回归。**
本 Story 改 `AuditActions`（共享文件）。跨模块护栏不在 shop 前缀里。**必须 `mvn -B test` 全量**（本机约 32 秒）。

### 🔴 写护栏测试的三条硬规矩（本工作线已出过三次假绿）

三次假绿形状不同、病根相同：**护栏的判定方式与它声称看守的对象对不上**。

1. **断言「文件含某字符串」前，先确认该字符串在文件里唯一** —— `damageSqlGuardsAvailableNotActual` 曾被同形状的 `lock()` 替它满足。要按方法/符号定位后再断言。
2. **不要用「出现次数 ≥ N」** —— `newPrimitivesClearPersistenceContext` 曾被合规项替违规项凑数。要逐个点名。
3. **写完必须做变异验证** —— 删掉被守护的东西，确认护栏变红。三次假绿都是绿着交出去、变异时才现形。

本 Story 的 AC2 护栏（上下架不碰库存）尤其要照做：它证明的是**能力缺席**，而「服务里没写库存调用」这件事在正常测试下永远是绿的。

### 复用而非重造

| 需要的能力 | 🔴 用这个 |
|---|---|
| 对外列表/详情的 active 过滤 | **已实现**：`ShopProductQueryService` 的 `findByActiveTrue*`（Story 1.1）。不要再加一层过滤 |
| 按权重排序 | **已实现**：`ORDER BY sort_weight DESC, id DESC` + 索引 `idx_shop_products_listing` |
| 配置项注入 | `@Value("${petgo.shop.sku-cap:30}")`，照 `InventoryService` 的 `low-stock-threshold` |
| 审计写入 | `AdminAuditService.record(actor, action, entityType, entityToken, detail)` |
| 权限判定 | `AdminShopProductController` 既有的 `EDIT_AUTH` 常量 |
| 已登录 actor 测试 | Story 1.4 的 `staffWith(...)` 范式（`AdminUserDetails` 6 参构造器 + `TestingAuthenticationToken`） |

### 边界：本 Story 不做什么

- ❌ **不实现购物车失效分区**（FR-96，属 Epic 3）—— 只保证下架状态可查
- ❌ **不实现档案推荐的权重排序**（FR-107 第 4 步，属 Epic 6）—— 只暴露字段
- ❌ **不实现召回式强制取消订单**（S-3 已定归 AB-11D / Story 4.4）
- ❌ **不新增权限码**、**不新增 Flyway 迁移**
- ❌ **不碰 `bottom_tab_bar.dart`**（DEP-1 未拍板）

### References

- [Source: `_bmad-output/planning-artifacts/epics-v1.4.0.md#Story 1.5`（L438–460）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0-后台.md#AB-10D`（L71–77，上限口径以此为准）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/decision-log.md#C-7`（SKU 上限 30 与三处依赖）· `#S-3`（召回走手工选单取消）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0.md#SPEC-7`（L865，本 Story 闭合其中「下架时已锁定库存归属」一项）]
- [Source: `petgo-backend/src/main/resources/db/migration/V101__init_shop_products_and_skus.sql`（L36-37 两列已存在）]
- [Source: `_bmad-output/implementation-artifacts/v1.4.0/1-4-库存管理与采购入库.md#Completion Notes`（评审整改与三次假绿护栏的教训）]

---

## Dev Agent Record

### Agent Model Used

claude-opus-5[1m]（Claude Code）

### Debug Log References

- 全量回归：`mvn -B test` → **1663 通过 / 0 失败 / 6 跳过**（实现前基线 1642，本 Story 新增 21）
- 新增用例：`AdminShopListingServiceTest` 13 条（L0）· `AdminShopListingEndpointIntegrationTest` 8 条（L1）
- 变异验证 2 轮，均正确变红后还原（见下）

### Completion Notes List

#### ✅ 三项「以为要做、其实已经有」——实际新增代码远少于 story 起草时的估计

| 项 | 起草时的假设 | 实际 |
|---|---|---|
| Flyway 迁移 | 可能要建列 | **不需要**。`sort_weight` / `is_active` V101 已建好（列注释「维护逻辑属 1.5」），含索引 `idx_shop_products_listing`。**V105 保持空号** |
| 下架后对外不可见 | 要实现过滤 | **已实现**。Story 1.1 的 `ShopProductQueryService` 全程走 `findByActiveTrue*` |
| AC3 排序权重写入口 | 要新建端点 + `SHOP_PRODUCT_SORT_UPDATED` 动作码 | **已实现**。Story 1.3 的商品编辑表单已含 `sortWeight` 字段，`ShopProductForm` 有该属性，`ShopProduct.apply(...)` 也赋值。**故本 Story 不新建第二条写入口、不加该动作码**——那是重复造轮子。AC3 改为**验证**：加了一条 L1 证明改权重后对外列表顺序确实随之改变 |

👉 因此 `AuditActions` 只追加 **2** 个（`SHOP_PRODUCT_LISTED` / `SHOP_PRODUCT_DELISTED`），非起草时写的 3 个。

#### 🔒 SPEC-7 的落地方式：能力缺席，不是自觉

「下架不碰已锁定库存」若只靠「服务里别写库存调用」，在任何正常测试下都是绿的——**没写的代码不会失败**。
故 `AdminShopListingService` **刻意不 import 任何库存类型**，并配一条源码护栏逐个点名 `InventoryService` / `SkuInventoryRepository` / `InventoryMovementService` / `InventoryMovementRepository` / `SkuInventory`。想违反得先加一个 import，那在评审里是看得见的。
同一条护栏也挡住了 `NotificationService` / `OrderCenterService` / `OrderRepository`——下架不得发通知、不得碰订单。

#### 🔴 变异验证（story T8 要求的两条，均已执行）

| 变异 | 结果 |
|---|---|
| 把 `if (after > skuCap)` 改成 `if (false)`（等于删掉上限校验） | ✅ **L0 2 红 + L1 1 红** |
| 给服务加一行 `import SkuInventoryRepository`（模拟「下架顺手释放锁定量」的念头） | ✅ **能力缺席护栏正确报红并指名该类型** |

两轮均已还原，全量复跑绿。

#### ⚠️ L1 测试踩到的一个真问题：SKU 上限是全局计数，与共享测试库冲突

首跑 8 条 L1 全挂，报的都是「上架会使在售 SKU 总数达到 185，超过上限 30」——**其他 story 的用例在共享库里留下了 184 个在售 SKU**。这不是代码 bug，是测试设计没考虑全局计数。

处置：本类 `@TestPropertySource(petgo.shop.sku-cap=3)` + `@BeforeEach` 把全库商品置为未上架，让计数从 0 起算。
🔴 **精确边界语义（29/30/31）留在 L0 用 mock 覆盖**，L1 只证明机制端到端成立、不复刻数字 30——否则 L1 会随其他 story 往库里塞数据而随机变红。

#### 顺手清掉 Story 1.3 的遗留缺陷

按 story Dev Notes §陷阱 1 的要求，把 `AdminShopProductController` 既有的 `create` / `update` / `upsertSku` **三个端点也补上了 `try/catch AppException`**，并统一 flash 键名为 `notice`/`error`。
它们与 Story 1.4 修掉的是同一个缺陷（`GlobalExceptionHandler` 是 `@RestControllerAdvice`，不 catch 就给运营吐裸 JSON）。至此模块 10 的**全部** 6 个写端点都已合规。

#### 未新增权限码（AC5）

复用 `shop.product_edit`。`AdminPermissionsTest.listStableSize` 保持 **49** 未动——这正是它作为 canary 的用途：本 Story 没碰权限表，那个数字就不该变。

### File List

**新增（3）**

- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopListingService.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopListingServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopListingEndpointIntegrationTest.java`

**修改（6）**

- `petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopProduct.java` —— 追加 `list()` / `delist()`
- `petgo-backend/src/main/java/com/tailtopia/shop/repository/ShopSkuRepository.java` —— 追加 `countByProductId` / `countActiveSkus`（在售 SKU 总数的**唯一定义处**）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java` —— 2 个上下架端点 + 告警条 model + **既有 3 端点补 catch**
- ⚠️ `petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java` —— **共享文件**，末尾追加 2 个动作码
- ⚠️ `petgo-backend/src/main/resources/i18n/messages_{zh_CN,en,id}.properties` —— **共享文件**，各追加 4 条
- `petgo-backend/src/main/resources/templates/admin/shop-products.html` —— 告警条 · 上下架按钮 · SPEC-7 说明文案 · flash 键名统一

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建。**闭合 SPEC-7 的「下架时已锁定库存归属」缺口**（产品拍板：下架只改可见性）。确认无需 Flyway 迁移——两列 V101 已建好，V105 保持空号 |
| 2026-08-17 | 实现完成 → `review`。范围较起草时缩小：AC3 排序写入口 Story 1.3 已实现，故不重建、动作码由 3 个减为 2 个。变异验证 2 轮均正确变红。顺手补齐 Story 1.3 三个端点的 catch。全量 **1663 通过 / 0 失败** |
