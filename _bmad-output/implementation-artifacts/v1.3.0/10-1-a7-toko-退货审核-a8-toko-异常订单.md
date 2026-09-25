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

Status: review

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

- [x] **T0 · 重核**（见 Dev Notes 重核步骤 → 结果已回写「✅ T0 重核结果」一节）
- [x] **T1 · A7 页面**（AC1 / AC2）
  - [x] `shop-returns.html` 改套 `tpl-a-workbench`：tabs 槽 = 五页签；filters 槽 = 类型 / 整单退；queue 槽 = `fragments/shop-return-queue.html`；detail 槽 = `fragments/shop-return-panel.html`（`shop-return-detail.html` 五区整体迁入，含试算卡 8 行、私有桶凭证签名 URL、公开桶质检照片上传、处置方式必选）
  - [x] `AdminReturnController.queue()` 增 `HX-Request` 分支返行片段；`GET /admin/shop/returns/{token}` 在 `HX-Request` 下返右栏 fragment、非 htmx 抛 404（复用同一 mapping，**零新端点**）
  - [x] 5 个 POST 在 `HX-Request` 下返 200 + done 片段（`data-next-id` + oob 删行 + 五页签计数）；路径 / `@PreAuthorize` 不动
  - [x] 「查开封判例」`th:href="@{/admin/shop/return-precedents}"` target=_blank
- [x] **T2 · A8 页面**（AC3，含三处 AC 偏差，见 T0 重核 ③④⑤）
  - [x] `shop-order-exceptions.html` 套模板 A（单页签）；**每行一个「取消这一行」+ 数量**（与 `cancel-line` 端点 1:1，不做多选批量）
  - [x] 三个 POST 改 fragment 响应；`cancel`/`cancel-line` 的 data-confirm 走 `admin.v130.shopExceptions.confirm.*`；三处 reason 均为必填输入框
- [x] **T3 · 导航**（AC4）：两页在 `AdminPageCatalog` 已归 SHOP 组（本次未动）；`NavBadgeService.QUEUES` 不含这两页 ⇒ 不进待办中心角标 ✅；顺带修好异常订单页的 `active` 值（原为 `shopOrders`，侧栏高亮到了「订单管理」）
- [x] **T4 · 退役**（AC5）：删 `shop-return-detail.html`；全库无残留 `th:href` 指向 `shop/returns/{token}`
- [x] **T5 · 三语**：新增 key 前缀 `admin.v130.shopReturns.*` / `admin.v130.shopExceptions.*`，**四包同批**（zh_CN / en / id / 默认）
- [x] **T6 · 测试**（AC6）：A7 六条 + A8 五条 MockMvc（整页 / htmx 片段 / 422 / 403 / oob）；新增 L0 `AdminReturnTabsTest`；既有 IT 全部保留
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

**门控已解除**：电商线（v1.4.0）**早已在本分支上**——145 个 shop Java 文件、20 支 shop 迁移、
9 个 `admin/shop/web/*Controller` 全在；v1.4.0 sprint-status 62 条全部 done/review。
AD-12 原文是「排在电商线合入 `dev_1.3.0` **之后**」，约束的是**施工顺序**不是功能前置。
（此前几轮把 story 头上的 ⛔ 当成阻塞门而整个跳过 Epic 10，是误判。）

**与「现状代码要点」表逐行 diff：端点路径 / 参数 / 权限、枚举值、模板行数（88 / 291 / 87）全部一致** ——
电商线自本 story 写作后没有改过这三个模板，AC 的改造起点有效（T0 第 3 条满足）。

**但查出 6 条 story 与现状不符，其中 ③④⑤ 是实质冲突，按 T0 第 4 条在此定档后再开工：**

| # | 差异 | 处置 |
|---|---|---|
| ① | AC6 的端点计数错：写「returns 8 + precedents 1 + order-exceptions 3 = 12」，实际 **returns 7** + 1 + 3 = **11**（`AdminReturnController` 里 8 个 `@PostMapping` 中有一个就是 precedents，被重复计了一次） | 按 11 核；AC6 文字以此为准 |
| ② | AC6 点名的 `AdminShopOrderExceptionIntegrationTest` 在 `admin/shop/**service**/` 包，不在「测试标准」说的 `web/*EndpointIntegrationTest` 范式下 | 存在即可，不搬家 |
| ③ | 🔴 **AC3「页签待处理｜已处理」没有数据源**：`exceptionCandidates()` 返回的是「已付款待发货 **且** 库存不足」的**实时计算候选集**，不是带状态字段的工单表。处置完订单就离开候选集 —— **不存在「已处理」这个可查询的集合**。造它需要新查询甚至新持久化 = 后端功能改动，破本 story 的「零后端功能改动」 | **A8 只做一个页签（待处理）**，页内常驻说明「本页是实时候选清单，处置完即离开；处置记录在操作审计」。**AC 偏差，待拍板** |
| ④ | 🔴 **AC3「行级勾选供部分取消」与端点签名冲突**：`POST …/cancel-line` 的参数是 `lineId` + `qty` + `reason`，**一次只能取消一行**。多选批量要么改端点（破零变更），要么前端串行发 N 次（失败一半没有回滚语义） | 改为**每行一个「取消这一行」+ 数量输入**，与端点 1:1；已取消行置灰。**AC 偏差，待拍板** |
| ⑤ | 🔴 **AC3 漏了必填的 reason**：`cancel` / `cancel-line` / `continue` 三个端点的 `reason` 都是 `@RequestParam String`（必填），服务层 `requireReason()` 空值抛 422；而 AC3 只提了 data-confirm、`continue` 还写着「无确认」 | 操作区三处都给**原因输入框**（`continue` 也要）；空值前置禁用。按代码走 |
| ⑥ | AC1 五页签 vs 服务签名：`queue(ReturnStatus, limit)` 只接受**单个**状态，而「待退款」对 2 个状态、「已完结·已驳回」对 4 个 | 加一个接受状态集合 + 分页的**只读派生查询**（Spring Data，无功能改动） |

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
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-9 #AD-11 #AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#5 ① 商城组行 2026-09-08 修订]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 10.1]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminReturnController.java]

## Dev Agent Record

### Agent Model Used

claude-opus-5（Claude Code，云端 headless session）

### Debug Log References

- L0：`bash scripts/ci/l0-backend.sh` → **BUILD SUCCESS，1851 tests / 0 failures / 6 skipped**
- `bash scripts/ci/check-i18n-keys.sh` → OK（2917 个 key，四包集合相等、无重复）
- `bash scripts/ci/list-admin-write-ops.sh --baseline 后台写操作清单-20260909-基线v2.md` → **未解释差异 0**（本 story 零端点变更）
- `bash scripts/ci/check-admin-permission-consistency.sh` → OK（零差异，74 码 / 42 页）
- `bash scripts/ci/check-admin-hardcoded-text.sh` → 裸文案 0 处（白名单豁免 24 处；`shop-returns.html` 的枚举豁免已摘除）
- `bash scripts/ci/check-flyway-versions.sh origin/main` → OK（**本 story 零迁移**）

### Completion Notes List

**⚠️ L1 / L2 待本地验收**：云端 headless 无 Docker daemon、无浏览器。下列必须回本地 / stag 验：
① 五页签切换与计数、滚动加载第 2 页；② 处置后 toast + 自动选中下一条（`data-next-id`）；
③ 质检照片上传（公开桶）与凭证灯箱（私有桶签名 URL）；④ 窄屏两屏切换；⑤ 与 UI 稿 5-10 / 5-11 比对（Story 11.5）。
**Thymeleaf 模板在云端从未被渲染过** —— 上面 1850 条 L0 全是不起 Spring 上下文的类，
模板层的表达式错误只能在 L1 才暴露，本地跑 `AdminReturnEndpointIntegrationTest` /
`AdminShopOrderExceptionIntegrationTest` 是第一道真正的验证。

**T0 重核结论**：门控（AD-12）不是功能前置而是施工顺序，电商线早已在本分支上（145 个 shop Java 文件 /
20 支 shop 迁移 / 9 个 admin shop Controller），故 Epic 10 可以开工。详见上方「✅ T0 重核结果」一节。

**🔴 三处 AC 偏差（按 T0 第 4 条先定档后开工，待拍板）**

| # | AC 原文 | 实际做法 | 为什么 |
|---|---|---|---|
| ③ | AC3「页签待处理 ｜ 已处理」 | **A8 只做「待处理」一个页签** + 页内常驻说明 | `exceptionCandidates()` 是**实时计算**的候选集（已付款待发货 ∧ 库存不足），处置完即离开集合——库里不存在「已处理」这个可查询的集合。造它要新查询甚至新持久化，破本 story「零后端功能改动」。处置记录在操作审计里 |
| ④ | AC3「行级勾选供部分取消」 | **每行一个「取消这一行」+ 数量输入**，与端点 1:1 | `POST …/cancel-line` 的签名是 `(lineId, qty, reason)`，一次只能取消一行。多选批量要么改端点（破零变更），要么前端串行发 N 次（失败一半没有回滚语义） |
| ⑤ | AC3 只提 data-confirm，`continue` 写「无确认」 | **三个端点都给必填原因输入框**（含 `continue`） | `cancel` / `cancel-line` / `continue` 的 `reason` 都是 `@RequestParam String`，服务层 `requireReason()` 空值抛 422。AC 与代码不符，按代码走 |

**其它需要记录的判断**

1. **AC6 的端点计数错**：原文「returns 8 + precedents 1 + order-exceptions 3 = 12」——
   `AdminReturnController` 的 8 个 `@PostMapping` 里已经包含 precedents 那一个，实际是 **returns 7 + precedents 1 + order-exceptions 3 = 11**。写操作清单 diff 以 11 为准，零变更。
2. **旧详情地址的处理**：T1 明确「不允许新开 `/{token}/detail` 端点」，所以右栏片段**复用
   `GET /admin/shop/returns/{token}` 这同一条 mapping**（`HX-Request` 返片段、直达抛 404，D-23 不做跳转）。
   连带影响两处护栏：`AdminRetiredRoutesTest`（判据是「不该再有 GET 映射」）与
   `AdminRetiredRoutesStaticTest.RETIRED_PARAM_HREFS`（会把队列行那句合法的 `hx-get` 判成死链）
   **都不能收这条路由**，已在两个类的 javadoc 里写明理由；「直达返 404」改由
   `AdminReturnEndpointIntegrationTest` 钉住。写操作白名单里那条预授权删除项保留并改注释说明「没删是对的」。
3. **五步进度条的操作人只有「批准」一步有**：实体上只有 `reviewed_by` 一列，寄回 / 质检 / 退款三步的操作人
   只存在于审计日志。补列 = 后端功能改动，本 story 不做。AC1「完成步带操作人 + 时间」在其余四步退化为只有时间。
4. **页签计数跟着筛选走**（AC4「页签计数与页内同源」）：处置表单里带两个隐藏字段 `type` / `full`，
   把当前筛选原样带回去，`done` 片段的 oob 计数才与左栏一个口径。这两个是**只影响展示的可选参数**，
   路径与 `@PreAuthorize` 未变，写操作清单零差异。
5. **A8 处置后 oob 换的是整条左栏队列**，不是删一行：候选集实时计算，部分取消可能让这一单不再缺货（该消失）
   也可能仍缺货（该留下），同一次库存变动还可能让别的单子进出候选集。
6. **`exceptionCandidates` 改成时间升序**（原为降序）：AC3 要求先进先出。副作用是窗口取的是**最旧的 100 条**
   待发货单——生产语义正确，但让「新造的单一定在候选里」这类测试断言变脆，故 L1 断言改问 `isCandidate()`。
7. **片段命名**未用 story 里写的 `row-shop-return.html` / `detail-shop-return.html`，
   改用 `shop-return-{queue,panel,done}.html`：Project Structure Notes 说的是「**资源名前缀**（AD-11 命名规则）」，
   而 `row-` 前缀恰恰不是资源名前缀；仓库里既有的五个模板 A 工作台一律是
   `<资源名>-{queue,panel,done}.html`（refund / anomaly / support / tickets / review），本页跟既有惯例走。
8. **「必填未填 → 按钮禁用」的页内脚本搬进 `admin-workbench.js`**：A6 原来把它写在 `refunds.html` 里，
   A7 需要同一套逻辑。抄第二份必然分叉，故提取共用并删掉 `refunds.html` 里那段（该页本来就加载这个 js）。
9. **`AdminTemplateStructureTest#everyFetchUploadPageDeclaresCsrfMeta` 扩了一条口径**：
   上传控件第一次进了 htmx 片段（片段没有自己的 `<head>`，CSRF meta 来自宿主页）。
   没有豁免 `fragments/` 目录（那等于让这条守门在片段上作废），改为让片段写一行
   `upload-host: xxx.html` 注释指名宿主，测试去查那张页面——宿主改名或宿主漏 meta 立刻红。
10. **顺手修的两个既有缺陷**：异常订单页的 `active` 是 `shopOrders`（侧栏高亮错到「订单管理」，
    目录里它的 `activeKey` 一直是 `shopOrderExceptions`）；`ReturnRequest` 补了
    `getReviewedBy()` / `getReviewedAt()` 两个只读 getter（字段自 5.3 就有，从未被读出来过）。

**复审（对抗性）修掉的 6 处**

| # | 问题 | 修法 |
|---|---|---|
| 1 | `AdminReturnTabsTest` 的深链断言是**恒真**的：`Tab.of(s).statuses().contains(s)` 由实现的构造方式保证为真，把 `REFUND_FAILED` 从「待退款」挪到「已完结」照样绿（真实后果：退款失败的单子只出现在终态队列，没人找得到） | 改成**独立写死的 AC1 期望表**（status → tab 九行），实现与需求两处对账 |
| 2 | `steps()` 把 `REJECTED` / `WITHDRAWN` 画成「批准 ✅ 某某 某时」——驳回与撤回都会写 `reviewed_by/at`，读起来就是「已批准，等寄回」 | 新增 `rejected` / `skip` 两种步态：按 `inspectionPassed` 区分「审核驳回」与「质检不通过」，`reviewedAt` 为空即从没审过。模板加红色「已驳回」标 + `.is-rejected` 样式 |
| 3 | 新的 `upload-host:` 机制只查「那个文件名存在且有 meta」——随手填一个有 meta 的页面即可蒙混 | 追加要求：**有 Controller 同时返回片段视图名与宿主页视图名**（编译期存在的事实）。另删掉 `viaHost.isNotEmpty()`（合法地没人用时会把正确状态判红） |
| 4 | `lineStocks` 的 javadoc 写「直接调 `hasInsufficientStock`」，代码其实抄了第二份判据 | 反过来：`hasInsufficientStock` = `lineStocks(...).anyMatch(insufficient)`，判据只剩一份 |
| 5 | A7 / A8 右栏都把订单号渲染成纯文本，**丢了去订单页的入口**（退役前一直有；审核/异常处置要看收件地址与物流） | 两处都恢复成新标签链接，并注明 Story 10.2 退役时改 `?open=` |
| 6 | `cancel-line` 的 data-confirm 没复述金额（AC3 要求「复述行数与金额」，T0 ④ 只改了勾选方式） | 文案加第三个参数：按单价 × 剩余件数的估算金额 |

另修一处 PLAUSIBLE：五个处置端点原本按端点**硬编码**「来源页签」，但 `ReturnRequest.reject` 允许
`PENDING_REVIEW` **或 `REFUND_FAILED`** —— 在「待退款」页签驳回会把运营弹到「待审核」队列。
改为处置**前**按该单当前状态算（`tabOf(token)`）。

**顺带补了一张网（本 story 自己踩到的）**：`AdminMessagesExternalizedTest
#everyMessageKeyReferencedByAdminTemplatesExistsInAllLocales`。
此前**没有任何检查**能发现「模板里 `#{...}` 引用了一个谁都没定义的 key」——
`check-i18n-keys.sh` 只比对四包之间是否相等（四包一致地都没有它 ⇒ 绿），
`everyReferencedCodeExistsInAllLocales` 只扫 java 源码的 `admin.flash.*` / `admin.err.*`。
运行期 Thymeleaf 原样吐 `??key_zh_CN??` 到页面上，不抛异常、不进日志。
本 story 开发时就真漏了一个（`admin.v130.shopReturns.step.rejected`，L0 1850 条全绿）。
新测试已按「注入假缺陷」验过会红。

**待拍板（本 story 不自行决定）**：上表 ③④⑤ 三处 AC 偏差；是否为「已处理」视图补一张异常处置工单表（那是新功能，应另开 story）。

### File List

**后端（Java）**
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminReturnController.java`（改：工作台 GET / 右栏片段 / 5 个 POST 的 htmx 分支）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminReturnService.java`（改：`Tab` 五页签 + 分页队列 + 计数 + `nextToken` + 五步 `steps()`）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopOrderExceptionController.java`（改：工作台 GET / `?open=` 右栏片段 / 3 个 POST 的 htmx 分支）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopOrderExceptionService.java`（改：候选集改升序 + `isCandidate()` + `lineStocks()`）
- `petgo-backend/src/main/java/com/tailtopia/shop/returns/repository/ReturnRequestRepository.java`（改：挂 `JpaSpecificationExecutor`）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/repository/ShopOrderRepository.java`（改：加升序派生查询）
- `petgo-backend/src/main/java/com/tailtopia/shop/returns/domain/ReturnRequest.java`（改：补两个只读 getter）

**模板 / 静态资源**
- `petgo-backend/src/main/resources/templates/admin/shop-returns.html`（重写：模板 A 壳）
- `petgo-backend/src/main/resources/templates/admin/shop-order-exceptions.html`（重写：模板 A 壳）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-return-queue.html`（新增）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-return-panel.html`（新增，五区）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-return-done.html`（新增）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-exception-queue.html`（新增）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-exception-panel.html`（新增，三区）
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-exception-done.html`（新增）
- `petgo-backend/src/main/resources/templates/admin/shop-return-detail.html`（**删除**，AC5 退役）
- `petgo-backend/src/main/resources/templates/admin/refunds.html`（改：页内脚本提取到 admin-workbench.js）
- `petgo-backend/src/main/resources/static/admin/admin-workbench.js`（改：`data-requires-form` 通用化）
- `petgo-backend/src/main/resources/static/admin/admin.css`（改：`.rf-flow--5` / `.sr-*` / `.se-*`）

**i18n（四包同批）**
- `petgo-backend/src/main/resources/i18n/messages_zh_CN.properties` / `messages_en.properties` / `messages_id.properties` / `messages.properties`

**测试**
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminReturnTabsTest.java`（新增，L0）
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminReturnEndpointIntegrationTest.java`（改：+6 条 L1）
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopOrderExceptionIntegrationTest.java`（改：+5 条 L1）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminTemplateStructureTest.java`（改：CSRF meta 片段宿主机制）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminMessagesExternalizedTest.java`（改：新增「模板 `#{...}` 引用的 key 四包都要有」）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminRetiredRoutesStaticTest.java`（改：收 `shop-return-detail`）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminRetiredRoutesTest.java`（改：javadoc 说明为何不收这条路由）

**CI**
- `scripts/ci/admin-write-ops-allowlist.txt`（改注释：该条预授权删除项「没删是对的」）
- `scripts/ci/admin-js-text-allowlist.txt`（摘除 `shop-returns.html` 的枚举豁免）
