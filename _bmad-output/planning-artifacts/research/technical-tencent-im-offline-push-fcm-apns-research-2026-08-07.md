---
stepsCompleted: [1, 2, 3, 4, 5, 6]
lastStep: 6
inputDocuments: []
workflowType: 'research'
research_type: 'technical'
research_topic: '腾讯云 IM 离线推送（系统推送 FCM/APNs）在 TailTopia 技术栈下的集成路径核实'
research_goals: '确认 TIMPush 统一插件 vs 旧厂商通道手动集成的正确路线、iOS APNs 证书/p8 密钥要求、Android FCM 通道注册流程、与 tencent_cloud_chat_sdk 9.x 的兼容性、权限申请双时机（决策 F7）前端实现方式，为 quick-dev spec 提供依据'
user_name: 'Dai'
date: '2026-08-07'
web_research_enabled: true
source_verification: true
---

# 腾讯云 IM 离线推送（FCM/APNs）集成路径核实 — 技术调研报告

**Date:** 2026-08-07
**Author:** Dai
**Research Type:** technical

---

## Executive Summary

TailTopia 的系统推送应走 **TIMPush 统一插件路线**：`tencent_cloud_chat_push 9.0.7652` 与项目现有 `tencent_cloud_chat_sdk 9.0.7652` 同号配套，仅接 FCM + APNs 双通道（印尼市场无需国内厂商通道）。后端 `LiveTencentImClient` 已带 `OfflinePushInfo`，**后端近零改动**；全部缺口在客户端接线与三个控制台（Firebase / Apple / 腾讯 IM）的一次性人工配置。国际站 Chat 套餐捆绑推送插件，当前体量下**预期零新增成本**（动手前需控制台确认权益口径）。

**Key Technical Findings:**

1. **路线定论**：TIMPush 插件版本与 SDK 完全匹配，官方主推；手动厂商集成 bug 史多，不建议。禁与 firebase_messaging 等第三方推送混用（官方明确 + 架构决策 F5 一致）。
2. **F7 权限双时机可落地**：插件无独立权限 API（源码确认 registerPush 只透传原生注册），落法是**把 registerPush 调用本身延迟到 F7 触发点**，配 `permission_handler`（已有依赖）前置弹权限 + 本地标记。
3. **三个必踩坑提前排掉**：① Flutter 裸 SDK 不自动同步前后台，须手动 `doForeground/doBackground`；② 登出必须 `unRegisterPush()` 绑 `toGuest` 收口（否则复现 IM 隐私泄漏同款事故）；③ FCM 凭证必须用 HTTP v1 服务账号 JSON（legacy key 已死）。
4. **验收路径友好**：带 Play 的 Android 模拟器可端到端收 FCM 推送；仅 iOS 须真机（development 证书环境）。
5. **文案双语归后端**：推送 Title/Desc 由服务端下发，需按接收方语言偏好在后端组装，且零 PII。

**Technical Recommendations:**

- 采用 TIMPush 9.0.7652 + 仅 FCM/APNs；`forceUseFCMPushChannel(true)` 显式锁通道
- iOS 证书走 .p12（设密码）；dev/prod 证书 ID 按构建环境 dart-define 注入
- `Ext` 深链载荷 = 路由路径 + 不可枚举 token，点击后 go_router 路径寻址
- 实施四阶段约 3-4 天：控制台凭证 → 客户端接线 → F7 门控+后端字段 → L2 验收
- 未来定时类推送（生日/里程碑 FR-40~42）走 IM 全员/指定 UserID 推送能力，不另接推送 SDK

---

## Research Overview

围绕「腾讯云 IM 离线推送（FCM/APNs）在 TailTopia 技术栈下的集成路径」做联网核实，方法为多源交叉验证（腾讯云中国站/国际站官方文档、pub.dev、trtc.io），关键结论标注置信度与来源。项目现状基线：Flutter 3.44 + `tencent_cloud_chat_sdk 9.0.7652`；Spring Boot 4 后端 `LiveTencentImClient` 发消息已带 `OfflinePushInfo`；SDKAppID 开在腾讯云**国际站**（新加坡数据中心）。

---

## Technical Research Scope Confirmation

**Research Topic:** 腾讯云 IM 离线推送（系统推送 FCM/APNs）在 TailTopia 技术栈下的集成路径核实
**Research Goals:** 确认 TIMPush 统一插件 vs 旧厂商通道手动集成、iOS APNs 证书/p8 密钥要求、Android FCM 通道注册流程、与 SDK 9.x 兼容性、权限申请双时机（决策 F7）前端实现方式，为 quick-dev spec 提供依据

**Scope Confirmed:** 2026-08-07

---

## Technology Stack Analysis（技术栈与插件生态）

### 官方统一推送插件：`tencent_cloud_chat_push`（TIMPush）

腾讯官方 Flutter 推送插件在 pub.dev 上的包名为 **`tencent_cloud_chat_push`**，即文档中的「TIMPush 统一推送插件」。
_最新版本：**9.0.7652**（截至 2026-08，发布约 2 个月前）_
_依赖要求：`tencent_cloud_chat_sdk ^9.0.7652` —— **与本项目锁定的 SDK 版本同号配套，完全匹配**_
_覆盖通道：APNs、Google FCM，以及华为/小米/OPPO/vivo/荣耀/魅族等国内厂商通道_
_置信度：高（pub.dev 官方元数据）_
_Source: https://pub.dev/packages/tencent_cloud_chat_push_

### 备选路线：厂商通道手动集成（不用 TIMPush 插件）

旧式路线仍然存在且有官方文档：自行集成 FCM SDK（firebase_messaging）拿 device token，登录成功后调 `TIMManager` 的 `setOfflinePushToken`/`setOfflinePushConfig` 把控制台生成的证书 ID + token 上报给 IM 服务端。
_注意：Flutter SDK 历史上在 `setOfflinePushConfig` 的 token 参数上有过传十六进制串异常的 bug，近期版本已修复 —— 若走手动路线需锁定新版 SDK_
_置信度：高（腾讯云国际站官方文档）_
_Source: https://www.tencentcloud.com/document/product/1047/34341_

### 计费边界（关键差异：中国站 vs 国际站）

- **中国站**：推送服务（Push）是**独立付费增值服务**（标准版/尊享版），「试用或购买到期后自动停止推送服务，**包括 IM 离线推送**」。
  _Source: https://cloud.tencent.com/document/product/269/101971_
- **国际站（本项目适用）**：trtc.io 的 Chat 套餐**捆绑推送插件**，官方对比材料明确「免费档（1000 MAU）即含 multi-vendor push plugin（APNs/FCM/华为/小米/OPPO/vivo）」，$399 的 Standard 档亦包含。
  _置信度：中高（来源为 trtc.io 官方博客对比文，属营销口径；**需在 IM 控制台实际确认当前套餐是否已含 Push 权益**）_
  _Source: https://trtc.io/blog/details/chat-api-pricing-and-hidden-costs-free-tier-mau-message-history-and-push-notifications_

### 与本项目技术栈的兼容性初判

- 插件与 SDK 同号（9.0.7652）配套发布，Flutter 3.44/Dart 3.12 无已知冲突（插件仅依赖 `flutter` + `plugin_platform_interface ^2.0.2`）。
- Android 侧需在 app-level build.gradle 加 FCM 相关依赖；本项目已有 compileSdk 36 + Kotlin 2.0 override（PostHog 接入时已调），与 Firebase 当前要求方向一致，具体版本矩阵在实现步核实。
- 印尼市场设备以 GMS 生态为主，**国内厂商通道（华为/小米等）可全部不接，只接 FCM + APNs**。
_置信度：高_
_Source: https://pub.dev/packages/tencent_cloud_chat_push · https://trtc.io/document/50032_

---

## Integration Patterns Analysis（集成模式与链路）

### 端到端推送链路（TIMPush 路线）

```
后端 REST sendmsg(OfflinePushInfo) → IM 服务端判定接收方离线
  → 经控制台绑定的厂商通道投递：FCM(Android) / APNs(iOS)
  → 系统通知栏展示 → 用户点击 → TIMPush onNotificationClicked(ext)
  → 客户端解析 ext(JSON) → go_router 按路径深链跳转
```
本项目后端 `LiveTencentImClient` 已在发消息时带 `OfflinePushInfo`，**后端侧无需新增基建**，只需核对字段填充（Title/Desc/Ext/ApnsInfo/AndroidInfo）。
_置信度：高_

### Android / FCM 通道注册流程

1. Firebase 控制台建项目 + 注册 Android 应用（包名 `com.tailtopia.app`），下载 `google-services.json` 放 `android/app/`。
2. Firebase 项目设置 → 服务账号 → Firebase Admin SDK → **生成私钥 JSON**（HTTP v1 凭证；legacy server key 已于 2024-06 被 Google 移除，**必须用服务账号 JSON**）。
3. IM 控制台 → App Push / 接入设置 → FCM → 上传服务账号私钥 JSON，消息类型选 **「通知消息」**（⚠️ 2026-08-07 实操修正：控制台明确警告透传消息「主要支持 Pixel，其他厂商手机推送失败率极高」——印尼市场三星/OPPO 为主，必须用通知消息；此前研究引用的透传建议来自音视频通话推送场景，不适用）。点击后续动作保持「打开应用内指定页面」+ 默认 CLICKACTION，由 TIMPush 回调 `onNotificationClicked`。
4. 控制台下载 `timpush-configs.json` → 放 `android/app/src/main/assets/`。
5. Gradle：project-level 加 `com.google.gms:google-services` classpath；app-level `apply plugin: 'com.google.gms.google-services'` + `com.tencent.timpush:timpush` / `com.tencent.timpush:fcm` 依赖。
6. `Application` 类需继承 `TencentCloudChatPushApplication`（AndroidManifest `android:name` 指过去）。⚠️ 本项目如已有自定义 Application，需确认继承链兼容。
_置信度：高（官方文档多源一致）_
_Source: https://trtc.io/document/78018 · https://www.tencentcloud.com/document/product/1024/30719_

### iOS / APNs 证书流程

1. Apple Developer 生成 APNs 推送证书；官方 Flutter 插件文档**只写了 .p12 路线**（须设密码，无密码收不到推送）；.p8 在腾讯部分产品线支持但 IM Chat 控制台 Flutter 文档未确认——**建议按 .p12 走，规避不确定性**。
2. 上传 IM 控制台 → 获得**证书 ID**（certificateID）。开发/生产环境证书区分：App Store 包必须用生产环境证书（Apple 合并型证书两端通用）。
3. Xcode：开启 Push Notifications capability + Background Modes(Remote notifications)；`AppDelegate.swift` 实现 `TIMPushDelegate`（`businessID()` 返回证书 ID、`onRemoteNotificationReceived`）。
4. Dart 侧 `registerPush(apnsCertificateID: ...)` 传入证书 ID。
_置信度：中高（p12 路线高；p8 支持情况官方 Flutter 文档缺失，标注为不确定项）_
_Source: https://trtc.io/document/67580 · https://trtc.io/document/50032_

### 深链透传：Ext 字段契约

- `OfflinePushInfo.Ext` 为透传字符串（推荐 JSON），用户点击通知后由 `onNotificationClicked(ext)` 回调原样带回。
- 本项目已有统一深链路由表（FR-38，go_router 按路径寻址），**Ext 内容建议直接放路由路径 + token**（对外标识用不可枚举 token，符合架构护栏），客户端解析后 `context.go(path)`。
- `ApnsInfo`：Sound/BadgeMode/Title/SubTitle/Image；`AndroidInfo`：Sound 等。角标与通知中心铃铛角标（已有）互不冲突。
_置信度：高_
_Source: https://cloud.tencent.com/document/product/269/2282_

### 注册时序契约

- `TencentCloudChatPush().registerPush()` 必须在 **IM 登录成功之后立即调用**（先于其他插件注册）；对应本项目 `toGuest`/登录跃迁收口点——登出时机与 IM 登出绑定（既有 [[im-logout-must-ride-toguest]] 决策同点位）。
- 官方明确**不建议与其他第三方推送 SDK（如 TPNS）混用**——即不要同时接 firebase_messaging 做自有推送，避免 token 抢占。⚠️ 这意味着若未来要做「非 IM 消息类推送」（生日/里程碑定时推），也应走 IM 侧全员/指定 UserID 推送能力，而非另接 FCM。
_置信度：高_
_Source: https://trtc.io/document/46306_

---

## Architectural Patterns and Design（架构落点）

### 注册/反注册生命周期（与既有 auth 收口点对齐）

- **注册**：`registerPush()` 在 IM 登录成功后调用（先于其他插件）。本项目 IM 登录收口在登录跃迁处，推送注册应挂在同一点位。
- **反注册**：插件提供 `unRegisterPush()`；官方语义：登出（主动 logout 或多端互踢）后不再收离线推送。**必须绑定 `toGuest` 单一收口**——与既有决策 [[im-logout-must-ride-toguest]] 完全同构（此前曾因漏登出 IM 导致同设备 B 看到 A 的兽医私聊，推送若不反注册会复现同类隐私泄漏：B 的设备收到 A 的消息推送）。
- 其他可用 API：`getRegistrationID`、`forceUseFCMPushChannel`（出海版强制走 FCM，**印尼场景建议显式调用**）、`disablePostNotificationInForeground`（前台是否弹通知）。
_置信度：高_
_Source: https://trtc.io/document/60559_

### 前台/后台/杀进程三态行为矩阵

| App 状态 | 消息到达方式 | 通知栏 |
|---|---|---|
| 前台（IM 在线） | IM 长连接在线消息 → 应用内处理 | 不触发厂商推送（可用 `disablePostNotificationInForeground` 控前台本地通知） |
| 后台（调用 doBackground 后） | 在线消息仍投递 SDK + **同时走厂商离线推送** | FCM/APNs 弹通知 |
| 杀进程 | 仅厂商离线推送 | FCM/APNs 弹通知 |

- SDK 5.0.1+ 语义：`doForeground()` 停厂商推送、`doBackground()` 开启；杀进程态天然走离线推送。
- ⚠️ **Flutter 裸 SDK 不会自动同步前后台状态**，需在 `AppLifecycleState` 监听里调 `doBackground/doForeground`（TIMUIKit 才有自动处理）。这是实现清单必备项，否则后台收不到推送或前台重复弹。
- FR-22A「用户正在查看该对话时不重复推送」：前台在线态天然不走厂商推送，由应用内 IM 监听处理，**无需额外服务端判定**。
_置信度：高_
_Source: https://cloud.tencent.com/document/product/269/75428 · https://im.sdk.qcloud.com/doc/zh-cn/classcom_1_1tencent_1_1imsdk_1_1v2_1_1V2TIMOfflinePushManager.html_

### 决策 F7（权限申请双时机）的架构落法

核实结论：Flutter 插件层 `registerPush` 只透传原生 `TIMPush.register()`（已读插件 iOS 源码确认），**插件不暴露独立的权限申请 API**；原生 TIMPush 注册流程内部会触发系统权限申请（iOS `requestAuthorization` / Android 13+ `POST_NOTIFICATIONS`）。
_置信度：插件层高（源码确认）；原生层内部行为中（文档未明写，需实现时真机验证）_

**推荐架构**：把 **`registerPush()` 调用本身作为 F7 门控对象**——
1. F7 触发点（首次问诊完成 **或** 建档完成且从未问诊，取最早）到达时：先用已有依赖 `permission_handler` 的 `Permission.notification.request()` 弹系统权限（配前置价值说明页可选），随后立即调 `registerPush()`。
2. 本地持久化「已过 F7 门」标记；后续每次冷启动在 IM 登录成功后直接 `registerPush()`（权限已定，注册不再弹窗）。
3. 权限被拒时：注册照常做（token 仍可上报），通知只是不展示；用户后续在系统设置开启即自动恢复，无需引导流。
- 代价说明：Android <13 无运行时权限，本可登录即推；统一门控后这批用户在过 F7 门之前收不到推送——与 F7 产品意图一致（避免冷启动即打扰），可接受。
- 与 iOS ATT 弹窗（AttGate）时序：ATT 在首帧 resumed 后触发，F7 在业务事件后触发，天然错开；仅需保证两弹窗不在同一帧竞态（F7 触发点都在业务页面深处，实际无冲突）。
_置信度：方案为本研究推荐设计；组件能力均已核实_
_Source: https://github.com/RoleWong/tencent_cloud_chat_push · https://pub.dev/documentation/tencent_cloud_chat_push/latest/_

### 安全与合规架构要点

- 推送 token 由插件直报 IM 服务端，**不经过本项目后端、不落库**——符合「凭证 env 注入、不入库」护栏，后端零改动。
- `Ext` 深链载荷只放路由路径 + 不可枚举 token，不放 PII/健康数据——推送文案（Title/Desc）同理：兽医回复推送标题不得带诊断内容，建议统一「您有新的兽医回复」类中性文案 + 双语（App 侧按 code 本地化不可行——推送文案由服务端下发，需在后端按用户语言偏好组装，注意 [[petgo-i18n-model-and-debt]] 的双语模型约束）。
- 日志护栏：`OfflinePushInfo` 组装日志严禁记录消息内容与 token（既有 SLF4J JSON 脱敏规则延伸覆盖）。
_置信度：高（项目护栏推导）_

---

## Implementation Approaches and Technology Adoption(实现落地)

### 版本矩阵与已知 bug 规避

- **`tencent_cloud_chat_push` 必须用 9.0.7652**（与 SDK 同号）：该版已修 iOS 偶发 crash、优化注册逻辑、适配 AGP 9.0、支持 FCM 触达统计。历史坑（均已修，佐证不要用旧版）：8.1 前 APNs 代理失效收不到推送 / Ext 为空、8.3 才有 `onNotificationClicked` 监听、8.2 才支持 FCM 点击自定义跳转。
- **Firebase 侧**：google-services plugin 当前 v4.5.0（文档示例的 4.3.15 偏旧，可用新版）；FCM 依赖由 TIMPush 的 `com.tencent.timpush:fcm` 间接引入，无需自己接 firebase-messaging BoM。Google Play services 生态当前基线 Kotlin 2.0.20 / minSdk 24 —— 与本项目 compileSdk 36 + Kotlin 2.0 override 兼容。
_置信度：高（pub.dev changelog + Google 官方 release notes）_
_Source: https://pub.dev/packages/tencent_cloud_chat_push/changelog · https://developers.google.com/android/guides/releases_

### 落地改动清单（预估）

**控制台/凭证（人工，一次性）—— ✅ 2026-08-07 已全部完成，实配值如下**
1. ✅ Firebase 项目 `tailtopia-ba4f7` + 应用 `com.tailtopia.app` → `google-services.json` 已入 `petgo_app/android/app/`
2. ✅ 服务账号私钥 JSON 已上传 IM 控制台 FCM 通道（**通知消息**类型）→ **FCM 证书 ID 9088**
3. ✅ APNs 合并证书（Sandbox & Production，过期 2027-09-06）同一 .p12 分传两条：**开发 ID 17703 / 生产 ID 17704**（普通推送非 VoIP，mutable-content 已勾）→ 证书 ID 按构建环境 dart-define 注入
4. ✅ `timpush-configs.json`（含 fcmPushBussinessId 9088）已入 `petgo_app/android/app/src/main/assets/`
5. ✅ 套餐确认可配 FCM/iOS 证书（控制台 console.trtc.io，应用 TailTopia-20043419，新加坡）
6. ⏰ **运维**：APNs 证书 2027-09-06 到期，2027-08-01 前重签 .p12 并在控制台「编辑」原条目重传（证书 ID 不变免发版）；.p12 与 Firebase 服务账号 JSON 存本地凭证目录，不入库

**Android（代码）**
- `google-services.json` → `android/app/`；`timpush-configs.json` → `android/app/src/main/assets/`
- project gradle：`com.google.gms:google-services` classpath；app gradle：apply plugin + `timpush`/`timpush-fcm` 依赖
- Application 继承 `TencentCloudChatPushApplication`（核对现有 Application）
- ⚠️ 内测 debug 签名包（d007 keystore）：FCM 推送不校验 SHA-1（那是 Google 登录的事），debug 包可直接收推送

**iOS（代码）**
- Push Notifications capability + Background Modes(Remote notifications) + `aps-environment` entitlement
- `AppDelegate.swift` 实现 `TIMPushDelegate`（`businessID()` 返证书 ID）
- ⚠️ debug 真机装机包走 development APNs 环境、TestFlight/App Store 走 production——证书 ID 需按构建环境切换（dart-define 注入）

**Dart（代码）**
- F7 门控服务：触发点判定 + `permission_handler` 弹权限 + `registerPush()` + 本地标记持久化
- `toGuest` 收口加 `unRegisterPush()`
- `AppLifecycleState` 监听调 `doForeground/doBackground`
- `onNotificationClicked(ext)` → 解析 JSON → go_router 路径深链
- `forceUseFCMPushChannel(true)`（出海场景显式锁 FCM）

**后端（微调）**
- `LiveTencentImClient` 的 `OfflinePushInfo` 字段核对：Title/Desc 按接收方语言组装、Ext 放路由路径+token、BadgeMode
_置信度：高_

### 测试与验收分层（对齐本项目 L0/L1/L2）

- **L0**：`flutter analyze/test`（F7 门控逻辑、ext 解析路由单测可测）；`mvn -B package`（OfflinePushInfo 组装单测）。云端可跑。
- **L2（推送验收全在此层）**：
  - Android：**带 Google Play 的 Android 模拟器即可端到端收 FCM 推送**（杀进程→后端发消息→通知栏→点击深链），符合「模拟器=Android」工作流，无需真机。
  - iOS：**模拟器收不了真实 APNs，必须真机**（development 环境证书）。
  - 工具：IM 控制台有推送自查工具（troubleshooting tool）可逐环节定位不达原因。
- 验收矩阵建议入 docs/L2 验收文档，含三态×两端×点击深链 = 12 个用例格。
_置信度：高_
_Source: https://trtc.io/document/50032_

### 成本与风险

- **成本**：国际站 Chat 免费档（1000 MAU）含推送插件，当前 DAU≤500 姿态下**预期零新增成本**；若套餐口径有变（营销文案 vs 控制台实际），风险敞口是被要求升级付费档——动手前先在控制台确认（已列入清单）。
- **风险 1（中）**：TIMPush 原生注册内部权限申请行为文档未明写，F7 门控设计基于「延迟注册」规避，真机验证时若发现 registerPush 不弹权限，则 permission_handler 前置弹窗方案不受影响（更稳）。
- **风险 2（低）**：Application 继承链冲突（若现有自定义 Application 已继承其他基类，需改组合方式接线，TIMPush 文档有非继承接法）。
- **风险 3（低）**：iOS 证书环境错配（debug 装机收不到推送的第一嫌疑位）。
_置信度：中高_

## Technical Research Recommendations

### 实施路线图（建议）

1. **P1 控制台与凭证**（人工，0.5 天）：Firebase/Apple/IM 控制台三件套 + 套餐权益确认
2. **P2 客户端接线**（1-2 天）：Android + iOS 原生配置 + Dart 注册/反注册/生命周期/深链
3. **P3 F7 门控 + 后端字段核对**（0.5-1 天）：权限双时机 + OfflinePushInfo 双语组装
4. **P4 L2 验收**（0.5 天）：Android 模拟器全链路 + iOS 真机抽验 + 控制台自查工具兜底

### 技术选型定论

**走 TIMPush 统一插件（`tencent_cloud_chat_push` 9.0.7652）+ 仅 FCM/APNs 双通道**，不走手动厂商集成（bug 史多、维护面大、官方主推插件路线）；不引入 firebase_messaging 独立推送（官方明确不建议混用，且违背「复用 IM」架构决策 F5）。

### 成功指标

- 三态（前台/后台/杀进程）× 双端推送到达且点击深链正确落页
- 登出后原账号消息不再推送到该设备（隐私回归用例）
- F7 时机外零权限弹窗；Android <13 无弹窗直接生效
- 后端零新增中间件、推送文案零 PII

---

## Technical Research Conclusion

### 结论

推送接入是「已规划未实现」的收尾工程，不是新架构决策：PRD（FR-22A~E/40~42）与架构（F5/F7、复用 IM 离线推送）均已定案，本次调研确认了实现路径全部可行且与现有技术栈零冲突。唯一路线级确认项是国际站套餐的 Push 权益口径（控制台一查即知）；唯一设计级不确定项是原生 TIMPush 注册的内部权限行为（延迟注册方案对两种行为都稳健，真机验证即可收敛）。

### 下一步建议

1. IM 控制台确认套餐 Push 权益（5 分钟，先做）
2. 走 `bmad-quick-dev` 出自包含 spec（含控制台人工步骤清单 + 云端 L0 执行须知），按 P1→P4 路线实施
3. L2 验收矩阵（三态 × 双端 × 深链）并入 docs/ 下既有 L2 验收文档体系

### 遗留不确定项（实现时收敛）

| 项 | 置信度 | 收敛方式 |
|---|---|---|
| 国际站免费/现套餐是否含 Push 插件权益 | 中高 | IM 控制台核实 |
| 原生 TIMPush.register() 是否自动弹权限 | 中 | 真机验证（方案对两种结果均兼容） |
| IM 控制台 APNs 是否支持 .p8 | 低（文档缺失） | 按 .p12 走，不依赖此项 |
| 现有 Android Application 继承链兼容性 | — | 实现时看代码（有非继承接法兜底） |

## Sources（关键来源清单）

- 插件与版本：[pub.dev/tencent_cloud_chat_push](https://pub.dev/packages/tencent_cloud_chat_push) · [changelog](https://pub.dev/packages/tencent_cloud_chat_push/changelog) · [插件源码 GitHub](https://github.com/RoleWong/tencent_cloud_chat_push)
- Flutter 集成：[trtc.io/document/50032](https://trtc.io/document/50032) · [trtc.io/document/46306](https://trtc.io/document/46306) · [API 文档 trtc.io/document/60559](https://trtc.io/document/60559)
- FCM 通道：[trtc.io/document/78018](https://trtc.io/document/78018) · [FCM Channel Integration（服务账号 JSON）](https://www.tencentcloud.com/document/product/1024/30719)
- APNs 证书：[trtc.io/document/67580](https://trtc.io/document/67580)
- 三态与 doForeground/doBackground：[cloud.tencent.com/document/product/269/75428](https://cloud.tencent.com/document/product/269/75428) · [V2TIMOfflinePushManager](https://im.sdk.qcloud.com/doc/zh-cn/classcom_1_1tencent_1_1imsdk_1_1v2_1_1V2TIMOfflinePushManager.html)
- REST OfflinePushInfo：[cloud.tencent.com/document/product/269/2282](https://cloud.tencent.com/document/product/269/2282)
- 计费：[中国站 Push 服务](https://cloud.tencent.com/document/product/269/101971) · [国际站定价对比（营销口径）](https://trtc.io/blog/details/chat-api-pricing-and-hidden-costs-free-tier-mau-message-history-and-push-notifications)
- Google 生态基线：[Google Play services releases](https://developers.google.com/android/guides/releases)

---

**Technical Research Completion Date:** 2026-08-07
**Source Verification:** 关键结论多源交叉验证；不确定项显式标注置信度与收敛方式
