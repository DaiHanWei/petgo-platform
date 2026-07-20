import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../domain/order_summary.dart';
import 'order_l10n.dart';
import 'order_list_controller.dart';
import 'widgets/order_card.dart';

/// 订单中心列表页（Story 5.2，p-orders；0718 保真改版）。
/// 紫渐变汇总头卡（本月支出 + 进行中/完成计数 + PawCoin 余额）+ 状态筛选（Semua/Berlangsung/Selesai，
/// 前端按后端真实态分组）+ 订单富卡 + 游标加载更多 + 空/错/离线态。
///
/// 头卡聚合前端算（V1 低量，`OrderCenterService` 不下发汇总）；状态分组只覆盖订单中心能出的 6 个真实态
/// （待接单/待支付/失败不进订单中心 A-5 → 无 Menunggu 组）。
class OrderListPage extends ConsumerStatefulWidget {
  const OrderListPage({super.key});

  @override
  ConsumerState<OrderListPage> createState() => _OrderListPageState();
}

class _OrderListPageState extends ConsumerState<OrderListPage> {
  /// 当前状态筛选（null = Semua）。前端过滤已加载项。
  OrderStatusGroup? _group;

  @override
  Widget build(BuildContext context) {
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
    final visible = _group == null
        ? state.items
        : state.items
            .where((o) => OrderStatusGroup.fromStatus(o.statusCode) == _group)
            .toList(growable: false);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        _summaryHeader(context, l10n, state),
        const SizedBox(height: AppSpacing.md),
        _statusChips(l10n),
        const SizedBox(height: AppSpacing.md),
        if (visible.isEmpty)
          _empty(l10n)
        else ...[
          for (final o in visible)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: OrderCard(order: o),
            ),
          // 加载更多仅在「全部」视图（前端分组过滤不跨页）。
          if (_group == null && state.hasMore) _loadMore(l10n, ref, state),
        ],
      ],
    );
  }

  /// 紫渐变汇总头卡：本月总支出 + 三宫格（进行中 / 完成 / PawCoin 余额）。
  Widget _summaryHeader(BuildContext context, AppLocalizations l10n, OrderListState state) {
    final now = DateTime.now();
    final monthSpent = state.items
        .where((o) =>
            o.amount != null &&
            o.createdAt != null &&
            o.createdAt!.year == now.year &&
            o.createdAt!.month == now.month &&
            o.statusCode != 'REFUNDED')
        .fold<int>(0, (sum, o) => sum + o.amount!);
    final ongoing = state.items
        .where((o) => OrderStatusGroup.fromStatus(o.statusCode) == OrderStatusGroup.ongoing)
        .length;
    final done = state.items
        .where((o) => OrderStatusGroup.fromStatus(o.statusCode) == OrderStatusGroup.done)
        .length;
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          colors: [Color(0xFF9E83DA), Color(0xFF845EC9)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.orderSpendingTitle(formatMonthYear(context, now)),
              style: AppTypography.caption.copyWith(color: Colors.white70)),
          const SizedBox(height: 4),
          Text('Rp${orderThousands(monthSpent)}',
              style: const TextStyle(
                  color: Colors.white, fontSize: 30, fontWeight: FontWeight.w800, height: 1.1)),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              _statTile(child: _statContent(ongoing.toString(), l10n.orderStatOngoing)),
              const SizedBox(width: AppSpacing.sm),
              _statTile(child: _statContent(done.toString(), l10n.orderStatDone)),
              const SizedBox(width: AppSpacing.sm),
              _statTile(
                onTap: () => context.push('/me/pawcoin'),
                child: _statContent(
                  _abbrCoins(state.pawcoinBalance),
                  l10n.orderStatPawcoin,
                  trailingChevron: true,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _statTile({required Widget child, VoidCallback? onTap}) {
    return Expanded(
      child: Material(
        color: Colors.white.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md, horizontal: AppSpacing.sm),
            child: child,
          ),
        ),
      ),
    );
  }

  Widget _statContent(String value, String label, {bool trailingChevron = false}) {
    return Column(
      children: [
        Text(value,
            style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
        const SizedBox(height: 2),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Flexible(
              child: Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white70, fontSize: 11)),
            ),
            if (trailingChevron)
              const Icon(Icons.chevron_right, size: 14, color: Colors.white70),
          ],
        ),
      ],
    );
  }

  /// 状态筛选 chip：Semua / Berlangsung / Selesai（前端过滤）。
  Widget _statusChips(AppLocalizations l10n) {
    final options = <(OrderStatusGroup?, String)>[
      (null, l10n.orderFilterAll),
      (OrderStatusGroup.ongoing, l10n.orderStatOngoing),
      (OrderStatusGroup.done, l10n.orderStatDone),
    ];
    return Wrap(
      spacing: AppSpacing.sm,
      children: [
        for (final (group, label) in options)
          ChoiceChip(
            label: Text(label),
            selected: _group == group,
            onSelected: (_) => setState(() => _group = group),
          ),
      ],
    );
  }

  /// PawCoin 余额缩写（≥1000 → "40K"，与参考头卡一致）。
  String _abbrCoins(int n) {
    if (n >= 1000) {
      final k = n / 1000;
      return '${k == k.truncate() ? k.truncate() : k.toStringAsFixed(1)}K';
    }
    return n.toString();
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
