# Story 3.1：banner 管理扩展（AB-25）

Status: ready-for-dev

> V1.4.2 · 主题 shop-admin · 分支 `feat/1.4.2-shop-admin`（电商线合入 `dev_1.4.2` 后切出）。
> 口径优先级：`决策日志-shop-admin.md` > `决策日志-shop.md` > 后台 PRD > 架构增量 > UI 稿。

## Story

As a 运营,
I want 多张 banner 排期和拖动排序,
So that 能安排活动.

## Acceptance Criteria

1. banner 列表 **Then** 权重列改拖动手柄，新增排期列，「原始尺寸」列保留（B1）〔L1〕
2. 六种状态由纯函数算出，与 App 取数共用生效判定；超 6 张的标超出展示上限，目标失效自动标记〔L0〕
3. 拖动写回 `sort_weight`，App 顺序随之变化〔L1〕
4. 抽屉去权重、去品类，加优惠券类型（只列用户领取券）与上下线时间（B3）〔L1〕
5. 存量品类 banner 编辑须改选，未改选不能保存，服务端同样拒绝（B4）〔L1〕
6. 目标无效保存失败（B5）〔L1〕
7. 尺寸说明常驻；比例偏差超 ±3% 提醒、不拦截（B6）〔L0〕
8. 修「编辑」按钮文字与抽屉标题两个小 bug〔L1〕
9. 排序、排期、跳转目标改动写审计〔L1〕
10. 空态 B2〔L1〕

## Tasks

- [ ] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [ ] 后端：迁移（如有）、服务、Controller
- [ ] 页面与三语文案
- [ ] 测试：L0 单测跑绿，L1 测试写好能编译
- [ ] 审计与提醒逐项核对

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-7
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` B1~B6；每屏 `frame-cap` 即实现说明
- **迁移**：—
- **代码入口**：`AdminShopBannerController`、`AdminShopBannerService`、`ShopBannerForm`、`ShopBannerQueryService`、`shop-banners.html`、`drawer-shop-banner.html`、`shop-banners-list.html:67`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`BannerStateTest`、`BannerImageRatioTest`

- 生效判定抽成一处供后台与 App 共用，App 接口结果不变
- 上线后品类跳转落 Toko 首页已由电商线处理

## Dev Agent Record

### Completion Notes

### File List
