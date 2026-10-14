---
title: 'AB-31 后台「运营通知」发送页'
type: 'feature'
created: '2026-10-15'
status: 'done'
baseline_commit: '564d0ac5'
context:
  - '{project-root}/_bmad-output/planning-artifacts/v1.3.4/决策日志-app.md'
  - '{project-root}/_bmad-output/implementation-artifacts/v1.3.4/6-2-运营通知发送核心-人群-分批推送-重试-定时.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** FR-138 运营系统通知的发送核心（人群解析、分批推送、重试、定时扫描）已实现，但后台没有页面，运营无法自己发通知，只能靠开发写 SQL。

**Approach:** 新增后台页面 `/admin/ops-notifications`：发送记录列表 + 新建抽屉（立即 / 定时）+ 取消待发送；保存调用已有的 `OpsNotificationCampaignService.create / cancel`，后台补人数预估、错误码文案和审计。后台 PRD 与 UI 稿不在仓库，按 AB-30 的风格自行设计（用户 2026-10-14 拍板）。

## Boundaries & Constraints

**Always:**
- 权限复用 `config.view`（看）/ `config.edit`（发、取消），页面挂侧栏「配置」组，与 AB-22 / AB-30 同口径。
- 文案：印尼文、英文标题 ≤120、正文 ≤255，四项都必填（PRD §3.7）；输入框按此限长。
- 人群三种（D-16）：全部用户 / 按条件（注册日期区间、最近活跃区间、是否有宠物、物种、是否下过已支付商城订单、是否填手机号；日期按 WIB 含两端，不填 = 不限）/ 指定用户 ID（文本框粘贴，逗号、空格、换行分隔，非数字报错，上限 50,000 个，无效 ID 允许、发送时跳过并计数）。
- 跳转目标与 AB-30 同一套（NONE / PRODUCT / CATEGORY / APP_PAGE / EXTERNAL），校验与错误码共用，表单块抽成共享片段两页共用。
- 发送前必须预估人数：服务端按当前人群条件实时计数，确认框写明「将发送给 N 人」，N 为 0 时不让发。
- 定时：WIB 输入，早于当前时刻拒绝；不填 = 立即发送。只有「待发送」可以取消。
- 发送记录不可编辑、不可重发；列表显示状态与送达 / 失败 / 跳过人数，不显示打开数（PostHog）。
- 审计 `OPS_NOTIFY_CREATED` / `OPS_NOTIFY_CANCELED`；app 主题已定的 6 个 `admin.err.ops-notify.*` 码及新增码四份 bundle 齐全；对外一律用 `public_token`（D-24）。

**Ask First:** 需要新增权限码、改发送核心的分批 / 重试 / 状态机、或改人群 SQL 的口径时。

**Never:** 不做编辑、重发、删除；不展示打开 / 点击数据；不做 CSV 文件上传（文本框粘贴即可）；测试里不得以「全部用户 + 立即发送」向共享库真发。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error Handling |
|---|---|---|---|
| 立即发送 | 文案齐、人群 = 指定 ID、未填定时 | 预估人数 → 确认 → 落 PENDING 并异步发送，列表出现该条，审计 CREATED | — |
| 定时发送 | 定时为明天 10:00 WIB | 落 PENDING，到点由扫描发送；列表显示定时时间 | — |
| 取消 | 待发送那条点取消 | 状态变「已取消」，不再发送，审计 CANCELED | 已在发送 / 已完成 → 409 提示 |
| 缺文案 / 超长 | 任一语言标题或正文为空，或 121 / 256 字 | 422 抽屉内报错并标红 | `text.blank` / `text.too-long` |
| 定时已过 | 定时早于现在 | 422 | `schedule.past` |
| 指定 ID 为空 / 非数字 / 超上限 | 空、`12,abc`、50,001 个 | 422 | `audience.ids-empty` / 新码 |
| 条件区间倒置 | 注册起 > 注册止 | 422 | `audience.invalid` |
| 预估 0 人 | 条件过严 | 提示 0 人，发送按钮不可用 | — |
| 只读账号 | 仅 `config.view` | 能看记录与详情，无新建 / 取消；POST 403 | — |

</frozen-after-approval>

## Code Map

- `petgo-backend/src/main/java/com/tailtopia/notify/ops/service/OpsNotificationCampaignService.java` -- `create`(L74) / `cancel`(L92) / `validate`(L104)；不写审计
- `.../notify/ops/domain/OpsCampaignDraft.java`、`AudienceSpec.java`、`OpsNotificationCampaign.java` -- 草稿、人群规格、实体
- `.../notify/ops/repository/OpsNotificationCampaignRepository.java` -- 缺分页列表查询
- `.../auth/service/OpsAudienceSql.java`(L64-78) + `OpsAudienceQuery.java`(L38) -- 人群 SQL，缺计数
- `.../admin/opspopup/service/AdminOpsPopupService.java`(L256-275 `checkTarget`) + `templates/admin/fragments/drawer-ops-popup.html`(L125-172) -- 跳转校验与表单块，抽共享
- `.../admin/usertag/web/AdminUserTagController.java`(L367-380) -- ID 文本解析先例
- `templates/admin/fragments/places-list.html`(L61-67) -- 分页先例
- `static/admin/admin-core.js`(L41-80 `data-confirm` + args) -- 确认框
- `src/test/java/com/tailtopia/admin/opspopup/AdminOpsPopupIntegrationTest.java` -- 测试范式；`notify/ops/OpsNotificationDispatchIntegrationTest.java`(L123) -- 清理待发送活动

## Tasks & Acceptance

**Execution:**
- [x] `OpsAudienceSql.java` / `OpsAudienceQuery.java` -- 加 `count(AudienceSpec, Collection<Long> ids)`：复用同一 WHERE 口径包 `count(*)`；IDS 模式按去重后的 ID 走 `u.id IN (...)`（分批）计有效人数
- [x] `OpsNotificationCampaignRepository.java` -- 加按 `created_at desc, id desc` 的分页查询
- [x] 跳转目标共享 -- 把 AB-30 的 `checkTarget` 抽到 `admin/ops/AdminOpsTargetValidator`、表单块抽成 `fragments/ops-target-fields.html`，AB-30 改为调用共享件，行为与文案不变
- [x] `admin/opsnotify/service/AdminOpsNotifyService.java`（新） -- 表单 → `OpsCampaignDraft`（WIB 定时、ID 文本解析与上限、条件转 `AudienceSpec`）；`preview` 计数；`create` 前用共享校验器给跳转具体错误码；调用核心 `create / cancel`；审计
- [x] `admin/opsnotify/web/AdminOpsNotifyController.java`（新） -- `GET /admin/ops-notifications`（整页 / HX 分页行 / `?create=1` 抽屉 / `?open=<token>` 只读详情）、`POST .../preview`（返回人数片段）、`POST` 新建、`POST /{token}/cancel`；权限同 AB-30
- [x] `AuditActions.java`、`AdminPageCatalog.java` -- 两个审计常量；页面挂「配置」组
- [x] 模板（新）`ops-notifications.html`、`fragments/ops-notifications-list.html`、`fragments/drawer-ops-notification.html` -- 抽屉分组：文案（ID / EN）/ 人群（三选一 + 条件或 ID 框按选择显隐）/ 跳转（共享片段）/ 发送时间；「预估人数」按钮 + 提交确认框带 N
- [x] 四份 `messages*.properties` -- 页面 / 字段 / 状态 / 按钮 / toast / 6 个已有 `admin.err.ops-notify.*` + 新码 + 审计动作名
- [x] 测试（新）-- `AdminOpsNotifyServiceTest`（矩阵校验行、ID 解析、条件转换）、`OpsAudienceSqlTest` 补计数、`AdminOpsNotifyIntegrationTest`（IDS 指定测试用户立即发送→通知中心收到；定时→取消；422；403；预估人数），每例 `@AfterEach` 取消残留待发送；AB-30 回归测试保持绿

**Acceptance Criteria:**
- Given 运营按「指定用户 ID」选 2 个账号并立即发送, when 发送完成, then 这 2 人通知中心各有一条，列表该条状态「已完成」、送达 2。
- Given 一条定时到明天的待发送通知, when 运营点取消, then 状态变「已取消」，到点也不会发送。
- Given 任一新建或取消成功, when 查看审计日志, then 有对应 `OPS_NOTIFY_*` 记录。

## Design Notes

- 预估人数与真正发送是两次查询，期间人数可能有出入；确认框文案写「约 N 人」。
- 跳转目标的具体错误码不改核心 `OpsTargetPolicy`，在后台共享校验器里给出，核心的无码兜底保留。
- 发送是对外动作，测试只用「指定 ID + 专用测试账号」或「未来定时 + 取消」，避免给共享库全员发通知。

## Verification

**Commands:**
- `cd petgo-backend && mvn -B clean package -DskipTests` -- expected: BUILD SUCCESS
- `cd petgo-backend && mvn -B test -Dtest='AdminOpsNotify*Test,AdminOpsPopupServiceTest,OpsAudienceSqlTest,OpsNotificationCampaignServiceTest,AdminPageCatalogTest,AdminMessages*Test,AdminTemplate*Test'` -- expected: 全绿
- L1：`AdminOpsNotifyIntegrationTest`、`AdminOpsPopupIntegrationTest`、`OpsNotificationDispatchIntegrationTest` 本地 Docker 临时库跑绿

**Manual checks:**
- 本地起后台：三种人群切换、预估人数、立即 / 定时发送、取消、只读详情；三语无裸 key；截图给用户。

## Spec Change Log

- 2026-10-15 复审补丁（不改冻结块意图，属实现加固）：
  - **服务端预估闸**：新建表单带回确认框里的 N（`previewCount`），`AdminOpsNotifyService.create` 按同一人群重算——缺 N → `admin.err.ops-notify.preview-required`；重算 0 人 → `admin.err.ops-notify.audience-empty`；重算比 N 多出 max(10, 10%) 以上 → `admin.err.ops-notify.preview-stale`（须重新预估）。人数变少不拦。均 422。
  - **一次性 submitKey 幂等**：抽屉渲染随机 UUID，`create` 校验全过后 Redis `SET NX`（`admin:ops-notify:submit:<key>`，TTL 30 分钟）认领；同键再提交 / 缺键 → 422 `admin.err.ops-notify.duplicate-submit`，不建活动；认领后核心校验失败或事务异常则释放键，表单可改后重提。前端另加 `hx-sync="this:drop"` + `hx-disabled-elt`。（用 422 而非 409：admin-core.js 只回填 422/403/404。）
  - **ID 上限按去重后计**：50,000 上限统计的是去重后的 ID 个数，重复粘贴不占名额。
  - 前端：预估请求按代数丢弃过期结果、预估按钮 `hx-sync="this:replace"`；填了定时时确认框改为「将于 <WIB 时间> 定时发送给约 N 人」（新键 `admin.opsNotify.confirmSchedule`）。

## Suggested Review Order

**发送链路（入口）**

- 表单 → 草稿：人群三选一、ID 解析与上限、WIB 定时；服务端预估闸与一次性提交键
  [`AdminOpsNotifyService.java:1`](../../../petgo-backend/src/main/java/com/tailtopia/admin/opsnotify/service/AdminOpsNotifyService.java#L1)

- 人数计数与真实发送共用同一段 WHERE
  [`OpsAudienceSql.java:1`](../../../petgo-backend/src/main/java/com/tailtopia/auth/service/OpsAudienceSql.java#L1)

**接口与页面**

- 列表分页 / 抽屉 / 预估 / 新建 / 取消，URL 只用 public_token
  [`AdminOpsNotifyController.java:1`](../../../petgo-backend/src/main/java/com/tailtopia/admin/opsnotify/web/AdminOpsNotifyController.java#L1)

- 抽屉：文案 / 人群 / 跳转（共享片段）/ 定时；预估门槛与确认文案
  [`drawer-ops-notification.html:1`](../../../petgo-backend/src/main/resources/templates/admin/fragments/drawer-ops-notification.html#L1)

- 前端预估门槛（代数丢弃过期结果、改人群即作废）
  [`admin-core.js:1`](../../../petgo-backend/src/main/resources/static/admin/admin-core.js#L1)

**与 AB-30 共享**

- 跳转目标校验抽出，两页共用
  [`AdminOpsTargetValidator.java:1`](../../../petgo-backend/src/main/java/com/tailtopia/admin/ops/AdminOpsTargetValidator.java#L1)

- 跳转表单片段
  [`ops-target-fields.html:1`](../../../petgo-backend/src/main/resources/templates/admin/fragments/ops-target-fields.html#L1)

**测试**

- 只用现造测试账号 / 未来定时，绝不对共享库全员立即发
  [`AdminOpsNotifyIntegrationTest.java:1`](../../../petgo-backend/src/test/java/com/tailtopia/admin/opsnotify/AdminOpsNotifyIntegrationTest.java#L1)
