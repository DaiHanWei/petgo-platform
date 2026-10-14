---
title: "V1.3.4 增补：场所签到盖章动画（Rive）"
status: approved
created: 2026-10-14
version: v1.3.4
---

# V1.3.4 增补：场所签到盖章动画（Rive）

## 0. 背景与目的

场所签到成功后目前只有一个 0.36 秒的小缩放落章，「拿到新章」的仪式感弱。设计侧已交付一段专门的 Rive 盖章动效（猫爪按章 → 落章 → 礼花，约 2.4 秒），本增补把它接进签到流程。

- **随 V1.3.4 上线**（2026-10-14 拍板），排在「点赞人列表」之后实现。
- 素材来源：`~/Downloads/stamp-paws/dist/`（交付包）。只用 `tailtopia_stamp.riv`（签名版，82.9KB，SHA-256 `34d32f5880f3b7ad6965cb5451ed8e9ea9ad5f2ed936f56bdf9dae8fcf700b91`）与 `flutter/stamp_ceremony.dart`（参考实现）。接入说明 `dist/INTEGRATION.md`、分镜 `dist/storyboard.png`。

## 1. 目标 / 不做 / 反目标

**目标**：第一次在某场所签到（拿到新章）时，签到成功后立刻全屏播放盖章动画，播完进入打卡成功页。

**不做**
- 同一场所再次签到（`isNewStamp=false`）不播，仍是现有「×n」角标跳动。
- 不改护照页、「Lihat Paspor」后的整页落章页（B4）。
- 不做音效（交付包无音频；有音频后再加）。
- 不改后端。

**反目标**
- 动画加载 / 章面图下载失败导致用户卡住或白屏。
- 动画挡住签到结果（播放期间无法离开且无兜底）。
- 引入依赖导致现有 Android / iOS 构建失败。

## 2. 产品规则（已拍板，2026-10-14）

- **触发**：点「Check in」→ 签到接口成功且 `isNewStamp == true` → 全屏播放盖章动画 → 结束后进入打卡成功页。`isNewStamp == false` → 与现状一致，直接进成功页。
- **成功页**：播过动画进入时，**不再播**成功页原有的 0.36 秒小落章（避免重复）；其余内容不变。未播动画（再访、或动画失败兜底）时成功页保持现状。
- **章面**：签到结果的 `stampImageUrl`（512×512 透明 PNG）；为空时用按场所类型的默认章 `assets/place_stamp/<type>.png`（映射 `pet_passport/presentation/default_stamp_assets.dart`）。章面图先准备好再开始播放。
- **结尾过渡**（2026-10-14 拍板 A）：动画上**不叠任何文字**；收到 `complete` 后最终帧停留 **0.4 秒**，再以约 **0.3 秒淡出**进入成功页。
- **承载方式**（我的决定）：签到成功后 push 一个全屏动画页，结束后 `pushReplacement` 成功页——成功页的「第一次打卡」里程碑当场庆祝必然在动画之后触发；返回栈：成功页返回 → 场所详情。
- **成功页的章**直接显示，不做从动画位置过渡的共享元素动效（我的决定）。
- **背景**：全屏米白（`AppColors.cream`），动画画板透明叠在上面。
- **触感**：落章时刻（Rive 事件 `stampImpact`，约 1.02s）`HapticFeedback.mediumImpact()`。
- **不可跳过**：播放中点击无效；系统返回键在播放期间不生效（约 2.4s）。系统开启「减少动态效果」时直接显示最终帧并立即结束。
- **兜底**：章面图下载失败 / 超时（2 秒）或 Rive 加载失败（`onError`）→ 不播，直接进成功页（成功页按「未播动画」处理，保留原小落章）。动画开始后若 4 秒内没收到 `complete` 事件，也强制结束进成功页。

## 3. 技术约束

- 依赖：`rive: ^0.14.11`（带 `rive_native` 原生库；首次构建需联网下载原生库；Android minSdk ≥ 21、compileSdk ≥ 35 —— App 现值 compileSdk 36、iOS 部署目标 15.0，均满足）。`main()` 中 `runApp` 前 `await RiveNative.init()`；初始化失败不得阻断启动（失败即全局视为动画不可用、走兜底）。
- 素材放 `petgo_app/assets/rive/tailtopia_stamp.riv` 并在 pubspec 声明；组件参考 `stamp_ceremony.dart` 改写进 `features/place/presentation/widgets/`，按名字取画板 `StampCeremony` 与状态机 `Ceremony SM`，View Model 属性 `stampImage` / `start` / `skip` / `confettiSeed`，事件 `stampImpact` / `inkDone` / `complete`。外层 `IgnorePointer`。
- 下载章面：复用 App 现有图片 / 网络缓存设施取字节，不新增网络库。
- 交付包注明 **iOS 与真机未实测**：实现后必须在 iOS 模拟器与 Android 模拟器上各跑一遍，并在 Completion Notes 记录。
- 不在测试环境依赖原生库：widget 测试中对 Rive 组件做可替换的注入（失败 / 完成回调可模拟）。

## 4. 留给 builder

- 章面字节下载：复用 App 现有的 dio 网络层（不新增依赖）。
- 验收时核对后台上传的专属章是否符合 512×512 透明、留白 ≤15px。
- `confettiSeed` 取 0（每次不同）。
- 埋点：建议 `place_stamp_ceremony_played {result: completed|skipped_reduce_motion|failed}`。

## 5. 验收要点

1. 新章签到：点 Check in → 全屏动画（爪子按章、落章震动、礼花）→ 自动进入成功页；成功页不再出现小落章。
2. 再访签到：不播动画，与现状一致。
3. 章面为空用默认章；章面下载失败 / Rive 失败 → 直接进成功页，不卡住。
4. 开启系统「减少动态效果」→ 只显示最终帧并立即进入成功页。
5. 返回栈：成功页返回 → 场所详情（不回到动画）。
6. Android 与 iOS 模拟器各验一遍；`flutter build apk --debug` 与 iOS 构建通过。
