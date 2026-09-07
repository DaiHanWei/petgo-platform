import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../notify/domain/push_permission_prompt.dart';

/// 触发点 5：兽医切换为「在线」时若系统通知关闭 → 提醒去开（Story 8.3 / FR-85）。
///
/// 🔴 **本文件刻意不复用 [PushPermissionPrompt] 的标记位逻辑**（AD-14 Rule 4）。
/// 三处差异，抄用户侧那套就全错：
///
/// | | 用户侧三点（Story 8.2） | 兽医侧（本文件） |
/// |---|---|---|
/// | 次数 | 每点**各一次**（有标记位） | **不限次数**（**无标记位**） |
/// | 硬拦截 | 不适用 | **绝不硬拦截**（不因通知关闭禁止上线） |
/// | 实现 | 三键模型 | **单独实现**，不复用「各一次」逻辑 |
///
/// **为什么兽医不限次数**：漏单是真金白银（错过问诊 = 没有收入），
/// 而「切换为在线」这个动作本身就说明他此刻**想接单** —— 每次都提醒是成比例的。
/// 用户侧反复索取只会招致反感，所以那边严格各一次。
///
/// 🛡 **本文件不得写入任何 prefs 键。** 「顺手加个键防止提醒太频繁」是极自然的冲动，
/// 而它正好违规 —— 有一条测试专门守「切换多次后 SharedPreferences 无新增键」。
class VetPushPermissionPrompt {
  VetPushPermissionPrompt._();

  /// 埋点 `trigger_point` 取值（埋点清单词表）。
  static const triggerPointValue = 'vet_online';

  /// 展示引导并返回用户响应。由兽医端 UI 注册（它才有 `BuildContext`）。
  ///
  /// 未注册时（例如非 UI 场景调用）**只上报曝光、不展示** —— 绝不因此抛错。
  static Future<PushPromptResult> Function()? showGuideHook;

  /// 权限读取覆盖点（测试注入）。生产走 `permission_handler`，
  /// 与 `PushPermissionBootstrap` / `PushPermissionStateReporter` 同一口径。
  @visibleForTesting
  static Future<bool> Function()? isGrantedOverride;

  /// 兽医**已经成功切为在线之后**调用。
  ///
  /// 🛡 **调用点必须在切换成功之后**，且本方法**吞掉一切异常** ——
  /// 提示失败绝不能让兽医上不了线（AC2 营收红线）。
  ///
  /// 🛡 无标记位：每次调用都判当前权限状态，关着就提醒。
  static Future<void> afterGoingOnline() async {
    try {
      final granted = await (isGrantedOverride ?? _isGranted)();
      if (granted) return; // 前置闸门：已经开着的人不打扰（AD-14 Rule 5）
      // 埋点不 await：它不该延后任何东西，更不该影响在线态。
      unawaited(PushPermissionPrompt.reportShownRaw(triggerPointValue));
      final hook = showGuideHook;
      if (hook == null) return; // 没有 UI 注册 → 只留下曝光记录
      final result = await hook();
      unawaited(PushPermissionPrompt.reportRespondedRaw(triggerPointValue, result));
    } catch (e) {
      debugPrint('[VetPushPrompt] failed: $e');
    }
  }

  static Future<bool> _isGranted() async =>
      (await ph.Permission.notification.status).isGranted;
}
