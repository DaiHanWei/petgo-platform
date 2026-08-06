import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../profile/data/health_event_repository.dart';
import '../../profile/presentation/archive_prompt_dialog.dart';
import '../data/triage_repository.dart';

/// 「存入档案」触发器签名（Story 4.4 · F5）。注入式以便测试替身验证「点击→调起存档」。
///
/// 返回**本次是否当场落了一条存档记录** —— 调用方据此给成功反馈（2026-08-05 用户反馈：
/// 绿色结果页点「Save to health notes」毫无动静）。跳过 / 转去建档（异步回灌）/ 被守卫拦下
/// 均返回 false，此时**不得**报「已保存」。
///
/// [explicitSave]：用户**主动按下保存键**（区别于流程结束时的自动询问）。为 true 时绕过
/// FR-16「只问一次」守卫 —— 之前选过「跳过」不该让显式保存永久静默失效，那正是「点了没反应」
/// 的另一条成因。自动询问路径必须保持 false，否则 FR-16 形同虚设。
typedef TriageArchiveHandler = Future<bool> Function(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
  bool explicitSave,
});

ArchivePromptArgs _args(int triageId, DangerLevel level, String? advice, String? symptom,
        {required bool redState, bool explicitSave = false}) =>
    ArchivePromptArgs(
      sourceRef: 'triage:$triageId',
      sourceType: HealthSourceType.aiTriage,
      symptomSummary: symptom,
      aiLevel: level.name.toUpperCase(),
      adviceSummary: advice,
      redState: redState,
      explicitSave: explicitSave,
    );

/// 绿/黄态存档（Story 4.4 · FR-16）：经 Story 2.5 [showArchivePrompt] 三态承接——
/// A 已建档弹「存入/跳过」；A 未建档 / B/C 弹建档引导 → 建档完成回灌（同 sourceRef 幂等）。
Future<bool> _defaultArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
  bool explicitSave = false,
}) async {
  if (triageId == null) return false;
  return showArchivePrompt(context, ref,
      _args(triageId, level, advice, symptom, redState: false, explicitSave: explicitSave));
}

final Provider<TriageArchiveHandler> triageArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultArchive);

/// 红色态存档（Story 4.5 R2 · FR-3 + AC4）：经 [showArchivePrompt] 红色态分流——
/// **状态 A 已建档 → 直接存入无弹窗**（减少摩擦）；A 未建档 / B/C → 触发 FR-16 建档引导，
/// 建档完成**跳过庆祝页**回灌本次问诊记录（同 sourceRef 幂等）。
/// 🔒 存档为免费工具，**不夹带任何兽医/地图/付费/引流**（NFR-9 零变现护栏）。
Future<bool> _defaultRedArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
  bool explicitSave = false,
}) async {
  if (triageId == null) return false;
  return showArchivePrompt(context, ref,
      _args(triageId, level, advice, symptom, redState: true, explicitSave: explicitSave));
}

final Provider<TriageArchiveHandler> triageRedArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultRedArchive);
