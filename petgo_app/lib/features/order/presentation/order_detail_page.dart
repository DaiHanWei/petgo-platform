import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
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
        // 主信息卡（0718 保真：白卡包裹 · 标题+徽章 · 内联行）。
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: [
              BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 12,
                  offset: const Offset(0, 4)),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(orderTypeLabel(l10n, d.orderType),
                        style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  OrderStatusBadge(statusCode: d.statusCode, statusColor: d.statusColor),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              if (d.payChannel != null) _infoLine(l10n.orderChannelLabel, orderChannelText(d.payChannel)),
              _infoLine(l10n.orderAmountLabel, orderAmountText(l10n, d.amount)),
              if (d.coins != null) _infoLine(l10n.orderCoinsLabel, '${orderThousands(d.coins!)} koin'),
              ..._dateLines(context, l10n, d),
              // 兽医：宠物区块（已删占位 FR-54D）。
              if (d.orderType == OrderType.vetConsult && (d.petDeleted || d.petName != null)) ...[
                const SizedBox(height: AppSpacing.sm),
                _petBlock(context, l10n, d),
              ],
            ],
          ),
        ),

        // AI 问诊订单：详情下方给「查看诊断结果」入口 → 只读回看分诊结果快照
        // （bug 20260720-312；triageTaskId 后端已下发）。
        if (d.orderType == OrderType.aiUnlock && d.triageTaskId != null) ...[
          const SizedBox(height: AppSpacing.md),
          _aiResultEntry(context, l10n, d.triageTaskId!),
        ],

        // 兽医问诊订单：给「查看问诊记录」入口 → 打开会话页（CLOSED 会话正文平铺只读问诊确认单）
        // （bug 20260720-312；consultSessionId 后端已下发）。身份证订单暂无源（Epic6 未接订单中心）。
        if (d.orderType == OrderType.vetConsult && d.consultSessionId != null) ...[
          const SizedBox(height: AppSpacing.md),
          _consultResultEntry(context, l10n, d.consultSessionId!),
        ],

        // 待支付充值订单：给「继续充值」入口 → 充值页复用未过期 PENDING 意图重发 QRIS
        // （bug 20260720-313；60min 窗内可继续付款，超时后 scanner 置 EXPIRED 自然移出列表）。
        if (d.orderType == OrderType.pawcoinTopup && d.statusCode == 'PENDING') ...[
          const SizedBox(height: AppSpacing.md),
          _continueTopupEntry(context, l10n),
        ],

        // 退款进度
        if (d.refundStage != null) ...[
          const SizedBox(height: AppSpacing.md),
          _refundBlock(context, l10n, d),
        ],
      ],
    );
  }

  /// 兽医问诊结果入口卡（点 → 会话页；CLOSED 会话正文平铺只读问诊确认单/诊断）。
  Widget _consultResultEntry(BuildContext context, AppLocalizations l10n, int sessionId) {
    return Material(
      color: AppColors.mintTint,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: const ValueKey('orderConsultResultEntry'),
        borderRadius: BorderRadius.circular(10),
        onTap: () => context.push('/consult/conversation/$sessionId'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              const Icon(Icons.medical_information_outlined, color: AppColors.mint600),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(l10n.orderViewConsultResult,
                    style: AppTypography.body
                        .copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600)),
              ),
              const Icon(Icons.chevron_right, color: AppColors.mint600),
            ],
          ),
        ),
      ),
    );
  }

  /// 继续充值入口卡（待支付充值 → 充值页复用 PENDING 意图重发 QRIS）。
  Widget _continueTopupEntry(BuildContext context, AppLocalizations l10n) {
    return Material(
      color: AppColors.goldTint,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: const ValueKey('orderContinueTopupEntry'),
        borderRadius: BorderRadius.circular(10),
        onTap: () => context.push('/me/pawcoin/recharge'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              const Icon(Icons.savings_outlined, color: AppColors.tipsBadgeText),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(l10n.orderTopupContinue,
                    style: AppTypography.body
                        .copyWith(color: AppColors.tipsBadgeText, fontWeight: FontWeight.w600)),
              ),
              const Icon(Icons.chevron_right, color: AppColors.tipsBadgeText),
            ],
          ),
        ),
      ),
    );
  }

  /// AI 诊断结果入口卡（点 → `/triage/result/:id` 只读快照回看）。
  Widget _aiResultEntry(BuildContext context, AppLocalizations l10n, int triageId) {
    return Material(
      color: AppColors.mintTint,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: const ValueKey('orderAiResultEntry'),
        borderRadius: BorderRadius.circular(10),
        onTap: () => context.push('/triage/result/$triageId'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              const Icon(Icons.description_outlined, color: AppColors.mint600),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(l10n.orderViewAiResult,
                    style: AppTypography.body
                        .copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600)),
              ),
              const Icon(Icons.chevron_right, color: AppColors.mint600),
            ],
          ),
        ),
      ),
    );
  }

  /// 日期行（按状态择一）：兽医会话开始/结束 · 支付时间 · 建单时间；均带时分。
  List<Widget> _dateLines(BuildContext context, AppLocalizations l10n, OrderDetail d) {
    if (d.orderType == OrderType.vetConsult) {
      if (d.sessionStartedAt != null && d.sessionEndedAt == null) {
        return [_infoLine(l10n.orderSessionStartedLabel, formatDayMonthYearTime(context, d.sessionStartedAt!))];
      }
      if (d.sessionEndedAt != null) {
        return [_infoLine(l10n.orderSessionEndedLabel, formatDayMonthYearTime(context, d.sessionEndedAt!))];
      }
    }
    if (d.paidAt != null) {
      return [_infoLine(l10n.orderPaidAtLabel, formatDayMonthYearTime(context, d.paidAt!))];
    }
    if (d.createdAt != null) {
      return [_infoLine(l10n.orderCreatedAtLabel, formatDayMonthYearTime(context, d.createdAt!))];
    }
    return const [];
  }

  /// 内联信息行「Label: value」（参考 0718 详情卡样式）。
  Widget _infoLine(String label, String value) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text('$label: $value', style: AppTypography.body),
      );

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
}
