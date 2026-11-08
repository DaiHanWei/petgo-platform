# Story 3.1：banner 管理扩展（AB-25）

Status: review

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

- [x] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [x] 后端：迁移（如有）、服务、Controller
- [x] 页面与三语文案
- [x] 测试：L0 单测跑绿，L1 测试写好能编译
- [x] 审计与提醒逐项核对
- [x] （核对补）`AdminShopBannerService.update` 现在把 `form.getSortWeight()` 写回去。表单去掉权重后它恒为 0，编辑一次就把排序打乱：`update` 不再改权重，`ShopBanner.apply` 和 `ShopBannerForm.sortWeight` 同步调整
- [x] （核对补）`create` 同理：新建 banner 排在列表末尾（当前最小 `sort_weight` − 1），不顶掉生效中的 banner。Completion Notes 标「待确认」

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-7
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` B1~B6；每屏 `frame-cap` 即实现说明
- **迁移**：—
- **代码入口**：`AdminShopBannerController`、`AdminShopBannerService`、`ShopBannerForm`、`ShopBannerQueryService`、`shop-banners.html`、`drawer-shop-banner.html`、`shop-banners-list.html:67`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`BannerStateTest`、`BannerImageRatioTest`

- 生效判定抽成一处供后台与 App 共用，App 接口结果不变
- 上线后品类跳转落 Toko 首页已由电商线处理

### 代码核对（2026-11-06，基线 `fd94b3ffa`，电商线 PR#49 已合入）

- 现状入口：`AdminShopBannerService.stateOf / liveId / liveIds`、`ShopBannerQueryService.liveBanners / select`、`ShopBannerRepository.findLiveCandidates`（JPQL 过滤上架与排期）、`findAllByOrderBySortWeightDescIdDesc`。
- `select` 已是纯函数，但它会跳过图片 URL 为空的 banner，且只处理已过滤的候选。六态判定要覆盖全部 banner，抽公共判定时注意这两点；`MAX_BANNERS = 6`，`CANDIDATE_LIMIT = 30`。
- 旧三态 `live / activeNotLive / inactive` 被 `shop-banners-list.html` 直接引用（如 `b.state == 'inactive'`），`AdminShopBannerStateTest`、`AdminShopBannerTargetTest` 也会被打破。
- `validate` 现在接受 CATEGORY，AC5 要改成服务端拒绝；App 取数里 CATEGORY → `TOKO_HOME` 不动。`ShopBannerForm.startAt / endAt` 是 `Instant`，页面控件按 WIB 转换。
- `POST /admin/shop/banners/reorder` 不会和 `POST /admin/shop/banners/{id}` 冲突，字面量路径优先。

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC1 / AC3~AC6 / AC8~AC10 为 L1（`AdminShopBannerReorderIntegrationTest`：新建排末尾、拖动重写 N..1、编辑不动排序、列表不一致拒绝；需真库，云端只编译）；拖动交互、比例提醒即时出现、页面还原度为 L2。
- L0 已跑绿：`BannerStateTest`（新：六态、6 张上限、后台 LIVE 集合 == App `select` 下发集合、图片 URL 拼不出不算生效）、`BannerImageRatioTest`（新，±3%）、`AdminShopBannerTemplateRenderTest`（新：拖动手柄 / 排期 / 六态 / 品类红字 / 去权重 / 券目标 / 排期回填 / B4 / B6 / 编辑标题）、`AdminShopBannerStateTest`（三态断言按设计改为六态）、`AdminShopBannerTargetTest`（品类改为一律拒绝）、`ShopBannerQueryServiceTest`。
- 生效判定：`ShopBannerQueryService.toLive` 抽出，`select`（App）与新的 `states` / `statesOf`（后台六态）共用；`states` 同时模拟 App 的候选上限（30），后台与 App 不会因候选截断分叉。`ShopBannerQueryService.liveBanners` 行为不变。原三态 `AdminShopBannerService.stateOf` 删除（`liveId` / `liveIds` 保留，仍有测试与旧单张端点语义）。
- 排序：`POST /admin/shop/banners/reorder`（ids 须恰为全部 banner）按新顺序写 `sort_weight` N..1，审计 `SHOP_BANNER_REORDERED` 记前后顺序；拖动未改位置不提交，保存失败行顺序退回原样并提示。**待确认**：新建 banner 排在列表末尾（当前最小权重 − 1），不顶掉生效中的。
- 编辑：不再改权重（`ShopBanner.apply` 去掉权重参数，新增 `reweight`；`ShopBannerForm.sortWeight` 删除）；上 / 下线时间按 WIB `datetime-local`（`startLocal` / `endLocal`），与现值同一分钟时保留原值（不截秒、不写假变更）；审计摘要只写变化字段（图 / 目标 / 排期）。
- 跳转：下拉去「品类」、加「优惠券（领券）」，只列 ACTIVE、用户领取类、发放期未结束的券；存量品类 banner 编辑时类型留空 + 顶部警示，空类型或 CATEGORY 保存一律拒绝（`admin.err.banner.categoryRetired`）。保存失败（B5）回抽屉 422 顶部 `.banner.err`，表单与比例提示保留。列表券跳转显示券名（不论是否可领）。
- 比例：`BannerImageRatio`（服务端编辑态回显）+ `admin-core.js [data-banner-ratio]`（上传后即时，同阈值），只提醒不拦截。
- 两个小 bug：行「编辑」按钮改用 `admin.v142.banner.btn.edit`；抽屉标题改由表单内按新建 / 编辑显示，外壳标题改为「顶部 Banner」。
- 文案取自三语表 B 节，`admin.shop.banners.hint`、`admin.shop.banners.target.COUPON` 按表改写；表外自拟（**待设计确认**）：六态英印译名中的 `PENDING` / `LIVE` / `EXPIRED` / `INACTIVE`、`flash.reordered`、`admin.err.banner.reorderStale`。
- 复审（code-review medium）采纳：拖动无变化不提交 / 失败退回原顺序、列表券名不限可领、422 回抽屉保留比例提示、六态模拟 App 候选上限、存量品类空类型服务端拒绝、排期按分钟保留原值、比例数字不按语言分组、Javadoc 位置；未采纳：删 `liveId/liveIds`（仍有测试与语义）。

### File List

- petgo-backend/src/main/java/com/tailtopia/shop/service/ShopBannerQueryService.java
- petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopBanner.java
- petgo-backend/src/main/java/com/tailtopia/shop/coupon/repository/ShopCouponRepository.java（`findByPublicTokenIn`）
- petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopBannerService.java
- petgo-backend/src/main/java/com/tailtopia/admin/shop/service/BannerImageRatio.java（新）
- petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopBannerForm.java
- petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopBannerController.java
- petgo-backend/src/main/resources/templates/admin/shop-banners.html
- petgo-backend/src/main/resources/templates/admin/fragments/shop-banners-list.html
- petgo-backend/src/main/resources/templates/admin/fragments/drawer-shop-banner.html
- petgo-backend/src/main/resources/static/admin/admin-core.js
- petgo-backend/src/main/resources/static/admin/admin.css
- petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/BannerStateTest.java（新）
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/BannerImageRatioTest.java（新）
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopBannerStateTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopBannerTargetTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/shop/service/AdminShopBannerReorderIntegrationTest.java（新，L1）
- petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopBannerTemplateRenderTest.java（新）
