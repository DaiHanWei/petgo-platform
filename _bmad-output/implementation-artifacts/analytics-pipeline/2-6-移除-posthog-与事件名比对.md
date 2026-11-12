# Story 2.6: 移除 PostHog 与事件名比对

Status: review

<!-- 主题 analytics-pipeline，不归属任何版本（App 侧随 V1.5.0 切换）。分支 feat/analytics-pipeline，基于 main。 -->

## Story

作为开发者，
我想要 App 里不再有 PostHog，实际发出的事件名都在后端清单里，
以便新版不发无用请求，改名在 CI 就能发现。

## Acceptance Criteria

本 story **只改 App，没有后端段**（比对测试只读后端的 `event-catalog.yaml`，不改它）。前置：Story 2.1–2.5 已完成；Story 1.2 已提交 `petgo-backend/src/main/resources/analytics/event-catalog.yaml`。L0 = `flutter analyze` / `flutter test`（以及验证构建覆盖项时的 `flutter build apk --debug`，云端可跑）；L1 = Android 模拟器。

1. **〔L0〕移除 PostHog**：移除 `posthog_flutter` 依赖、`POSTHOG_KEY` / `POSTHOG_HOST` dart-define 和全部 `Posthog()` 调用，`flutter analyze` 干净，`main.dart` 里的 PostHog 启动注释同步清理。
2. **〔L0〕Android 构建覆盖项**：Android 侧为 PostHog 加的构建覆盖项，确认无其他依赖需要后一并移除，否则保留并注明原因。
3. **〔L0〕事件名比对测试**：`test/core/analytics/event_catalog_sync_test.dart` 扫描 `lib/` 下 `Analytics.capture(` / `capturePurchase` / `buttonTapped` 等调用的事件名字符串字面量和事件名常量，与 `../petgo-backend/src/main/resources/analytics/event-catalog.yaml` 比对；三元表达式两侧都要识别；动态拼接的名字（如 `'${tabName}_page'`）列白名单；缺项时测试失败并列出。
4. **〔L1〕无 PostHog 请求**：模拟器上走一遍主流程，抓包确认没有任何发往 posthog.com 的请求。

## Tasks / Subtasks

- [x] T1 删 PostHog 代码（AC: 1）
  - [x] `lib/core/analytics/analytics.dart`：删 `import 'package:posthog_flutter/posthog_flutter.dart';`、`_apiKey`（`POSTHOG_KEY`）、`_host`（`POSTHOG_HOST`）两个常量、`init()` 里的 `PostHogConfig` / `Posthog().setup(...)` 整段。`init()` 只剩 2.1 / 2.3 / 2.5 加的自建部分（环境采集、sink 启动、生命周期）。2.1–2.4 应已删掉 capture / identify / reset / screen 里的 `Posthog()` 调用，这里复查一遍。
  - [x] 类注释：去掉「PostHog Cloud EU · project 211847」「PostHog 专项豁免」等已失效的说法，改为描述自建上报的约束（不传 PII / 健康数据、哈希用户标识、吞错、debug 不发送）。其余方法注释里描述 PostHog 行为的句子顺手改准确，不扩大改动面。
  - [x] `lib/core/router/app_router.dart`：2.4 应已去掉 import 和 `PosthogObserver`，复查。
  - [x] 完成后 `grep -rn "Posthog\|posthog_flutter\|POSTHOG_" lib` 必须为空（注释中作为历史背景出现的「PostHog」字样可以保留）。
- [x] T2 删依赖（AC: 1）
  - [x] `pubspec.yaml`：删 `posthog_flutter: ^5.33.0` 及其上方 2 行注释。
  - [x] `flutter pub get` 更新 `pubspec.lock`（`posthog_flutter` 条目消失；检查有没有其他条目被连带升降级，有就在 Completion Notes 列出）。
  - [x] iOS 侧 `GeneratedPluginRegistrant.m`、`ios/Flutter/ephemeral/**`、`Podfile.lock` 都**不在 git 里**（已核实），无需手改；本地下次 `pod install` 会自然去掉。
- [x] T3 `main.dart` 注释清理（AC: 1）
  - [x] 第 36–49 行那段关于「PostHog setup 必须在 runApp 之前、不 await 也不丢首帧 `$screen`」的长注释已不成立（自建 sink 在加载完成前用内存缓冲，不会丢事件），改成两三行：说明 `Analytics.init()` 发起不 await 的原因（不拖首帧，2026-08-07 冷启动治理），以及启动前已 `await` 的匿名 id / 生命周期快照（2.1、2.3）。**`unawaited(Analytics.init())` 这一行保留在 `runApp` 之前**。
  - [x] 只改这段注释，不动其他行；不整文件 `dart format`。
- [x] T4 Android 构建覆盖项（AC: 2）
  - [x] `android/build.gradle.kts` 第 42–58 行 `subprojects { if (project.name == "posthog_flutter") { ... } }` 整块（含上方 3 行注释）**只作用于 posthog_flutter**，依赖移除后是死代码 → 删除。
  - [x] `android/app/build.gradle.kts` 第 56–58 行 `compileSdk = maxOf(flutter.compileSdkVersion, 36)`：注释说是为 posthog_flutter 连带的 androidx 抬到 ≥34。当前 Flutter（3.44）的 `flutter.compileSdkVersion` 已是 36（`flutter_tools/gradle/src/main/kotlin/FlutterExtension.kt`），`maxOf` 结果不变。处理：**保留表达式**（作为保底，防以后 Flutter 默认值变化时 `rive` / `geolocator` / 地图等插件编译失败），只把注释改为「保底 36；原因已非 PostHog，当前 Flutter 默认即 36」。在 Completion Notes 写明保留原因。
  - [ ] 验证：`cd petgo_app && flutter build apk --debug` 成功（L0，云端可跑）。**不要**打 release / AAB 包。
- [x] T5 新建 `test/core/analytics/event_catalog_sync_test.dart`（AC: 3）
  - [x] **读 yaml**：路径 `../petgo-backend/src/main/resources/analytics/event-catalog.yaml`（测试以 `petgo_app/` 为工作目录）。文件不存在 → **测试失败**并提示「Story 1.2 未合入」（不要 skip：缺清单时放行就失去守门意义）。
  - [x] **不加 yaml 解析依赖**（`yaml` 只是传递依赖，直接 import 会触发 `depend_on_referenced_packages`）。按 Story 1.2 定的结构逐行解析：`events:` 下每个条目的首行形如 `  - name: <事件名>`（事件名可能带引号，生命周期事件含空格），属性行是 `- { name: ..., ... }` 的行内写法、缩进更深，不能混淆。**开工前先打开真实 yaml 核对缩进和写法**，按实际结构写解析，并在测试里断言解析出的事件数 > 150（防解析静默失败恒绿）。
  - [x] 同时读每个事件的 `status`（`ACTIVE` / `PLANNED` / `RETIRED`）。App 用到的事件名若在清单里是 `RETIRED`，测试失败（死埋点）；`PLANNED` 视为已登记。
  - [x] **从 `lib/` 提取 App 实际发出的事件名**（递归所有 `.dart`）：
    1. `Analytics.capture(` 后第一个参数是字符串字面量（允许换行和空白）：沿用现有守卫的正则 `Analytics\.capture\(\s*'([A-Za-z0-9_ ]+)'`（`test/analytics/v140_events_inventory_test.dart:185`，这里多放行空格以容纳生命周期事件）。
    2. 三元：`Analytics\.capture\(\s*[^,()]+?\?\s*'([^']+)'\s*:\s*'([^']+)'` 两侧都取（现有：`sku_picker_sheet.dart:69`、`follow_button.dart:265`）。
    3. 常量：第一个参数是标识符（如 `kKeepsakeCardGeneratedEvent`、`AnalyticsEvents.screenViewed`）时，在 `lib/` 里查找 `const String <名> = '<值>'` 或 `static const <名> = '<值>'` 的定义取值（`event_names.dart` 里的 `AnalyticsEvents` 与 `keepsake_card_style.dart` 的 `kKeepsakeCardGeneratedEvent` 两类都要能解析）。解析不到 → 测试失败并列出调用位置，提示「改用字面量或常量，或加白名单」。
    4. 门面派生：`Analytics.capturePurchase(` → `af_purchase`；`Analytics.captureSignupSucceeded(` → `signup_succeeded`；`Analytics.buttonTapped(` → `button_tapped`；`Analytics.screen(` 与路由观察者 → `screen_viewed`；`Analytics.identifyUser(` → `user_identified`；生命周期 → `Application Installed / Updated / Opened`。这些映射写成测试里的一张表，并断言门面源码里确实还在用这些名字（防门面改名后表过期）。
    5. `analytics.dart` 内部的裸调用 `capture('button_tapped', ...)` 也要覆盖（第 4 条已映射）。
  - [x] **白名单**：测试内一个 `const dynamicEventAllowlist = <String, String>{位置: 理由}`。注意：`'${tabName}_page'` / `'${tab.analyticsName}_page'` 是 `Analytics.screen()` 的**页面名**（2.4 后作为 `screen_viewed` 的 `screen` 属性），**不是事件名**，不进白名单；当前 `lib/` 中没有动态拼接的事件名，白名单初始为空。新增条目必须写理由。
  - [x] **失败输出**：列出缺项的事件名 + 首次出现的 `文件:行号`，并提示固定三步：「在 `event-catalog.yaml` 登记 → 加 Dart 事件常量 → 本测试变绿」。
  - [x] 自检用例：提取结果包含 `toko_add_to_cart_tapped`、`toko_buy_now_tapped`、`user_followed`、`user_unfollowed`、`keepsake_card_generated`、`af_purchase`、`screen_viewed`、`Application Opened`（证明三元、常量、派生三条路径都在工作）。
- [x] T6 处理随 PostHog 失效的旧测试 / 注释（AC: 1）
  - [x] `grep -rn "Posthog\|posthogProperties\|posthogMoneyKeys\|personProperties\|\$screen" test` 应为空（2.1、2.2、2.4 已处理对应测试；这里兜底）。只在描述性注释里提到 PostHog 的测试不用改。
  - [x] `test/analytics/v140_gift_events_test.dart:91` 测试标题「送礼事件只发 PostHog」改为「只进自建埋点」（只改字符串）。
- [x] T7 L0 收尾：`flutter analyze` 零问题；`flutter test`（全量，确认移除依赖无回归）；不整文件 `dart format`。
- [ ] T8 L1（本地，模拟器）（AC: 4）
  - [ ] 静态：`flutter pub deps | grep -i posthog` 为空；构建出的 profile APK `unzip -p app-profile.apk 'classes*.dex' | strings | grep -ci posthog` 为 0。
  - [ ] 动态：模拟器用 Google APIs 镜像，`adb root` 后 `adb shell tcpdump -i any -s 0 -w /sdcard/cap.pcap port 53 or port 443` 抓一遍主流程（冷启动 → 浏览 Diary / Social / Toko → 进商品详情 → 登录 → 切后台再回来），`adb pull` 后用 `tcpdump -r cap.pcap -nn | grep -i posthog` 或 Wireshark 过滤 `dns.qry.name contains "posthog"`，应无记录；同时能看到发往 stag 后端 `/api/v1/analytics/batch` 的请求（HTTPS 只能看到域名，确认目标域名是 stag 即可）。
  - [ ] 结果记进 Completion Notes。

## Dev Notes

### 前置与范围

- 2.1–2.5 完成后，`Posthog()` 只剩 `init()` 一处；本 story 收尾删依赖、删构建覆盖项、补比对测试。
- **不改** `petgo-backend/` 下任何文件，包括 `event-catalog.yaml`。Story 1.2 约定「Epic 2 / 3 落地时把预登记事件的 `status` 由 `PLANNED` 改 `ACTIVE` 并补齐属性」，但本 epic 定为「只改 App」。若比对测试发现 Epic 2 新增的属性（如 2.3 的 `version` / `build` / `previous_version` / `previous_build`、2.5 的 context 无需登记）在清单里缺失，**不在本 story 改 yaml**，在 Completion Notes 列出缺项，交后端随下一次部署补登记（未登记属性会被明细层过滤，D-4）。
- **不碰** `scripts/ops/daily-health-check.py`、`docs/reference/analytics-posthog-tracking.md` 等运维脚本和文档里的 PostHog 内容（后端 / 运维范围，另行处理）。后端的 `PostHogAnalyticsClient` 由 Story 1.9 删除。

### 现有代码（已核实，2026-11-12）

- `pubspec.yaml`：`posthog_flutter: ^5.33.0`（lock 中 5.33.0），上方注释两行。
- `lib/core/analytics/analytics.dart`：`String.fromEnvironment('POSTHOG_KEY', defaultValue: 'phc_…')`、`String.fromEnvironment('POSTHOG_HOST', defaultValue: 'https://eu.i.posthog.com')`；`init()` 里 `PostHogConfig(_apiKey)..host..debug..captureApplicationLifecycleEvents = true..sessionReplay = false..flushAt` + `Posthog().setup(config).timeout(3s)`。
- 仓库内**没有**任何构建脚本或 CI 传 `--dart-define=POSTHOG_KEY/HOST`（已 grep `scripts/`、`.github/`、`petgo_app/`），删除常量不影响出包流程。
- `android/build.gradle.kts` 42–58 行：只对 `project.name == "posthog_flutter"` 生效的 Kotlin 2.0 / compileSdk 36 修正。
- `android/app/build.gradle.kts` 56–58 行：`compileSdk = maxOf(flutter.compileSdkVersion, 36)`，注释归因 posthog_flutter。
- 现有事件名守卫：`test/analytics/v112_events_test.dart`（`eventNamesInSource()`、命名规范、`_page` 结尾）、`test/analytics/v140_events_inventory_test.dart`（`_eventNamesInSource()`）。新测试与它们并存，正则风格保持一致；不要改旧守卫的行为。
- 事件名常量的现有用例：`lib/features/keepsake/presentation/keepsake_card_style.dart:11` `const String kKeepsakeCardGeneratedEvent = 'keepsake_card_generated';`，被 4 个分享卡片文件以 `Analytics.capture(kKeepsakeCardGeneratedEvent, ...)` 调用。

### 必须遵守

- 比对测试**只认机器提取的结果**，不手抄事件清单（现有守卫的教训：手抄清单会随代码漂移，测试恒绿、看板恒空）。
- 缺 yaml 时测试失败，不 skip。
- 不加新依赖；不整文件 `dart format`；不改 `kApiBaseUrl` 默认值。
- 不打 release / AAB 包（只在 T4 跑 `flutter build apk --debug` 验证构建、在 T8 本地出 profile 包验收）。

### Project Structure Notes

新增：`test/core/analytics/event_catalog_sync_test.dart`（架构目录树已列出）。
修改：`pubspec.yaml`、`pubspec.lock`、`lib/core/analytics/analytics.dart`、`lib/main.dart`（注释）、`android/build.gradle.kts`、`android/app/build.gradle.kts`（注释）、`test/analytics/v140_gift_events_test.dart`（标题）。

### References

- [Source: _bmad-output/planning-artifacts/analytics-pipeline/epics.md#Story 2.6、#Story 1.2]
- [Source: _bmad-output/planning-artifacts/analytics-pipeline/prd.md#FR-1（新版不再向 PostHog 发请求）]
- [Source: _bmad-output/planning-artifacts/analytics-pipeline/architecture.md#一、数据架构 D-7、#六、过渡与收尾 · PostHog 清理、#实现规范 · 4. 处理规则（新增事件的固定三步）、#目录结构]
- [Source: _bmad-output/implementation-artifacts/analytics-pipeline/1-2-事件清单盘点与口径骨架.md（yaml 结构、预登记事件、status 字段）]
- [Source: petgo_app/pubspec.yaml、lib/core/analytics/analytics.dart、lib/main.dart、android/build.gradle.kts、android/app/build.gradle.kts、test/analytics/v140_events_inventory_test.dart]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5（claude-opus-5-5），云端 headless session。

### Debug Log References

- `cd petgo_app && flutter test test/core/analytics/event_catalog_sync_test.dart` → 7 passed。另做反向探针（临时在 `lib/` 放未登记字面量、三元一侧未登记、`'${x}_evt'` 动态拼接、裸变量首参数各一处）→ 缺项与无法解析两条用例均按预期失败并列出 `文件:行号`，探针文件已删除。
- `cd petgo_app && flutter analyze` → 0 error / 0 warning（800 条均为 info；沙箱 Flutter SDK 为 3.47，多出的 info 是 SDK 自身对 `package:flutter/material.dart` 的 deprecated 提示，与本 story 无关；新文件无 info）。
- `cd petgo_app && flutter test`（全量）→ **5073 passed / 3 skipped / 0 failed**（All tests passed）。
- `cd petgo_app && flutter build apk --debug` → **未能执行**：沙箱无 Android SDK（`No Android SDK found`），T4 构建验证留本地。
- `grep -rn "Posthog\|posthog_flutter\|POSTHOG_" lib` → 仅剩 `app_router.dart:486`、`analytics_route_observer.dart:5/7/17` 四处注释，均为「取代 PosthogObserver / 逐条复刻其触发时机」的历史背景说明（Story 2.4 写入），按 T1 允许保留。
- `grep -rn "Posthog\|posthogProperties\|posthogMoneyKeys\|personProperties\|\$screen" test` → 只剩断言「不再含 `Posthog()` / `posthogMoneyKeys`」的守卫（`self_hosted_sink_test.dart`、`late_landing_correction_test.dart`）和描述性注释，以及 `mini_profile_test.dart` 的 `$screenWidth` 误命中，均无需改。

### Completion Notes List

- **L1/L2 待本地验收**：
  - T4：本地 `cd petgo_app && flutter build apk --debug` 成功（云端沙箱无 Android SDK，未跑）——重点确认删掉 `android/build.gradle.kts` 的 posthog_flutter 子工程修正块后构建正常。
  - T8 / AC4（L1）：`flutter pub deps | grep -i posthog` 为空；profile APK `classes*.dex` 中 `posthog` 计数为 0；模拟器 tcpdump 抓主流程无 posthog.com 的 DNS / 443 请求，且能看到发往 stag 后端的 `/api/v1/analytics/batch`。
  - iOS：下次本地 `pod install` 后 `Podfile.lock` 自然去掉 PostHog pod（不在 git 中）。
- AC1：`analytics.dart` 删除 posthog import、`POSTHOG_KEY` / `POSTHOG_HOST` 常量与 `_setupPosthog()`；`init()` 现为「sink 启动（含 2.5 环境采集）→ 生命周期事件」两步串行（原先就是 `then` 串行，去掉的只是并行的 PostHog 分支）。类注释改为自建上报约束；`distinctIdFor`、AppsFlyer 白名单注释里「送 PostHog」的说法改准确。`signup_succeeded` 注释中 `$set_once` / person property 的长段描述未动（属业务口径说明，改写超出「顺手改准确」的范围；`$set_once` 在自建管线中如何处理由后端口径决定），供后续确认。
- 结转清理：`lib/app.dart` 两处注释（「遵守 PostHog 换人先 reset 规约」「role 落成 person property」）改为自建口径；`lib/main.dart` 长注释换成三行；`lib/core/analytics/appsflyer_client.dart` 注释里「与 `POSTHOG_KEY` 同款」删除（为满足 T1 的 grep `POSTHOG_` 为空）。
- AC2：`android/build.gradle.kts` 中只作用于 `posthog_flutter` 的 `subprojects` 修正块（含 3 行注释）删除；`android/app/build.gradle.kts` 的 `compileSdk = maxOf(flutter.compileSdkVersion, 36)` **保留**作保底（当前 Flutter 默认即 36，结果不变；防日后默认值变化时 rive / geolocator / 地图等插件编译失败），只改注释说明原因已非 PostHog。
- **pubspec.lock**：沙箱 Flutter SDK 是 3.47（项目基线 3.44），`flutter pub get` 会连带改动 32 个条目并把 `sdks.flutter` 抬到 `>=3.47.0`（会让本地 3.44 无法解析）。故提交的 lock 是 **HEAD 版本仅删除 `posthog_flutter` 条目**（8 行），无其他条目升降级；posthog_flutter 5.33.0 的依赖（flutter / flutter_web_plugins / meta / plugin_platform_interface）均被其他包共用，无需连带删除。本地 3.44 下 `flutter pub get` 应只确认该结果；如有差异以本地为准。
- **macOS `GeneratedPluginRegistrant.swift`**：属本 story 合理变化——只删 `import posthog_flutter` 与 `PosthogFlutterPlugin.register(...)` 两行。沙箱 SDK 另外造成的 firebase 两行顺序调换、以及 `windows/flutter/*` 的 share_plus 顺序调换均为 SDK 版本噪音，已还原为 HEAD 内容，不属于本 story。
- AC3：新建 `test/core/analytics/event_catalog_sync_test.dart`。逐行解析 yaml（228 个事件，断言 >150、每个事件都有 status、属性行不被当成事件）；从 `lib/` 提取：字面量、三元两侧、常量（顶层 `const String` 与类内 `static const`，支持 `Class.name` 限定）、门面派生表（af_purchase / signup_succeeded / button_tapped / screen_viewed / user_identified / 生命周期三事件，并断言门面源码仍用这些名字、`lib/` 仍有调用）。整行注释先剔除（注释里的示例调用不计、行号不变）。无法解析的首参数 → 失败并要求改字面量/常量或进白名单；白名单键为 `<lib 相对路径>::<首参数源码>`（比行号稳），初始为空，另有用例防过期条目。RETIRED 事件被使用 → 失败。当前 App 发出的事件名全部已登记，无缺项。
- 清单状态：`Application Installed / Updated / Opened`、`screen_viewed`、`user_identified` 在 `event-catalog.yaml` 仍为 `PLANNED`（另有 `push_opened`、`toko_cart_item_removed`、`toko_cart_quantity_changed` 亦为 PLANNED）。本 story 定为只改 App、不改 yaml，故未改；**提示后端随下一次部署把 Epic 2 已落地事件改为 ACTIVE**。相关属性（2.3 的 `version` / `build` / `previous_version` / `previous_build`、`screen_viewed.screen`、`user_identified.role`）已在清单登记，无缺项。
- T6：`test/analytics/v140_gift_events_test.dart` 测试标题改为「送礼事件只进自建埋点」。
- 未整文件 `dart format`；未加依赖；未动 `petgo-backend/`。

### Review Findings（代码复审 2026-11-12）

- **R1 · MEDIUM · CONFIRMED · 已修**：比对测试只扫 `Analytics.capture(`，门面 `analytics.dart` 内部的裸 `capture(...)`（`screen` / `buttonTapped` / `identifyUser`）只靠手写 `_derived` 表覆盖；日后门面新增方法用裸 `capture('新事件')` 会逃过比对（违背「只认机器提取」）。修复：对 `lib/core/analytics/analytics.dart` 额外扫描裸 `capture(` 调用（跳过定义行），走同一套字面量 / 三元 / 常量解析。反向探针（临时加 `capture('zz_probe_evt')`）已验证失败并列出 `analytics.dart:312`，探针已撤。
- **R2 · LOW · PLAUSIBLE · 未修**：`ios/Runner.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` **在 git 中**（与 T2「iOS 侧均不在 git」的说法不符），仍钉着 `posthog-ios 3.67.0`。iOS 走 Flutter SwiftPM 集成，多余 pin 不会导致构建失败，下次本地 Xcode / `flutter build ios` 解析时会自动剪掉并重算 `originHash`；手改生成文件有风险，留本地重新解析后随提交带上。
- **核实无问题（REJECTED）**：`lib/` 无 posthog import / 运行时调用 / `POSTHOG_*` dart-define（`dart_defines.json`、`scripts/`、`.github/` 均无）；Android 原生侧（Manifest / gradle）无其他 posthog 残留；删除的 `android/build.gradle.kts` 块以 `project.name == "posthog_flutter"` 守卫，不影响其他插件（tiktok_events_sdk 修正块保留）；yaml 逐行解析与 1.2 格式约定一致（带引号的生命周期事件名、属性行不误判、228 个事件）、RETIRED 判失败、白名单键与过期检查均成立；三元 / 常量 / 门面派生路径有自检用例。
- 复审后 L0：`flutter analyze` 0 error / 0 warning（800 info 同前，沙箱 SDK 3.47 噪音）；`flutter test test/core/analytics test/analytics` → 258 passed；比对测试 7 passed。复审期间 flutter 命令造成的 `windows/**`、macOS firebase 顺序、`pubspec.lock` 改写已还原（lock 仍为「HEAD 仅删 posthog_flutter 条目」）。

### File List

- 新增：`petgo_app/test/core/analytics/event_catalog_sync_test.dart`
- 修改：`petgo_app/pubspec.yaml`
- 修改：`petgo_app/pubspec.lock`（仅删 posthog_flutter 条目）
- 修改：`petgo_app/macos/Flutter/GeneratedPluginRegistrant.swift`（仅删 posthog 两行）
- 修改：`petgo_app/lib/core/analytics/analytics.dart`
- 修改：`petgo_app/lib/core/analytics/appsflyer_client.dart`（注释）
- 修改：`petgo_app/lib/main.dart`（注释）
- 修改：`petgo_app/lib/app.dart`（注释）
- 修改：`petgo_app/android/build.gradle.kts`
- 修改：`petgo_app/android/app/build.gradle.kts`（注释）
- 修改：`petgo_app/test/analytics/v140_gift_events_test.dart`（测试标题）
- 修改：`_bmad-output/implementation-artifacts/analytics-pipeline/2-6-移除-posthog-与事件名比对.md`
- 修改：`_bmad-output/implementation-artifacts/analytics-pipeline/sprint-status-analytics-pipeline.yaml`

### Change Log

- 2026-11-12：实现 Story 2.6——移除 posthog_flutter 依赖与 init 残留、清理相关注释与 Android 构建覆盖项，新增事件名比对测试；状态 → review。
- 2026-11-12：代码复审——比对测试补扫门面内部裸 `capture(` 调用（R1）；Package.resolved 残留 pin 留本地处理（R2）。状态保持 review。
