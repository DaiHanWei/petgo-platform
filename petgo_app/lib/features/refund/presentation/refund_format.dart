import '../../../l10n/app_localizations.dart';
import '../domain/refund_request.dart';

/// 退款 UI 本地化/格式化辅助（Story 4.5）。按 code 本地化，勿渲染后端串。

/// 千分位（不带 Rp，如 `1.234.500`）。
String thousandsFmt(int n) {
  final s = n.abs().toString();
  final buf = StringBuffer();
  for (int i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
    buf.write(s[i]);
  }
  return buf.toString();
}

/// 千分位金额（`Rp1.234.500`）。
String rpFmt(int n) => 'Rp${thousandsFmt(n)}';

/// 出款渠道本地化（BCA/OVO/GOPAY）。
String channelLabel(AppLocalizations l10n, String code) => switch (code) {
      'BCA' => l10n.refundChannelBca,
      'OVO' => l10n.refundChannelOvo,
      'GOPAY' => l10n.refundChannelGopay,
      _ => code,
    };

/// 退款状态本地化：优先 approvalStatus，其次 needDecision（供列表状态 chip）。
String refundStatusLabel(AppLocalizations l10n, MyRefund r) {
  switch (r.approvalStatus) {
    case 'DONE':
      return l10n.refundStatusDone;
    case 'PENDING_APPROVAL':
    case 'APPROVED':
    case 'PROCESSING':
      return l10n.refundStatusSubmitted;
    case 'REJECTED':
      return l10n.refundStatusRejected;
  }
  return switch (r.needDecision) {
    'APPROVED' => l10n.refundStatusApproved,
    'REJECTED' => l10n.refundStatusRejected,
    _ => l10n.refundStatusPendingReview,
  };
}
