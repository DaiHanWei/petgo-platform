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
