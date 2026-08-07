---
title: '系统推送接入（TIMPush FCM/APNs）'
type: 'feature'
created: '2026-08-07'
status: 'done'
baseline_commit: '311400151c741d503033316a5a211e7924b40096'
context:
  - '{project-root}/_bmad-output/planning-artifacts/research/technical-tencent-im-offline-push-fcm-apns-research-2026-08-07.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 通知链路上游全齐（NotificationService 按收件人 locale 渲染文案、deepLinkType/Token 契约、F7 权限双时机、深链路由表），但 `pushOffline()` 是空桩、客户端未接 TIMPush——App 杀进程/后台时用户与兽医收不到任何系统推送（FR-22A/B/E 断链）。

**Approach:** 客户端接 `tencent_cloud_chat_push 9.0.7652`（仅 FCM+APNs 通道），注册挂 IM 登录成功点、反注册挂 `toGuest`；会话消息由发送端 SDK 带 OfflinePushInfo；通知类推送由后端实现 `pushOffline` → `POST /v4/timpush/batch`（指定 UserID 推送）。控制台已配好：FCM 证书 9088、APNs 开发 17703/生产 17704。

## Boundaries & Constraints

**Always:**
- 插件版本锁 `9.0.7652`（与 chat_sdk 同号）；显式 `forceUseFCMPushChannel(true)`
- Ext 载荷只含 `type` + `token`（+可选 `targetRef`），禁顺序 id / PII / 健康数据明文（TencentImClient.java:37 既有契约）
- C 端注册时机受 F7 门控：仅当 `pushPermissionAsked==true` 才在 IM 登录后 registerPush；F7 弹窗完成时立即补注册。**兽医角色不走 F7**：IM 登录成功即请求权限并注册
- `unRegisterPush()` 在 `toGuest()` 里、IM logout **之前**调用；页面级 IM logout（会话页 dispose）不得触发反注册
- iOS 证书 ID 按构建环境编译期切换：`#if DEBUG` → 17703，否则 17704
- 通知点击统一走 `NotificationDeepLink.open` → `pushPayloadToLocation`；Shell Tab 根路由必须 `go` 不能 `push`；冷启动复用 `pendingDeepLinkProvider` 通道
- 前后台状态手动同步：全局 `WidgetsBindingObserver` 调 SDK `doForeground/doBackground`
- 会话消息推送文案用中性印尼语（不含消息内容/诊断内容）
- 后端 `/v4/timpush/batch` 失败仅 log.warn，不影响主事务（沿用 NotificationPusher @Async 语义）；日志禁记 token/文案内容

**Ask First:**
- 若 TIMPush 与现有 posthog/appsflyer 的 gradle 依赖发生版本冲突需要升降级任何既有依赖
- 若 `/v4/timpush/batch` 实测契约与文档不符需换端点（如退回 sendmsg+OfflinePushInfo 方案）

**Never:**
- 不接 firebase_messaging / flutter_local_notifications / 任何第三方推送 SDK
- 不接国内厂商通道（华为/小米等）
- 不动 SafetyRuleLayer、不加 MQ/中间件、不新增 Flyway 迁移（本特性零 schema 变更）
- 不改 F7 门控既有判定逻辑与文案 sheet

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 杀进程收通知类推送 | 后端 NotificationService.send → pushOffline | 通知栏展示 locale 文案；点击冷启动→pending 深链落地目标页 | batch 4xx/5xx 仅 warn 日志 |
| 杀进程收兽医回复 | 兽医端 sendText（带 OfflinePushInfo） | 用户通知栏中性文案；点击进对应会话 | SDK 发送失败走既有重试语义 |
| 前台收消息 | App resumed，IM 在线 | 不弹系统通知（doForeground 已同步），应用内既有监听处理 | — |
| 未过 F7 门的 C 端用户 | pushPermissionAsked=false，IM 已登录 | 不调 registerPush，无权限弹窗 | — |
| 兽医登录 | role=VET，IM 登录成功 | 直接请求通知权限+registerPush | 拒绝权限仍注册（token 可上报） |
| 登出/注销/401 | toGuest() 触发 | unRegisterPush 后 IM logout；原账号消息不再推到本机 | unRegister 异常吞掉不阻断登出 |
| 权限被拒后收推送 | 系统通知权限 denied | 不展示，无崩溃；系统设置开启后自动恢复 | — |

</frozen-after-approval>

## Code Map

- `petgo_app/pubspec.yaml:78` -- 加 `tencent_cloud_chat_push: 9.0.7652`（chat_sdk ^9.0.7652+1 已在）
- `petgo_app/android/settings.gradle.kts:20-24` -- plugins 块加 `com.google.gms.google-services` 4.5.0 apply false
- `petgo_app/android/app/build.gradle.kts:4-8` -- apply google-services plugin + `com.tencent.timpush:fcm` 依赖
- `petgo_app/android/app/src/main/kotlin/com/tailtopia/app/` -- 新建 `TailTopiaApplication.kt` 继承 `TencentCloudChatPushApplication`
- `petgo_app/android/app/src/main/AndroidManifest.xml:19` -- `${applicationName}` → `.TailTopiaApplication`
- `petgo_app/ios/Runner/Runner.entitlements` -- 加 `aps-environment`
- `petgo_app/ios/Runner/Info.plist` -- 加 `UIBackgroundModes: [remote-notification]`
- `petgo_app/ios/Runner/AppDelegate.swift` -- 实现 `TIMPushDelegate.businessID()`（#if DEBUG 17703/17704）
- `petgo_app/lib/core/im/im_service.dart:162,177` -- IM 登录成功点（注册钩子）；`:65` sendText/sendImage 加 offlinePushInfo
- `petgo_app/lib/features/auth/domain/auth_state.dart:123` -- toGuest 收口（反注册钩子）
- `petgo_app/lib/features/notify/domain/push_permission_gate.dart:73` -- F7 完成点（补注册钩子）
- `petgo_app/lib/features/notify/domain/notification_deep_link.dart:19` + `core/router/deep_link_routes.dart:46` -- 点击落地（已有，接 ext 解析）
- `petgo_app/lib/app.dart:54` -- 全局 lifecycle observer 挂载点；`:70,76` pending 深链通道
- `petgo-backend/.../shared/im/LiveTencentImClient.java:195` -- pushOffline 空桩（实现处）；`StubTencentImClient.java:53` 同步改
- `petgo-backend/.../notify/service/NotificationPusher.java:39` -- 既有调用方（无需改）

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/pubspec.yaml` -- 加 tencent_cloud_chat_push 9.0.7652 依赖 -- 地基
- [x] `petgo_app/android/{settings.gradle.kts,app/build.gradle.kts,app/src/main/AndroidManifest.xml}` + 新建 `TailTopiaApplication.kt`（继承插件 Application）+ `com.tencent.timpush:fcm:9.0.7652`（mavenCentral 无 7653 配套 fcm，腾讯发布错位） -- Android 原生接线 -- FCM 通道
- [x] `petgo_app/third_party/tencent_cloud_chat_push/`（vendor + dependency_overrides） -- pub 原版 build.gradle 含 `jcenter()`（Gradle 9 已移除）+ compileSdk 33 过低，vendor 打两行补丁（见 VENDORED.md，上游修复即可删）；`third_party/**` 加入 analyzer exclude -- 构建可行性
- [x] `petgo_app/ios/Runner/{Runner.entitlements,Info.plist}` -- aps-environment + UIBackgroundModes（**AppDelegate 零改动**：插件自含 TIMPushDelegate，证书 ID 由 Dart `kReleaseMode` 切换注入） -- APNs 通道
- [x] `petgo_app/lib/core/push/push_service.dart`（新建） -- syncRegistration（F7 门控 + 兽医旁路）/unregister（仅注册态触发）/点击回调→NotificationDeepLink.openFromPush/三态导航（splash pending 通道）/forceUseFCMPushChannel/生命周期 doForeground|doBackground -- 单一收口
- [x] `petgo_app/lib/app.dart` -- 登录跃迁监听触发 syncRegistration + WidgetsBindingObserver 生命周期同步 -- 注册时机与三态
- [x] `petgo_app/lib/core/im/im_service.dart` + `im_chat_placeholder.dart` + 双会话页 -- sendText/sendImage 带 ChatPushSpec（中性印尼语文案 + ext{VET_REPLY,sessionId}，双向共用，兽医侧靠角色守卫落工作台） -- 会话推送
- [x] `petgo_app/lib/features/auth/domain/auth_state.dart` -- toGuest 先 unregister 再 IM logout（顺序硬约束） -- 隐私
- [x] `petgo_app/lib/features/notify/domain/push_permission_gate.dart` + providers -- gate 新增 onAsked 回调 → F7 完成即补注册 -- 双时机
- [x] `petgo-backend/.../ImUserSigController.java` -- **MAU 闸门放宽为登录用户都签**（2026-08-07 实现期决策，用户拍板；否则未问诊用户永远无法注册推送） -- 覆盖面前置
- [x] `petgo-backend/.../{TencentImClient,LiveTencentImClient,StubTencentImClient,NotificationPusher,NotificationService}.java` -- pushOffline 实现 /v4/timpush/batch + 接口全链补 targetRef + sendSystemMessage 附中性 OfflinePushInfo（防中文正文进推送预览） -- 通知类推送
- [x] `petgo_app/test/notify/{push_payload_test,push_permission_gate_test}.dart` + `petgo-backend/.../{LiveTencentImClientTest,ImUserSigGateTest,ImUserSigControllerEndpointTest,NotificationServiceTest}.java` -- ext 契约双端往返/门控 onAsked/batch 请求体/闸门放宽矩阵 -- L0 覆盖

**Acceptance Criteria:**
- Given C 端用户已过 F7 门且 IM 登录，when App 杀进程后兽医回复/他人点赞，then 通知栏出现推送且点击落地对应会话/详情页（L2：Android 模拟器全链路，iOS 真机抽验）
- Given 兽医账号登录工作台，when 有新问诊请求广播，then 杀进程态收到推送（L2）
- Given 用户 A 登出、用户 B 登录同一设备，when A 收到任何消息/通知，then 本机零推送（L2 隐私回归）
- Given 全部改动，when `flutter analyze` + `flutter test` + `mvn -B test-compile` + 后端相关单测，then 全绿（L0，含新增测试）

## Spec Change Log

**2026-08-07 · 三重评审（盲审/边界猎手/验收审计）**：无 intent_gap / 无 bad_spec（未触发循环）。
10 条 patch 已当场修复：① 换账号直切（auth→auth 且 id 变）现解绑旧号+IM 换身份+重注册；
② PushService 加注册代际号（epoch）作废在途注册，防跨账号 token 残留；③ 并发注册不再吞
调用方的 isVet；④ IM 登录失败重试一次（保杀进程点击事件不丢）；⑤ 点击回调全程 catch 兜底
通知中心；⑥ toGuest 反注册限时 5s 防饿死 IM logout；⑦ **会话页 dispose 不再登出 IM**（登出即
停投推送，原「控 MAU」动机已随 usersig 放宽失效——评审最重发现，不修则用户离开会话页后推送
全灭）；⑧ F7 门控异常不外抛且 onAsked 必达；⑨ usersig role 显式白名单（非 USER/VET 拒签）；
⑩ 后端 jsonEscape 补控制字符转义。3 条 defer 入 deferred-work.md（IM 滥用面缓解 / 死代码清理 /
兽医侧 targetRef 穿透）。核实后拒绝的误报：POST_NOTIFICATIONS 本就在 manifest；
google-services.json 为客户端公开配置（无 private_key），不违「凭证不入库」护栏（FCM 服务账号
私钥 JSON 确认不在仓库）。修复后 L0 复验：flutter analyze 0 / flutter test 722 全绿 /
后端定向 20 全绿 / debug APK 构建通过。

## Design Notes

- 通知类与会话类是两条独立链路：前者后端 REST 主动推（不进会话、不增未读），后者 IM 服务端对离线接收方自动走厂商通道（发送端附带的 OfflinePushInfo 决定文案与 ext）
- `/v4/timpush/batch`（UserID 定向推送，1-500/批，本特性单收件人）文档：trtc.io UserID-Targeted Push；沿用 LiveTencentImClient 既有 usersig/admin 鉴权参数拼接模式
- registerPush 内部会触发系统权限申请（原生层），故 F7 门控的对象就是 registerPush 调用本身；permission_handler 前置弹窗为主、插件内部申请为兜底
- 推送文案 i18n 全在后端（NotificationService 已按 users.locale 渲染），App 端零文案新增

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: 0 errors
- `cd petgo_app && flutter test` -- expected: 全绿（含新增门控/ext 解析测试）
- `cd petgo_app && flutter build apk --debug` -- expected: 构建成功（验证 gradle 接线）
- `cd petgo-backend && mvn -B test-compile && mvn -B test -Dtest='*TencentIm*,*NotificationPusher*'` -- expected: 编译+相关单测绿

**Manual checks (if no CLI):**
- L2 验收矩阵（三态×双端×点击深链）：Android 用带 Play 的模拟器全链路；iOS 真机（development 证书 17703）；登出隐私回归用例必测
- iOS 构建需 Xcode 自动签名刷新 Provisioning Profile（Push capability 新增后）

## Suggested Review Order

**入口：推送单一收口（设计意图全在这）**

- 注册门控/代际作废/点击深链/三态导航/生命周期，全部收口一个类
  [`push_service.dart:57`](../../petgo_app/lib/core/push/push_service.dart#L57)

- 注册时机唯二触发点：登录跃迁 + 换账号直切（先解绑旧号）
  [`app.dart:126`](../../petgo_app/lib/app.dart#L126)

**隐私与账号切换（安全攸关）**

- toGuest：反注册（限时 5s）→ IM logout 硬保序
  [`auth_state.dart:123`](../../petgo_app/lib/features/auth/domain/auth_state.dart#L123)

- 在途注册作废机制（epoch）——快速登录/登出竞态的防线
  [`push_service.dart:95`](../../petgo_app/lib/core/push/push_service.dart#L95)

- 会话页不再离开即登出 IM（登出=停投推送，评审最重发现）
  [`consult_conversation_page.dart:108`](../../petgo_app/lib/features/consult/presentation/consult_conversation_page.dart#L108)

**F7 权限双时机**

- gate 新增 onAsked 回调：F7 达成即刻注册（拒绝也回调）
  [`push_permission_gate.dart:52`](../../petgo_app/lib/features/notify/domain/push_permission_gate.dart#L52)

- provider 接线：onAsked → syncRegistration
  [`push_permission_providers.dart:32`](../../petgo_app/lib/features/notify/data/push_permission_providers.dart#L32)

**深链契约（双端同构）**

- ext 解析纯函数 + 载荷契约（type/token/targetRef）
  [`push_service.dart:19`](../../petgo_app/lib/core/push/push_service.dart#L19)

- 会话消息发送端附带：中性文案 + VET_REPLY ext（双向共用，兽医靠角色守卫落工作台）
  [`im_service.dart:20`](../../petgo_app/lib/core/im/im_service.dart#L20)

- 推送点击与列表点击共用核心（WidgetRef/Ref 双包装）
  [`notification_deep_link.dart:19`](../../petgo_app/lib/features/notify/domain/notification_deep_link.dart#L19)

**后端：通知类推送落地**

- 空桩 pushOffline → /v4/timpush/batch 实现 + Ext 组装（含控制字符转义）
  [`LiveTencentImClient.java:196`](../../petgo-backend/src/main/java/com/tailtopia/shared/im/LiveTencentImClient.java#L196)

- MAU 闸门放宽（用户拍板）+ role 白名单加固
  [`ImUserSigController.java:34`](../../petgo-backend/src/main/java/com/tailtopia/shared/im/web/ImUserSigController.java#L34)

- targetRef 全链穿透（推送直跳 id 寻址类目标页）
  [`NotificationPusher.java:26`](../../petgo-backend/src/main/java/com/tailtopia/notify/service/NotificationPusher.java#L26)

**原生接线与构建**

- Android：google-services + timpush:fcm（版本错位注释必读）
  [`build.gradle.kts:4`](../../petgo_app/android/app/build.gradle.kts#L4)

- vendor 补丁说明（jcenter 已死 + compileSdk 33→36，上游修复即删）
  [`VENDORED.md:1`](../../petgo_app/third_party/tencent_cloud_chat_push/VENDORED.md#L1)

- iOS 零 AppDelegate：entitlements + 后台模式即可
  [`Runner.entitlements:12`](../../petgo_app/ios/Runner/Runner.entitlements#L12)

**外围：测试**

- ext 契约双端往返测试
  [`push_payload_test.dart:1`](../../petgo_app/test/notify/push_payload_test.dart#L1)

- batch 请求体契约断言
  [`LiveTencentImClientTest.java:1`](../../petgo-backend/src/test/java/com/tailtopia/shared/im/LiveTencentImClientTest.java#L1)
