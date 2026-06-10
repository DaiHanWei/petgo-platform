import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../profile/data/health_event_repository.dart';
import '../../profile/presentation/archive_prompt_dialog.dart';
import '../data/triage_repository.dart';

/// 「存入档案」触发器签名（Story 4.4 · F5）。注入式以便测试替身验证「点击→调起存档」。
typedef TriageArchiveHandler = Future<void> Function(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
});

ArchivePromptArgs _args(int triageId, DangerLevel level, String? advice, String? symptom,
        {required bool redState}) =>
    ArchivePromptArgs(
      sourceRef: 'triage:$triageId',
      sourceType: HealthSourceType.aiTriage,
      symptomSummary: symptom,
      aiLevel: level.name.toUpperCase(),
      adviceSummary: advice,
      redState: redState,
    );

/// 绿/黄态存档（Story 4.4 · FR-16）：经 Story 2.5 [showArchivePrompt] 三态承接——
/// A 已建档弹「存入/跳过」；A 未建档 / B/C 弹建档引导 → 建档完成回灌（同 sourceRef 幂等）。
Future<void> _defaultArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
}) async {
  if (triageId == null) return;
  await showArchivePrompt(context, ref, _args(triageId, level, advice, symptom, redState: false));
}

final Provider<TriageArchiveHandler> triageArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultArchive);

/// 红色态存档（Story 4.5 R2 · FR-3 + AC4）：经 [showArchivePrompt] 红色态分流——
/// **状态 A 已建档 → 直接存入无弹窗**（减少摩擦）；A 未建档 / B/C → 触发 FR-16 建档引导，
/// 建档完成**跳过庆祝页**回灌本次问诊记录（同 sourceRef 幂等）。
/// 🔒 存档为免费工具，**不夹带任何兽医/地图/付费/引流**（NFR-9 零变现护栏）。
Future<void> _defaultRedArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
}) async {
  if (triageId == null) return;
  await showArchivePrompt(context, ref, _args(triageId, level, advice, symptom, redState: true));
}

final Provider<TriageArchiveHandler> triageRedArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultRedArchive);
