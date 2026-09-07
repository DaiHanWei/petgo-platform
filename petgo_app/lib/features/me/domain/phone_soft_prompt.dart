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
    required Future<bool?> Function() showSheet,
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
    required Future<bool?> Function() showSheet,
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
  /// [showSheet] 返回**三态**（`PhoneEditSheet.openDetailed` 正是这个语义）：
  /// `true` 保存成功 · `false` 点了「取消」· `null` 划走 / 点遮罩关掉。
  ///
  /// 🛡 **展示即置位**（不等用户作答）：用户划走浮层后下次打开不该再弹 ——
  /// 否则就成了「反复索要个人信息」，PRD 明确要避免。
  ///
  /// 异常一律吞掉：它挂在推送权限之后的排队里，绝不能把调用链搞崩。
  static Future<void> maybeShow({
    required AppPrefs prefs,
    required DateTime? registeredAt,
    required bool hasPhone,
    required Future<bool?> Function() showSheet,
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

      // E-4 `phone_prompt_shown`（Story 10.1 补齐）：**软引导曝光，是 E-5 的分母**。
      // 没有它，「打断了多少人」只能拿 E-5 的条数当分母 —— 而那是"作答数"，
      // 两者在崩溃/杀进程/埋点丢失时并不相等，用作答数当曝光数会系统性高估响应率。
      //
      // 🔴 `trigger` 只有一个取值 `day3_open`：埋点清单 §3 写的
      //    `first_consult` / `profile_created` 是**旧时机的词表，已被 X-20 判为死代码**
      //    （旧的两个时机在线上不再触发），X-21 把时机改成"注册第 3 天首次打开"。
      //    照旧词表上报会产出一个恒为空的属性，看数的人只会以为埋点坏了。
      //    仍保留这个属性而非删掉：将来加第二个时机时它就是唯一的拆分维度。
      unawaited(Analytics.capture('phone_prompt_shown', {'trigger': 'day3_open'}));

      final result = await showSheet();
      // 埋点不 await：它排在推送权限之后，不该再延后任何东西。
      unawaited(Analytics.capture('phone_prompt_responded', {
        // 三个取值与埋点清单 E-5 的词表一致。
        // 🔴 `skipped`（点了取消 = **拒绝**）与 `dismissed`（划走 = **可能只是没看懂**）
        //    必须分开 —— 清单 §3 明写这一条，两者混在一起就分不清"该改文案"还是"该撤掉"。
        'action': switch (result) {
          true => 'submitted',
          false => 'skipped',
          null => 'dismissed',
        },
      }));
    } catch (e) {
      debugPrint('[PhonePrompt] failed: $e');
    }
  }
}
