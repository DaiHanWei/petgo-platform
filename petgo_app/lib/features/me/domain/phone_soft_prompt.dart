import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/storage/prefs.dart';
import '../../notify/domain/push_permission_prompt.dart';
import 'phone_prompt_timing.dart';

/// 手机号软引导的编排（Story 7.2 · FR-70）。
///
/// **时机的算法**在 [PhonePromptTiming]（决策 X-21：用户第 3 天打开 App）。
/// 本类负责围绕它的那圈事：判定 → 展示 → **置位** → 埋点，并接进
/// Story 8.2 建好的排队入口，保证「先推送权限、后手机号」（AD-14 Rule 7）。
///
/// **PRD 明确的两条边界**（别顺手做）：
/// - **App 侧不设第二次、第三次自动提醒** —— 后续催填由运营后台手动触发（AB-11A）
/// - **填写入口不加同意勾选 / 用途说明文案** —— 用途告知由用户协议承接（OA-2 已闭合）
class PhoneSoftPrompt {
  PhoneSoftPrompt._();

  /// 把自己接进 Story 8.2 的排队入口。**在启动期调用一次即可。**
  ///
  /// 🛡 **不要另写一套「同时命中谁先谁后」的判定** —— 那正是 AD-14 Rule 7 要消除的
  /// 不确定性：两处各判一次的话，先后就取决于两段代码的偶然调用顺序。
  ///
  /// 参数都是取值函数而非值：注册发生在启动期，而判定所需的注册时间 / 是否已填手机号
  /// 要到**触发那一刻**才去读（用户可能刚在「我的」页填了号码）。
  static void register({
    required Future<AppPrefs> Function() prefs,
    required Future<DateTime?> Function() registeredAt,
    required Future<bool> Function() hasPhone,
    required Future<bool> Function() showSheet,
    DateTime Function()? now,
  }) {
    PushPermissionPrompt.phonePromptHook = () => maybeShowLazy(
          prefs: prefs,
          registeredAt: registeredAt,
          hasPhone: hasPhone,
          showSheet: showSheet,
          now: now,
        );
  }

  /// 取值全部延迟到触发那一刻的版本（供 [register] 使用）。
  static Future<void> maybeShowLazy({
    required Future<AppPrefs> Function() prefs,
    required Future<DateTime?> Function() registeredAt,
    required Future<bool> Function() hasPhone,
    required Future<bool> Function() showSheet,
    DateTime Function()? now,
  }) async {
    try {
      final p = await prefs();
      // 先看便宜的本地条件：已提示过就不必再去读注册时间 / 手机号。
      if (p.getBool(AppPrefs.kPhonePromptShown)) return;
      await maybeShow(
        prefs: p,
        registeredAt: await registeredAt(),
        hasPhone: await hasPhone(),
        showSheet: showSheet,
        now: now?.call(),
      );
    } catch (e) {
      debugPrint('[PhonePrompt] lazy check failed: $e');
    }
  }

  /// 判定 → 展示 → 置位 → 埋点。
  ///
  /// [showSheet] 返回**是否保存成功**（7-1 的编辑抽屉 `PhoneEditSheet.open` 正是这个语义）。
  ///
  /// 🛡 **展示即置位**（不等用户作答）：用户划走浮层后下次打开不该再弹 ——
  /// 否则就成了「反复索要个人信息」，PRD 明确要避免。
  ///
  /// 异常一律吞掉：它挂在推送权限之后的排队里，绝不能把调用链搞崩。
  static Future<void> maybeShow({
    required AppPrefs prefs,
    required DateTime? registeredAt,
    required bool hasPhone,
    required Future<bool> Function() showSheet,
    DateTime? now,
  }) async {
    try {
      final should = PhonePromptTiming.shouldPrompt(
        registeredAt: registeredAt,
        now: now ?? DateTime.now(),
        hasPhone: hasPhone,
        alreadyPrompted: prefs.getBool(AppPrefs.kPhonePromptShown),
      );
      if (!should) return;

      await prefs.setBool(AppPrefs.kPhonePromptShown, true);
      final saved = await showSheet();
      // 埋点不 await：它排在推送权限之后，不该再延后任何东西。
      unawaited(Analytics.capture('phone_prompt_responded', {
        // `submitted` / `skipped` 取值与埋点清单 E-5 的词表一致。
        // ⚠️ 「划走」与「点跳过」在 7-1 的抽屉里都表现为"未保存"，故合并计入 skipped ——
        //    区分两者需要抽屉回传更细的结果，那是 7-1 的接口，本 story 不改它。
        'action': saved ? 'submitted' : 'skipped',
      }));
    } catch (e) {
      debugPrint('[PhonePrompt] failed: $e');
    }
  }
}
