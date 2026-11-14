import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show OverflowBoxFit;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../data/account_milestone_repository.dart';
import '../data/milestone_celebration_reporter.dart';
import '../data/milestone_repository.dart';
import '../data/newbie_task_repository.dart';
import '../domain/milestone.dart';
import '../domain/milestone_account_codes.dart';
import '../domain/milestone_titles.dart';
import 'widgets/account_achievements_section.dart';
import 'widgets/milestone_badge.dart';
import 'widgets/milestone_celebration.dart';
import 'widgets/newbie_task_card.dart';

/// 账号级里程碑页路由（V1.5.0 onboarding-recall Story 3.2 · FR-178 · UI 稿 D2 / D3）。
const String kAccountMilestonesRoute = '/me/achievements';

/// 里程碑分发路由（OR-26）：有宠物 → `/profile/milestones`，无宠物 → [kAccountMilestonesRoute]；query 原样透传。
const String kMilestonesEntryRoute = '/me/milestones-entry';

/// 分发去向（纯函数，L0 可测）：有宠物（ACTIVE 或只读）→ 宠物里程碑页，否则账号级里程碑页；透传 [query]
/// （如 3-3 / 7-1 的 `celebrate=lulus`）。
String milestonesEntryLocation({required bool hasPet, Map<String, String> query = const {}}) {
  final base = hasPet ? '/profile/milestones' : kAccountMilestonesRoute;
  return query.isEmpty ? base : Uri(path: base, queryParameters: query).toString();
}

/// 账号级里程碑页（没宠物的用户从「我的」页进；V1.5.0 Story 3.2 · D2）：
/// 顶栏「Pencapaian kamu」→ 新手任务卡（`page = account_milestones`，① 无宠物显示「Buat profil」）→「Pencapaian akun」。
/// 无页头卡、无 L / M / S。任一接口失败 → 整页 D3 失败态 + 重试（不显示半截内容）。
class AccountMilestonesPage extends ConsumerStatefulWidget {
  const AccountMilestonesPage({super.key, this.celebrate});

  /// `?celebrate=lulus`（3-3 / 7-1：从毕业通知 / 召回 D7 来）。本页本就只会补弹 Lulus Pemula
  /// （账号级里只有它有「待庆祝」），参数只用于埋点 path 之外的语义对齐，不改变判定。
  final String? celebrate;

  @override
  ConsumerState<AccountMilestonesPage> createState() => _AccountMilestonesPageState();
}

class _AccountMilestonesPageState extends ConsumerState<AccountMilestonesPage> {
  /// 补弹只做一次（build 会因 provider 刷新反复跑）。
  bool _catchupDone = false;

  /// V1.5.0 onboarding-recall Story 3.3 · AC3：没宠物的账号进本页时，Lulus Pemula `pendingCelebration` → 补弹一次
  /// （E4 布局：无宠物行、只留日期），**展示后**异步回报 `POST /me/account-milestones/celebrations`（best-effort，
  /// 失败下次再弹）。弹出前先记本地（本会话不再弹）；后端回报会把账号下宠物同 flag 行一并置位 → 宠物页也不再弹。
  void _maybeCatchUp(AccountMilestones data) {
    if (_catchupDone) return;
    if (ModalRoute.of(context)?.isCurrent != true) return;
    final local = ref.read(locallyCelebratedMilestonesProvider);
    final entry = data.items
        .where((e) => e.flagKey == 'LULUS_PEMULA' && e.completed && e.pendingCelebration && !local.contains(e.code))
        .firstOrNull;
    if (entry == null) return;
    _catchupDone = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      if (ModalRoute.of(context)?.isCurrent != true) {
        _catchupDone = false;
        return;
      }
      final codes = kLulusPemulaCodes.toList();
      markMilestonesCelebrating(ref, codes);
      final container = ProviderScope.containerOf(context, listen: false);
      final item = MilestoneItem(
        code: entry.code,
        title: entry.code,
        level: MilestoneLevel.s,
        trigger: MilestoneTrigger.systemAuto,
        completed: true,
        completedAt: entry.completedAt,
      );
      await showMilestoneCelebration(
        context,
        item,
        petName: '',
        path: MilestoneCelebrationPath.catchup,
      );
      // best-effort：不阻塞、失败静默（同宠物页回报口径 AD-A3.1）。
      container.read(accountMilestoneRepositoryProvider).reportCelebrated([entry.code]).then((_) {
        container.invalidate(accountMilestonesProvider);
        container.invalidate(milestoneListProvider);
      }, onError: (_) {});
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tasks = ref.watch(newbieTasksProvider);
    final account = ref.watch(accountMilestonesProvider);
    final Widget body;
    if (tasks.hasError || account.hasError) {
      body = _AccountMilestonesError(onRetry: () {
        ref.invalidate(newbieTasksProvider);
        ref.invalidate(accountMilestonesProvider);
      });
    } else if (!tasks.hasValue || !account.hasValue) {
      body = const Center(
        key: ValueKey('accountMilestonesLoading'),
        child: SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2)),
      );
    } else {
      final data = account.value!;
      _maybeCatchUp(data);
      body = RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(newbieTasksProvider);
          ref.invalidate(accountMilestonesProvider);
        },
        child: ListView(
          key: const ValueKey('accountMilestonesList'),
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.xl),
          children: [
            const NewbieTaskCard(page: kNewbieCardPageAccountMilestones),
            const SizedBox(height: AppSpacing.lg),
            AccountAchievementsSection(
              completed: data.completedCount,
              total: data.totalCount,
              badges: [for (final e in data.items) _AccountBadge(entry: e)],
            ),
          ],
        ),
      );
    }
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        title: Text(l10n.myAchievementsTitle,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
        backgroundColor: AppColors.cream,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
      ),
      body: body,
    );
  }
}

/// 账号级徽章（64，标题按 code 本地化；三物种同名同图）。
class _AccountBadge extends StatelessWidget {
  const _AccountBadge({required this.entry});

  final AccountMilestoneEntry entry;

  static const double _size = 64;

  @override
  Widget build(BuildContext context) {
    final completed = entry.completed;
    return SizedBox(
      key: ValueKey('accountBadge_${entry.flagKey}'),
      width: _size,
      child: Column(
        children: [
          MilestoneBadge(code: entry.code, size: _size, level: MilestoneLevel.s, locked: !completed),
          const SizedBox(height: 6),
          OverflowBox(
            maxWidth: _size + AppSpacing.md,
            fit: OverflowBoxFit.deferToChild,
            child: Text(
              localizedMilestoneTitle(entry.code, Localizations.localeOf(context)),
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                height: 1.15,
                color: completed ? AppColors.ink : AppColors.muted,
                fontWeight: completed ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// D3：复用宠物里程碑页的失败态样式（cloud_off 44 +「Gagal memuat tonggak…」+「Coba lagi」）。
class _AccountMilestonesError extends StatelessWidget {
  const _AccountMilestonesError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('accountMilestonesError'),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.cloud_off_rounded, size: 44, color: AppColors.muted),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.milestoneLoadFailed, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(height: AppSpacing.sm),
          TextButton(
            key: const ValueKey('accountMilestonesRetry'),
            onPressed: onRetry,
            child: Text(l10n.growthLoadRetry),
          ),
        ],
      ),
    );
  }
}
