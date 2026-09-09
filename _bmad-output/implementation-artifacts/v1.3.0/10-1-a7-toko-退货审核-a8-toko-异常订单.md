---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 10
story: 10.1
ad: [AD-9, AD-11, AD-12]
decisions: [D-8, D-14, D-23]
pages: [A7, A8]
ui_frames: [5-10, 5-11]
gate: v1.4.0 电商线合入 dev_1.3.0
depends_on: [2-3a, 2-3b]
---

# Story 10.1: A7 Toko 退货审核 + A8 Toko 异常订单（模板 A，导航在商城组）

Status: ready-for-dev

> ⛔ **门控（AD-12）**：本 story 属 Epic 10，**必须等 v1.4.0 电商线合入 `dev_1.3.0` 之后再启动**。启动前先执行下方「电商线合入后的重核步骤」。依赖 Story 2-3a（htmx 响应约定 / `admin/shared`）与 2-3b（五套模板壳 / `admin-drawer.js` / `admin-workbench.js`）已合入。
> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。本 story **零后端功能改动、零新端点**（AB-19A 原则），只改模板 / Controller 返回形态 / 页面路由退役。

## Story

As a 运营 / 财务，
I want Toko 退货申请按五步状态流在一个双栏工作台里处理，异常订单在另一个工作台里部分取消 / 整单取消 / 继续履约，处置完自动到下一条，
so that 售后与异常不再靠列表页和独立详情页来回跳。

## Acceptance Criteria

**AC1 · A7 退货审核套模板 A**
**Given** `/admin/shop/returns`
**When** 打开
**Then** 页签 = 待审核（PENDING_REVIEW）｜ 待寄回（AWAIT_SHIPBACK）｜ 待质检（INSPECTING）｜ 待退款（REFUNDING / REFUND_FAILED）｜ 已完结·已驳回（REFUNDED / CLOSED / REJECTED / WITHDRAWN），各带计数；筛选 = 退货类型（QUALITY_ISSUE / NON_QUALITY_ISSUE / REFUSED_ON_DELIVERY / CANCEL_BEFORE_SHIPMENT）· 整单退 `[L1]`
**And** 左栏行：第一行 = 申请编号 + 退货类型标签 + 状态色点；第二行 = 订单号 + 行数（整单退标记）+ 申请时间；默认时间**升序**（先进先出）；每页 20 滚动加载 `[L2]`
**And** 右栏五区：① 五步进度条（申请 → 批准 → 寄回 → 质检 → 退款，当前步高亮，完成步带操作人 + 时间）② 退货行表（商品 / 数量 / 金额，整单退表头标注）③ 退款试算卡 **8 行现状字段原样**（商品金额 / 去程运费退回 / PawCoin 段 / 现金段 / 平台责任补偿溢价 / 转 PawCoin 激励溢价 / 回程运费返还 / **总退回（含补偿）加粗**）④ 用户说明 + 凭证图（私有桶签短 TTL URL，点开大图）⑤ 当前步操作区 + 「查开封判例」链接（跳 B19，新开标签） `[L2]`

**AC2 · A7 操作表（端点零变更）**
| 步 | 按钮 | 端点 | 权限 | 确认 |
|---|---|---|---|---|
| 审核 | 批准退货 / 驳回 | `POST returns/{token}/approve` / `reject` | `refund.approve` | 驳回原因必填（常驻文本框，空则禁用） |
| 寄回 | 登记寄回 | `POST returns/{token}/shipback` | `refund.approve` | 无 |
| 质检 | 质检通过并入库 / 质检不通过 | `POST returns/{token}/inspect-pass` / `inspect-fail`；质检照片 `POST returns/images` | `refund.approve` | 通过：**处置方式必选**（`RejectDisposal` 现有枚举，含 RETURN_TO_USER），未选禁用；不通过：原因必填 |
| 退款 | 执行退款 | `POST returns/{token}/refund` | `refund.payout` | data-confirm 复述**总退回金额** |
**Then** 仅登录者权限匹配的段渲染按钮，其余段只读并注明所缺权限名 `[L1]`
**And** 处置走 htmx：200 + 右栏 fragment + oob 左栏行与页签计数；成功 toast 2s 自动选中下一条（`data-next-id`）；失败 422 行内 err `[L1]`

**AC3 · A8 异常订单套模板 A**
**Given** `/admin/shop/order-exceptions`
**When** 打开
**Then** 页签待处理 ｜ 已处理；左栏行 = 订单号 + 状态点 / 异常摘要 + 商品数 + 时间，默认时间升序 `[L2]`
**And** 右栏：① 异常原因说明卡 ② 订单行表（商品名 / 规格 / 数量 / 已取消标记，**行级勾选**供部分取消，已部分取消行置灰不可勾）③ 操作区 `[L2]`
**And** 操作：部分取消（`POST order-exceptions/{token}/cancel-line`，勾选 ≥1 行才可用，data-confirm 复述行数与金额）· 整单取消并退款（`POST …/cancel`，data-confirm 复述订单金额）· 联系用户后继续（`POST …/continue`，无确认，恢复履约）——权限 `shop.order_fulfill`；整单取消后进已处理页签终态 `[L1]`

**AC4 · 导航与计数归属**
**Then** 两页导航在 **商城组**（D-8 / 2026-09-08 修订），**不进待办中心角标**；页签计数与页内同源 `[L1]`

**AC5 · 退役**
**Then** `shop-return-detail.html` 与 `GET /admin/shop/returns/{token}` 页面路由退役并入右栏（旧地址 404，不做跳转 D-23）；A8 现状无独立详情页，无退役项 `[L1]`

**AC6 · 回归**
**Then** `AdminReturnEndpointIntegrationTest`、`AdminShopOrderExceptionIntegrationTest` 全绿；写端点 12 个（returns 8 + precedents 1 归 10-4 + order-exceptions 3）路径 / 权限不变 `[L0/L1]`

---

## Tasks / Subtasks

- [ ] **T0 · 重核**（见 Dev Notes 重核步骤）
- [ ] **T1 · A7 页面**（AC1 / AC2）
  - [ ] `shop-returns.html` 改套 `tpl-a-workbench`：tabs 槽 = 五页签（状态映射见 AC1）；filters 槽 = 类型 / 整单退；queue 槽 = 行 fragment `fragments/row-shop-return.html`；detail 槽 = 右栏 fragment `fragments/detail-shop-return.html`（把 `shop-return-detail.html` 五区内容迁入，含试算卡 8 行、凭证签名 URL 逻辑复用 Controller 现有 `detail()` 数据组装）
  - [ ] `AdminReturnController.queue()` 增加 `HX-Request` 分支返 fragment；新增 `GET /admin/shop/returns/{token}/detail` **不允许**（新端点）——改为 `GET /admin/shop/returns/{token}` 在 `HX-Request` 下返右栏 fragment、非 htmx 请求 404（页面路由退役但 fragment 路径复用同一 mapping）
  - [ ] 5 个 POST 在 `HX-Request` 下改返 200 + 右栏 fragment + `hx-swap-oob` 左栏行/计数（PRG 退役）；参数与 `@PreAuthorize` 不动
  - [ ] 「查开封判例」`th:href="@{/admin/shop/return-precedents}"` target=_blank
- [ ] **T2 · A8 页面**（AC3）
  - [ ] `shop-order-exceptions.html` 套模板 A；行级勾选 + 部分取消按钮禁用逻辑（`admin-workbench.js` 通用「选中 ≥1 才激活」）
  - [ ] 三个 POST 改 fragment 响应；`cancel-line` 复述行数与金额的 data-confirm 文案走 `admin.v130.shopExceptions.confirm.cancelLine`
- [ ] **T3 · 导航**（AC4）：`AdminPageCatalog` 中两页归 SHOP 组；确认 `badges` 聚合查询不含这两页
- [ ] **T4 · 退役**（AC5）：删 `shop-return-detail.html`；grep 全库 `shop/returns/{token}` 的 `th:href` 改为 `?open=<token>`
- [ ] **T5 · 三语**：新增 key 前缀 `admin.v130.shopReturns.*` / `admin.v130.shopExceptions.*`，三包同批
- [ ] **T6 · 测试**（AC6）：两页各四条 MockMvc；既有 IT 回归
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
| `admin/shop/web/AdminReturnController.java` | GET `returns`（?status 过滤，`statuses` 全集）、GET `returns/{token}`（凭证私有桶签 URL、逗号拼 key 拆分——空串须拆空列表）、POST approve / reject / shipback / images / inspect-pass / inspect-fail / refund；VIEW=`refund.view`，APPROVE=`refund.approve`，PAYOUT=`refund.payout` | GET 返 fragment 分支；POST 响应形态；页面路由退役 | 全部端点路径 / 参数 / 权限；私有桶签名与空 key 处理 |
| `admin/shop/web/AdminShopOrderExceptionController.java` | GET `order-exceptions`；POST cancel / cancel-line / continue；VIEW=`shop.order_view`，HANDLE=`shop.order_fulfill` | 同上 | 同上 |
| `shop/returns/domain/ReturnStatus` | PENDING_REVIEW REJECTED AWAIT_SHIPBACK INSPECTING REFUNDING REFUNDED REFUND_FAILED CLOSED WITHDRAWN | 页签映射（AC1） | 状态机不动 |
| `shop/returns/domain/ReturnType` / `RejectDisposal` | 四种退货类型；处置方式含 RETURN_TO_USER | 筛选与质检处置下拉数据源 | — |
| `templates/admin/shop-returns.html`（88 行）/ `shop-return-detail.html`（291 行）/ `shop-order-exceptions.html`（87 行） | 列表 + 独立详情 PRG | 套模板 A，详情迁入右栏 | 试算卡 8 行字段、质检处置必选、凭证展示 |
| `test/.../admin/shop/web/AdminReturnEndpointIntegrationTest.java`、`AdminShopOrderExceptionIntegrationTest.java` | 端点级 IT | 增 htmx 四条 | 全部既有断言 |

### 🔴 权限码现状不规整（FR-21A-7，如实记录不擅自改）
退货审核与 B19 开封判例、以及 A6 退款管理三者共用 `refund.view / refund.approve / refund.payout`，无法只放开其中一个。本 story 不拆码；矩阵注脚由 Story 1.5 负责。

### 🔴 状态色点语义
🔵 PENDING_REVIEW / INSPECTING · 🟡 AWAIT_SHIPBACK / REFUNDING（等外部）· 🔴 REFUND_FAILED · ⚪ REFUNDED / CLOSED / REJECTED / WITHDRAWN。终态只在「已完结·已驳回」页签出现。

### 与 A6 的边界
A6（Story 2-8）是虚拟商品退款三段流；A7 是实物退货五步流。两者共用 `refund.*` 码但页面、表、服务完全独立，不要互相复用 fragment。

### 测试标准

- 每个改造后的 Controller GET 至少四条 MockMvc（`ApiIntegrationTest` 范式，参考 `test/.../admin/shop/web/*EndpointIntegrationTest.java`）：整页 200 / 带 `HX-Request` 返 fragment / 422 行内 err fragment / 403 禁用态 fragment。
- 写端点回归：既有 `Admin*EndpointIntegrationTest` 全绿，端点路径 / 参数 / `@PreAuthorize` 逐条与「现状代码要点」表一致（Story 11-1 diff 会再核一次）。
- L2：stag 部署后按 UI 稿对应帧逐页比对（Story 11-5），切 ID 语言无截断。
- L1 跑法（本地）：flush Redis DB0 → 重建 scratch 库 → `DB_NAME` env。

### 云端执行须知
云端 headless 只到 L0；模板 A 的自动下一条、窄屏切换属 L2，留本地 stag。

### Project Structure Notes
- 改动落既有 `admin/shop/web`、`templates/admin/shop-*.html`、`fragments/`；不新建模块。
- 新增 fragment 命名 `fragments/row-shop-return.html`、`detail-shop-return.html`、`row-shop-exception.html`、`detail-shop-exception.html`（资源名前缀，AD-11 命名规则）。

### References
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#A7 / #A8 / 模板 A 通用规格]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 5 帧 5-10、5-11]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md#AD-9 #AD-11 #AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#5 ① 商城组行 2026-09-08 修订]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0.md#Story 10.1]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminReturnController.java]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
