/// 订单详情状态头的**纯函数**判定（v1.4.2 shop Story 9.1 AC3.3 / AC3.4 / AC5 / AC6，PRD §3.0）。
///
/// 🔴 只看服务端字段（`displayStatus`、`cancelReason`、`logistics.exception`、`reordered*`、`cancelledLines`），
/// **不按本地时钟改状态**（S-7：宽限期由后端 `displayStatus = CANCELLED` 表达）。
///
/// ⚠️ 9.2 扩展点：退款态（F13 / F16 / F19 / F25）在 [resolveShopOrderHeader] 最前面插分支即可。
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

class ShopOrderHeader {
  const ShopOrderHeader(this.kind, {this.hint = ShopHeaderHint.none});

  final ShopHeaderKind kind;
  final ShopHeaderHint hint;

  @override
  bool operator ==(Object other) =>
      other is ShopOrderHeader && other.kind == kind && other.hint == hint;

  @override
  int get hashCode => Object.hash(kind, hint);

  @override
  String toString() => 'ShopOrderHeader($kind, $hint)';
}

ShopOrderHeader resolveShopOrderHeader(ShopOrderDetail d) {
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
