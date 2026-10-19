import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../pet_slot/data/pet_slot_repository.dart' show kPetReadOnlySlug;
import '../../pet_slot/presentation/pet_readonly_block.dart' show openPetSlotRenewalWith;
import '../data/health_event_repository.dart';
import '../data/pet_list_repository.dart';
import '../data/timeline_repository.dart';
import '../data/profile_repository.dart';
import '../domain/archive_prompt_guard.dart';
import '../domain/pending_archive.dart';

/// 问诊存档触发参数（由 Epic 4/5 结束页 / 红色态结果页提供）。
///
/// [petId]（V1.4.0 multi-pet Story 4.1 · AC8.1）：存到哪只宠物——AI 问诊 = 提交时所选、兽医问诊 = 会话订单上的；
/// null = 按用户状态解析当前宠物（改造前行为）。
class ArchivePromptArgs {
  const ArchivePromptArgs({
    required this.sourceRef,
    required this.sourceType,
    this.symptomSummary,
    this.aiLevel,
    this.adviceSummary,
    this.imImageRefs = const [],
    this.redState = false,
    this.explicitSave = false,
    this.petId,
  });

  final String sourceRef;
  final HealthSourceType sourceType;
  final String? symptomSummary;
  final String? aiLevel;
  final String? adviceSummary;
  final List<String> imImageRefs;

  /// 红色态结果页触发（AC4）：状态 A 已建档直接存入无弹窗；建档完成回灌后语义返回结果页。
  final bool redState;

  /// 用户显式点「存入宠物档案」按钮触发（bug 20260727 保存静默无效）：绕过「只问一次」守卫——
  /// FR-16 只约束结束时的自动弹窗，之前选过「跳过」不应让显式保存永久静默失效（后端支持 SKIPPED→ARCHIVED 升级）。
  final bool explicitSave;

  /// 存档目标宠物（可空，见类注释）。
  final int? petId;
}

/// 存档目标宠物（内部）：id + 界面用名字 + 是否只读。名字只渲染，不进日志 / 埋点（§5）。
class _ArchivePet {
  const _ArchivePet(this.id, this.name, {required this.readOnly});
  final int id;
  final String name;
  final bool readOnly;
}

/// 存档目标解析结果：[pet] 为 null 且 [gone] = 指定的宠物已确认不在（刚被删）→ 不存档。
typedef _ArchiveTarget = ({_ArchivePet? pet, bool gone});

/// 按 [petId] 取存档目标（code review 4-1 A1：给了 id 就**只存这只**，绝不悄悄落到当前宠物）：
/// - 宠物列表里有 → 这只（名字 + 只读状态）；
/// - 列表已取到但没有它（刚被删）→ `gone`，调用方不存档；
/// - 列表取不到 → 仍按这个 id 存（名字尽力按 id 取档案，取不到留空）；只读由后端 409 兜底、已删由后端拒绝。
/// 没给 id → `petProfileProvider`（= 改造前取唯一宠物的旧路径，只读状态从已加载的列表补查）。
Future<_ArchiveTarget> _resolveArchivePet(WidgetRef ref, int? petId) async {
  if (petId != null) {
    List<PetListItem>? pets;
    try {
      pets = await ref.read(petListProvider.future);
    } catch (_) {
      pets = null;
    }
    if (pets != null) {
      for (final p in pets) {
        if (p.id == petId) return (pet: _ArchivePet(p.id, p.name, readOnly: p.isReadOnly), gone: false);
      }
      return (pet: null, gone: true);
    }
    var name = '';
    try {
      name = (await ref.read(profileRepositoryProvider).getMyProfile(petId: petId))?.name ?? '';
    } catch (_) {
      // 名字只用于标题，取不到不阻断；归属 / 只读 / 存在性以后端为准。
    }
    return (pet: _ArchivePet(petId, name, readOnly: false), gone: false);
  }
  final pet = await ref.read(petProfileProvider.future);
  if (pet == null) return (pet: null, gone: false);
  var readOnly = false;
  final loaded = ref.exists(petListProvider) ? ref.read(petListProvider).value : null;
  for (final p in loaded ?? const <PetListItem>[]) {
    if (p.id == pet.id) readOnly = p.isReadOnly;
  }
  return (pet: _ArchivePet(pet.id, pet.name, readOnly: readOnly), gone: false);
}

/// 存档后失效 Diary 时间线 / 统计（V1.4.0 multi-pet Story 4.1 · AC8.6）：知道存到哪只 → 只失效**该宠物**的
/// petId family；不知道（旧路径）→ 整个 family 失效（= 改造前行为）。
void invalidateArchiveViews(WidgetRef ref, int? petId) {
  if (petId != null) {
    ref.invalidate(timelineFirstPageProvider(petId));
    ref.invalidate(archiveStatsProvider(petId));
  } else {
    ref.invalidate(timelineFirstPageProvider);
    ref.invalidate(archiveStatsProvider);
  }
}

/// H2 存档只读拦截（V1.4.0 multi-pet Story 4.1 · AC8.4 · decision-log H2）：沿用 E1 同款 [showConfirmSheet]——
/// H-4 标题 / H-5 正文 /「Perpanjang slot」→ 名额管理页并弹续租 /「Lewati」。两键都**不**记录决策。
Future<void> showArchiveReadOnlyBlockSheet(BuildContext context, {required String petName}) async {
  final l10n = AppLocalizations.of(context);
  final router = GoRouter.maybeOf(context);
  final renew = await showConfirmSheet(
    context,
    title: l10n.petSlotArchiveBlockedTitle,
    message: l10n.petSlotArchiveBlockedBody(petName),
    confirmLabel: l10n.petSlotRenewSheetTitle,
    cancelLabel: l10n.archivePromptSkip,
    icon: Icons.lock_rounded,
    confirmKey: const ValueKey('archiveBlockedRenew'),
    cancelKey: const ValueKey('archiveBlockedSkip'),
  );
  if (renew && router != null) openPetSlotRenewalWith(router, source: 'block_sheet');
}

/// 一次性存档弹窗（Story 2.5 · AC1 三态 / AC3 回灌 / AC4 红色态）。供 Epic4/5 结束页与红色态结果页调用。
///
/// 「只问一次」（FR-16）：先经 [ArchivePromptGuard] 判断。按当前用户状态三态分流：
/// - ① 状态 A + 已建档：弹「存入/跳过」（redState 时直接存入无弹窗）。
/// - ② 状态 A + 未建档：弹「立即创建/跳过」→ 立即创建挂起 pending + 跳 FR-11 建档（回灌见 create 页）。
/// - ③ 状态 B/C：弹「去创建/跳过」→ 去创建先 FR-0G 切状态为 A，再挂起 pending + 跳 FR-11 建档。
/// 返回 true 当且仅当本次调用【当场持久化了一条 ARCHIVED 记录】（供调用方 toast「已存入 diary」，
/// bug 20260721-338）。跳过 / 已处理过 / 弹窗关闭 / 转去建档（异步回灌）均返回 false。
Future<bool> showArchivePrompt(
  BuildContext context,
  WidgetRef ref,
  ArchivePromptArgs args,
) async {
  final guard = ref.read(archivePromptGuardProvider);
  // 显式保存绕过「只问一次」守卫（守卫只管自动弹窗场景）；自动场景照旧一次为限。
  if (!args.explicitSave && !await guard.needsPrompt(args.sourceRef)) return false;
  if (!context.mounted) return false;

  final auth = ref.read(authControllerProvider);
  final status = auth.profile?.petStatus;
  final hasProfile = auth.profile?.hasPetProfile ?? false;
  final isOwner = status == null || status == 'HAS_PET';

  // ① 状态 A + 已建档。
  if (isOwner && hasProfile) {
    final target = await _resolveArchivePet(ref, args.petId);
    if (!context.mounted) return false;
    if (target.gone) {
      // 所选宠物已被删（A1）：不存到别的宠物，给既有「保存失败」提示后结束。
      showAppToast(context, AppLocalizations.of(context).consultArchiveSaveFailed);
      return false;
    }
    final pet = target.pet;
    if (pet != null) {
      if (pet.readOnly) {
        // V1.4.0 multi-pet Story 4.1（AC8.4）：只读宠物 → 弹 H2 拦截，不弹「存入 / 跳过」、不发任何决策。
        guard.markHandled(args.sourceRef);
        await showArchiveReadOnlyBlockSheet(context, petName: pet.name);
        return false;
      }
      if (args.redState) {
        // AC4：红色态 A 已建档 → 直接存入，无弹窗确认。
        guard.markHandled(args.sourceRef);
        return _recordOrBlock(context, ref, args, pet, ArchiveDecision.archived);
      }
      // 存入档案确认用底部抽屉（stag：与病例/诊断结果等底部弹层交互统一）。
      final decision = await showArchiveSaveSheet(context, petName: pet.name);
      if (decision == null) return false; // 关闭未选 → 不记录，下次仍可弹（bug 20260721-338 返回 bool）
      if (!context.mounted) return false;
      guard.markHandled(args.sourceRef);
      final recorded = await _recordOrBlock(context, ref, args, pet, decision);
      return recorded && decision == ArchiveDecision.archived;
    }
    // hasProfile 但拉取为空（异常）→ 落到建档引导分支。
  }

  // ② A 未建档 / ③ B/C：弹建档引导，同样用底部抽屉（与存入确认统一）。
  final isBc = !isOwner; // PLANNING / ENTHUSIAST
  final create = await showArchiveCreateGuideSheet(context, isBc: isBc);
  if (create != true) {
    guard.markHandled(args.sourceRef); // 跳过：本 session 不再弹
    return false;
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
  if (!context.mounted) return false;
  // 跳 FR-11 建档；origin=triageArchive → 建档完成跳过庆祝页并回灌（pet_profile_create_page 接管）。
  // 存档在建档成功后异步回灌，非本次当场完成 → 返回 false（不在此 toast）。
  context.push('/profile/create?origin=triageArchive');
  return false;
}

/// ① 存入档案确认底部抽屉（状态 A 已建档）。返回用户决定；下滑关闭未选返回 null。
Future<ArchiveDecision?> showArchiveSaveSheet(BuildContext context, {required String petName}) {
  final l10n = AppLocalizations.of(context);
  return showModalBottomSheet<ArchiveDecision>(
    context: context,
    backgroundColor: AppColors.surface,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
    builder: (ctx) => _archiveSheet(
      title: l10n.archivePromptTitle(petName),
      primaryKey: 'archiveSave',
      primaryLabel: l10n.archivePromptSave,
      onPrimary: () => Navigator.of(ctx).pop(ArchiveDecision.archived),
      skipLabel: l10n.archivePromptSkip,
      onSkip: () => Navigator.of(ctx).pop(ArchiveDecision.skipped),
    ),
  );
}

/// ②③ 建档引导底部抽屉（A 未建档 / B·C 待切状态）。返回是否去创建；下滑关闭返回 null。
Future<bool?> showArchiveCreateGuideSheet(BuildContext context, {required bool isBc}) {
  final l10n = AppLocalizations.of(context);
  return showModalBottomSheet<bool>(
    context: context,
    backgroundColor: AppColors.surface,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
    builder: (ctx) => _archiveSheet(
      title: isBc ? l10n.archivePromptSwitchTitle : l10n.archivePromptCreateTitle,
      primaryKey: 'archiveCreate',
      primaryLabel: isBc ? l10n.archivePromptSwitchCta : l10n.archivePromptCreateCta,
      onPrimary: () => Navigator.of(ctx).pop(true),
      skipLabel: l10n.archivePromptSkip,
      onSkip: () => Navigator.of(ctx).pop(false),
    ),
  );
}

/// 存档相关底部抽屉的统一版式：拖拽条 + 居中标题 + 主按钮 + 跳过文字按钮。
Widget _archiveSheet({
  required String title,
  required String primaryKey,
  required String primaryLabel,
  required VoidCallback onPrimary,
  required String skipLabel,
  required VoidCallback onSkip,
}) {
  return SafeArea(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, AppSpacing.xl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(color: AppColors.divider, borderRadius: BorderRadius.circular(999)),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          Text(title, style: AppTypography.title, textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.xl),
          FilledButton(key: ValueKey(primaryKey), onPressed: onPrimary, child: Text(primaryLabel)),
          const SizedBox(height: AppSpacing.sm),
          TextButton(key: const ValueKey('archiveSkip'), onPressed: onSkip, child: Text(skipLabel)),
        ],
      ),
    ),
  );
}

/// 记录决策；后端兜底 409 `pet-read-only`（前端状态过期，AC8.5）→ 失效宠物列表 + 弹 H2、返回 false
/// （不显后端 detail、不当通用失败抛出）。其余错误照旧抛给调用方（toast「保存失败」）。成功返回 true。
Future<bool> _recordOrBlock(
  BuildContext context,
  WidgetRef ref,
  ArchivePromptArgs args,
  _ArchivePet pet,
  ArchiveDecision decision,
) async {
  try {
    await _record(ref, args, pet.id, decision);
    return true;
  } on DioException catch (e) {
    final pd = ProblemDetail.fromDioException(e);
    if (pd?.typeSlug != kPetReadOnlySlug) rethrow;
    ref.invalidate(petListProvider);
    if (context.mounted) await showArchiveReadOnlyBlockSheet(context, petName: pet.name);
    return false;
  }
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
