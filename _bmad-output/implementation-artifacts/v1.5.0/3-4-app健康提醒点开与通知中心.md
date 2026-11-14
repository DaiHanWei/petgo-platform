# Story 3.4: App 健康提醒点开与通知中心

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段（`kPushDirectTypes` 三方取并集）> 架构增量 §0 Z-4/Z-5、§5.4、§7、§8 > PRD FR-167（全部）、FR-169 Settings description / UI notification center、§4 `health_notification_opened` + Attribution、AC44、AC46 ~ AC49、AC55、AC63 Open firing > UI 稿 E1 ~ E5、G1 ~ G3。
> 前置：后端 2.4（`/me/health-notifications/{token}/open`、通知落库文案）；App 3.1（页面参数）、3.2（`healthFormDirtyProvider`、表单 prefill）。
> 范围层：**只 App**。

## Story

As a 宠物主人,
I want 点健康提醒（推送或通知中心）直接切到那只宠物、打开对应的健康页或预填好的表单,
so that 不用自己找宠物、找类型、找第几针。

## Acceptance Criteria

### AC1 — 推送点击直达 `[L0]`（PRD FR-167 Skips the inbox；架构 §7）
1. `core/push/push_service.dart` 的 `kPushDirectTypes` **追加** 23 个 `HEALTH_*`（与后端 `NotificationType` 中 `HEALTH_` 前缀的值逐字一致，建议写成 `kHealthReminderTypes` 常量集合再 `...` 展开）；**不删、不改**现有值（③ 推送与召回、⑥ 运营活动也会往这里加，合并时取并集）。
2. `DeepLinkRoutes.pushPayloadToLocation`：`type` 以 `HEALTH_` 开头 → `/health-reminder/open?t=<targetRef>&src=<push|inbox>`（targetRef 为空 → 通知中心）。`pushClickLocation` 对直达类型带 `src=push`；通知中心点击路径带 `src=inbox`。
3. L0 测试：`pushClickLocation(hasToken: true, type: 'HEALTH_VACCINE_D_DAY', …)` 返回 `/health-reminder/open?...src=push`；`pushPayloadToLocation` 对全部 23 个类型都不落通知中心。

### AC2 — 点开路由页 `[L0]` `[L2]`（架构 §5.4、§7；PRD AC46 ~ AC49）
1. 新路由 `/health-reminder/open`（顶层，shell 外，受控前缀判定与 `/profile` 一致：游客 → 被现有 redirect 门控）→ `HealthReminderOpenPage`：显示加载态，调 `GET /api/v1/me/health-notifications/{t}/open?source=<src>`（`ApiPaths` 新常量 + `HealthNotificationRepository`）。
2. 结果处理：
   - `PET_UNAVAILABLE` → `go` 通知中心 + toast「Hewan ini sudah tidak tersedia.」（EN「This pet is no longer available.」，`showAppToast`），route = `inbox_fallback`。
   - `OK` → 若 `petId` ≠ 当前宠物：`currentPetIdProvider.select(petId)` + `pet_switched {source: health_reminder}`（同一只不报）；然后 **replace** 当前路由为 `/profile/health?…`：FORM → `add=<type>&preset=&dose=&deworm=&custom=&dateEmpty=1(若 eventDateEmpty)&nid=<t>`；PAGE / PAGE_SATISFIED → `focus=<focusRecordId>&safety=<逗号连接>&nid=<t>`。
   - 接口失败 / 404 → 通知中心（无 toast），route = `inbox_fallback`。
3. 冷启动、后台、前台三种点击都走同一入口（现有 `PushService._onNotificationClicked` → `pushClickLocation`），不另写分支。
4. 返回键：从路由页 replace 后，返回回到点击前的页面（冷启动 = 首页）。

### AC3 — 表单未保存时点提醒 `[L0]`（PRD FR-167 Fallbacks，决策日志 2026-11-05「E4 取消二次确认」）
1. 推送点击处理与通知中心条目点击处理在**导航之前**读 `healthFormDirtyProvider`：为 true 且即将去往 `/health-reminder/open` → 不切宠物、不导航，只弹 toast「Simpan atau tutup catatan ini dulu, lalu buka lagi pengingatnya.」（EN「Save or close this record first, then open the reminder again.」）。通知中心条目保持原状可再点。没填内容的表单不算（dirty=false 照常打开）。
2. 不弹任何确认抽屉 / 对话框。

### AC4 — 埋点与归因 `[L0]`（PRD §4、Attribution、AC63）
1. 点开接口返回后报 `health_notification_opened {notification_id, pet_id, notification_kind, push_point, origin, merged_count, source, route}`（取接口的 `analytics` 对象；`route` ∈ form / page / page_satisfied / inbox_fallback）。`source=push` 每个通知最多一次（内存记已报过的 token），`source=inbox` 每次都报。
2. 新增 `HealthReminderAttribution`（内存，App 进程级 Provider）：记录 `petId → (notificationId, notificationKind, openedAt)`，每次 opened 覆盖；3.2 的保存埋点读它：同宠物、30 分钟内 → `source=health_reminder` + `notification_id` + `notification_kind`。
3. 埋点字面量、`health_` 前缀；属性不含宠物名。

### AC5 — 通知中心显示 `[L0]` `[L2]`（PRD FR-169 UI notification center；架构 Z-5；UI G2、E5）
1. `notification_center_page.dart`：`type` 以 `HEALTH_` 开头 → 标题 / 正文走 `_storedTitle` / `_storedBody`（后端已按收件人语言存好）；正文 `maxLines: 2`（其他类型保持 1 行）；图标 `Icons.health_and_safety_rounded`、前景 `AppColors.mint`、底 `AppColors.violet100`（未读行底 `cream2`）。生日仍用蛋糕。
2. 点击条目：先按 AC3 检查，再走 `pushPayloadToLocation`（→ `/health-reminder/open?…&src=inbox`）；已读标记由后端点开接口完成，本地乐观置已读。

### AC6 — 推送设置描述 `[L0]`（PRD FR-169 Settings description、AC55；UI G3）
1. 只改 arb `pushSettingsPetReminderDesc`：ID「Ulang tahun, hari jadi & pengingat kesehatan」、EN「Birthdays, anniversaries & health reminders」。**不改**「Hewan yang kamu ikuti」那一行（③ 推送与召回线负责改名）。开关行为不变（一个开关同时管生日、纪念日、健康提醒——后端 `PushCategory` 已归类）。

### AC7 — 视觉与真机 `[L2]`
1. 本地：Android + iOS 真机，推送冷启动 / 后台 / 前台三态点开都落到正确页面并上报 `health_notification_opened`（PRD AC63「Open firing」必须真机验，不可假设）；通知中心 G2、E5 还原度 ≥95%（4.1 统一做）。

## Tasks / Subtasks

- [ ] T1 `kPushDirectTypes` + `pushPayloadToLocation`（AC1）
- [ ] T2 `HealthReminderOpenPage` + 路由 + 仓储（AC2）
- [ ] T3 未保存拦截（AC3）
- [ ] T4 埋点 + 归因（AC4）
- [ ] T5 通知中心样式（AC5）
- [ ] T6 推送设置描述（AC6）
- [ ] T7 测试：`test/core/push_click_location_test.dart`（扩展）、`test/notify/health_reminder_open_page_test.dart`（假仓储：四种结果、切宠物、replace 路由参数）、`test/notify/notification_center_health_item_test.dart`（2 行、图标、stored copy）、未保存 toast 测试
- [ ] T8 `flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

- 文件：`core/push/push_service.dart`（`kPushDirectTypes` 第 60 行、`pushClickLocation` 第 79 行）、`core/router/deep_link_routes.dart`（`pushPayloadToLocation` 第 69 行）、`core/router/app_router.dart`、`features/notify/presentation/notification_center_page.dart`（`_storedTitle` 约 1104 行、图标 switch 约 974 行）、`features/notify/domain/notification_item.dart`、`features/profile/data/current_pet_provider.dart`、`l10n/*.arb`。
- `core/` 不 import `features/`：路由页放 `features/notify/presentation/`，`deep_link_routes.dart` 里写字面量路径（同 `/me/growth` 的写法）。
- 换账号后 `HealthReminderAttribution` 要清空：登记进 `app.dart` 的 `resetUserScopedCaches`。
- 不整文件 `dart format`。

### 验证层级
- L0：`flutter analyze`；上面四个测试文件
- L2：真机三态点开 + 埋点、通知中心视觉

## Dev Agent Record

### Completion Notes

### File List
