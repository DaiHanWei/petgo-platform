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

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-18
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` G5~G6；每屏 `frame-cap` 即实现说明
- **迁移**：—
- **代码入口**：`AdminShopProductController.java:272`、`AdminShopProductService.upsertSku`、`AdminShopSkuPackageService`、`ShopSkuForm`、`shop-product-form.html:252-261`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`SkuPackageValidationTest`

- 退货规则、进货价列保留

## Dev Agent Record

### Completion Notes

### File List
