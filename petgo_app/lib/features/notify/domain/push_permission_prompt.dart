import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../../core/analytics/analytics.dart';
import '../../../core/storage/prefs.dart';

/// FR-85 的用户侧触发点（Story 8.2）。
///
/// **订正后 FR-85 实际做的事**：在几个时刻把关着通知的人**引去系统设置页**。
/// 🔴 **不存在「唤起原生弹窗」这一分支** —— 那个机会在首启就被第二代
/// （`PushPermissionBootstrap`，2026-08-07 上线）消耗掉了，PRD §2.8 里那段分流整段作废。
/// 实现一律以架构 delta **AD-14** 为准，不看 PRD 原文。
///
/// **本类只管用户侧三点。** 兽医端触发点 5 归 Story 8.3，且模型完全不同
/// （不限次数、无标记位、不做硬拦截）—— **不要复用本类的标记位逻辑**。
enum PushTriggerPoint {
  /// 触发点 1：首次问诊完成后。
  firstConsult,

  /// 触发点 2：建档完成后。
  profileCreated,

  /// 触发点 4：打开通知中心。
  ///
  /// ⚠️ **触发点 3 不存在** —— PRD 编号如此（1/2/4/5），不是漏了一个。
  notificationCenter,
}

/// 用户对引导的响应。取值须与埋点清单的 `result` 词表一致。
enum PushPromptResult {
  granted,
  denied,

  /// ⚠️ **只表示跳走了，不代表真的开了。** 净授权率看 E-21
  /// （`push_permission_state_reported`，Story 8.1 已交付）。
  settingsOpened,
  dismissed,
}

class PushPermissionPrompt {
  PushPermissionPrompt._();

  /// 三个触发点各自的一次性标记键。
  ///
  /// 🛡 **AD-14 Rule 2：四键物理隔离** —— 这三个 + 手机号软引导（Story 7.2）那一个，
  /// **禁止任何两者共用**。混用一个键 = 一个触发点用掉全部机会，
  /// FR-85 直接退化成「只提醒一次」。
  ///
  /// 🛡 **AD-14 Rule 3：绝不读写既有 `petgo.push_permission_asked`** ——
  /// 那个键归第二代「首启即申请」，且仍供既有「我的」页被动引导使用。
  /// 本类不迁移、不删除、不读它。
  /// ⚠️ 键的**字面量集中在 [AppPrefs]**（仓库约定：prefs 键全部在 AppPrefs 静态常量里），
  /// 这里只做「触发点 → 键」的映射。
  static const _keys = <PushTriggerPoint, String>{
    PushTriggerPoint.firstConsult: AppPrefs.kPushPromptFirstConsult,
    PushTriggerPoint.profileCreated: AppPrefs.kPushPromptProfileCreated,
    PushTriggerPoint.notificationCenter: AppPrefs.kPushPromptNotificationCenter,
  };

  @visibleForTesting
  static String prefsKeyOf(PushTriggerPoint point) => _keys[point]!;

  /// 埋点 `trigger_point` 取值。须与埋点清单词表一致 ——
  /// 它是本 FR「砍哪个触发点」的唯一依据，值写错等于那一档数据白收。
  static String analyticsValueOf(PushTriggerPoint point) => switch (point) {
        PushTriggerPoint.firstConsult => 'first_consult',
        PushTriggerPoint.profileCreated => 'profile_created',
        PushTriggerPoint.notificationCenter => 'notification_center',
      };

  static String _resultValueOf(PushPromptResult r) => switch (r) {
        PushPromptResult.granted => 'granted',
        PushPromptResult.denied => 'denied',
        PushPromptResult.settingsOpened => 'settings_opened',
        PushPromptResult.dismissed => 'dismissed',
      };

  /// 手机号软引导的接入点（**Story 7.2 注册它**）。
  ///
  /// 🛡 **AD-14 Rule 7：同一时机同时命中时，先推送权限、后手机号，不叠同屏。**
  /// 本 story 先落地，故在此建统一排队入口；7-2 只需在启动期赋值一次：
  ///
  /// ```dart
  /// PushPermissionPrompt.phonePromptHook = () => PhoneSoftPrompt.maybeShow(...);
  /// ```
  ///
  /// ⚠️ **7-2 不要另写一套判定**。两处各判一次的话，「同时命中」的先后就取决于
  /// 两段代码的偶然调用顺序，而这正是 Rule 7 要消除的不确定性。
  static Future<void> Function()? phonePromptHook;

  /// 该触发点此刻是否应该提示。
  ///
  /// 顺序刻意如此：
  /// 1. **已提示过 → false**（各一次）
  /// 2. **通知已开 → false，且 🛡 不消耗标记**（AD-14 Rule 5 前置闸门）
  ///
  /// 🔴 第 2 条「不消耗标记」是本类最容易被写错的一处：用户今天开着、下个月关掉，
  /// 这个触发点仍应有一次机会。顺手置位就等于把机会白扔了，
  /// 而本 FR 的全部目的就是**多给几次机会**。
  static Future<bool> shouldPrompt(
    PushTriggerPoint point, {
    required AppPrefs prefs,
    Future<bool> Function()? isGranted,
  }) async {
    if (prefs.getBool(prefsKeyOf(point))) return false;
    final granted = await (isGranted ?? _isGranted)();
    return !granted;
  }

  /// 置位「该触发点已用掉」。
  static Future<void> markPrompted(
    PushTriggerPoint point, {
    required AppPrefs prefs,
  }) =>
      prefs.setBool(prefsKeyOf(point), true);

  /// 曝光上报（E-19）。分母 —— 与 [reportResponded] 配对才能算各点的响应率。
  static Future<void> reportShown(PushTriggerPoint point) => Analytics.capture(
        'push_permission_prompt_shown',
        {
          'trigger_point': analyticsValueOf(point),
          // 🛡 AD-14 Rule 6 的可线上校验护栏：**恒为 in_app_guide**。
          //    一旦出现 native_dialog 即为实现违背规格 —— 那条分支是死的
          //    （原生弹窗机会首启已被消耗），出现即说明有人绕过了本类。
          'prompt_type': 'in_app_guide',
        },
      );

  /// 响应上报（E-20）。**按 `trigger_point` 拆分的分布就是「砍哪个触发点」的依据。**
  static Future<void> reportResponded(
    PushTriggerPoint point,
    PushPromptResult result,
  ) =>
      Analytics.capture(
        'push_permission_responded',
        {
          'trigger_point': analyticsValueOf(point),
          'prompt_type': 'in_app_guide',
          'result': _resultValueOf(result),
        },
      );

  /// 到达某触发点时跑一遍：**先推送权限引导、后手机号软引导**（AD-14 Rule 7）。
  ///
  /// [showGuide] 由调用方提供（各处的界面形态不同：庆祝页后是弹层、通知中心是顶部条），
  /// 返回用户的响应用于上报。
  ///
  /// ⚠️ **手机号钩子在推送权限被跳过时也要跑** —— 否则"通知已开的用户永远不会被问手机号"，
  /// 那是无意的耦合。
  ///
  /// 异常一律吞掉：本方法挂在建档庆祝页与问诊完成之后，绝不能因为一个提示把主流程搞崩。
  static Future<void> runForTrigger(
    PushTriggerPoint point, {
    required AppPrefs prefs,
    required Future<PushPromptResult> Function() showGuide,
    Future<bool> Function()? isGranted,
  }) async {
    try {
      if (await shouldPrompt(point, prefs: prefs, isGranted: isGranted)) {
        // 先置位再展示：用户划走浮层也算用掉这次机会，否则下次到达同一触发点又弹。
        await markPrompted(point, prefs: prefs);
        // 🔴 **埋点刻意不 await**：本方法夹在「建档完成 → 进首页」与「问诊完成」之间，
        //    上报慢一拍就会卡住导航。埋点丢一条无所谓，卡住用户不行。
        //    （`Analytics.capture` 内部已 try/catch 吞异常，不 await 也不会产生未捕获错误。）
        unawaited(reportShown(point));
        final result = await showGuide();
        unawaited(reportResponded(point, result));
      }
    } catch (e) {
      debugPrint('[PushPrompt] $point failed: $e');
    }
    // 手机号软引导排在后面（7-2 注册钩子）。它自己的"仅一次"由 7-2 负责。
    try {
      await phonePromptHook?.call();
    } catch (e) {
      debugPrint('[PushPrompt] phone hook failed: $e');
    }
  }

  /// 与 `PushPermissionBootstrap._isGranted` / `PushPermissionStateReporter` 同一口径。
  static Future<bool> _isGranted() async =>
      (await ph.Permission.notification.status).isGranted;
}
