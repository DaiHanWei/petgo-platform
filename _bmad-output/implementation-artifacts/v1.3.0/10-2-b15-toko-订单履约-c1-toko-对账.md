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

Status: review

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

- [x] **T0 · 重核**（结果已回写「✅ T0 重核结果」一节，7 条差异定档）
- [x] **T1 · B15 列表**（AC1）：`shop-orders.html` 套 `tpl-b-list`；摘要条 `AdminShopOrderService#summary`（单条 `COUNT FILTER`，只读新方法）；异常挂起标记 = `AdminShopOrderExceptionService#flagged(本页 token)`；每页 20 + 翻页
- [x] **T2 · B15 抽屉**（AC2）：`fragments/drawer-shop-order.html` 迁入五区；`detail()` 在 `HX-Request` 下返抽屉 fragment、非 htmx 抛 404；三个 POST 增 htmx 分支（抽屉原地刷新 + oob 列表行 + toast）
- [x] **T4 · 退役**（AC3）：删 `shop-order-detail.html`；三处指向整页详情的链接改 `?open=`（A7 退货右栏 / A8 异常右栏 / 列表自身）；三个 POST 的 `redirect:` 改回列表
- [x] **T4 · C1**（AC4）：`shop-reconciliation.html` 套 `tpl-c-report`；四卡 fragment；`segmentsBalance()` 不平 → 整卡 `.card-alert` 红框 + 一条说明；期间切换 htmx 只换卡区
- [x] **T5 · 三语**：`admin.v130.shopOrders.*` / `admin.v130.shopRecon.*`，**四包同批**
- [x] **T6 · 测试**（AC5）：改 1 条既有断言（AC3 冲突）+ 新增 6 条 MockMvc；既有 IT 其余原样
- [x] **T-云端 · 云端执行须知**
  - [x] 云端只跑 L0：`bash scripts/ci/l0-backend.sh` 全绿（1851 tests）
  - [x] Completion Notes 标注「L1/L2 待本地验收」+ 重核 diff 结果

---

## Dev Notes

### 🔴 电商线合入后的重核步骤（启动本 story 的第一件事）

1. 在 `dev_1.3.0` 上重跑 `scripts/ci/list-admin-write-ops.sh`（Story 2-1 建），得到合入后的商城组写端点清单。
2. 与本 story「现状代码要点」表逐行 diff：**新增的端点 / 权限码 / 模板一律以新清单为准**，补进本 story 的「操作表」并在 Completion Notes 记录；被电商线删除的端点从本 story 移除。
3. 若电商线已改过本 story 涉及的模板（`shop-*.html`），以合入后的模板为改造起点，不要从本 story 写作时的版本覆盖回去。
4. 重核有实质差异时，先更新本 story 文件再开工；不要边写边猜。

### ✅ T0 重核结果（2026-09-11 执行，本节由 dev 回写）

**门控已解除**（同 Story 10.1 的重核结论）：电商线早已在本分支上，AD-12 约束的是施工顺序不是功能前置。
Story 10.1 已落地，A7 / A8 两页在本分支上已是模板 A 形态。

**与「现状代码要点」表逐行 diff：端点路径 / 参数 / 权限、模板行数（115 / 167 / 79）全部一致**，改造起点有效。

**查出 7 条与现状不符 / story 未写到的，按 T0 第 4 条在此定档后再开工：**

| # | 差异 | 处置 |
|---|---|---|
| ① | 🔴 **现状表漏了第三个权限位 `shop.order_phone_search`**（AB-11A / NFR-11）：按电话搜索是**独立能力** —— 独立权限位、无权限时连输入框都不渲染、服务端再判一次、每次写审计且摘要里只有命中数与查询指纹（**不含号码**）、搜完不回显号码。AC1 只写了「收件人电话独立搜索框（现状保留）」 | 整套原样保留；重构后逐条用测试钉住（这是 PII 攸关，不能只靠肉眼） |
| ② | 🔴 **既有断言与 AC3 冲突**：`AdminShopOrderEndpointIntegrationTest#detailPageShowsShipToAndPaymentSplit` 直接 `GET /admin/shop/orders/{token}` 断言 200 + 含收件人；而 AC3 要求这个地址**非 htmx 一律 404** | 以 AC3 为准：该条改为带 `HX-Request` 取抽屉片段（PII 断言原样保留），**另加一条直达 404**。story「必须保留：既有断言」在这一条上让位于 AC3 |
| ③ | 列表现状 `PAGE_SIZE = 100` 且**没有分页**；AC1 要求「每页 20」 | 加翻页（上一页 / 下一页）。⚠️ **T0 当时定的是「多取一条判 hasNext，不引入 count」——那个方案是错的**：`PageRequest` 的偏移量是 `页码 × 页大小`，把页大小写成 `size+1` 会让第 1 页从第 21 条开始，全局第 21 条永远不出现在任何一页。复审查出后改为带显式 `countQuery` 的 `searchPage(...)` + `Page.hasNext()`（见 Completion Notes P1-4） |
| ④ | AC2 的「四节点时间轴（已下单 → 已发货 → **在途** → 已签收）」：库里**没有「在途」时间戳**，只有 `createdAt` / `shippedAt` / `deliveredAt` / `completedAt` | 「在途」按状态推导（`SHIPPED` 即当前节点），其余三节点用真实时间戳；`completedAt` 与 `deliverySource` / `completionSource` 留在订单卡里（现状有，不能丢） |
| ⑤ | AC2 的发货表单只写「承运商 + 单号常驻输入」，现状**承运成本也是必填**（S-11：不录则毛利看板缺行，自营毛利无法验证） | 三项都必填、都参与「空则禁用」 |
| ⑥ | AC1「有异常挂起的订单行打异常标」的数据源：逐行调 `isCandidate()` 会是 N × （订单行查询 + 库存查询） | ⚠️ **T0 当时定的「一次取候选集再求交」也是错的**：那份候选集是「**最旧的** N 条待发货」（A8 的先进先出口径），而本列表是「**最新** 20 条」—— 待发货一超过 N 两边零交集，第一页永远不出现异常标。复审查出后改为 `flagged(本页 token 集)`：只判这几条，并用状态短路（见 Completion Notes P1-5） |
| ⑦ | AC3 的退役形态与 10.1 同款：**mapping 保留**（`HX-Request` 返抽屉片段、直达 404），所以 `/admin/shop/orders/` 同样**不能**进 `AdminRetiredRoutesStaticTest.RETIRED_PARAM_HREFS`，也进不了 `AdminRetiredRoutesTest`（那条的判据是「不该再有 GET 映射」） | 在两个类的 javadoc 里写明；「直达返 404」由订单页自己的 IT 钉住。写操作白名单里那条预授权删除项改注释说明「没删是对的」 |

**全库指向订单详情页的入口共 3 处**（T3 的 grep 结果）：`shop-orders.html`（本 story 重写）、
`fragments/shop-return-panel.html`、`fragments/shop-exception-panel.html`（后两处是 Story 10.1 刚加的，
当时已在注释里预留「10.2 退役时改 `?open=`」）。另有三个 POST 的 `redirect:/admin/shop/orders/{token}`
也要改成回列表 —— 往已退役的地址 302 等于把旧地址永久续上（D-23）。

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

claude-opus-5（Claude Code，云端 headless session）

### Debug Log References

- L0：`bash scripts/ci/l0-backend.sh` → **BUILD SUCCESS，1851 tests / 0 failures / 6 skipped**
- `check-i18n-keys.sh` → OK（2940 个 key，四包集合相等、无重复）
- `list-admin-write-ops.sh --baseline …基线v2.md` → **未解释差异 0**（三个订单写端点 + 三个 finance GET 零变更）
- `check-admin-permission-consistency.sh` → OK（零差异）
- `check-admin-hardcoded-text.sh` → 裸文案 0 处（豁免 24 → **23**，`shop-orders.html` 的枚举豁免已摘）
- `check-flyway-versions.sh origin/main` → OK（**本 story 零迁移**）

### Completion Notes List

**⚠️ L1 / L2 待本地验收**：云端 headless 无 Docker、无浏览器。**模板在云端从没被渲染过** ——
1851 条 L0 全是不起 Spring 上下文的类。必须回本地跑 `AdminShopOrderEndpointIntegrationTest`
（尤其新加的 6 条 htmx 断言，其中 `htmxShipReturnsDrawerFragmentWithOobRow` 是唯一能抓到「done 片段渲染 500」那类问题的），
再到 stag 验：抽屉开合与 `?open=` 深链、发货钮的「填满才可点」、二次确认弹窗、toast 自动消失、
oob 行原位更新、翻页、对账期间切换与红框、窄屏（Story 11.5 按 UI 稿 5-8 / 5-9 / 5-16 比对）。

**T0 重核的 7 条差异**见上方「✅ T0 重核结果」一节（其中 ①②⑤⑦ 是必须保留 / 必须改断言的硬约束）。

**🔴 复审（对抗性）修掉的 9 处 —— 其中 3 条是 P0，会让本 story 的核心交互整个不可用：**

| # | 问题 | 修法 |
|---|---|---|
| P0-1 | `done()` 的 model 里没有 `exceptionTokens`，而它 `th:replace` 的行片段要读 —— 三个写端点的 htmx 成功响应**必然 500**。更糟的是写入**已经提交**，htmx 不 swap 500 ⇒ 运营看到「什么都没发生」再点一次 ⇒ 按 S-2「一单多包」语义登记**第二个运单号** | 行片段把「有没有异常」改成**形参** `row(r, oob, flagged)`，不再读 model —— 传不进来就编译不过 |
| P0-2 | 抽屉的发货钮写 `disabled data-requires-target`，而处理这组属性的 JS 只在 `admin-workbench.js` 里，**模板 B 页不引那个文件** ⇒ 按钮永久灰着，AC2 的「发货」根本点不了 | 把「钮级 `data-confirm`」与「必填未填 → 提交钮禁用」两块从 `admin-workbench.js` **移进 `admin-core.js`**（它们本来就不是工作台专属的，全后台都引 core）。同时修好 P1-6 |
| P0-3 | oob 列表行用 `<table th:remove="tag">` 包 —— htmx 只在响应**以 `<tr` 开头**时才补表格外壳，本响应第一个标签是 toast div ⇒ 裸 `<tr>` 被 HTML 解析器丢掉，单元格文本被 foster-parent 进抽屉顶部 | 改成**真的 `<table hidden>`**（`drawer-users.html` / `drawer-content.html` 早踩过，写法照抄） |
| P1-4 | 「多取一条判 hasNext」写成 `PageRequest.of(p, s + 1)` —— 偏移量是 `页码 × 页大小`，第 1 页从第 21 条开始 ⇒ **全局第 21 条永远不出现在任何一页**，越翻漏越多 | 加一条带显式 `countQuery` 的 `searchPage(...)`，老实走 `Page.hasNext()`；多一次 count 换正确性 |
| P1-5 | 「异常」标的数据源取的是 A8 候选集（**最旧的 100 条**待发货），而列表是**最新 20 条** ⇒ 待发货一超过 100，两个集合零交集，第一页**永远不出现异常标**；顺带每次开页跑 ~300 次查询 | 新增 `AdminShopOrderExceptionService#flagged(本页 token 集)`，只判本页这几条，并用状态短路（非 `PENDING_SHIPMENT` 一次库存查询都不发） |
| P1-6 | 「标记整单已送达」的 `data-confirm` 挂在 button 上，而 `admin-core.js` 只认 `form[data-confirm]`，钮级处理器在没引的 `admin-workbench.js` 里 ⇒ 二次确认静默失效（这个动作改的是 `deliveredAt`，即**退货窗口起点**） | 随 P0-2 一并修好 |
| P1-7 | 抽屉表单上标的 `data-inline-error` 在模板 B 页是**死属性**（只有 `admin-workbench.js` 读，且还要求祖先有 `[data-workbench]`），注释却宣称它能防止「422 把整个抽屉清空」，方向正好相反 | 去掉这些属性，并把真相写进注释：抽屉体有 id ⇒ 错误由 advice 回填到**抽屉体**，这正是 `tpl-b-list` 给抽屉体加 id 的用意（9.2 复审 M2） |
| P2-8 | toast 直接把 `hx-swap-oob="beforeend:…"` 写在 `.toast` 上 —— `beforeend:` 搬的是**子节点**，落进宿主的只有裸文本：没样式、`armToast` 找不到它 ⇒ **永远不消失**，处置几单就堆几行 | 外面多包一层 div（`drawer-consult-order.html` 有逐字说明）。**顺带把 Story 10.1 的 `shop-return-done` / `shop-exception-done` 两处同款写法一并修了** —— 那两个文件是本轮新写的 |
| P2-9 | `summary()` 的 javadoc 说绑 `TIMESTAMP_WITH_TIMEZONE`、代码绑的是 `TIMESTAMP`；且「今日签收」被下单时间范围二次约束 —— 运营选「今天」时它只数「今天下单且今天签收」的单，而前几天下单今天签收的才是主体 | 注释改回实际类型；SQL 改成只有前两格随筛选联动，「今日签收」按 WIB 当日的 `delivered_at` 独立算，并在注释里写明为什么 |

**其它记录**

1. **旧详情地址的处理与 10.1 同款**：AC2 要「点行 → `GET /admin/shop/orders/{token}` 返抽屉片段」，AC3 要「同一地址非 htmx 404」——
   于是 mapping 保留、不新开 `/drawer`。连带 `AdminRetiredRoutesTest`（判据是「不该再有 GET 映射」）与
   `AdminRetiredRoutesStaticTest.RETIRED_PARAM_HREFS`（会把行上合法的 `data-drawer-url` 判成死链）**都不能收这条路由**，
   两个类的 javadoc 已写明；「直达返 404」由 `AdminShopOrderEndpointIntegrationTest#oldDetailPageIsRetired` 钉住。
2. **改了一条既有断言**（story「必须保留：既有断言」在这一条让位于 AC3）：`detailPageShowsShipToAndPaymentSplit`
   原来直接 GET 详情页断言 200 + 含收件人，现改为带 `HX-Request` 取抽屉片段（**PII 断言原样保留**），
   另加一条直达 404。换的是入口，不是「哪里能看到收件人」这条口径。
3. **按电话搜索的整套 PII 护栏一条没动**：独立权限位 `shop.order_phone_search`、无权限连表单都不渲染、
   服务端二次判定、每次写审计且摘要只有命中数与指纹、搜完不回显号码。
   电话表单刻意**走整页 GET 不挂 `hx-get`**：这是一次对 PII 的访问，要留在浏览器历史里可追溯，
   也避免号码被混进 htmx 的局部刷新参数反复重放；筛选表单与翻页链接都不带 `phone`。
4. **对账四卡里只有 ① 有校验行**（`segmentsBalance()`），另外三张现状就没有可判平的等式 —— 不硬造一个。

**待拍板（本 story 不自行决定）**：无新增。AC 与现状的 7 条差异都已按 T0 定档并在上方记录。

### File List

**后端（Java）**
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopOrderController.java`（改：列表分页 / 摘要 / 异常标 / 抽屉片段 / 3 个 POST 的 htmx 分支）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopOrderService.java`（改：`page()` + `summary()`）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopOrderExceptionService.java`（改：`flagged(tokens)`）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopFinanceController.java`（改：对账 htmx 分支）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/repository/ShopOrderRepository.java`（改：`searchPage`）

**模板 / 静态资源**
- `petgo-backend/src/main/resources/templates/admin/shop-orders.html`（重写：模板 B 壳）
- `petgo-backend/src/main/resources/templates/admin/shop-reconciliation.html`（重写：模板 C 壳）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-orders-list.html`（新增）
- `petgo-backend/src/main/resources/templates/admin/fragments/drawer-shop-order.html`（新增，五区 + done）
- `petgo-backend/src/main/resources/templates/admin/fragments/cards-shop-reconciliation.html`（新增，四卡）
- `petgo-backend/src/main/resources/templates/admin/shop-order-detail.html`（**删除**，AC3 退役）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-return-panel.html` / `shop-exception-panel.html`（改：订单链接改 `?open=`；toast 见 P2-8）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-return-done.html` / `shop-exception-done.html`（改：toast 多包一层）
- `petgo-backend/src/main/resources/static/admin/admin-core.js`（改：接收从 workbench 移来的两块通用行为）
- `petgo-backend/src/main/resources/static/admin/admin-workbench.js`（改：移出那两块）
- `petgo-backend/src/main/resources/static/admin/admin.css`（改：`.so-*` / `.recon-card` / `.card-alert`）

**i18n（四包同批）**：`messages_zh_CN` / `messages_en` / `messages_id` / `messages`

**测试**
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopOrderEndpointIntegrationTest.java`（改 1 条既有断言 + 新增 6 条）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminRetiredRoutesStaticTest.java`（改：收 `shop-order-detail`）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminRetiredRoutesTest.java`（改：javadoc 说明为何不收这条路由）

**CI**
- `scripts/ci/admin-write-ops-allowlist.txt`（改注释：该条预授权删除项「没删是对的」）
- `scripts/ci/admin-js-text-allowlist.txt`（摘除 `shop-orders.html` 的枚举豁免）
