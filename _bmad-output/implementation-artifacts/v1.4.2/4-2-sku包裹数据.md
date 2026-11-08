# Story 4.2：SKU 包裹数据

Status: review

> V1.4.2 · 主题 shop-admin · 分支 `feat/1.4.2-shop-admin`（电商线合入 `dev_1.4.2` 后切出）。
> 口径优先级：`决策日志-shop-admin.md` > `决策日志-shop.md` > 后台 PRD > 架构增量 > UI 稿。

## Story

As a 运营,
I want 在商品页补重量体积,
So that 上线前存量 SKU 可售.

## Acceptance Criteria

1. 商品编辑 **Then** SKU 表加重量、长宽高，表单四项必填，未填不能保存（G6）〔L1〕
2. 改动进审计〔L1〕
3. 商品列表加「包裹数据」筛选、摘要、缺失徽标（G5）〔L1〕
4. 现有 SKU 保存流程不受影响，退货规则、进货价列保留〔L1〕

## Tasks

- [x] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [x] 后端：迁移（如有）、服务、Controller
- [x] 页面与三语文案
- [x] 测试：L0 单测跑绿，L1 测试写好能编译
- [x] 审计与提醒逐项核对
- [x] （核对补）`AdminShopSkuPackageService.updatePackage` 允许四项全空（清空 = 暂不可售），AC1 要求必填，校验放在 `AdminShopProductService.upsertSku`
- [x] （核对补）`updatePackage` 按 `skuToken` 寻址，`upsertSku` 同事务调用时传 `sku.getPublicToken()`，新建规格也要走到
- [x] （核对补）`AdminShopProductServiceTest`、`Epic1ChainIntegrationTest` 也调 `upsertSku`，fixture 补四项

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-18
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` G5~G6；每屏 `frame-cap` 即实现说明
- **迁移**：—
- **代码入口**：`AdminShopProductController.java:272`、`AdminShopProductService.upsertSku`、`AdminShopSkuPackageService`、`ShopSkuForm`、`shop-product-form.html:252-261`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`SkuPackageValidationTest`

- 退货规则、进货价列保留

### 代码核对（2026-11-06，基线 `fd94b3ffa`，电商线 PR#49 已合入）

- 列名：`package_weight_g`、`package_length_cm`、`package_width_cm`、`package_height_cm`（`V20261105_1600`），实体 `ShopSku.applyPackage(...)`。`ShopSkuForm` 现在没有这四项。
- 审计沿用 `SHOP_SKU_UPSERTED`，`updatePackage` 已写一条，`upsertSku` 自己也写一条，同一次保存会有两条。PRD 要求带上重量体积，保留这两条即可。
- `page(category, active, ...)` 是商品列表现有入口，「包裹数据」筛选加在这里。

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC1~AC4 全部为 〔L1〕，云端未起容器未验；`AdminShopProductEndpointIntegrationTest` 新增「包裹任一未填不能保存 / 四项齐全落库」并给原 4 处 `/skus` 提交补四项，`Epic1ChainIntegrationTest.skuForm` 补四项，能编译，L1 待本地验收。页面还原度 L2 待本地验收。
- 无迁移（列已在 `V20261105_1600`）。`ShopSkuForm` 加 `packageWeightG / packageLengthCm / packageWidthCm / packageHeightCm`；`AdminShopProductService.validateSku` 内 `validatePackage` 四项必填正整数（422 `admin.err.v142.sku.packageRequired`）；`AdminShopSkuPackageService` 仍允许四项全空（电商线清空语义不动）。
- `upsertSku` 同事务调 `AdminShopSkuPackageService.updatePackage`，新建规格也走到；审计两条（`SHOP_SKU_UPSERTED` 规格本身 + 包裹 `package weight_g=… length_cm=…`），按代码核对保留。「待确认」：新增 `updatePackage(ShopSku, …)` 重载直接传已加载实体（原 token 版委托它），省一次按 token 回查，行为与按 `sku.getPublicToken()` 寻址一致。
- `AdminShopProductService` 构造器加 `AdminShopSkuPackageService`（`@Autowired`）；保留 5 参构造器供存量单测（自建包裹服务）。
- 商品列表（G5）：「包裹数据」筛选（全部 / `packageData=MISSING`，`ShopProductRepository.adminSearchMissingPackage`，排序与 `adminSearch` 一致，翻页带筛选）；摘要第 4 格「缺重量体积的 SKU」（`ShopSkuRepository.countMissingPackage`，> 0 标红）；商品名下缺失徽标。原筛选、摘要、8 列、行操作不变。
- 商品编辑（G6）：规格表加「重量(g)」「长×宽×高(cm)」列，缺失显示「未填」标红；卡顶红色横幅「有 {n} 个规格缺…」；表单四项必填，行「编辑」回填四项。退货规则、进货价列保留。
- 顺带修（L0 守门 `AdminMessagesExternalizedTest` 此前未纳入云端 L0 清单，本条起纳入）：① 2-2 `AdminShopCouponService.parse` 两处校验异常未挂码 → 挂 `admin.err.v142.coupon.optionRequired`（自拟三语，待设计确认）；② 3-1 下线品类目标后 `admin.err.banner.targetCategory` 成死键 → 四份 properties 删除。
- 复审：「缺包裹参数」JPQL 条件 `ShopProductRepository.MISSING_PACKAGE` 一处定义、计数复用；重量格标红与可售口径一致（空或非正）。未改：每次保存都写包裹审计（代码核对：保留两条）；存量缺包裹的规格改价也须先补四项（AC1 必填口径，待确认是否给存量放行）。
- 文案取三语表 G5 / G6；自拟（待设计确认）：`admin.err.v142.sku.packageRequired`。
- L0：`SkuPackageValidationTest`、`AdminShopProductServiceTest`（fixture 补四项）、`AdminShopProductsPackageRenderTest`、`AdminMessagesExternalizedTest`、`AdminMessagesParityTest`、`AdminTemplateStructureTest`、`AdminNoNestedFormTest` 全绿。

### File List

- petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopSkuForm.java
- petgo-backend/src/main/java/com/tailtopia/admin/shop/service/{AdminShopProductService,AdminShopSkuPackageService,AdminShopCouponService}.java
- petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java
- petgo-backend/src/main/java/com/tailtopia/shop/repository/{ShopProductRepository,ShopSkuRepository}.java
- petgo-backend/src/main/resources/templates/admin/{shop-products,shop-product-form}.html、fragments/shop-products-list.html
- petgo-backend/src/main/resources/static/admin/admin.css
- petgo-backend/src/main/resources/i18n/messages{,_en,_id,_zh_CN}.properties
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/{SkuPackageValidationTest（新）,AdminShopProductServiceTest}.java
- petgo-backend/src/test/java/com/tailtopia/admin/shop/web/{AdminShopProductsPackageRenderTest（新）,AdminShopProductEndpointIntegrationTest}.java
- petgo-backend/src/test/java/com/tailtopia/shop/Epic1ChainIntegrationTest.java
