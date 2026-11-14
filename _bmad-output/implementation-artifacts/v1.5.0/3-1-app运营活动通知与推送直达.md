# Story 3.1: App 运营活动通知与推送直达（A1 ~ A4）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ⑥ ops-activity · 分支 `feat/1.5.0-ops-activity`
> 口径优先级：`CROSS-STORY-DECISIONS.md`（地基：类型名 `OPS_ACTIVITY_REWARDED` / `OPS_ACTIVITY_RANK_WON`；`kPushDirectTypes` 三方取并集）> `decision-log.md`（11-13：两类新通知用 `campaign_rounded`、走后端文案条目、获奖推送直达帖子、帖子已删 toast）> PRD §3.3、§3.5、§3.7、§6「App」> App 整合稿 `运营活动规则引擎/ui-1-5-0-ops-activity-integrated-v1.html` 页头「研发执行说明」> 架构 §1 #1、§4.1。拆页 `运营活动规则引擎/拆页-App/` A1 ~ A4。
> 前置：1-2（到账通知）、1-5（获奖通知）——App 侧只依赖通知字段约定，可用 mock JSON 开发。
> 范围层：**只 App**。

## Story

As a 参加活动的用户,
I want 在通知中心看到「来自某活动的奖励」与「你的帖子拿了第 n 名」，点开能到对应页面，获奖推送直接打开我的帖子,
so that 我知道奖励到了、在哪看。

## Acceptance Criteria

### AC1 — 类型名 `[L0]`
- 一律用地基类型名 `OPS_ACTIVITY_REWARDED`、`OPS_ACTIVITY_RANK_WON`（App 稿执行说明里的 `ACTIVITY_REWARD` / `ACTIVITY_RANK_WON` 是旧写法，**不用**）。

### AC2 — 通知中心条目（A1、A2、A4）`[L0]` `[L2]`
`A/features/notify/presentation/notification_center_page.dart`：
1. 两类加进「读后端 title / body」的分支：`_typeLabel` / `_typeBody` 里与 `'OPS_ANNOUNCEMENT' || 'PET_SLOT_EXPIRING' || 'PET_SLOT_EXPIRED'` 同一行（`_storedTitle` / `_storedBody`）；同步更新 `notification_item.dart` 第 39 行类型注释。⚠️ gift-pack 6-3 会在同一行加 `PACK_CREDIT_EXPIRING`——合并时**取并集**，不互相覆盖。
2. `_iconStyle`：两类都 `(Icons.campaign_rounded, AppColors.mint, AppColors.violet100)`（执行说明原文）。
3. 正文最多 2 行，超出用现有「Lihat semua / Ringkas」展开（A4，「Lihat semua」在时间行右侧，照 `_NotificationTile` 现有写法），正文固定两行高不被压缩重叠。
4. 不新增 `.arb` 词条（文案由后端落库，执行说明「App 端无新增 .arb 词条」）。

### AC3 — 点开跳转 `[L0]`
1. `A/core/router/deep_link_routes.dart` `pushPayloadToLocation`：
   - `case 'OPS_ACTIVITY_REWARDED'`：放在**「无 targetRef → 通知中心」短路之前**（同 `COUPON_EXPIRING`）：`targetRef == 'COUPONS'` → `'/me/coupons'`，其余（`WALLET` / 空）→ `'/me/pawcoin'`（「Koin & Jatah Saya」页，路由不变）。字面量，core 不 import feature。
   - `case 'OPS_ACTIVITY_RANK_WON'`：放在 id 寻址段：`int.tryParse(targetRef)` 非空 → `'/content/$id'`，否则通知中心（同 `PET_DIARY_POSTED` 防崩写法）。
2. 通知中心点获奖条目：`_postGone` 的类型判断加 `OPS_ACTIVITY_RANK_WON`（帖子已删 → 留在通知中心 + toast `detailGoneTitle`「Konten ini sudah tidak ada」，同追更宠物）；已读照标。
3. `_notifTypeForAnalytics`：走现有 `type.toLowerCase()` 兜底即可（PRD §4 不新增埋点），不加显式行。
4. L0：`test/notify/deep_link_routes_test.dart` 补：REWARDED + `WALLET` / `COUPONS` / null；RANK_WON 数字 / 非数字 / null。

### AC4 — 推送直达（A3）`[L0]`
1. `A/core/push/push_service.dart` 的 `kPushDirectTypes` **只加** `'OPS_ACTIVITY_RANK_WON'`（保留现有三个值；① / ③ 也会各加自己的值，合并取并集）；更新 `pushClickLocation` 上方注释列表。
2. 到账类不推送，不加。
3. L0：`test/notify/push_payload_test.dart`（或现有 `pushClickLocation` 测试）补：RANK_WON 带 token 也直达 `/content/{id}`；REWARDED 不在直达集合。

### AC5 — Widget 测试 `[L0]`
`test/notify/ops_activity_notification_test.dart`（照 `coupon_expiring_notification_test.dart` 写法，结构断言，不写 golden）：
- 到账条目显示后端 `title / body`、图标 `campaign_rounded`、点开（WALLET）→ `/me/pawcoin`、（COUPONS）→ `/me/coupons`。
- 获奖条目点开 → `/content/{id}`；帖子探测返回已删 → 不跳转、出现 toast。
- 长正文显示两行 +「Lihat semua」。

## Tasks / Subtasks

- [ ] 读后端文案分支 + 图标（AC2）
- [ ] 路由 + `_postGone`（AC3）
- [ ] `kPushDirectTypes`（AC4）
- [ ] 测试（AC3 ~ AC5）

## Dev Notes

- 现有：`notification_center_page.dart`（`_onTap`、`_postGone`、`_iconStyle`、`_typeLabel` / `_typeBody`、`_storedTitle` / `_storedBody`）、`notification_item.dart`、`core/router/deep_link_routes.dart`、`core/push/push_service.dart`（`kPushDirectTypes`、`pushClickLocation`）。
- **不要对整个文件跑 `dart format`**（存量未统一格式化），手工对齐缩进。
- 旧版 App 不认识这两类：通知中心显示兜底、点开留在通知中心（可接受，架构 §6）。

## 验证层级

- L0：`flutter analyze`；`flutter test test/notify/`
- L2（本地，Android 模拟器 + 真后端）：A1 ~ A4 逐屏对照拆页 ≥ 95%；真机收获奖推送点开直达帖子

## Dev Agent Record

### Completion Notes

### File List
