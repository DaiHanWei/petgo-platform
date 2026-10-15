---
title: 'AB-30 后台「运营弹窗」配置页'
type: 'feature'
created: '2026-10-14'
status: 'done'
baseline_commit: '9d42d9f9'
context:
  - '{project-root}/_bmad-output/planning-artifacts/v1.3.4/决策日志-app.md'
  - '{project-root}/_bmad-output/implementation-artifacts/v1.3.4/5-1-运营弹窗数据与-app-读取接口.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** FR-137 运营弹窗的数据表 `ops_popups` 与 App 读取 / 展示已实现，但后台没有页面，运营无法自己新建、修改、启停弹窗，只能靠开发写 SQL。

**Approach:** 新增后台页面 `/admin/ops-popups`（列表 + 抽屉表单，照 Toko 横幅页 `AdminShopBannerController` 的结构），支持新建、编辑、启用、停用；图片复用横幅的上传组件但限 1MB；跳转目标校验一律走 `OpsTargetPolicy`。后台 PRD 与 UI 稿不在仓库，按现有后台风格自行设计（用户 2026-10-14 拍板）。

## Boundaries & Constraints

**Always:**
- 权限复用 `config.view`（看）/ `config.edit`（改），页面挂侧栏「配置」组，与 AB-22 同口径，不新增权限码。
- 新建一律 DISABLED，需手动启用；编辑不换 `id` / `public_token`（频次记录挂在 id 上，AD-14）。
- 时间按 WIB 输入、UTC 落库；结束时间可空（长期有效），非空须晚于开始；列表显示「未开始 / 进行中 / 已结束」（由时间窗现算）。
- 字段规则（D-23）：名称 ≤100 必填；印尼文图必填，英文图可空（空 = 共用印尼文图）；标题 ≤120、正文 ≤255 两语言均可空；印尼文按钮 ≤40 必填，英文按钮可空；优先级整数；人群 ALL / NEW / OLD，新老分界天数 >0 默认 7；频次 ONCE / DAILY。
- 跳转目标：NONE / PRODUCT / CATEGORY / APP_PAGE / EXTERNAL；保存时先 `OpsTargetPolicy.isWellFormed` 再 `isAvailable`（商品须存在且在售）。CATEGORY、APP_PAGE 用下拉，不让手填。
- 图片：单张 >1MB 拒绝上传；4:5（建议 1080×1350）与「右上角留空给 ✕」只做提示。
- 每次写操作记审计（`OPS_POPUP_CREATED / UPDATED / ACTIVATED / DEACTIVATED`）；新增文案四份 bundle 齐全。

**Ask First:** 需要新增权限码、改 `ops_popups` 表结构、改 App 读取接口或 `OpsTargetPolicy` 规则时。

**Never:** 不做删除（停用即可，保留频次与审计）；不展示曝光 / 点击数（走 PostHog）；不做 AB-31；不引入新上传通道或预签名。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error Handling |
|---|---|---|---|
| 新建 | 必填齐全 | 落一条 DISABLED 记录，列表出现，toast，审计 CREATED | — |
| 启用 / 停用 | 列表行按钮 | 状态切换，App 候选接口随之出现 / 消失，审计 | — |
| 编辑 | 改文案 / 时间 / 目标 | 原记录更新，id 与 token 不变 | — |
| 缺必填 | 无名称 / 无印尼文图 / 无印尼文按钮 / 无开始时间 | 422 抽屉内报错，对应字段标红 | `admin.err.opsPopup.*` |
| 时间窗非法 | 结束 ≤ 开始，或时间格式错 | 422 | 同上 |
| 跳转值非法 | EXTERNAL 非 http(s)、PRODUCT 不存在或已下架、NONE 带值 | 422 | 同上 |
| 超长 | 标题 121 字、正文 256 字、按钮 41 字 | 422 | 同上 |
| 图片超限 | 上传 1.2MB | 400 JSON 报错，前端提示，不入库 | `admin.err.opsPopup.imageTooLarge` |
| 只读账号 | 仅 `config.view` | 能看列表与详情，无新建 / 编辑 / 启停按钮；POST 403 | — |

</frozen-after-approval>

## Code Map

- `petgo-backend/src/main/java/com/tailtopia/ops/popup/domain/OpsPopup.java` -- 实体；有 `create` / `activate` / `disable`，缺编辑方法
- `.../ops/popup/repository/OpsPopupRepository.java` -- 缺后台列表查询
- `.../ops/target/OpsTargetPolicy.java` -- `isWellFormed`(L54) / `isAvailable`(L76)；`OpsAppPage`、`shop/domain/ProductCategory` 为下拉选项来源
- `.../admin/shop/web/AdminShopBannerController.java` + `admin/shop/service/AdminShopBannerService.java` -- 列表 / 抽屉 / 启停 / 审计 / 上传端点(L158) 的范式
- `.../admin/seed/service/AdminSeedImageService.java` + `admin/places/service/PlaceStampImageValidator.java` -- 上传与「按功能更小上限」先例
- `.../admin/pin/web/AdminContentPinController.java:291`、`shared/schedule/ScheduleWindow.java` -- WIB 时间解析与阶段计算
- `.../admin/shared/AdminPageCatalog.java`、`admin/audit/service/AuditActions.java` -- 侧栏登记、审计常量
- `petgo-backend/src/main/resources/templates/admin/shop-banners.html`、`fragments/shop-banners-list.html`、`fragments/drawer-shop-banner.html` -- 模板范式
- `petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopProductEndpointIntegrationTest.java`(横幅段 L404-540)、`admin/seed/AdminSeedPostIntegrationTest.java`(OSS 桩 L62-77) -- 测试范式

## Tasks & Acceptance

**Execution:**
- [x] `OpsPopup.java` -- 加 `update(...)`（同 `create` 字段，不动 id / token / status）
- [x] `OpsPopupRepository.java` -- 加按 `priority desc, created_at desc, id desc` 的后台列表查询
- [x] `admin/opspopup/service/AdminOpsPopupService.java`（新） -- `create / update / activate / deactivate`；按 Always 规则校验抛 `admin.err.opsPopup.*`；空白串归一为 null；审计
- [x] `admin/opspopup/web/AdminOpsPopupController.java`（新） -- `GET /admin/ops-popups`（整页 / HX 行 / `?create=1` / `?open=<token>` 抽屉）、`POST` 新建、`POST /{token}` 编辑、`/{token}/activate`、`/{token}/deactivate`、`POST /admin/ops-popups/images`（复用 `AdminSeedImageService`，目录 `ops-popup`，>1MB 先拒）；权限 `config.view` / `config.edit`
- [x] `AuditActions.java`、`AdminPageCatalog.java` -- 四个审计常量；页面 `ops-popups` 挂「配置」组
- [x] `templates/admin/ops-popups.html`、`fragments/ops-popups-list.html`、`fragments/drawer-ops-popup.html`（新） -- 列表列：缩略图、名称、状态 + 阶段、时间窗（WIB）、优先级、人群、频次、跳转；抽屉表单按字段规则分组（基本 / 投放 / 内容 ID / 内容 EN / 跳转），两个上传组件 `data-max-bytes=1048576`，跳转类型切换显隐对应输入
- [x] 四份 `messages*.properties` -- 页面 / 导航 / 字段 / 提示 / 按钮 / toast / 错误码
- [x] 测试（新） -- `AdminOpsPopupServiceTest`（矩阵各校验行）、`AdminOpsPopupIntegrationTest`（新建→启用→候选接口可见→停用→不可见；编辑后 token 不变；422；403；上传超限）；按需更新 `AdminPageCatalogTest` 等钉列表的测试

**Acceptance Criteria:**
- Given 运营新建并启用一条「全部用户 · 每人一次 · 跳 Know Your Pet」弹窗, when 游客调 `/api/v1/ops-popups/candidates`, then 返回该弹窗，跳转为 `APP_PAGE / KNOW_YOUR_PET`。
- Given 一条已启用弹窗, when 运营停用它, then 候选接口不再返回，列表状态变「停用」。
- Given 任一写操作成功, when 查看审计日志, then 有对应 `OPS_POPUP_*` 记录。

## Design Notes

- 用 `public_token` 做 URL 标识，不外露自增 id（护栏）。
- 图片落公开桶 objectKey，App 侧由 `ShopImageUrlResolver` 换 CDN 地址；列表缩略图同样走它。
- 不做删除：删除会级联掉曝光记录，频次失忆后同一用户可能再次看到。

## Verification

**Commands:**
- `cd petgo-backend && mvn -B clean package -DskipTests` -- expected: BUILD SUCCESS
- `cd petgo-backend && mvn -B test -Dtest='AdminOpsPopup*Test,AdminPageCatalogTest,AdminMessages*Test,AdminTemplate*Test,OpsTargetPolicyTest'` -- expected: 全绿
- L1：`AdminOpsPopupIntegrationTest`、`OpsPopupIntegrationTest` 本地 Docker 临时库跑绿

**Manual checks:**
- 本地起后台：新建、上传图片（含 >1MB）、切换跳转类型、启停；三语无裸 key；截图给用户。

## Suggested Review Order

**写入与校验（入口）**

- 新建 / 编辑 / 启停全在这里；校验口径、跳转目标只在改动时查在售
  [`AdminOpsPopupService.java:79`](../../../petgo-backend/src/main/java/com/tailtopia/admin/opspopup/service/AdminOpsPopupService.java#L79)

- 编辑不碰 id / token / status；@DynamicUpdate 防编辑把并发启停写回
  [`OpsPopup.java:156`](../../../petgo-backend/src/main/java/com/tailtopia/ops/popup/domain/OpsPopup.java#L156)

**接口与权限**

- 列表 / 抽屉 / 新建 / 编辑 / 启停，URL 只用 public_token
  [`AdminOpsPopupController.java:92`](../../../petgo-backend/src/main/java/com/tailtopia/admin/opspopup/web/AdminOpsPopupController.java#L92)

- 上传：>1MB 先拒，不发 OSS；报错本地化
  [`AdminOpsPopupController.java:202`](../../../petgo-backend/src/main/java/com/tailtopia/admin/opspopup/web/AdminOpsPopupController.java#L202)

- 侧栏挂「配置」组，复用 config.view / config.edit
  [`AdminPageCatalog.java:242`](../../../petgo-backend/src/main/java/com/tailtopia/admin/shared/AdminPageCatalog.java#L242)

**页面与交互**

- 抽屉表单五组字段 + 两个单图上传 + 跳转类型显隐
  [`drawer-ops-popup.html:16`](../../../petgo-backend/src/main/resources/templates/admin/fragments/drawer-ops-popup.html#L16)

- 行内启停落列表上方报错位，不再清空正在编辑的抽屉
  [`ops-popups-list.html:67`](../../../petgo-backend/src/main/resources/templates/admin/fragments/ops-popups-list.html#L67)

- 上传控件张数上限（含上传中）、粘贴只给最近操作的那个
  [`admin-core.js:668`](../../../petgo-backend/src/main/resources/static/admin/admin-core.js#L668)

- 下拉驱动的显隐（隐藏即禁用、不提交）
  [`admin-core.js:1339`](../../../petgo-backend/src/main/resources/static/admin/admin-core.js#L1339)

**测试**

- 服务层矩阵校验
  [`AdminOpsPopupServiceTest.java:1`](../../../petgo-backend/src/test/java/com/tailtopia/admin/opspopup/service/AdminOpsPopupServiceTest.java#L1)

- 新建→启用→候选可见→停用；403；上传超限
  [`AdminOpsPopupIntegrationTest.java:1`](../../../petgo-backend/src/test/java/com/tailtopia/admin/opspopup/AdminOpsPopupIntegrationTest.java#L1)
