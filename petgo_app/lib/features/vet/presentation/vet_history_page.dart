import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../triage/presentation/widgets/triage_paywall.dart' show formatIdr;
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 兽医历史列表刷新信号：切到历史 Tab（工作台 `_select(2)`）时 bump，页面据此重拉。
/// bug 20260702-219：历史页挂 IndexedStack 保活、`initState` 只拉一次（且早于兽医结束会话），
/// 之后切 Tab 只切可见性 → 刚结束的会话当 app 生命周期内永不出现在历史。
class VetHistoryRefreshNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<VetHistoryRefreshNotifier, int> vetHistoryRefreshProvider =
    NotifierProvider<VetHistoryRefreshNotifier, int>(VetHistoryRefreshNotifier.new);

/// 历史记录 Tab（原型 vet-history.html 1:1）：深色顶栏 + 今日总数 + 筛选 Chip + 记录卡（只读）。
class VetHistoryPage extends ConsumerStatefulWidget {
  const VetHistoryPage({super.key});

  @override
  ConsumerState<VetHistoryPage> createState() => _VetHistoryPageState();
}

/// 筛选维度（客户端过滤已拉列表）。
enum _HistoryFilter { all, topRated, yellow, red }

class _VetHistoryPageState extends ConsumerState<VetHistoryPage> {
  late Future<List<VetHistoryEntry>> _items;
  _HistoryFilter _filter = _HistoryFilter.all;

  @override
  void initState() {
    super.initState();
    _items = ref.read(vetRepositoryProvider).history();
  }

  /// 重拉历史（切到本 Tab / 下拉刷新触发）。
  ///
  /// 用块体 `setState`：箭头体 `() => _items = future` 的返回值是被赋的 Future，
  /// 会触发 Flutter「setState 回调返回 Future」断言，在 debug 下于 markNeedsBuild 前抛出 →
  /// _items 虽赋新值但界面不重建，历史永不刷新（结束会话后新会话不出现）。
  void _reload() {
    final f = ref.read(vetRepositoryProvider).history();
    setState(() {
      _items = f;
    });
  }

  List<VetHistoryEntry> _apply(List<VetHistoryEntry> all) {
    switch (_filter) {
      case _HistoryFilter.all:
        return all;
      case _HistoryFilter.topRated:
        return all.where((e) => e.stars != null && e.stars! >= 4).toList();
      case _HistoryFilter.yellow:
        return all.where((e) => e.dangerLevel == 'YELLOW').toList();
      case _HistoryFilter.red:
        return all.where((e) => e.dangerLevel == 'RED').toList();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 切到历史 Tab（或结束会话后）bump → 重拉，绕过 IndexedStack 保活的一次性 initState（bug 20260702-219）。
    ref.listen<int>(vetHistoryRefreshProvider, (_, _) => _reload());
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: FutureBuilder<List<VetHistoryEntry>>(
        future: _items,
        builder: (context, snapshot) {
          final loading = snapshot.connectionState == ConnectionState.waiting;
          final all = snapshot.data ?? const <VetHistoryEntry>[];
          final filtered = _apply(all);
          return Column(
            children: [
              _topBar(l10n, all.length),
              _filterRow(l10n),
              Expanded(
                child: loading
                    ? const Center(child: CircularProgressIndicator())
                    : all.isEmpty
                        ? VetEmptyState(icon: Icons.history, message: l10n.vetHistoryEmpty)
                        : filtered.isEmpty
                            ? VetEmptyState(icon: Icons.filter_alt_off_outlined, message: l10n.vetHistoryFilterEmpty)
                            : RefreshIndicator(
                                color: AppColors.vetPrimary,
                                onRefresh: () async {
                                  final f = ref.read(vetRepositoryProvider).history();
                                  setState(() {
                                    _items = f;
                                  });
                                  await f;
                                },
                                child: ListView.separated(
                                  padding: const EdgeInsets.fromLTRB(
                                      AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.xl),
                                  itemCount: filtered.length,
                                  separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                                  itemBuilder: (ctx, i) => _HistoryCard(entry: filtered[i]),
                                ),
                              ),
              ),
            ],
          );
        },
      ),
    );
  }

  /// 深色顶栏 #2B2540：「Riwayat Konsultasi」+ 今日总数。
  Widget _topBar(AppLocalizations l10n, int total) {
    return Container(
      width: double.infinity,
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.md),
          child: Row(
            children: [
              Expanded(
                child: Text(l10n.vetHistoryTitle, style: AppTypography.title.copyWith(color: Colors.white)),
              ),
              Text(l10n.vetHistoryTotalToday(total),
                  style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.5))),
            ],
          ),
        ),
      ),
    );
  }

  /// 筛选 Chip 行：Semua / ⭐4-5 / 🟡 / 🔴。选中薄荷实底白字。
  Widget _filterRow(AppLocalizations l10n) {
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
        children: [
          _chip(l10n.vetHistoryFilterAll, _HistoryFilter.all),
          _chip(l10n.vetHistoryFilterTopRated, _HistoryFilter.topRated),
          _chip(l10n.vetHistoryFilterYellow, _HistoryFilter.yellow),
          _chip(l10n.vetHistoryFilterRed, _HistoryFilter.red),
        ],
      ),
    );
  }

  Widget _chip(String label, _HistoryFilter value) {
    final selected = _filter == value;
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.sm),
      child: InkWell(
        onTap: () => setState(() => _filter = value),
        borderRadius: BorderRadius.circular(999),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
          decoration: BoxDecoration(
            color: selected ? AppColors.vetPrimary : AppColors.surface,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: selected ? AppColors.vetPrimary : AppColors.border),
          ),
          child: Center(
            child: Text(
              label,
              style: AppTypography.caption.copyWith(
                color: selected ? AppColors.vetOnAccent : AppColors.textSecondary,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _HistoryCard extends StatelessWidget {
  const _HistoryCard({required this.entry});

  final VetHistoryEntry entry;

  bool get _interrupted => entry.terminalState == 'INTERRUPTED';

  String _speciesEmoji() {
    switch (entry.petSpecies) {
      case 'CAT':
        return '🐱';
      case 'DOG':
        return '🐶';
      default:
        return '🐾';
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final nameLine = entry.ownerHandle != null ? '${entry.petName} · @${entry.ownerHandle}' : entry.petName;
    // 整卡可点 → 只读问诊结果页（Bug 20260701-196：原「View →」是裸 Text 无手势，点击无反应）。
    // 底色移到 Material，InkWell 水波纹才可见；boxShadow 留在内层 Container。
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('vetHistoryView_${entry.sessionId}'),
        onTap: () => context.push('/vet/history/${entry.sessionId}', extra: entry),
        borderRadius: BorderRadius.circular(14),
        child: Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(color: AppColors.ink.withValues(alpha: 0.06), blurRadius: 8, offset: const Offset(0, 2)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: (_levelColor() ?? AppColors.muted).withValues(alpha: 0.12),
                  shape: BoxShape.circle,
                ),
                child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 16)),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(nameLine,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.body.copyWith(color: AppColors.ink, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text(entry.summary,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  if (!_interrupted && entry.stars != null)
                    Text('⭐ ${entry.stars!.toStringAsFixed(1)}',
                        style: AppTypography.caption.copyWith(color: AppColors.gold, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(entry.dateLabel, style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                ],
              ),
            ],
          ),
          if (entry.reviewText != null && entry.reviewText!.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.sm),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
              decoration: BoxDecoration(color: AppColors.vetSurface2, borderRadius: BorderRadius.circular(10)),
              child: Text('"${entry.reviewText!}"',
                  style: AppTypography.caption.copyWith(color: AppColors.ink2, height: 1.5)),
            ),
          ],
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              if (entry.dangerLevel != null) ...[
                _badge('${_levelEmoji()} ${_levelLabel(l10n)!}', _levelBg(), _levelColor()!),
                const SizedBox(width: 6),
              ],
              _badge(
                _interrupted ? l10n.terminalInterrupted : l10n.terminalClosed,
                _interrupted ? AppColors.muted.withValues(alpha: 0.15) : AppColors.vetSurface,
                _interrupted ? AppColors.textSecondary : AppColors.vetPrimaryDeep,
              ),
              const Spacer(),
              Text(l10n.vetHistoryView,
                  style: AppTypography.caption.copyWith(color: AppColors.vetPrimary, fontWeight: FontWeight.w600)),
            ],
          ),
          // V88（ref36）：类型徽章（Konsultasi Dasar/Upgrade dari AI/Refund）+ 到手金额 Diterima。
          const SizedBox(height: 8),
          Row(
            children: [
              _typeBadge(l10n),
              const Spacer(),
              _payoutLabel(l10n),
            ],
          ),
        ],
      ),
        ),
      ),
    );
  }

  /// 类型徽章：退款→Refund(珊瑚)；AI_UPGRADE→Upgrade dari AI(紫)；否则→Konsultasi Dasar(琥珀)。
  Widget _typeBadge(AppLocalizations l10n) {
    if (entry.refunded) {
      return _badge(l10n.vetHistoryTypeRefund, AppColors.coralTint, AppColors.coral);
    }
    if (entry.source == null) return const SizedBox.shrink();
    final upgrade = entry.source == 'AI_UPGRADE';
    return _badge(
      upgrade ? l10n.vetHistoryTypeUpgrade : l10n.vetHistoryTypeBasic,
      upgrade ? AppColors.mintTint2 : AppColors.goldTint,
      upgrade ? AppColors.mint : AppColors.tipsBadgeText,
    );
  }

  /// 到手金额：退款→Diterima Rp0 删除线(muted)；有额→绿字 Diterima Rp…；老数据无额→「—」。
  Widget _payoutLabel(AppLocalizations l10n) {
    if (entry.refunded) {
      return Text(
        l10n.vetHistoryPayout(formatIdr(0)),
        style: AppTypography.body.copyWith(
          color: AppColors.textTertiary,
          fontWeight: FontWeight.w700,
          decoration: TextDecoration.lineThrough,
        ),
      );
    }
    if (entry.payoutAmount == null) {
      return Text('—', style: AppTypography.body.copyWith(color: AppColors.textTertiary));
    }
    return Text(
      l10n.vetHistoryPayout(formatIdr(entry.payoutAmount!)),
      style: AppTypography.body.copyWith(color: AppColors.onlineDeepGreen, fontWeight: FontWeight.w700),
    );
  }

  String _levelEmoji() {
    switch (entry.dangerLevel) {
      case 'RED':
        return '🔴';
      case 'YELLOW':
        return '🟡';
      default:
        return '🟢';
    }
  }

  Color? _levelColor() {
    switch (entry.dangerLevel) {
      case 'RED':
        return AppColors.triageRed;
      case 'YELLOW':
        return AppColors.gold;
      case 'GREEN':
        return AppColors.vetPrimaryDeep;
      default:
        return null;
    }
  }

  Color _levelBg() {
    switch (entry.dangerLevel) {
      case 'RED':
        return AppColors.coralTint;
      case 'YELLOW':
        return AppColors.goldTint;
      default:
        return AppColors.vetSurface;
    }
  }

  String? _levelLabel(AppLocalizations l10n) {
    switch (entry.dangerLevel) {
      case 'RED':
        return l10n.vetQueueLevelRed;
      case 'YELLOW':
        return l10n.vetQueueLevelYellow;
      case 'GREEN':
        return l10n.vetQueueLevelGreen;
      default:
        return null;
    }
  }

  Widget _badge(String text, Color bg, Color fg) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(5)),
      child: Text(text, style: AppTypography.micro.copyWith(color: fg, fontWeight: FontWeight.w600)),
    );
  }
}
