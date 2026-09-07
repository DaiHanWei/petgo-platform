import 'package:flutter/material.dart';

import '../../../core/storage/prefs.dart';
import '../data/push_permission_providers.dart';
import '../domain/push_permission_prompt.dart';
import 'push_permission_sheet.dart';

/// FR-85 触发点 1 / 2 的引导流（Story 8.2）。
///
/// **形态只有一种**：App 内说明抽屉 → 用户点「开启通知」→ **跳系统设置页**。
/// 🔴 **不存在「唤起原生弹窗」这一分支**（AD-14 Rule 6）——
/// 那个机会在首启就被第二代消耗了，再调一次系统 API 不会有任何 UI。
///
/// 复用既有的 P-09 说明抽屉（`showPushPermissionSheet`）：它的三条好处说明与
/// 「开启通知 / 不，谢谢」两个按钮在新模型下**语义依然成立** ——
/// 点「开启通知」现在通向系统设置，而不是系统弹窗。**零新增文案。**
///
/// 触发点 4（通知中心引导条）形态不同（页内顶部条，不是抽屉），故不走本函数，
/// 但两者共用 [PushPermissionPrompt] 的判定与埋点。
Future<void> maybeShowPushPermissionGuide(
  BuildContext context,
  PushTriggerPoint point, {
  AppPrefs? prefs,
  Future<bool> Function()? isGranted,
  Future<bool> Function()? openSettings,
}) async {
  final p = prefs ?? await AppPrefs.create();
  await PushPermissionPrompt.runForTrigger(
    point,
    prefs: p,
    isGranted: isGranted,
    showGuide: () async {
      if (!context.mounted) return PushPromptResult.dismissed;
      final wantsToEnable = await showPushPermissionSheet(context);
      if (!wantsToEnable) return PushPromptResult.dismissed;
      // ⚠️ 跳走即上报 settingsOpened —— 它**只表示跳走了，不代表真的开了**。
      //    净授权率看 E-21（冷启动快照，Story 8.1）。两者别混着解读。
      await (openSettings ?? openPushSettings)();
      return PushPromptResult.settingsOpened;
    },
  );
}
