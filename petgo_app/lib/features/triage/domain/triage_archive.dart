import 'package:flutter/widgets.dart';
import 'package:flutter/material.dart' show ScaffoldMessenger, SnackBar;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../profile/data/health_event_repository.dart';
import '../../profile/data/profile_repository.dart';
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

/// 默认实现：取当前宠物档案 → 调 Story 2.5 的 [showArchivePromptIfNeeded]（FR-16 只问一次）。
/// 无宠物档案 → 提示先建档（存档需绑定宠物时间线）。
Future<void> _defaultArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
}) async {
  final l10n = AppLocalizations.of(context);
  if (triageId == null) return;
  final pet = await ref.read(petProfileProvider.future);
  if (!context.mounted) return;
  if (pet == null) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.triageArchiveNoPet)));
    return;
  }
  await showArchivePromptIfNeeded(
    context,
    ref,
    ArchivePromptArgs(
      sourceRef: 'triage:$triageId',
      sourceType: HealthSourceType.aiTriage,
      petId: pet.id,
      petName: pet.name,
      symptomSummary: symptom,
      aiLevel: level.name.toUpperCase(),
      adviceSummary: advice,
    ),
  );
}

final Provider<TriageArchiveHandler> triageArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultArchive);

/// 红色态存档（Story 4.5 R2 · FR-3）。与绿/黄的 [_defaultArchive]（FR-16 弹窗）不同：
/// **状态 A 已建档 → 直接存入（recordDecision ARCHIVED），无弹窗确认**（红色态减少摩擦）；
/// **A 未建档 / B/C（无 pet）→ 引导先建档**（不静默成功）。
/// 🔒 存档为免费工具，**不夹带任何兽医/地图/付费/引流**（NFR-9 零变现护栏对存档入口同样适用）。
Future<void> _defaultRedArchive(
  BuildContext context,
  WidgetRef ref, {
  required int? triageId,
  required DangerLevel level,
  String? advice,
  String? symptom,
}) async {
  final l10n = AppLocalizations.of(context);
  if (triageId == null) return;
  final pet = await ref.read(petProfileProvider.future);
  if (!context.mounted) return;
  if (pet == null) {
    // A 未建档 / B/C：引导先建档（FR-16 建档引导，存档需绑定宠物时间线）。
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.triageArchiveNoPet)));
    return;
  }
  // A 已建档：直接存入，无弹窗确认（FR-3 红色态）。
  await ref.read(healthEventRepositoryProvider).recordDecision(
        sourceType: HealthSourceType.aiTriage,
        sourceRef: 'triage:$triageId',
        petId: pet.id,
        decision: ArchiveDecision.archived,
        symptomSummary: symptom,
        aiLevel: level.name.toUpperCase(),
        adviceSummary: advice,
      );
  if (!context.mounted) return;
  ScaffoldMessenger.of(context)
    ..clearSnackBars()
    ..showSnackBar(SnackBar(content: Text(l10n.triageRedArchived)));
}

final Provider<TriageArchiveHandler> triageRedArchiveHandlerProvider =
    Provider<TriageArchiveHandler>((ref) => _defaultRedArchive);
