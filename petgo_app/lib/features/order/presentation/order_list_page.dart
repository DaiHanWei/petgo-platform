import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/order_summary.dart';
import 'order_l10n.dart';
import 'order_list_controller.dart';
import 'widgets/order_card.dart';

/// 订单中心列表页（Story 5.2，p-orders）。PawCoin 余额块 + 类型筛选 + 订单卡 + 游标加载更多 + 空/错/离线态。
class OrderListPage extends ConsumerWidget {
  const OrderListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(orderListProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.orderMyTitle)),
      body: RefreshIndicator(
        onRefresh: () => ref.read(orderListProvider.notifier).refresh(),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => _error(context, l10n, ref),
          data: (state) => _list(context, l10n, ref, state),
        ),
      ),
    );
  }

  Widget _list(BuildContext context, AppLocalizations l10n, WidgetRef ref, OrderListState state) {
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        _pawcoinBlock(context, l10n, state.pawcoinBalance),
        const SizedBox(height: AppSpacing.md),
        _filterChips(l10n, ref, state.filter),
        const SizedBox(height: AppSpacing.md),
        if (state.items.isEmpty)
          _empty(l10n)
        else ...[
          for (final o in state.items)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: OrderCard(order: o),
            ),
          if (state.hasMore) _loadMore(l10n, ref, state),
        ],
      ],
    );
  }

  Widget _pawcoinBlock(BuildContext context, AppLocalizations l10n, int balance) {
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => context.push('/me/pawcoin'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
          child: Row(
            children: [
              const Icon(Icons.savings_outlined, size: 20, color: AppColors.mint),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(l10n.orderPawcoinBalance,
                    style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
              ),
              Text(orderAmountText(l10n, balance).replaceFirst('Rp', ''),
                  style: AppTypography.title.copyWith(fontWeight: FontWeight.w700, color: AppColors.mint)),
              const SizedBox(width: 4),
              Text('koin', style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _filterChips(AppLocalizations l10n, WidgetRef ref, OrderType? current) {
    final options = <(OrderType?, String)>[
      (null, l10n.orderFilterAll),
      (OrderType.vetConsult, l10n.orderTypeVet),
      (OrderType.aiUnlock, l10n.orderTypeAi),
      (OrderType.pawcoinTopup, l10n.orderTypeTopup),
    ];
    return Wrap(
      spacing: AppSpacing.sm,
      children: [
        for (final (type, label) in options)
          ChoiceChip(
            label: Text(label),
            selected: current == type,
            onSelected: (_) => ref.read(orderListProvider.notifier).setFilter(type),
          ),
      ],
    );
  }

  Widget _loadMore(AppLocalizations l10n, WidgetRef ref, OrderListState state) {
    if (state.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(AppSpacing.md),
        child: Center(child: SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))),
      );
    }
    return Center(
      child: TextButton(
        onPressed: () => ref.read(orderListProvider.notifier).loadMore(),
        child: Text(state.loadMoreFailed ? l10n.orderRetry : l10n.orderLoadMore),
      ),
    );
  }

  Widget _empty(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.only(top: 80),
        child: Column(
          children: [
            const Icon(Icons.receipt_long_outlined, size: 48, color: AppColors.textTertiary),
            const SizedBox(height: AppSpacing.md),
            Text(l10n.orderEmpty, style: AppTypography.title),
            const SizedBox(height: AppSpacing.sm),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
              child: Text(l10n.orderEmptyHint,
                  textAlign: TextAlign.center,
                  style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
            ),
          ],
        ),
      );

  Widget _error(BuildContext context, AppLocalizations l10n, WidgetRef ref) => ListView(
        children: [
          const SizedBox(height: 120),
          Center(child: Text(l10n.orderLoadFailed, style: AppTypography.body)),
          const SizedBox(height: AppSpacing.md),
          Center(
            child: OutlinedButton(
              onPressed: () => ref.read(orderListProvider.notifier).refresh(),
              child: Text(l10n.orderRetry),
            ),
          ),
        ],
      );
}
