import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/order_repository.dart';
import '../domain/order_detail.dart';
import '../domain/order_summary.dart';
import 'order_l10n.dart';
import 'widgets/order_status_badge.dart';

/// 订单详情页（Story 5.3，p-order-detail）。按 orderType 分支 + 退款进度 + 宠物已删失效占位（FR-54D）+ 加载/404 态。
class OrderDetailPage extends ConsumerWidget {
  const OrderDetailPage({super.key, required this.token});

  final String token;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(orderDetailProvider(token));
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.orderDetailTitle)),
      body: SafeArea(
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _error(context, l10n, ref, e),
          data: (d) => _detail(context, l10n, d),
        ),
      ),
    );
  }

  Widget _detail(BuildContext context, AppLocalizations l10n, OrderDetail d) {
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        // 头部：类型 + 状态徽章 + 金额
        Row(
          children: [
            Icon(orderTypeIcon(d.orderType), color: AppColors.mint),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(orderTypeLabel(l10n, d.orderType),
                  style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
            ),
            OrderStatusBadge(statusCode: d.statusCode, statusColor: d.statusColor),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        _row(l10n.orderAmountLabel, orderAmountText(l10n, d.amount)),
        if (d.coins != null) _row(l10n.orderCoinsLabel, '${d.coins} koin'),
        if (d.payChannel != null) _row(l10n.orderChannelLabel, d.payChannel!),
        if (d.paidAt != null) _row(l10n.orderPaidAtLabel, _fmtDate(d.paidAt!)),
        if (d.createdAt != null) _row(l10n.orderCreatedAtLabel, _fmtDate(d.createdAt!)),

        // 兽医：宠物区块（已删占位）+ 会话
        if (d.orderType == OrderType.vetConsult) ...[
          const Divider(height: AppSpacing.xl),
          _petBlock(context, l10n, d),
          if (d.sessionEndedAt != null) _row(l10n.orderSessionEndedLabel, _fmtDate(d.sessionEndedAt!)),
        ],

        // 退款进度
        if (d.refundStage != null) ...[
          const Divider(height: AppSpacing.xl),
          _refundBlock(context, l10n, d),
        ],
      ],
    );
  }

  Widget _petBlock(BuildContext context, AppLocalizations l10n, OrderDetail d) {
    if (d.petDeleted) {
      // FR-54D：宠物已删失效占位（非报错）。
      return Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(color: AppColors.line2, borderRadius: BorderRadius.circular(10)),
        child: Row(
          children: [
            const Icon(Icons.pets_outlined, color: AppColors.muted),
            const SizedBox(width: AppSpacing.sm),
            Text(l10n.orderPetDeleted,
                style: AppTypography.body.copyWith(color: AppColors.textTertiary)),
          ],
        ),
      );
    }
    return Row(
      children: [
        CircleAvatar(
          radius: 20,
          backgroundColor: AppColors.mintTint,
          backgroundImage: (d.petAvatarUrl != null && d.petAvatarUrl!.isNotEmpty)
              ? NetworkImage(d.petAvatarUrl!)
              : null,
          child: (d.petAvatarUrl == null || d.petAvatarUrl!.isEmpty)
              ? const Icon(Icons.pets_outlined, size: 18, color: AppColors.mint)
              : null,
        ),
        const SizedBox(width: AppSpacing.sm),
        Text(d.petName ?? '—', style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
      ],
    );
  }

  // 退款进度块（可点 → 我的退款列表，用户在那里可选退款方式/查看进度）。
  // 退款入口已从「我的」页移除、统一并入订单流程，故此块必须可达 /me/refunds，否则退款不可达。
  Widget _refundBlock(BuildContext context, AppLocalizations l10n, OrderDetail d) {
    return Material(
      color: AppColors.mintTint,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: const ValueKey('orderRefundEntry'),
        borderRadius: BorderRadius.circular(10),
        onTap: () => context.push('/me/refunds'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.orderRefundProgressLabel,
                        style: AppTypography.micro
                            .copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
                    const SizedBox(height: 4),
                    Text(refundStageLabel(l10n, d.refundStage!),
                        style: AppTypography.body
                            .copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600)),
                    if (d.refundNetAmount != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                            '${l10n.refundNetLabel}: ${orderAmountText(l10n, d.refundNetAmount)}',
                            style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                      ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: AppColors.mint600),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(String label, String value) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
            Text(value, style: AppTypography.body),
          ],
        ),
      );

  Widget _error(BuildContext context, AppLocalizations l10n, WidgetRef ref, Object e) {
    final notFound = e is DioException && e.response?.statusCode == 404;
    return ListView(
      children: [
        const SizedBox(height: 120),
        Center(
          child: Text(notFound ? l10n.orderNotFound : l10n.orderLoadFailed,
              style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
        ),
        if (!notFound) ...[
          const SizedBox(height: AppSpacing.md),
          Center(
            child: OutlinedButton(
              onPressed: () => ref.invalidate(orderDetailProvider(token)),
              child: Text(l10n.orderRetry),
            ),
          ),
        ],
      ],
    );
  }

  String _fmtDate(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}
