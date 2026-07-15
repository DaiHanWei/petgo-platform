import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../domain/pawcoin_transaction.dart';
import 'pawcoin_controller.dart';

/// PawCoin 余额与流水页（Story 1.4 · `p-pawcoin-balance`）。品牌渐变余额头卡 + 只读流水列表。
/// <b>流水行绝不可点</b>（禁转账 UI）；错误态显式报错+重试（不静默画空态）；类型按 code 本地化。
class PawCoinPage extends ConsumerWidget {
  const PawCoinPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(pawCoinProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.pawcoinBalanceLabel)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        // bug 20260625-088：加载失败必须显式报错 + 重试，绝不静默画成空态。
        error: (e, _) => _ErrorState(onRetry: () => ref.read(pawCoinProvider.notifier).refresh()),
        data: (state) => _Content(state: state),
      ),
    );
  }
}

class _Content extends ConsumerWidget {
  const _Content({required this.state});

  final PawCoinState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return RefreshIndicator(
      onRefresh: () => ref.read(pawCoinProvider.notifier).refresh(),
      child: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          _BalanceCard(balance: state.balance),
          const SizedBox(height: AppSpacing.xl),
          Text(l10n.pawcoinTxnListTitle,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2)),
          const SizedBox(height: AppSpacing.sm),
          if (state.items.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.xl),
              child: EmptyState(
                key: const ValueKey('pawcoinEmpty'),
                title: l10n.pawcoinEmpty,
                message: l10n.pawcoinEmptyHint,
                icon: Icons.receipt_long_outlined,
                iconBackground: AppColors.cream2,
              ),
            )
          else
            ...state.items.map((t) => _LedgerRow(item: t)),
          if (state.items.isNotEmpty) _LoadMoreFooter(state: state),
        ],
      ),
    );
  }
}

/// 品牌渐变余额头卡：violet 渐变 + 白字大号余额 + 汇率副行 + Isi Saldo。
class _BalanceCard extends StatelessWidget {
  const _BalanceCard({required this.balance});

  final int balance;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.xl),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint500], // brand-primary → secondary
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.pawcoinBalanceLabel,
              style: const TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: AppSpacing.xs),
          Text(_grouped(balance),
              key: const ValueKey('pawcoinBalance'),
              style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w800, color: Colors.white)),
          const SizedBox(height: AppSpacing.xxs),
          Text(l10n.pawcoinRateHint(_grouped(balance)),
              style: const TextStyle(fontSize: 12, color: Colors.white70)),
          const SizedBox(height: AppSpacing.lg),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('pawcoinIsiSaldo'),
              style: FilledButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: AppColors.mint,
              ),
              onPressed: () => context.push('/me/pawcoin/recharge'), // 充值页属 Story 1.5
              child: Text(l10n.pawcoinIsiSaldo),
            ),
          ),
        ],
      ),
    );
  }
}

/// 只读流水行（继承 canonical ledger row）。<b>整行非交互、绝不可点</b>（禁转账 UI）。
/// 入账（delta>=0）绿 `+` / 消费（delta<0）红 `−`；类型按 code 本地化。
class _LedgerRow extends StatelessWidget {
  const _LedgerRow({required this.item});

  final PawCoinTxnItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final positive = item.delta >= 0;
    final amountColor = positive ? AppColors.triageGreen : AppColors.coral;
    final sign = positive ? '+' : '-';
    // 无 InkWell/onTap —— 行不可点。
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(_typeLabel(l10n, item.type),
                    style: const TextStyle(fontSize: 14, color: AppColors.ink)),
                if (item.createdAt != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(_date(item.createdAt!),
                        style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  ),
              ],
            ),
          ),
          Text('$sign${_grouped(item.delta.abs())}',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: amountColor)),
        ],
      ),
    );
  }
}

class _LoadMoreFooter extends ConsumerWidget {
  const _LoadMoreFooter({required this.state});

  final PawCoinState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    if (state.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
      );
    }
    if (state.loadMoreFailed) {
      // 加载更多失败：保留已加载 + 底部重试，不整屏报错。
      return Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Center(
          child: TextButton(
            key: const ValueKey('pawcoinLoadMoreRetry'),
            onPressed: () => ref.read(pawCoinProvider.notifier).loadMore(),
            child: Text(l10n.pawcoinLoadRetry),
          ),
        ),
      );
    }
    if (state.hasMore) {
      return Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Center(
          child: TextButton(
            key: const ValueKey('pawcoinLoadMore'),
            onPressed: () => ref.read(pawCoinProvider.notifier).loadMore(),
            child: Text(l10n.pawcoinLoadMore),
          ),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        key: const ValueKey('pawcoinError'),
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off_outlined, size: 48, color: AppColors.muted),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.pawcoinLoadFailed, style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(
            key: const ValueKey('pawcoinRetry'),
            onPressed: onRetry,
            child: Text(l10n.pawcoinLoadRetry),
          ),
        ],
      ),
    );
  }
}

/// 类型按 code 本地化（勿渲染后端串）。
String _typeLabel(AppLocalizations l10n, String type) => switch (type) {
      'TOPUP' => l10n.pawcoinTxnTopup,
      'SPEND' => l10n.pawcoinTxnSpend,
      'REFUND' => l10n.pawcoinTxnRefund,
      'BONUS' => l10n.pawcoinTxnBonus,
      _ => type,
    };

/// 千分位分组（印尼语 '.' 分隔），如 120000 → 120.000。
String _grouped(int n) {
  final s = n.abs().toString();
  final buf = StringBuffer();
  for (int i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
    buf.write(s[i]);
  }
  return buf.toString();
}

String _date(DateTime d) {
  final local = d.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)}';
}
