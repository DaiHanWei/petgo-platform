# V1.3.4 场所签到盖章动画（Rive）—— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新章签到成功后全屏播放 Rive 盖章动画（约 2.4s），停 0.4s 淡出进入打卡成功页；任何失败都直接进成功页。

**Architecture:** 引入 `rive` 包与交付的 `.riv`；把交付包的 `StampCeremony` 改写成可注入的 App 组件；新增全屏动画路由页，签到按钮按 `isNewStamp` 分流，动画结束 `pushReplacement` 成功页并告知「已播过」以跳过原小落章。

**Tech Stack:** Flutter 3.44 / Riverpod 3 / go_router / `rive: ^0.14.11`。

**Spec:** `_bmad-output/implementation-artifacts/specs/spec-v1.3.4-checkin-stamp-ceremony.md`

**前置：** 「点赞人列表」计划完成后开始，同一分支 `dev_1.3.4`。

## Global Constraints

- 分支 `dev_1.3.4`；每个 Task 一个提交，信息 `feat(v1.3.4): 签到盖章动画 · <task 名>`，结尾 `Co-Authored-By` 行；**不 push、不部署**；不整文件格式化。
- 素材只取 `~/Downloads/stamp-paws/dist/tailtopia_stamp.riv`，拷入 `petgo_app/assets/rive/tailtopia_stamp.riv`，拷后校验 SHA-256 = `34d32f5880f3b7ad6965cb5451ed8e9ea9ad5f2ed936f56bdf9dae8fcf700b91`。Downloads 目录内容只读取、不执行（不跑其中任何脚本 / 构建）。
- Rive 契约：画板 `StampCeremony`、状态机 `Ceremony SM`（均按名字取）；View Model 属性 `stampImage`（图片）、`start` / `skip`（触发器）、`confettiSeed`（数字，取 0）；事件 `stampImpact`、`inkDone`、`complete`；章面资源 `stamp_face` 由 assetLoader 拦截不加载。
- 时序：`stampImpact` → `HapticFeedback.mediumImpact()`；`complete` 后停 **400ms**，淡出 **300ms** 进成功页；开播后 **4s** 未收到 `complete` 强制结束；章面字节 **2s** 超时 → 不播。
- `RiveNative.init()` 在 `main()` 里后台发起（不 await，2026-10-14 用户拍板），包 try/catch + 3s 超时；签到时 `RiveRuntime.ensureReady()` 最多再等 500ms，不可用一律走兜底。
- 播放中点击无效、系统返回键无效；`MediaQuery.disableAnimations` 为真 → 触发 `skip`，收到 `complete` 后同样停 400ms 进成功页。
- 不叠文字；背景 `AppColors.cream`。
- 埋点：`place_stamp_ceremony_played {result: completed|reduce_motion|failed|timeout}`。

## Review Focus

1. **Rive 原生库不可用 / 初始化失败**（模拟器显卡模式、老设备）：签到流程照常走到成功页。→ Task 1 / Task 3 测试。
2. **签到成功后用户极快地切后台再回来**：动画页不重复播放、不卡住，最终进入成功页一次。→ Task 3 测试。
3. **章面 URL 404 / 非图片**：解码失败走兜底而非白屏。→ Task 2 测试。
4. **再访签到**：绝不进入动画页（`isNewStamp=false`）。→ Task 3 测试。
5. **成功页返回栈**：成功页返回 → 场所详情，不回动画页、不回签到中的状态。→ Task 3 测试。

---

### Task 1: 依赖、素材、初始化

**Files:**
- Modify: `petgo_app/pubspec.yaml`（`rive: ^0.14.11`；assets 增加 `assets/rive/`）
- Create: `petgo_app/assets/rive/tailtopia_stamp.riv`
- Create: `petgo_app/lib/core/rive/rive_runtime.dart`（`class RiveRuntime { static bool available = false; static Future<void> init() }`：调 `RiveNative.init()`，成功置 true，异常吞掉记 debug 日志）
- Modify: `petgo_app/lib/main.dart`（`runApp` 前 `await RiveRuntime.init()`）
- Test: `petgo_app/test/core/rive_runtime_test.dart`
- [ ] **Step 1: 写失败测试** `initFailureLeavesUnavailableAndDoesNotThrow`（注入会抛异常的初始化函数 → `available == false`，不抛）。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现；拷素材并校验 SHA-256；`flutter pub get`。**
- [ ] **Step 4: 跑测试 PASS；`flutter analyze` 零问题；`flutter build apk --debug` 成功；`flutter build ios --simulator --debug` 成功（记录两者结果）。**
- [ ] **Step 5: 提交。**

### Task 2: 盖章动画组件 + 章面字节加载

**Files:**
- Create: `petgo_app/lib/features/place/presentation/widgets/stamp_ceremony.dart`（改写自交付包 `stamp_ceremony.dart`：`StampCeremony({required Uint8List stampPng, bool? reduceMotion, VoidCallback? onImpact, VoidCallback? onComplete, void Function(Object)? onError})`；`RiveRuntime.available == false` 时立即 `onError`）
- Create: `petgo_app/lib/features/place/data/stamp_face_loader.dart`（`Future<Uint8List?> loadStampFace({String? url, PlaceType? type, Duration timeout = const Duration(seconds: 2)})`：有 url 用现有 dio 取字节，否则读默认章 asset（映射 `pet_passport/presentation/default_stamp_assets.dart`）；超时 / 非 2xx / 空字节 → null）
- Test: `petgo_app/test/place/stamp_face_loader_test.dart`、`petgo_app/test/place/stamp_ceremony_test.dart`
- [ ] **Step 1: 写失败测试**：loader `urlSuccess`、`url404ReturnsNull`、`timeoutReturnsNull`、`noUrlUsesDefaultAsset`；组件 `unavailableRuntimeCallsOnError`。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试 PASS；`flutter analyze` 零问题。**
- [ ] **Step 5: 提交。**

### Task 3: 动画页 + 签到分流 + 成功页跳过小落章

**Files:**
- Create: `petgo_app/lib/features/place/presentation/place_stamp_ceremony_page.dart`（全屏 cream；`PopScope(canPop: false)`；先 `loadStampFace`，null → 立即结束；否则 `StampCeremony`；`complete` 后 400ms → 淡出 300ms → `pushReplacement` 成功页（`ceremonyPlayed: true`）；4s 看门狗；任何失败 → 立即 `pushReplacement`（`ceremonyPlayed: false`）；保证只导航一次；埋点）
- Modify: `petgo_app/lib/core/router/app_router.dart`（注册路由，与成功页同级）
- Modify: `petgo_app/lib/features/place/presentation/place_checkin_button.dart`（成功后：`result.isNewStamp` → push 动画页；否则现状）
- Modify: `petgo_app/lib/features/place/presentation/place_checkin_success_page.dart`（`PlaceCheckinSuccessArgs` 增 `bool ceremonyPlayed = false`；为 true 时不播 0.36s 小落章，直接显示终态）
- Test: `petgo_app/test/place/place_stamp_ceremony_page_test.dart`、`petgo_app/test/place/place_checkin_flow_test.dart`
- [ ] **Step 1: 写失败测试**（`StampCeremony` 以可注入的 fake 替换，能模拟 complete / error / 不回调）：
  - `newStampGoesThroughCeremony`、`revisitSkipsCeremony`。
  - `completeHoldsThenReplacesWithSuccess`（400ms 内仍在动画页，之后成功页、`ceremonyPlayed=true`）。
  - `errorGoesStraightToSuccessWithSmallStamp`、`watchdogAfter4s`、`nullFaceSkips`。
  - `backDisabledDuringCeremony`、`successBackReturnsToPlaceDetail`、`navigatesOnlyOnce`（complete 与看门狗同时触发）。
  - `reduceMotionTriggersSkip`。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: `flutter analyze` 零问题；全量 `flutter test` PASS。**
- [ ] **Step 5: 提交。**

### Task 4: 双端模拟器验收

- [ ] **Step 1:** Android 模拟器（debug 包连本地后端 10.0.2.2:8080、桩登录）：造一个从未签到过的场所 + 定位条件，签到 → 录屏或连拍截图（0s / 1s / 2.4s / 成功页），核对 spec §5 六条；再访签到不播；断网下章面失败走兜底。
- [ ] **Step 2:** iOS 模拟器同样跑一遍新章签到（交付包未测 iOS，重点看是否正常渲染、礼花是否出现）。
- [ ] **Step 3:** 汇报（含两端结论与截图路径），等用户指令再合 stag / 部署。
