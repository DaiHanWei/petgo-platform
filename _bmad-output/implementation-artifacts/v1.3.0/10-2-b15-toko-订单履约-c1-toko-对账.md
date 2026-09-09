---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 10
story: 10.2
ad: [AD-9, AD-11, AD-12]
decisions: [D-8, D-23]
pages: [B15, C1]
ui_frames: [5-8, 5-9, 5-16]
gate: v1.4.0 电商线合入 dev_1.3.0
depends_on: [2-3a, 2-3b]
---

# Story 10.2: B15 Toko 订单履约（列表 + 详情抽屉）+ C1 Toko 对账（模板 C）

Status: ready-for-dev

> ⛔ **门控（AD-12）**：本 story 属 Epic 10，**必须等 v1.4.0 电商线合入 `dev_1.3.0` 之后再启动**。启动前先执行下方「电商线合入后的重核步骤」。依赖 Story 2-3a（htmx 响应约定 / `admin/shared`）与 2-3b（五套模板壳 / `admin-drawer.js` / `admin-workbench.js`）已合入。
> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。本 story **零后端功能改动、零新端点**（AB-19A 原则），只改模板 / Controller 返回形态 / 页面路由退役。

## Story

As a 发货专员 / 财务，
I want 订单列表点行就在抽屉里看金额构成、商品行、收货信息、包裹并直接发货或标记送达；对账页四张核对卡不平时整卡红框，
so that 履约不再跳独立详情页，对账异常一眼可见。

## Acceptance Criteria

**AC1 · B15 列表套模板 B**
**Given** `/admin/shop/orders`
**When** 打开
**Then** 摘要条：待发货（PENDING_SHIPMENT）· 在途（SHIPPED）· 今日签收（DELIVERED，WIB 当日），随筛选联动，单条 `COUNT FILTER` 聚合 `[L1]`
**And** 筛选：订单号 · 状态（`ShopOrderStatus` 全集）· 日期段 · **收件人电话独立搜索框**（现状保留，两条搜索并列）；文本搜索配显式「查询」钮 `[L2]`
**And** 表格列：订单号 ｜ 状态（色点 + 徽标）｜ 金额 ｜ 支付构成（PawCoin·QRIS 段）｜ 配送区 ｜ 包裹数 ｜ 下单时间；默认下单时间倒序；每页 20 `[L1]`
**And** 有异常挂起的订单行打「异常」标并链到 A8（`/admin/shop/order-exceptions?open=<token>`）`[L2]`

**AC2 · B15 详情抽屉（吸收 `shop-order-detail.html`）**
**Given** 点行 → `GET /admin/shop/orders/{token}`（`HX-Request` 返抽屉 fragment）
**Then** ① 订单卡：小计 / 运费 / 免运抵扣 / 合计 / 支付渠道 / PawCoin·QRIS 段 / 四节点时间轴（已下单 → 已发货 → 在途 → 已签收，完成节点带时间）② 商品行表（含**退货规则列**）③ 收货信息卡（收件人 / 电话 / 地址）④ **包裹表**（承运商 / 物流单号 / 承运成本 / 状态 / 送达时间，每行「标记送达」钮）⑤ 底部固定操作条 `[L2]`
**And** 操作（端点零变更，权限 `shop.order_fulfill`）：发货（`POST orders/{token}/ship`，承运商 + 单号常驻输入，空则禁用）· 标记整单已送达（`POST orders/{token}/mark-delivered`，data-confirm）· 标记该包裹送达（`POST orders/{token}/packages/{shipmentId}/delivered`）；抽屉内 htmx 提交，成功刷新抽屉 + oob 列表行，失败 422 行内 err `[L1]`
**And** 无 `shop.order_fulfill` 者按钮禁用并注明所缺权限 `[L1]`

**AC3 · B15 退役**
**Then** `shop-order-detail.html` 删除；`GET /admin/shop/orders/{token}` 非 htmx 请求 404（不做跳转 D-23）；全库 `th:href` 指向该详情页处改为 `?open=<token>` `[L1]`

**AC4 · C1 对账套模板 C**
**Given** `/admin/shop/reconciliation`（`shop.finance_view`）
**When** 选期间
**Then** 四张核对卡字段**现状原样**：① 订单（订单数 / 实付合计 / PawCoin 段 / QRIS 段 / **两段之和 = 实付** 校验行）② 运费（收取合计 / 被 PawCoin 抵扣）③ 退款（已退合计 / PawCoin 段 / **现金净流入**）④ 赠币（发放 / 消费 / 核销额近似）`[L1]`
**And** 校验行不平 → **整卡红框告警**（现状仅文字变色）；顶栏挂「只读」视觉标识；期间切换 htmx 局部替换卡区 `[L2]`
**And** 无写操作、无导出（现状无导出端点，不新增）`[L0]`

**AC5 · 回归**
**Then** `AdminShopOrderEndpointIntegrationTest` 全绿；写端点 3 个路径 / 权限不变；`AdminShopFinanceController` 三个 GET 不变 `[L0/L1]`

---

## Tasks / Subtasks

- [ ] **T0 · 重核**（见 Dev Notes）
- [ ] **T1 · B15 列表**（AC1）：`shop-orders.html` 套 `tpl-b-list`；摘要条聚合查询进 `AdminShopOrderService`（只读新方法，不算新端点）；异常挂起标记的数据源 = 订单是否存在未处理异常（复用 `AdminShopOrderExceptionService` 现有查询）
- [ ] **T2 · B15 抽屉**（AC2）：`fragments/drawer-shop-order.html` 迁入 detail 五区；`AdminShopOrderController.detail()` 在 `HX-Request` 下返 fragment、否则 404；三个 POST 改 fragment 响应
- [ ] **T3 · 退役**（AC3）：删模板；grep `shop/orders/{` 修链接（A5 客服工单「跳订单详情」、A6 来源卡、A7 订单号跳转均改 `?open=`）
- [ ] **T4 · C1**（AC4）：`shop-reconciliation.html` 套 `tpl-c-report`；四卡 fragment `fragments/cards-shop-reconciliation.html`；不平判定在服务层返布尔，模板据此加 `.card-alert` 红框
- [ ] **T5 · 三语**：`admin.v130.shopOrders.*`、`admin.v130.shopReconciliation.*`
- [ ] **T6 · 测试**（AC5）：两页四条 MockMvc；对账红框判定单测（平 / 不平）
- [ ] **T-云端 · 云端执行须知**
  - [ ] 云端只跑 L0：`mvn -B clean package`（跳过需 Docker 的 IT）；L1/L2 留本地
  - [ ] Completion Notes 标注「L1/L2 待本地验收」+ 重核步骤的 diff 结果

---

## Dev Notes

### 🔴 电商线合入后的重核步骤（启动本 story 的第一件事）

1. 在 `dev_1.3.0` 上重跑 `scripts/ci/list-admin-write-ops.sh`（Story 2-1 建），得到合入后的商城组写端点清单。
2. 与本 story「现状代码要点」表逐行 diff：**新增的端点 / 权限码 / 模板一律以新清单为准**，补进本 story 的「操作表」并在 Completion Notes 记录；被电商线删除的端点从本 story 移除。
3. 若电商线已改过本 story 涉及的模板（`shop-*.html`），以合入后的模板为改造起点，不要从本 story 写作时的版本覆盖回去。
4. 重核有实质差异时，先更新本 story 文件再开工；不要边写边猜。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `admin/shop/web/AdminShopOrderController.java` | GET `orders`（筛选含收件人电话）、GET `orders/{token}` 独立详情、POST ship / mark-delivered / packages/{shipmentId}/delivered；VIEW=`shop.order_view`，FULFILL=`shop.order_fulfill` | 列表 fragment 分支、详情改抽屉 fragment、POST 响应形态 | 端点路径 / 参数 / 权限；收件人电话独立搜索 |
| `admin/shop/web/AdminShopFinanceController.java` | GET margin / inventory-turnover / reconciliation，均 `shop.finance_view` | reconciliation 期间切换 fragment 分支 + 红框判定 | 三个 GET 路径 |
| `shop/order/domain/ShopOrderStatus` | PENDING_PAYMENT PENDING_SHIPMENT SHIPPED DELIVERED COMPLETED CANCELLED REFUNDING REFUNDED | 摘要条与色点映射 | — |
| `templates/admin/shop-orders.html`（115）/ `shop-order-detail.html`（167）/ `shop-reconciliation.html`（79） | 列表 + 独立详情 + 四卡文字告警 | 套模板 B / C；详情迁抽屉 | 详情五区全部字段、四卡全部字段 |
| `test/.../AdminShopOrderEndpointIntegrationTest.java` | 端点 IT | 增 htmx 四条 | 既有断言 |

### 🔴 收件人电话搜索是 PII
现状有独立搜索框；重构后保留，但**搜索词不得进应用日志**（既有脱敏按字段名 `phone` 匹配——请求参数名保持 `phone`，不要改成 `recipientPhone`）。

### 色点语义
🔵 PENDING_SHIPMENT · 🟡 SHIPPED · 🔴 有未处理异常 · ⚪ DELIVERED / COMPLETED / CANCELLED / REFUNDED。

### C1 只读标识
模板 C 通用：顶栏 `.badge-readonly`（Story 2-3b 提供）；本页无任何 form。

### 测试标准

- 每个改造后的 Controller GET 至少四条 MockMvc（`ApiIntegrationTest` 范式，参考 `test/.../admin/shop/web/*EndpointIntegrationTest.java`）：整页 200 / 带 `HX-Request` 返 fragment / 422 行内 err fragment / 403 禁用态 fragment。
- 写端点回归：既有 `Admin*EndpointIntegrationTest` 全绿，端点路径 / 参数 / `@PreAuthorize` 逐条与「现状代码要点」表一致（Story 11-1 diff 会再核一次）。
- L2：stag 部署后按 UI 稿对应帧逐页比对（Story 11-5），切 ID 语言无截断。
- L1 跑法（本地）：flush Redis DB0 → 重建 scratch 库 → `DB_NAME` env。

### Project Structure Notes
- 新 fragment：`fragments/drawer-shop-order.html`、`fragments/rows-shop-orders.html`、`fragments/cards-shop-reconciliation.html`。
- 不新增 Controller、不新增端点；摘要条聚合为 Service 只读方法。

### References
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#B15 / #C1 / 模板 B 通用规格 / 模板 C]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 5 帧 5-8、5-9、5-16]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-9 #AD-11 #AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#5 ① 商城组行 / #5 ② 模板表]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-admin.md D-8 / D-23]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 10.2]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopOrderController.java]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
