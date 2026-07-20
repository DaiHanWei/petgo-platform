import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../triage/presentation/widgets/triage_paywall.dart' show formatIdr;
import '../data/vet_repository.dart';
import '../domain/vet_income.dart';
import 'vet_empty_state.dart';
import 'widgets/vet_top_bar.dart';

/// 兽医收入页（Story 3.7，`p-vet-income`，UX-DR12）。当月待结算卡（到手合计 + 单数）+ 历史月结倒序。
/// 到手金额恒为订单 `vet_payout` 快照（后台改价不影响历史）。无轮询（一次拉取 + 下拉刷新）。
class VetIncomePage extends ConsumerStatefulWidget {
  const VetIncomePage({super.key});

  @override
  ConsumerState<VetIncomePage> createState() => _VetIncomePageState();
}

class _VetIncomePageState extends ConsumerState<VetIncomePage> {
  VetIncome? _income; // null = 首屏加载中
  bool _loading = true;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final income = await ref.read(vetRepositoryProvider).income();
      if (mounted) {
        setState(() {
          _income = income;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _failed = true;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final income = _income;
    return Scaffold(
      backgroundColor: AppColors.base,
      // 0718：作为底部 Tab（无 AppBar/返回键），用兽医端共享深色顶栏（原型 ref37「Pendapatan Saya」）。
      body: Column(
        children: [
          VetTopBar(title: l10n.vetIncomeTitle),
          Expanded(
            child: _loading && income == null
                ? const Center(child: CircularProgressIndicator())
                : _failed && income == null
                    ? VetEmptyState(icon: Icons.cloud_off_outlined, message: l10n.vetIncomeLoadFailed)
                    : RefreshIndicator(
                        onRefresh: _load,
                        child: ListView(
                          padding: const EdgeInsets.all(AppSpacing.md),
                          children: [
                      _CurrentMonthCard(item: income!.currentMonth),
                      const SizedBox(height: AppSpacing.lg),
                      Text(
                        l10n.vetIncomeHistoryTitle,
                        style: AppTypography.caption.copyWith(
                          color: AppColors.textSecondary,
                          letterSpacing: 0.6,
                        ),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      if (income.history.isEmpty)
                        VetEmptyState(icon: Icons.receipt_long_outlined, message: l10n.vetIncomeEmpty)
                      else
                        ...income.history.map((p) => Padding(
                              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                              child: _HistoryRow(item: p),
                            )),
                          ],
                        ),
                      ),
          ),
        ],
      ),
    );
  }
}

/// 当月待结算卡：大字到手合计 + 单数 + 「待结算」徽章（当月恒 PENDING，月结次月生成）。
class _CurrentMonthCard extends StatelessWidget {
  const _CurrentMonthCard({required this.item});

  final VetIncomePeriod item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 0718 ref37：当月待结算=品牌紫渐变卡 + 白字（区别于历史白卡）。
    return Container(
      key: const ValueKey('vetIncomeCurrentCard'),
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint600],
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(l10n.vetIncomeCurrentMonth,
                    style: AppTypography.caption
                        .copyWith(color: Colors.white.withValues(alpha: 0.8))),
              ),
              // 待结算药丸：白半透底 + 白字（紫卡之上）。
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(l10n.vetIncomePending,
                    style: AppTypography.micro
                        .copyWith(color: Colors.white, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            formatIdr(item.payoutAmount),
            key: const ValueKey('vetIncomeCurrentPayout'),
            style: AppTypography.display.copyWith(color: Colors.white, fontSize: 32),
          ),
          const SizedBox(height: 4),
          Text(l10n.vetIncomeOrders(item.orderCount),
              style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.8))),
        ],
      ),
    );
  }
}

/// 历史月结行：period + 单数 + 到手 Rp + 状态徽章。
class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.item});

  final VetIncomePeriod item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: ValueKey('vetIncomeHistory_${item.period}'),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.period, style: AppTypography.body.copyWith(color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(l10n.vetIncomeOrders(item.orderCount),
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(formatIdr(item.payoutAmount),
                  style: AppTypography.title.copyWith(color: AppColors.vetPrimary)),
              const SizedBox(height: 4),
              _StatusBadge(status: item.status),
            ],
          ),
        ],
      ),
    );
  }
}

/// 状态徽章：PENDING（待结算，琥珀）/ SETTLED（已结算，薄荷）。
class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final settled = status == 'SETTLED';
    // ref37：已结算=绿药丸；待结算=琥珀。
    final bg = settled ? AppColors.triageGreen.withValues(alpha: 0.14) : AppColors.goldTint;
    final fg = settled ? AppColors.onlineDeepGreen : AppColors.tipsBadgeText;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Text(
        settled ? l10n.vetIncomeSettled : l10n.vetIncomePending,
        style: AppTypography.micro.copyWith(color: fg, fontWeight: FontWeight.w700),
      ),
    );
  }
}
