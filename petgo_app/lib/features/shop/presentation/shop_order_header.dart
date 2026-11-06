/// 订单详情状态头的**纯函数**判定（v1.4.2 shop Story 9.1 AC3.3 / AC3.4 / AC5 / AC6，PRD §3.0）。
///
/// 🔴 只看服务端字段（`displayStatus`、`cancelReason`、`logistics.exception`、`reordered*`、`cancelledLines`），
/// **不按本地时钟改状态**（S-7：宽限期由后端 `displayStatus = CANCELLED` 表达）。
///
/// 9.2：退款态（F13 / F16 / F19 / F25）由 [displayRefundOf] 选出一张退款单，挂在 [ShopOrderHeader.refundStage]；
/// 文案覆盖程度见 [ShopRefundTextMode]。
library;

import '../domain/shop_order_detail.dart';

/// 状态头主体。
enum ShopHeaderKind {
  /// 待付款（F4）：倒计时块即状态头。
  pendingPayment,

  /// 待发货（F6）。
  pendingShipment,

  /// 已发货（F7）。
  shipped,

  /// 已送达（F9）。
  delivered,

  /// 配送异常（F10，`logistics.exception = DELIVERY_ISSUE`，覆盖已发货 / 已送达）。
  deliveryIssue,

  /// 已完成（F11）。
  completed,

  /// 已取消 · 付款超时（F12，含宽限期 S-7）。
  cancelledTimeout,

  /// 已取消 · 缺货（F13 标题；副句与退款入口归 9.2）。
  cancelledOutOfStock,

  /// 已取消 · 其余原因 / 存量无原因（只显示标题）。
  cancelled,

  /// 原单已重新下单（F20）。
  reordered,

  /// 退款中 / 已退款：通用兜底（9.2 覆盖）。
  refunding,
  refunded,

  /// 认不出的状态。
  other,
}

/// 副句覆盖（不改标题，只换副句）。
enum ShopHeaderHint {
  /// 用 [ShopHeaderKind] 自带的副句。
  none,

  /// F17 部分取消。
  partialCancel,

  /// F21 由原单重下的新单。
  reorderedFrom,
}

/// 退款态（9.2，PRD §3.0「退款中 / 已退款」行）。
enum ShopRefundStage {
  /// F13 待选择退款方式（`AWAITING_DESTINATION`）。
  awaitingDestination,

  /// F16 退款处理中（`PAYOUT_PENDING`）。
  payoutPending,

  /// F19 已退款（`COMPLETED` 且有现金段）。
  paidOut,

  /// F25 打款失败（`PAYOUT_FAILED`）。
  payoutFailed,
}

/// 退款态对状态头文案的覆盖程度。
enum ShopRefundTextMode {
  /// 不改标题与副句（如 F17 部分取消待选、其余原因待选、F20 原单）。
  none,

  /// 只换副句、保留原状态标题（部分取消 / 签收后赔付：订单仍有效，不能把「待发货」等标题盖掉）。
  hintOnly,

  /// 标题与副句都换（整单退款的 F13 缺货 / F16 / F19；任意范围的 F25）。
  full,
}

class ShopOrderHeader {
  const ShopOrderHeader(
    this.kind, {
    this.hint = ShopHeaderHint.none,
    this.refund,
    this.refundStage,
    this.refundTextMode = ShopRefundTextMode.none,
  });

  final ShopHeaderKind kind;
  final ShopHeaderHint hint;

  /// 状态头要展示的那张退款单（[displayRefundOf]）；无退款态为 null。
  final ShopOrderRefundSummary? refund;
  final ShopRefundStage? refundStage;
  final ShopRefundTextMode refundTextMode;

  /// F13 米黄提醒底色（UI「有意不改」：要用户去选）；F17 部分取消保持原样式。
  bool get warnTone =>
      refundStage == ShopRefundStage.awaitingDestination && refund?.refundScope != RefundScope.line;

  /// 底栏「Pilih cara pengembalian dana」（F13 / F17）。
  bool get showsChooseRefund => refundStage == ShopRefundStage.awaitingDestination;

  /// 状态头内「Isi ulang rekening」（F25）。
  bool get showsReenterAccount => refundStage == ShopRefundStage.payoutFailed;

  @override
  bool operator ==(Object other) =>
      other is ShopOrderHeader &&
      other.kind == kind &&
      other.hint == hint &&
      other.refund?.refundToken == refund?.refundToken &&
      other.refundStage == refundStage &&
      other.refundTextMode == refundTextMode;

  @override
  int get hashCode => Object.hash(kind, hint, refund?.refundToken, refundStage, refundTextMode);

  @override
  String toString() =>
      'ShopOrderHeader($kind, $hint, ${refund?.refundToken}, $refundStage, $refundTextMode)';
}

ShopOrderHeader resolveShopOrderHeader(ShopOrderDetail d) {
  final base = _baseHeader(d);
  // 9.2 退款分支：选一张退款单，挂退款态；文案覆盖程度按范围定。
  final r = displayRefundOf(d.refunds);
  if (r == null) return base;
  final stage = refundStageOf(r)!;
  return ShopOrderHeader(base.kind,
      hint: base.hint, refund: r, refundStage: stage, refundTextMode: _textModeOf(base.kind, r, stage));
}

ShopOrderHeader _baseHeader(ShopOrderDetail d) {
  // F20：原单已被重新下单 —— 标题整体替换，优先于取消原因。
  if (d.reorderedToOrderToken != null) {
    return const ShopOrderHeader(ShopHeaderKind.reordered);
  }
  final kind = _kindOf(d);
  var hint = ShopHeaderHint.none;
  if (d.reorderedFromOrderNo != null) {
    hint = ShopHeaderHint.reorderedFrom;
  } else if (isPartialCancel(d)) {
    hint = ShopHeaderHint.partialCancel;
  }
  return ShopOrderHeader(kind, hint: hint);
}

/// 单张退款单的退款态；不影响状态头（现金段 = 0 的 `COMPLETED`、认不出的状态）→ null。
ShopRefundStage? refundStageOf(ShopOrderRefundSummary r) => switch (r.refundStatus) {
      ShopRefundStatus.payoutFailed => ShopRefundStage.payoutFailed,
      ShopRefundStatus.awaitingDestination => ShopRefundStage.awaitingDestination,
      ShopRefundStatus.payoutPending => ShopRefundStage.payoutPending,
      // 无现金段（币段建单即退）不改状态头（AC1.1：等同 COIN_REFUNDED）。
      ShopRefundStatus.completed =>
        r.cashRefund > 0 && r.cashDestination != null ? ShopRefundStage.paidOut : null,
      ShopRefundStatus.unknown => null,
    };

/// 多张退款单只展示一张（AC1.1）：`PAYOUT_FAILED` > `AWAITING_DESTINATION` > `PAYOUT_PENDING` > `COMPLETED`，
/// 同级取最新创建（`createdAt` 缺失视为最早；同时刻取列表中靠后者——后端按创建时间升序下发）。
ShopOrderRefundSummary? displayRefundOf(List<ShopOrderRefundSummary> refunds) {
  ShopOrderRefundSummary? best;
  var bestRank = 1 << 30;
  for (final r in refunds) {
    final stage = refundStageOf(r);
    if (stage == null) continue;
    final rank = _rankOf(stage);
    if (best == null || rank < bestRank || (rank == bestRank && !_createdBefore(r, best))) {
      best = r;
      bestRank = rank;
    }
  }
  return best;
}

int _rankOf(ShopRefundStage s) => switch (s) {
      ShopRefundStage.payoutFailed => 0,
      ShopRefundStage.awaitingDestination => 1,
      ShopRefundStage.payoutPending => 2,
      ShopRefundStage.paidOut => 3,
    };

/// a 是否严格早于 b 创建。
bool _createdBefore(ShopOrderRefundSummary a, ShopOrderRefundSummary b) {
  final at = a.createdAt;
  final bt = b.createdAt;
  if (at == null) return bt != null;
  if (bt == null) return false;
  return at.isBefore(bt);
}

ShopRefundTextMode _textModeOf(ShopHeaderKind kind, ShopOrderRefundSummary r, ShopRefundStage stage) {
  // F25：要用户重新动作，任何范围都换成失败标题与副句。
  if (stage == ShopRefundStage.payoutFailed) return ShopRefundTextMode.full;
  // F20 原单：标题与「查看新订单」入口保留（入口仍在底栏）。
  if (kind == ShopHeaderKind.reordered) return ShopRefundTextMode.none;
  final whole = r.refundScope == RefundScope.whole;
  switch (stage) {
    case ShopRefundStage.awaitingDestination:
      // §6 只给了缺货一句；其余原因 / LINE / COMPENSATION 保持原标题与副句（待确认 4）。
      return whole && r.cancelReason == CancelReason.outOfStock
          ? ShopRefundTextMode.full
          : ShopRefundTextMode.none;
    case ShopRefundStage.payoutPending:
    case ShopRefundStage.paidOut:
      return whole ? ShopRefundTextMode.full : ShopRefundTextMode.hintOnly;
    case ShopRefundStage.payoutFailed:
      return ShopRefundTextMode.full;
  }
}

/// F19 打到银行的到账金额（展示用）。
///
/// 🔴 P-1 例外（Story 9.2 AC3.4 先例，同 `PayoutChannel.fee` 注释「费率只用于展示，净额由后端权威计算」）：
/// 后端 `ShopOrderRefundView` 未下发净额字段，只下发 `cashRefund` 与服务端写入的 `payoutFee`；
/// 净额口径与后端 `ShopRefundCalculator.payoutNet(cash, channel) = cash − fee` 一致。全 App 只此一处相减，
/// 后端补 `payoutNetAmount` 后改为直接取服务端值。
int payoutNetForDisplay(int cashRefund, int fee) => cashRefund - fee;

ShopHeaderKind _kindOf(ShopOrderDetail d) {
  final shown = d.shownStatus;
  switch (shown) {
    case ShopOrderStatus.pendingPayment:
      return ShopHeaderKind.pendingPayment;
    case ShopOrderStatus.pendingShipment:
      return ShopHeaderKind.pendingShipment;
    case ShopOrderStatus.shipped:
      return d.hasDeliveryIssue ? ShopHeaderKind.deliveryIssue : ShopHeaderKind.shipped;
    case ShopOrderStatus.delivered:
      return d.hasDeliveryIssue ? ShopHeaderKind.deliveryIssue : ShopHeaderKind.delivered;
    case ShopOrderStatus.completed:
      return ShopHeaderKind.completed;
    case ShopOrderStatus.cancelled:
      // S-7 宽限期：原始仍待支付、展示已取消 → 按超时显示（AC5.1）。
      if (d.status == ShopOrderStatus.pendingPayment) return ShopHeaderKind.cancelledTimeout;
      return switch (d.cancelReason) {
        CancelReason.paymentTimeout => ShopHeaderKind.cancelledTimeout,
        CancelReason.outOfStock => ShopHeaderKind.cancelledOutOfStock,
        _ => ShopHeaderKind.cancelled,
      };
    case ShopOrderStatus.refunding:
      return ShopHeaderKind.refunding;
    case ShopOrderStatus.refunded:
      return ShopHeaderKind.refunded;
    case ShopOrderStatus.unknown:
      return ShopHeaderKind.other;
  }
}

/// F17 部分取消（9.1 复审）：有取消行、订单仍有效（[_isLiveKind]）、且**没有整单退款单**（`scope = WHOLE`）。
///
/// 🔴 整单取消的已付单，后端 `fillRefundedQty` 会把全部行写进 `cancelledLines` —— 那不是「部分取消」，
/// 不画 F17 的行标 / 降权 /「Dikembalikan (N produk dibatalkan)」两行（否则会出现一行 Rp 0 的退款合计）。
bool isPartialCancel(ShopOrderDetail d) =>
    d.cancelledLines.isNotEmpty &&
    d.reorderedToOrderToken == null &&
    !d.refunds.any((r) => r.scope == 'WHOLE') &&
    _isLiveKind(_kindOf(d));

/// 部分取消副句只在「订单还活着」的态出现（整单已取消时那句「其余商品照常发货」不成立）。
bool _isLiveKind(ShopHeaderKind k) =>
    k == ShopHeaderKind.pendingShipment ||
    k == ShopHeaderKind.shipped ||
    k == ShopHeaderKind.delivered ||
    k == ShopHeaderKind.deliveryIssue ||
    k == ShopHeaderKind.completed;

/// 配送异常埋点取值（AC4.4，C-6）：`RETURNED → returned`、`LOST → lost`、`DAMAGED → damaged`；
/// 缺字段 / 认不出 → `unknown`。
String deliveryExceptionTypeForAnalytics(String? exceptionType) => switch (exceptionType) {
      'RETURNED' => 'returned',
      'LOST' => 'lost',
      'DAMAGED' => 'damaged',
      _ => 'unknown',
    };

/// 物流 5 段（AC4.1）：`statusKey` → 当前段下标（0..4）；未知 / null → null（不画时间轴）。
int? logisticsStepOf(String? statusKey) => switch (statusKey) {
      'awaiting_pickup' => 0,
      'picked' => 1,
      'in_transit' => 2,
      'out_for_delivery' => 3,
      'delivered' => 4,
      _ => null,
    };
