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

Status: ready-for-dev

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

- [ ] **T0 · 重核**（见 Dev Notes）
- [ ] **T1 · B16 列表**（AC1）：`shop-products.html` 套 `tpl-b-list`（无 drawer 槽，操作列直接按钮）；摘要条聚合进 `AdminShopProductService` 只读方法；侧栏按 `AdminPageCatalog` SHOP 组 12 项真实顺序（5-1 帧 09-08 修正）
- [ ] **T2 · B16 表单页**（AC2）：`shop-product-form.html` 套 `tpl-d-config-card` 四卡；**两个 form 结构不动**，只重排卡片与保存钮；喂量表校验错误映射 422 行内 err（现状若整页报错则改为 fragment，PRG 成功路径可保留）；`shop.cost_view` 门控现状核对（列渲染 + Controller 不下发）
- [ ] **T3 · B17**（AC3 / AC4）：`shop-banners.html` 套 `tpl-b-list` + `fragments/drawer-shop-banner.html`；三档状态计算移入 Service（若现状在模板里算则下沉）；删除页面底部常驻表单卡
- [ ] **T4 · 三语**：`admin.v130.shopProducts.*`、`admin.v130.shopBanners.*`（三档状态名、越大越优先提示）
- [ ] **T5 · 测试**（AC5）：三页 MockMvc 四条；三档状态判定单测（多条上架取权重最大；权重相同取 id 最新——与 App 端 `ShopBannerRepository` 现有排序一致，写作时按此假设，重核时对照代码）
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

### Debug Log References

### Completion Notes List

### File List
