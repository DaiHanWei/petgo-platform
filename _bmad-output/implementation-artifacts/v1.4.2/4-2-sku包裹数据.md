# Story 4.2：SKU 包裹数据

Status: ready-for-dev

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

- [ ] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [ ] 后端：迁移（如有）、服务、Controller
- [ ] 页面与三语文案
- [ ] 测试：L0 单测跑绿，L1 测试写好能编译
- [ ] 审计与提醒逐项核对
- [ ] （核对补）`AdminShopSkuPackageService.updatePackage` 允许四项全空（清空 = 暂不可售），AC1 要求必填，校验放在 `AdminShopProductService.upsertSku`
- [ ] （核对补）`updatePackage` 按 `skuToken` 寻址，`upsertSku` 同事务调用时传 `sku.getPublicToken()`，新建规格也要走到
- [ ] （核对补）`AdminShopProductServiceTest`、`Epic1ChainIntegrationTest` 也调 `upsertSku`，fixture 补四项

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

### File List
