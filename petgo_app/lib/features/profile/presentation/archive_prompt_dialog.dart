import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../data/health_event_repository.dart';
import '../domain/archive_prompt_guard.dart';

/// 问诊存档触发参数（由 Epic 4/5 结束页提供）。
class ArchivePromptArgs {
  const ArchivePromptArgs({
    required this.sourceRef,
    required this.sourceType,
    required this.petId,
    required this.petName,
    this.symptomSummary,
    this.aiLevel,
    this.adviceSummary,
    this.imImageRefs = const [],
  });

  final String sourceRef;
  final HealthSourceType sourceType;
  final int petId;
  final String petName;
  final String? symptomSummary;
  final String? aiLevel;
  final String? adviceSummary;
  final List<String> imImageRefs;
}

/// 一次性存档弹窗（Story 2.5 · F1/F3）。供 Epic4/5 结束页调用。
///
/// 「只问一次」（FR-16）：先经 [ArchivePromptGuard] 判断是否仍需弹；用户选「存入/跳过」后记录决策
/// 并置位本地标记。文案插宠物名，i18n 双套。
Future<void> showArchivePromptIfNeeded(
  BuildContext context,
  WidgetRef ref,
  ArchivePromptArgs args,
) async {
  final guard = ref.read(archivePromptGuardProvider);
  if (!await guard.needsPrompt(args.sourceRef)) return;
  if (!context.mounted) return;

  final l10n = AppLocalizations.of(context);
  final decision = await showDialog<ArchiveDecision>(
    context: context,
    builder: (ctx) => AlertDialog(
      content: Text(l10n.archivePromptTitle(args.petName)),
      actions: [
        TextButton(
          key: const ValueKey('archiveSkip'),
          onPressed: () => Navigator.of(ctx).pop(ArchiveDecision.skipped),
          child: Text(l10n.archivePromptSkip),
        ),
        FilledButton(
          key: const ValueKey('archiveSave'),
          onPressed: () => Navigator.of(ctx).pop(ArchiveDecision.archived),
          child: Text(l10n.archivePromptSave),
        ),
      ],
    ),
  );

  if (decision == null) return; // 关闭未选 → 不记录，下次仍可弹
  guard.markHandled(args.sourceRef);
  await ref.read(healthEventRepositoryProvider).recordDecision(
        sourceType: args.sourceType,
        sourceRef: args.sourceRef,
        petId: args.petId,
        decision: decision,
        symptomSummary: args.symptomSummary,
        aiLevel: args.aiLevel,
        adviceSummary: args.adviceSummary,
        imImageRefs: args.imImageRefs,
      );
}
