import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pet_status_selector.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../content/domain/home_refresh_provider.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/card_link.dart';
import '../domain/pet_profile.dart';
import '../domain/share_service.dart';
import '../domain/timeline_item.dart';
import 'widgets/pet_info_card.dart';
import 'widgets/share_fab.dart';
import 'widgets/timeline_tiles.dart';

/// 成长档案 Tab 主屏（Story 2.4）。三态：
/// - 状态 A + 有档案 → 信息卡 + FAB 占位 + 倒序时间线；
/// - 状态 A + 无档案 → 空状态「立即创建」；
/// - 状态 B/C → 「有宠专属」+ 修改状态入口。
class GrowthArchivePage extends ConsumerWidget {
  const GrowthArchivePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final petStatus = auth.profile?.petStatus;

    // 状态 B/C：非有宠态。
    if (petStatus != null && petStatus != 'A') {
      return _NonOwnerView(onChangeStatus: () => _openStatusEditor(context, ref));
    }

    // 状态 A（或未知）：据是否有档案分支。
    final profileAsync = ref.watch(petProfileProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabProfile), backgroundColor: AppColors.base),
      // 分享名片 FAB（Story 2.7）：仅 A + 有档案 + 有 cardToken 渲染；动效首访一次。
      floatingActionButton: _shareFab(context, ref, profileAsync.asData?.value),
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => _EmptyProfileView(onCreate: () => context.go('/profile/create')),
        data: (profile) {
          if (profile == null) {
            return _EmptyProfileView(onCreate: () => context.go('/profile/create'));
          }
          return _ArchiveBody(
            child: PetInfoCard(
              profile: profile,
              onEditStatus: () => _openStatusEditor(context, ref),
            ),
          );
        },
      ),
    );
  }

  /// 仅 (状态 A + 有档案 + 有 cardToken) 渲染分享 FAB；B/C 或无档案不渲染（AC3）。
  Widget? _shareFab(BuildContext context, WidgetRef ref, PetProfile? profile) {
    if (profile == null || profile.cardToken.isEmpty) return null;
    final l10n = AppLocalizations.of(context);
    final alreadyShown = ref.watch(shareFabAnimatedShownProvider).asData?.value ?? true;
    return ShareFab(
      semanticLabel: l10n.shareFabLabel,
      animate: !alreadyShown,
      onAnimationShown: () {
        markShareFabAnimated();
        ref.invalidate(shareFabAnimatedShownProvider);
      },
      onPressed: () => ref.read(shareServiceProvider)(petCardShareUrl(profile.cardToken)),
    );
  }

  Future<void> _openStatusEditor(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    String? selected = ref.read(authControllerProvider).profile?.petStatus;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.base,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.growthEditStatusTitle, style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: AppSpacing.md),
              PetStatusSelector(
                selected: selected,
                onChanged: (v) => setSheetState(() => selected = v),
              ),
              const SizedBox(height: AppSpacing.md),
              FilledButton(
                key: const ValueKey('saveStatusButton'),
                onPressed: selected == null
                    ? null
                    : () async {
                        await _saveStatus(context, ref, selected!);
                        if (ctx.mounted) Navigator.of(ctx).pop();
                      },
                child: Text(l10n.commonSave),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _saveStatus(BuildContext context, WidgetRef ref, String status) async {
    final l10n = AppLocalizations.of(context);
    try {
      final updated = await ref.read(meRepositoryProvider).updatePetStatus(status);
      // FR-21 即时同步：回填全局 profile → 「我的」一致；bump 首页 → Feed 即时刷新。
      ref.read(authControllerProvider.notifier).applyProfile(updated);
      ref.read(homeRefreshProvider.notifier).bump();
      ref.invalidate(petProfileProvider);
      ref.invalidate(timelineFirstPageProvider);
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.growthStatusSaveFailed)));
      }
    }
  }
}

class _ArchiveBody extends ConsumerWidget {
  const _ArchiveBody({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final timelineAsync = ref.watch(timelineFirstPageProvider);
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(timelineFirstPageProvider),
      child: ListView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        children: [
          child,
          timelineAsync.when(
            loading: () => const Padding(
              padding: EdgeInsets.all(AppSpacing.xl),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (err, stack) => const SizedBox.shrink(),
            data: (page) {
              if (page.items.isEmpty) {
                return Padding(
                  padding: const EdgeInsets.all(AppSpacing.xl),
                  child: Center(
                    child: Text(l10n.growthArchiveTimelineEmpty,
                        style: TextStyle(color: AppColors.textTertiary)),
                  ),
                );
              }
              return Column(children: [for (final item in page.items) _tile(item)]);
            },
          ),
        ],
      ),
    );
  }

  Widget _tile(TimelineItem item) {
    return switch (item.kind) {
      TimelineKind.healthEvent => HealthEventTile(item: item),
      _ => HappyMomentTile(item: item),
    };
  }
}

class _EmptyProfileView extends StatelessWidget {
  const _EmptyProfileView({required this.onCreate});

  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          EmptyState(title: l10n.growthArchiveEmptyTitle),
          FilledButton(
            key: const ValueKey('growthCreateButton'),
            onPressed: onCreate,
            child: Text(l10n.growthArchiveEmptyCreate),
          ),
        ],
      ),
    );
  }
}

class _NonOwnerView extends StatelessWidget {
  const _NonOwnerView({required this.onChangeStatus});

  final VoidCallback onChangeStatus;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabProfile), backgroundColor: AppColors.base),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(l10n.growthArchiveNonOwnerTitle, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                key: const ValueKey('changeStatusButton'),
                onPressed: onChangeStatus,
                child: Text(l10n.growthArchiveChangeStatus),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
