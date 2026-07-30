import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../domain/order_detail.dart';
import '../domain/order_summary.dart';

/// 订单枚举 → 本地化文案 + 图标 + 徽章配色（Story 5.2）。按 code 本地化，勿渲染后端串。

/// 类型图标（4 类；HD 预留）。
IconData orderTypeIcon(OrderType t) => switch (t) {
      OrderType.vetConsult => Icons.medical_services_outlined,
      OrderType.aiUnlock => Icons.auto_awesome_outlined,
      OrderType.pawcoinTopup => Icons.savings_outlined,
      OrderType.idHd => Icons.badge_outlined,
      OrderType.unknown => Icons.receipt_long_outlined,
    };

/// 类型标题。
String orderTypeLabel(AppLocalizations l10n, OrderType t) => switch (t) {
      OrderType.vetConsult => l10n.orderTypeVet,
      OrderType.aiUnlock => l10n.orderTypeAi,
      OrderType.pawcoinTopup => l10n.orderTypeTopup,
      OrderType.idHd => l10n.orderTypeIdHd,
      OrderType.unknown => l10n.orderTypeUnknown,
    };

/// 状态副标题（按 statusCode）。
String orderStatusLabel(AppLocalizations l10n, String statusCode) => switch (statusCode) {
      'IN_PROGRESS' => l10n.orderStatusInProgress,
      'COMPLETED' => l10n.orderStatusCompleted,
      'COMPLETED_REFUND_REJECTED' => l10n.orderStatusRefundRejected,
      'REFUNDING' => l10n.orderStatusRefunding,
      'REFUNDED' => l10n.orderStatusRefunded,
      'PAID' => l10n.orderStatusPaid,
      'PENDING' => l10n.orderStatusPending,
      _ => statusCode,
    };

/// statusColor → 徽章 (背景, 文字)。**INFO→紫柔底（非红）**：退款处理中不制造焦虑（UX-DR2）。
({Color bg, Color fg}) orderStatusColors(OrderStatusColor c) => switch (c) {
      OrderStatusColor.success => (bg: AppColors.momenBadgeBg, fg: AppColors.momenBadgeText),
      OrderStatusColor.warn => (bg: AppColors.goldTint, fg: AppColors.tipsBadgeText),
      OrderStatusColor.info => (bg: AppColors.mintTint, fg: AppColors.mint600),
      OrderStatusColor.unknown => (bg: AppColors.line2, fg: AppColors.muted),
    };

/// 退款子阶段文案（Story 5.3，兽医订单退款进度）。
String refundStageLabel(AppLocalizations l10n, RefundStage stage) => switch (stage) {
      RefundStage.awaitingMethod => l10n.orderRefundAwaitingMethod,
      RefundStage.awaitingApproval => l10n.orderRefundAwaitingApproval,
      RefundStage.awaitingPayout => l10n.orderRefundAwaitingPayout,
      RefundStage.processing => l10n.orderRefundProcessing,
      RefundStage.rejected => l10n.orderRefundRejected,
      RefundStage.unknown => l10n.orderStatusRefunding,
    };

/// 千分位（点分隔，印尼格式）。
String orderThousands(int n) {
  final s = n.abs().toString();
  final buf = StringBuffer();
  for (int i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
    buf.write(s[i]);
  }
  return buf.toString();
}

/// 金额展示：非 null 千分位 `Rp...`，null → "Belum ada pembayaran"（待接单/HD 预留）。
String orderAmountText(AppLocalizations l10n, int? amount) {
  if (amount == null) return l10n.orderNoPayment;
  return 'Rp${orderThousands(amount)}';
}

/// 订单卡顶部状态色条颜色（复用徽章前景强调色：WARN=金 / INFO=薄荷 / SUCCESS=绿）。
Color orderStatusStripe(OrderStatusColor c) => orderStatusColors(c).fg;

/// 支付渠道展示（品牌名不本地化；PAWCOIN→PawCoin，其余大写原样如 QRIS/DANA）。
String orderChannelText(String? channel) {
  if (channel == null || channel.isEmpty) return '';
  if (channel.toUpperCase() == 'PAWCOIN') return 'PawCoin';
  return channel.toUpperCase();
}

/// 订单卡副行：`渠道 · 金额 [→ N koin] · 日期`（缺渠道省渠道段，充值附币量换算）。
String orderCardSubtitle(BuildContext context, AppLocalizations l10n, OrderSummary order) {
  final segs = <String>[];
  final ch = orderChannelText(order.payChannel);
  if (ch.isNotEmpty) segs.add(ch);
  if (order.orderType == OrderType.pawcoinTopup && order.amount != null) {
    segs.add('${orderAmountText(l10n, order.amount)} → ${orderThousands(order.amount!)} ${l10n.orderCoinsSuffix}');
  } else {
    segs.add(orderAmountText(l10n, order.amount));
  }
  if (order.createdAt != null) segs.add(formatDayMonthTime(context, order.createdAt!));
  return segs.join(' · ');
}
