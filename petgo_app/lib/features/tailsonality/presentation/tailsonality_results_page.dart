import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../profile/data/profile_repository.dart';
import '../data/tailsonality_providers.dart';
import '../data/tailsonality_repository.dart';
import '../domain/tailsonality_result.dart';
import 'tailsonality_retake.dart';
import 'tailsonality_routes.dart';
import 'widgets/tailsonality_intro_sheet.dart';
import 'widgets/ts_result_row.dart';

/// Tailsonality 结果列表页（V1.3.2 Story 2.6）：一只宠物的全部测试结果，最新在上（接口顺序）。
///
/// 吸底「Tes Ulang」走与结果页 ⋯ 菜单**同一个**重测流程 [startTailsonalityRetake]。
/// 行尾佩戴切换（Story 3.3 · AC3 · D-16）：已解锁未佩戴「Pakai ini」→ 切换；已佩戴「Dipakai」→ 确认后卸下；
/// 未解锁不给控件（行内「Belum dibuka」由 2-6 提供，本身不可点）。
class TailsonalityResultsPage extends ConsumerStatefulWidget {
  const TailsonalityResultsPage({super.key});

  @override
  ConsumerState<TailsonalityResultsPage> createState() => _TailsonalityResultsPageState();
}

class _TailsonalityResultsPageState extends ConsumerState<TailsonalityResultsPage> {
  bool _opening = false;

  /// 正在请求的结果 token（防重复点；请求中该行显示 loading，其它行照常）。
  String? _busyToken;

  Future<void> _equip(TailsonalityResult r) async {
    if (_busyToken != null) return;
    setState(() => _busyToken = r.token);
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(tailsonalityRepositoryProvider).equipBadge(r.token);
      // 仅 PUT 成功后报（AC3.4）。
      Analytics.capture('tailsonality_badge_equipped', {'result_index': r.resultIndex});
      await _refreshAfterChange();
    } catch (_) {
      // 失败不动列表：UI 仍是请求前的状态（等价于回滚）。
      if (mounted) showAppToast(context, l10n.tailsonalityBadgeEquipFailed);
    } finally {
      if (mounted) setState(() => _busyToken = null);
    }
  }

  Future<void> _unequip(TailsonalityResult r) async {
    if (_busyToken != null) return;
    final l10n = AppLocalizations.of(context);
    final pet = ref.read(petProfileProvider).value?.name ?? '';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const ValueKey('tsBadgeRemoveDialog'),
        title: Text(l10n.tailsonalityBadgeRemoveTitle),
        content: Text(l10n.tailsonalityBadgeRemoveBody(pet)),
        actions: [
          TextButton(
            key: const ValueKey('tsBadgeRemoveCancel'),
            style: TextButton.styleFrom(minimumSize: const Size(64, 44)),
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(l10n.commonCancel),
          ),
          FilledButton(
            key: const ValueKey('tsBadgeRemoveConfirm'),
            style: FilledButton.styleFrom(
                minimumSize: const Size(64, 44),
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.tailsonalityBadgeRemoveConfirm),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busyToken = r.token);
    try {
      await ref.read(tailsonalityRepositoryProvider).unequipBadge();
      await _refreshAfterChange();
    } catch (_) {
      if (mounted) showAppToast(context, l10n.tailsonalityBadgeEquipFailed);
    } finally {
      if (mounted) setState(() => _busyToken = null);
    }
  }

  /// 佩戴变了：列表（equipped）与本人档案（小标）都要重取。等列表回来再解除 loading，避免闪回旧状态。
  Future<void> _refreshAfterChange() async {
    ref.invalidate(petProfileProvider);
    try {
      ref.invalidate(tailsonalityResultsProvider);
      await ref.read(tailsonalityResultsProvider.future).timeout(const Duration(seconds: 10));
    } catch (_) {
      // 刷新失败由列表自己的错误态处理。
    }
  }

  Widget? _badgeControl(TailsonalityResult r) {
    if (!r.unlocked) return null;
    return _BadgeControl(
      key: ValueKey('tsBadgeControl_${r.token}'),
      equipped: r.equipped,
      busy: _busyToken == r.token,
      onTap: _busyToken != null ? null : () => r.equipped ? _unequip(r) : _equip(r),
    );
  }

  /// 空态「Mulai Tes」：档案加载中会等一会儿，期间再点忽略（否则叠两层抽屉）；取不到给提示。
  Future<void> _startFromEmpty() async {
    if (_opening) return;
    _opening = true;
    final pet = await readPetForTailsonality(ref);
    _opening = false;
    if (!mounted) return;
    if (pet == null) {
      showAppToast(context, AppLocalizations.of(context).detailNetworkError);
      return;
    }
    await showTailsonalityIntroSheet(context, pet);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(tailsonalityResultsProvider);
    final items = async.asData?.value;
    // 卸下确认文案要宠物名：保持档案 provider 在页面期间已加载。
    ref.watch(petProfileProvider);
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.tailsonalityHistoryTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(l10n.detailNetworkError, style: const TextStyle(color: AppColors.muted)),
              const SizedBox(height: 8),
              TextButton(
                key: const ValueKey('tsResultsRetry'),
                onPressed: () => ref.invalidate(tailsonalityResultsProvider),
                child: Text(l10n.commonRetry),
              ),
            ],
          ),
        ),
        data: (list) => list.isEmpty
            ? EmptyState(
                key: const ValueKey('tsResultsEmpty'),
                title: l10n.tailsonalityHistoryEmpty,
                icon: Icons.psychology_alt_outlined,
                actionLabel: l10n.tailsonalityIntroStart,
                onAction: _startFromEmpty,
              )
            : ListView.separated(
                key: const ValueKey('tsResultsList'),
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                itemCount: list.length,
                separatorBuilder: (_, _) => const SizedBox(height: 10),
                itemBuilder: (context, i) => TsResultRow(
                  key: ValueKey('tsResultRow_${list[i].token}'),
                  result: list[i],
                  onTap: () => context.push(TailsonalityRoutes.result(list[i].token), extra: list[i]),
                  trailing: _badgeControl(list[i]),
                ),
              ),
      ),
      bottomNavigationBar: items == null || items.isEmpty
          ? null
          : SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: FilledButton(
                  key: const ValueKey('tsResultsRetake'),
                  onPressed: () => startTailsonalityRetake(context, ref),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    minimumSize: const Size.fromHeight(48),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text(l10n.tailsonalityMenuRetake,
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
    );
  }
}

/// 行尾佩戴控件（Story 3.3 · AC3）：「Dipakai」选中态胶囊 /「Pakai ini」描边按钮；热区 ≥44×44。
class _BadgeControl extends StatelessWidget {
  const _BadgeControl({super.key, required this.equipped, required this.busy, required this.onTap});

  final bool equipped;
  final bool busy;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final label = equipped ? l10n.tailsonalityBadgeEquipped : l10n.tailsonalityBadgeEquip;
    final child = busy
        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
        : Text(label,
            style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: equipped ? AppColors.onAccent : AppColors.mint));
    return Semantics(
      button: true,
      selected: equipped,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: busy ? null : onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
          child: Center(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: equipped ? AppColors.mint : Colors.transparent,
                borderRadius: BorderRadius.circular(9999),
                border: Border.all(color: AppColors.mint, width: 1.4),
              ),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}
