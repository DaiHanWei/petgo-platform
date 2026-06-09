import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../l10n/app_localizations.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../data/health_event_repository.dart';
import '../data/profile_repository.dart';
import '../domain/archive_prompt_guard.dart';
import '../domain/pending_archive.dart';

/// 问诊存档触发参数（由 Epic 4/5 结束页 / 红色态结果页提供）。petId 不在此——按用户状态解析。
class ArchivePromptArgs {
  const ArchivePromptArgs({
    required this.sourceRef,
    required this.sourceType,
    this.symptomSummary,
    this.aiLevel,
    this.adviceSummary,
    this.imImageRefs = const [],
    this.redState = false,
  });

  final String sourceRef;
  final HealthSourceType sourceType;
  final String? symptomSummary;
  final String? aiLevel;
  final String? adviceSummary;
  final List<String> imImageRefs;

  /// 红色态结果页触发（AC4）：状态 A 已建档直接存入无弹窗；建档完成回灌后语义返回结果页。
  final bool redState;
}

/// 一次性存档弹窗（Story 2.5 · AC1 三态 / AC3 回灌 / AC4 红色态）。供 Epic4/5 结束页与红色态结果页调用。
///
/// 「只问一次」（FR-16）：先经 [ArchivePromptGuard] 判断。按当前用户状态三态分流：
/// - ① 状态 A + 已建档：弹「存入/跳过」（redState 时直接存入无弹窗）。
/// - ② 状态 A + 未建档：弹「立即创建/跳过」→ 立即创建挂起 pending + 跳 FR-11 建档（回灌见 create 页）。
/// - ③ 状态 B/C：弹「去创建/跳过」→ 去创建先 FR-0G 切状态为 A，再挂起 pending + 跳 FR-11 建档。
Future<void> showArchivePrompt(
  BuildContext context,
  WidgetRef ref,
  ArchivePromptArgs args,
) async {
  final guard = ref.read(archivePromptGuardProvider);
  if (!await guard.needsPrompt(args.sourceRef)) return;
  if (!context.mounted) return;

  final l10n = AppLocalizations.of(context);
  final auth = ref.read(authControllerProvider);
  final status = auth.profile?.petStatus;
  final hasProfile = auth.profile?.hasPetProfile ?? false;
  final isOwner = status == null || status == 'HAS_PET';

  // ① 状态 A + 已建档。
  if (isOwner && hasProfile) {
    final pet = await ref.read(petProfileProvider.future);
    if (!context.mounted) return;
    if (pet != null) {
      if (args.redState) {
        // AC4：红色态 A 已建档 → 直接存入，无弹窗确认。
        guard.markHandled(args.sourceRef);
        await _record(ref, args, pet.id, ArchiveDecision.archived);
        return;
      }
      final decision = await showDialog<ArchiveDecision>(
        context: context,
        builder: (ctx) => AlertDialog(
          content: Text(l10n.archivePromptTitle(pet.name)),
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
      await _record(ref, args, pet.id, decision);
      return;
    }
    // hasProfile 但拉取为空（异常）→ 落到建档引导分支。
  }

  // ② A 未建档 / ③ B/C：弹建档引导。
  final isBc = !isOwner; // PLANNING / ENTHUSIAST
  final create = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      content: Text(isBc ? l10n.archivePromptSwitchTitle : l10n.archivePromptCreateTitle),
      actions: [
        TextButton(
          key: const ValueKey('archiveSkip'),
          onPressed: () => Navigator.of(ctx).pop(false),
          child: Text(l10n.archivePromptSkip),
        ),
        FilledButton(
          key: const ValueKey('archiveCreate'),
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(isBc ? l10n.archivePromptSwitchCta : l10n.archivePromptCreateCta),
        ),
      ],
    ),
  );
  if (create != true) {
    guard.markHandled(args.sourceRef); // 跳过：本 session 不再弹
    return;
  }

  // 挂起存档意图，待建档成功回灌（AC3/AC4）。
  ref.read(pendingArchiveProvider.notifier).set(PendingArchive(
        sourceRef: args.sourceRef,
        sourceType: args.sourceType,
        symptomSummary: args.symptomSummary,
        aiLevel: args.aiLevel,
        adviceSummary: args.adviceSummary,
        imImageRefs: args.imImageRefs,
        returnToResult: args.redState,
      ));

  // ③ B/C：先走 FR-0G 系统切状态为 A。
  if (isBc) {
    try {
      final updated = await ref.read(meRepositoryProvider).updatePetStatus('HAS_PET');
      ref.read(authControllerProvider.notifier).applyProfile(updated);
    } catch (_) {
      // 切换失败不阻断——建档页仍可继续；幂等回灌不受影响。
    }
  }
  if (!context.mounted) return;
  // 跳 FR-11 建档；origin=triageArchive → 建档完成跳过庆祝页并回灌（pet_profile_create_page 接管）。
  context.go('/profile/create?origin=triageArchive');
}

Future<void> _record(
  WidgetRef ref,
  ArchivePromptArgs args,
  int petId,
  ArchiveDecision decision,
) {
  return ref.read(healthEventRepositoryProvider).recordDecision(
        sourceType: args.sourceType,
        sourceRef: args.sourceRef,
        petId: petId,
        decision: decision,
        symptomSummary: args.symptomSummary,
        aiLevel: args.aiLevel,
        adviceSummary: args.adviceSummary,
        imImageRefs: args.imImageRefs,
      );
}
