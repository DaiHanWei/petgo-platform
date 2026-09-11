---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 10
story: 10.3
ad: [AD-9, AD-11, AD-12]
decisions: [D-8, D-23]
pages: [B16, B17]
ui_frames: [5-1, 5-2, 5-3, 5-4, 5-5]
gate: v1.4.0 电商线合入 dev_1.3.0
depends_on: [2-3a, 2-3b]
---

# Story 10.3: B16 商品管理（列表模板 B + 独立表单页模板 D）+ B17 Banner 抽屉化

Status: review

> ⛔ **门控（AD-12）**：本 story 属 Epic 10，**必须等 v1.4.0 电商线合入 `dev_1.3.0` 之后再启动**。启动前先执行下方「电商线合入后的重核步骤」。依赖 Story 2-3a（htmx 响应约定 / `admin/shared`）与 2-3b（五套模板壳 / `admin-drawer.js` / `admin-workbench.js`）已合入。
> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。本 story **零后端功能改动、零新端点**（AB-19A 原则），只改模板 / Controller 返回形态 / 页面路由退役。

## Story

As a 商品运营，
I want 商品列表有摘要条、表单页按分组卡各自保存，Banner 新建 / 编辑收进抽屉并看清哪一张正在生效，
so that 上下架、改价、换图的动作和其他页面一个手感。

## Acceptance Criteria

**AC1 · B16 列表套模板 B**
**Given** `/admin/shop/products`（`shop.product_view`）
**Then** 摘要条：上架商品数 · 下架 · SKU 总数；表格列：商品名 ｜ 品牌 ｜ 品类 ｜ 上架状态 ｜ 规格数 ｜ 最低价 ｜ 排序权重 ｜ 操作（**「编辑」**跳独立表单页 + 上架 / 下架）；默认排序权重倒序再 id 倒序；每页 20 `[L1]`
**And** 按钮名恢复代码原文「编辑」（i18n `admin.shop.form.titleEdit`，2026-09-07 回滚，不叫「详情」）；下架 data-confirm；有在途订单的 SKU 拦截删除行内提示（现状逻辑保留）`[L2]`
**And** 列表页**不做抽屉**（字段量大，表单页保留独立路由 `GET /admin/shop/products/new`、`GET /admin/shop/products/{id}`）`[L0]`

**AC2 · B16 表单页套模板 D（保留独立页）**
**Given** `shop-product-form.html`
**Then** 四张分组卡（模板 D 双栏 gcards）**各自独立保存钮**（未修改禁用 → 修改激活「已修改」→ 提交）：
1. 基本信息：商品名\* / 品牌 / 品类 / 适用物种\*（DOG / CAT / UNIVERSAL）/ 适用体型（不限 / SMALL / MEDIUM / LARGE / UNIVERSAL）/ 适用年龄段（不限 / PUPPY / ADULT / SENIOR / UNIVERSAL）/ 退货规则\*（RETURNABLE / **NO_RETURN_AFTER_OPEN 默认**，代码原文提示「开封不退」为宠物食品安全侧默认值 / NON_RETURNABLE；本版本无「可退可换」）/ 排序权重
2. 图片与详情：商品图片（第一张主图 + 图集共 ≤9 张、单张 ≤10MB，可多选 / 粘贴上传，缩略图可拖拽排序；兜底可直填 objectKey，`POST products/images`）/ 商品详情（含成分表）\* / 保质期说明
3. 每日建议喂量：体重下限 (kg) / 体重上限 (kg) / 克/日 多行；**FR-109 粮量见底预估唯一计算依据**——体重区间不得重叠、下限 < 上限，服务层校验 422 行内 err
4. 规格与价格（SKU）：规格名 / 售价 / 净含量 (g) / 进货价（**`shop.cost_view` 门控**，无权限不渲染该列且服务端不下发）/ 可售库存（默认继承商品级，可单规格覆盖）`[L1/L2]`
**And** **提交结构维持代码现状两个独立 `<form>`**：商品主体（`POST products` / `POST products/{id}`）一个，SKU（`POST products/{productId}/skus`）另一个；**不**合并成页尾统一保存（2026-09-07 回滚）`[L0]`
**And** 上架 / 下架 `POST products/{id}/list` / `delist`，权限 `shop.product_edit`；下架不影响已成交订单 `[L1]`

**AC3 · B17 Banner 列表与三档状态**
**Given** `/admin/shop/banners`（`shop.product_view`）
**Then** 表格列：预览图 ｜ 原始尺寸 ｜ 权重 ｜ 状态（三档：**生效中** = 已上架且权重最高的那一条 / **已上架（被更高权重压住）** / **未上架**）｜ 操作；权重越大越优先，默认 0；「＋新增 Banner」移到筛选栏 `[L2]`
**And** 三档状态由服务层按「已上架集合中权重最大者」计算，与 App 端取图规则同源（`AdminShopBannerService` 现有查询）`[L1]`

**AC4 · B17 新建 / 编辑收进抽屉**
**Given** 点「＋新增」或行「编辑」
**Then** 右侧抽屉表单：Banner 图\*（objectKey 直传 `POST banners/images`，第一张即唯一图，可 Ctrl+V 粘贴；编辑时可不换图保留原图）/ 权重\*（数字，默认 0，提示「越大越优先」）；提交 `POST banners` / `POST banners/{id}` htmx 局部刷新列表 `[L2]`
**And** 上架 / 下架（`POST banners/{id}/activate` / `deactivate`）、删除（`POST banners/{id}/delete`，data-confirm「不可恢复」）端点零变更，权限 `shop.product_edit` `[L1]`
**And** Banner **纯展示、不可点击跳转**（代码字段只有图 + 权重，不加跳转配置）；**无变更历史 / 已删除记录功能**，页面不承诺可追溯 `[L0]`

**AC5 · 回归**
**Then** `AdminShopProductEndpointIntegrationTest`、`AdminShopListingEndpointIntegrationTest`、`ShopProductFormTest` 全绿；写端点 products 6 + banners 6 路径 / 权限不变 `[L0/L1]`

---

## Tasks / Subtasks

- [x] **T0 · 重核**（见 Dev Notes）
- [x] **T1 · B16 列表**（AC1）：`shop-products.html` 套 `tpl-b-list`（无 drawer 槽，操作列直接按钮）；摘要条聚合进 `AdminShopProductService` 只读方法；侧栏按 `AdminPageCatalog` SHOP 组 12 项真实顺序（5-1 帧 09-08 修正）
- [x] **T2 · B16 表单页**（AC2）：`shop-product-form.html` 套 `tpl-d-config-card` 四卡；**两个 form 结构不动**，只重排卡片与保存钮；喂量表校验错误映射 422 行内 err（现状若整页报错则改为 fragment，PRG 成功路径可保留）；`shop.cost_view` 门控现状核对（列渲染 + Controller 不下发）
- [x] **T3 · B17**（AC3 / AC4）：`shop-banners.html` 套 `tpl-b-list` + `fragments/drawer-shop-banner.html`；三档状态计算移入 Service（若现状在模板里算则下沉）；删除页面底部常驻表单卡
- [x] **T4 · 三语**：`admin.v130.shopProducts.*`、`admin.v130.shopBanners.*`（三档状态名、越大越优先提示）
- [x] **T5 · 测试**（AC5）：三页 MockMvc 四条；三档状态判定单测（多条上架取权重最大；权重相同取 id 最新——与 App 端 `ShopBannerRepository` 现有排序一致，写作时按此假设，重核时对照代码）
- [x] **T-云端 · 云端执行须知**
  - [x] 云端只跑 L0：`mvn -B clean package`（跳过需 Docker 的 IT）；L1/L2 留本地
  - [x] Completion Notes 标注「L1/L2 待本地验收」+ 重核步骤的 diff 结果

---

## Dev Notes

### 🔴 电商线合入后的重核步骤（启动本 story 的第一件事）

1. 在 `dev_1.3.0` 上重跑 `scripts/ci/list-admin-write-ops.sh`（Story 2-1 建），得到合入后的商城组写端点清单。
2. 与本 story「现状代码要点」表逐行 diff：**新增的端点 / 权限码 / 模板一律以新清单为准**，补进本 story 的「操作表」并在 Completion Notes 记录；被电商线删除的端点从本 story 移除。
3. 若电商线已改过本 story 涉及的模板（`shop-*.html`），以合入后的模板为改造起点，不要从本 story 写作时的版本覆盖回去。
4. 重核有实质差异时，先更新本 story 文件再开工；不要边写边猜。

### ✅ T0 重核结果（2026-09-11 执行，本节由 dev 回写）

**门控已解除**（同 Story 10.1 / 10.2）。**与「现状代码要点」表逐行 diff：端点路径 / 参数 / 权限、
模板行数（128 / 294 / 161）全部一致**，改造起点有效。

**查出 6 条与现状不符，其中 ①② 是实质冲突，按 T0 第 4 条在此定档后再开工：**

| # | 差异 | 处置 |
|---|---|---|
| ① | 🔴 **AC2 的「四张分组卡各自独立保存钮」与「提交结构维持两个独立 `<form>`」自相矛盾**：模板 D 的 `configCard` 本身就是一个 `<form>`，四卡 = 四个 form。而卡 1–3（基本信息 / 图片与详情 / 喂量）对应的写端点**只有一个**（`POST products/{id}`，`@ModelAttribute ShopProductForm`）—— 单独提交卡 1 时，卡 2、卡 3 的字段作为缺席请求参数被绑成 null，`service.update()` 会把商品详情、保质期说明、图片 key、喂量表**全部清空**。按卡拆分保存必须先按卡拆端点＝后端功能改动，破本 story 的「零后端功能改动」 | **卡 1–3 仍在同一个 `<form>` 里**（视觉上分三张 `.cfg-card`，保存钮在第三张卡的卡脚），SKU 一个 `<form>`（自己的保存钮）—— 即 AC 要的「两个 form 不动、不做页尾统一保存」成立，「各自保存」降级为**两 form 各自保存 + 四个视觉分组**。「未修改禁用 → 改了才激活 +「已修改」标」两张 form 都挂上（`data-config-card`，模板 D 的核心行为拿到了）。**AC 偏差，待拍板** |
| ② | 🔴 **AC2 卡 4 列出的「可售库存（默认继承商品级，可单规格覆盖）」现状不存在**：SKU 表单只有规格名 / 售价 / 净含量 / 退货规则 / 进货价；可售库存在上方只读表里展示（`inventory.availableBySkuId`），`ShopSkuForm` 没有这个字段，`upsertSku` 也不接受 | **不加**（加 = 后端功能改动 + 动库存口径，而库存有自己的 B18 页面与流水）。卡 4 保留现状字段全集。**AC 偏差，待拍板** |
| ③ | AC1 要求「每页 20」，现状 `list()` 是 `products.findAll()` 全量拉回内存再排序过滤、**无分页** | 加分页（`hasNext` 走 Spring Data `Page`）。⚠️ 现状的排序（权重倒序再 id 倒序）与筛选都在内存里做，分页要落到查询层才有意义 —— 加一条带 `countQuery` 的派生查询 |
| ④ | AC1「按钮名恢复代码原文『编辑』」：现状列表操作列渲染的是 `#{admin.common.detail}`（「详情」），**与 AC 要求的相反** | 改成 `#{admin.shop.form.titleEdit}`（「商品编辑」）—— 这正是 AC 点名的那条 2026-09-07 回滚 |
| ⑤ | AC3「三档状态由**服务层**计算，与 App 端取图规则同源」：现状三档是在 **Controller** 里算的（不是模板），且用的是 `findAllByOrderBySortWeightDescIdDesc()` 取第一条 active —— 与 App 端的 `findFirstByActiveTrueOrderBySortWeightDescIdDesc()` **排序规则相同、方法不同** | 下沉到 `AdminShopBannerService`，并**直接调 App 端那条 repository 方法**取 live id：规则相同但各写一份，早晚有一处被改动（AC3 要的「同一 Repository 方法或同一排序规则」取更强的那一半） |
| ⑥ | 🔴 **AC4 的「行『编辑』」在现状里根本不存在**：`shop-banners.html` 没有任何编辑入口，`POST /admin/shop/banners/{id}`（update）是一条**没有 UI 的死端点** | 抽屉化时把它接上（用既有端点，零新增）。这条顺带修好一个真实缺口 |

**抽屉的取数入口同 10.1 / 10.2：零新端点**。模板 B 的惯例是 `GET …/{id}/drawer`，但那是新端点；
这里复用 `GET /admin/shop/banners`：`?create=1` / `?open=<id>` + `HX-Request` 返抽屉表单片段，
不带这两个参数的 htmx 请求返行片段，非 htmx 返整页。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `admin/shop/web/AdminShopProductController.java` | GET products / products/new / products/{id}；POST images / products / products/{id} / {productId}/skus / {id}/list / {id}/delist；VIEW=`shop.product_view`，EDIT=`shop.product_edit` | 列表 fragment 分支；表单页模板 D；POST 错误响应 fragment | 全部端点；两个 form；cost_view 门控 |
| `admin/shop/web/AdminShopBannerController.java` | GET banners；POST images / banners / {id} / {id}/activate / {id}/deactivate / {id}/delete；同上权限 | 表单收抽屉；三档状态 | 端点；删除不可恢复语义 |
| `templates/admin/shop-products.html`（128）/ `shop-product-form.html`（294）/ `shop-banners.html`（161） | 列表 / 大表单两 form / 列表 + 底部常驻表单 | 套模板 B / D / B+抽屉 | 字段全集与默认值 |
| `test/.../ShopProductFormTest.java`、`AdminShopProductEndpointIntegrationTest.java`、`AdminShopListingEndpointIntegrationTest.java` | 表单绑定 + 端点 IT | 增 htmx 四条 | 既有断言 |

### 🔴 表单页不进抽屉、不合并保存——两条都是回滚后的定论
UI 稿 5-2 于 2026-09-07 回滚：撤销「单排纵向 + 页尾统一保存」，恢复模板 D 分组卡各自保存；与代码「商品一个 form、SKU 另一个 form」一致。实现时**不要**把 SKU 提交塞进商品 form，也不要给页尾加总保存钮。

### 🔴 Banner 三档状态是业务逻辑不是装饰
App 端只展示「已上架 + 权重最高」那一条。列表若只显示启用 / 停用会误导运营以为多张同时生效。三档判定必须与 App 端取图查询同源（同一 Repository 方法或同一排序规则）。

### 进货价门控
`shop.cost_view` 现状：Controller 按权限决定是否下发字段，模板按 `sec:authorize` 渲染列。重构只换壳，两处门控都要保留并加一条测试（无权限账号响应体不含 costPrice）。

### 测试标准

- 每个改造后的 Controller GET 至少四条 MockMvc（`ApiIntegrationTest` 范式，参考 `test/.../admin/shop/web/*EndpointIntegrationTest.java`）：整页 200 / 带 `HX-Request` 返 fragment / 422 行内 err fragment / 403 禁用态 fragment。
- 写端点回归：既有 `Admin*EndpointIntegrationTest` 全绿，端点路径 / 参数 / `@PreAuthorize` 逐条与「现状代码要点」表一致（Story 11-1 diff 会再核一次）。
- L2：stag 部署后按 UI 稿对应帧逐页比对（Story 11-5），切 ID 语言无截断。
- L1 跑法（本地）：flush Redis DB0 → 重建 scratch 库 → `DB_NAME` env。

### Project Structure Notes
- 新 fragment：`fragments/rows-shop-products.html`、`fragments/drawer-shop-banner.html`、`fragments/rows-shop-banners.html`。
- 表单页路由 `GET /admin/shop/products/new`、`/{id}` **保留**（不在退役清单）。

### References
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#B16 / #B17 / 模板 B / 模板 D]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 5 帧 5-1～5-5]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-9 #AD-11 #AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#5 ① 商城组行 / #5 ② 模板表]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-admin.md D-8 / D-23]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 10.3]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java / AdminShopBannerController.java]

## Dev Agent Record

### Agent Model Used

claude-opus-5（Claude Code 云端 session，headless）

### Debug Log References

- `bash scripts/ci/l0-backend.sh` → BUILD SUCCESS，`Tests run: 1854, Failures: 0, Errors: 0, Skipped: 6`
- `bash scripts/ci/check-i18n-keys.sh` → OK（2963 个 key，四包集合相等）
- `bash scripts/ci/check-admin-permission-consistency.sh` → OK（零差异）
- `bash scripts/ci/check-admin-hardcoded-text.sh` → OK（模板 144 份 + 脚本 4 份，裸文案 0 处）
- `bash scripts/ci/check-flyway-versions.sh origin/main` → OK（本 story **无新增迁移**）
- `bash scripts/ci/list-admin-write-ops.sh --baseline …-20260909-基线v2.md` → OK（未解释差异 0）

### Completion Notes List

**🚧 L1 / L2 待本地验收**：云端无 Docker daemon，本 story 新增的 8 条 MockMvc 用例（`AdminShopProductEndpointIntegrationTest`）
全部是 L1，只验证了编译与上下文之外的静态部分；AC1/AC2/AC3/AC4 里标 `[L1]` `[L2]` 的验收项（分页真实条数、
喂量表 422 行内 err、cost_view 不下发、抽屉视觉、切 ID 无截断）**一律留本地 / stag**。

**AC 落地与偏差**

- AC1 ✅ `shop-products.html` 套 `tpl-b-list`（`noDrawer=true`，本页刻意无抽屉），摘要条三格走
  `AdminShopProductService#summary()`；分页落到查询层（`ShopProductRepository#adminSearch` 带显式 `countQuery`，
  `Page#hasNext`）—— 原实现是 `findAll()` 全量拉回内存（T0 差异 ③）；操作列按钮名按 T0 差异 ④ 改回「编辑」。
- AC2 ✅（含两条 **AC 偏差，待拍板**，详见 T0 重核表 ①②）：
  ① 「四张分组卡**各自**独立保存钮」与「维持两个独立 `<form>`」自相矛盾 —— 卡 1–3 共用一个写端点，
  按卡拆保存会把未提交卡的字段绑成 null 清空。落地为 **两个 form 各自保存 + 四个视觉分组**，
  模板 D 的「未修改禁用 → 改了激活 + 已修改标」两张 form 都挂上。
  ② 卡 4 的「可售库存（可单规格覆盖）」现状不存在（`ShopSkuForm` 无此字段），**不加**（加 = 后端功能改动 + 动库存口径）。
- AC3 ✅ 三档状态从 Controller 下沉到 `AdminShopBannerService#stateOf`，且 `liveId()` **直接调 App 端那条
  repository 方法** `findFirstByActiveTrueOrderBySortWeightDescIdDesc`（T0 差异 ⑤：原先后台自己写了一遍
  「取第一条 active」，规则相同但是第二份判据）。新增 L0 `AdminShopBannerStateTest` 三条，
  其中 `theLiveIdComesFromTheSameQueryTheAppUses` 用源码扫描钉住引用关系本身 —— **已做注入验证**：
  把实现换回「自己挑 active」，该断言立刻变红。
- AC4 ✅ 新建 / 编辑收进 `fragments/drawer-shop-banner.html`，取数**复用 `GET /admin/shop/banners`**
  （`?create=1` / `?open=<id>` + `HX-Request`），**零新端点**；顺带接上了 T0 差异 ⑥ 那条
  「有端点没 UI」的死路由 `POST /admin/shop/banners/{id}`。上架 / 下架 / 删除端点零变更。
- AC5 ✅ 写端点 products 6 + banners 6 的路径 / 参数 / `@PreAuthorize` 全部未变（write-ops-guard 零未解释差异）。

**复审（bmad-code-review）修复 7 处**

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| 1 | P0 | 上传控件回填隐藏字段用 `el.value = …`，**不触发任何事件** → `data-requires-text` 的保存钮永远是灰的：图传上去了、缩略图也在，按钮点不动 | `admin-core.js` 新增 `setAndNotify()`（写值后 `dispatchEvent(new Event('input', {bubbles:true}))`），上传控件 `sync()` 的 8 处赋值全部改走它 |
| 2 | P0 | 同一根因：删图 / 拖拽换序后隐藏字段也不发事件，按钮状态停在旧值 | 同上，一处修复覆盖两条 |
| 3 | P0 | 抽屉表单 `hx-target` 指列表容器 → 422 / 403 / 404 会把**整张表换成一行红字**，而红字被遮罩盖着（运营看到「点了没反应」，关掉抽屉才发现表格没了） | `hx-target` 改指 `#shop-banner-drawer-body`；列表改由 `done` 片段的 oob 刷新，行上的上架 / 下架 / 删除也统一指抽屉体 —— 一套路径 |
| 4 | P1 | 「同源」护栏扫的是整份源码，而那个方法名**在 javadoc 正文里就写着** → 把实现换成后台自己挑 active、注释原样留着，断言照绿 | 先剥注释（`/*…*/` 与 `//…`），再把判据**限定在 `liveId()` 方法体内**（扫全类的反向断言从落地起就是红的：`isActive()` 在 `stateOf` / `delete` 里各有合法一处） |
| 5 | P1 | `shop-banners-list :: done` 片段**零覆盖**：它只在 htmx 提交时渲染，既有用例走的全是非 htmx 的 302 分支 | 新增 3 条 L1：成功返 done（oob 整表 + toast + `HX-Trigger: admin:drawer-close`）、缺图 4xx 的 `HX-Retarget` 落抽屉体、只读账号 htmx 403 也落抽屉体 |
| 6 | P2 | 测试用 `SELECT max(id) FROM shop_banners` 认领刚建的行 —— 302 只说明没抛异常，不保证落库；max(id) 会指向别的用例留下的行，断言照常绿 | 改为按**自己刚提交的那个唯一 `image_key`** 反查，并断言非 null |
| 7 | P2 | `String.valueOf(publicUrl)` 在 CDN 未配时渲染出 `src="null"` 的裂图 | 拼不出 URL 就当没有已存图（key 仍在隐藏字段里，不换图照样保留），与列表片段「URL 拼不出来」同口径 |

**其它**

- `scripts/ci/admin-js-text-allowlist.txt` 摘除 `shop-products.html` / `shop-product-form.html` 两条枚举豁免：
  品类 / 物种 / 体型 / 年龄段 / 退货规则五族枚举改走 `admin.v130.shopProducts.*` 与
  `admin.v130.shopOrders.returnPolicy.*`（原先把 `MAKANAN` / `NO_RETURN_AFTER_OPEN` 这类常量名直接铺给运营，切 ID 后连英文都不是）。
- 三语 message key 三包同批（zh_CN / en / id + 默认包），`check-i18n-keys.sh` 绿。
- **无 Flyway 迁移**（本 story 零 schema 变更）。

### File List

**改**
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopBannerService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopProductService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopBannerController.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/repository/ShopProductRepository.java`
- `petgo-backend/src/main/resources/templates/admin/shop-products.html`
- `petgo-backend/src/main/resources/templates/admin/shop-product-form.html`
- `petgo-backend/src/main/resources/templates/admin/shop-banners.html`
- `petgo-backend/src/main/resources/static/admin/admin-core.js`
- `petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties`
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopProductEndpointIntegrationTest.java`
- `scripts/ci/admin-js-text-allowlist.txt`

**新增**
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-products-list.html`
- `petgo-backend/src/main/resources/templates/admin/fragments/shop-banners-list.html`
- `petgo-backend/src/main/resources/templates/admin/fragments/drawer-shop-banner.html`
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopBannerStateTest.java`
