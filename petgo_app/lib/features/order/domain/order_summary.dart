/// 订单中心领域模型（Story 5.2）。对齐后端 5-1 `OrderPage`/`OrderSummaryView`。
///
/// 泛化 3 类订单（兽医/AI/充值；HD 预留）。**statusColor 后端权威**（兽医 REFUNDING→INFO 蓝非红）；
/// **title/subtitle 不由后端下发**——前端按 [orderType]+[statusCode] 本地化（i18n 契约，App 不渲染后端串）。
library;

/// 订单类型（后端 orderType）。
enum OrderType {
  vetConsult,
  aiUnlock,
  pawcoinTopup,
  idHd,
  unknown;

  static OrderType fromCode(String? code) => switch (code) {
        'VET_CONSULT' => OrderType.vetConsult,
        'AI_UNLOCK' => OrderType.aiUnlock,
        'PAWCOIN_TOPUP' => OrderType.pawcoinTopup,
        'ID_HD' => OrderType.idHd,
        _ => OrderType.unknown,
      };

  /// 回传后端筛选值（unknown/idHd 不作筛选值）。
  String? toApi() => switch (this) {
        OrderType.vetConsult => 'VET_CONSULT',
        OrderType.aiUnlock => 'AI_UNLOCK',
        OrderType.pawcoinTopup => 'PAWCOIN_TOPUP',
        OrderType.idHd => 'ID_HD',
        OrderType.unknown => null,
      };
}

/// 订单状态分组（前端筛选 + 头卡统计）。**只覆盖后端订单中心真实能出的 6 个状态码**
/// （待接单/待支付/失败等不进订单中心 A-5 → 不设 Menunggu 组，避免恒空筛选）。
/// - [ongoing] Berlangsung：IN_PROGRESS / REFUNDING（退款处理中仍算进行中）
/// - [done] Selesai：COMPLETED / COMPLETED_REFUND_REJECTED / REFUNDED / PAID
enum OrderStatusGroup {
  ongoing,
  done;

  static OrderStatusGroup fromStatus(String statusCode) => switch (statusCode) {
        // PENDING = 待支付充值（bug 20260720-313），归「进行中」组（可继续支付）。
        'IN_PROGRESS' || 'REFUNDING' || 'PENDING' => OrderStatusGroup.ongoing,
        _ => OrderStatusGroup.done,
      };
}

/// 状态色语义（后端权威 WARN/INFO/SUCCESS）。前端映射实际徽章色。
enum OrderStatusColor {
  warn,
  info,
  success,
  unknown;

  static OrderStatusColor fromCode(String? code) => switch (code) {
        'WARN' => OrderStatusColor.warn,
        'INFO' => OrderStatusColor.info,
        'SUCCESS' => OrderStatusColor.success,
        _ => OrderStatusColor.unknown,
      };
}

/// 一条订单卡。
class OrderSummary {
  const OrderSummary({
    required this.orderType,
    required this.orderToken,
    required this.displayNo,
    required this.statusCode,
    required this.statusColor,
    this.amount,
    this.payChannel,
    this.createdAt,
  });

  final OrderType orderType;
  final String orderToken;
  /// 人类可读订单号（bug 299，PREFIX-yyyyMMdd-序号）。
  final String displayNo;

  /// 状态码（前端本地化 + 图标/文案分支；如 IN_PROGRESS/COMPLETED/REFUNDING/COMPLETED_REFUND_REJECTED/PAID）。
  final String statusCode;
  final OrderStatusColor statusColor;

  /// 金额（IDR，可空——泛化/HD/待接单预留；本 story 3 类恒非 null）。
  final int? amount;
  final String? payChannel;
  final DateTime? createdAt;

  factory OrderSummary.fromJson(Map<String, dynamic> j) => OrderSummary(
        orderType: OrderType.fromCode(j['orderType'] as String?),
        orderToken: (j['orderToken'] ?? '') as String,
        displayNo: (j['displayNo'] ?? '') as String,
        statusCode: (j['statusCode'] ?? '') as String,
        statusColor: OrderStatusColor.fromCode(j['statusColor'] as String?),
        amount: (j['amount'] as num?)?.toInt(),
        payChannel: j['payChannel'] as String?,
        createdAt:
            j['createdAt'] == null ? null : DateTime.tryParse(j['createdAt'] as String)?.toLocal(),
      );
}

/// 订单列表页（游标分页 + PawCoin 余额汇总）。
class OrderPage {
  const OrderPage({
    required this.items,
    this.nextCursor,
    required this.hasMore,
    required this.pawcoinBalance,
  });

  final List<OrderSummary> items;
  final String? nextCursor;
  final bool hasMore;

  /// PawCoin 余额（koin，1:1；无钱包→0）。
  final int pawcoinBalance;

  factory OrderPage.fromJson(Map<String, dynamic> j) => OrderPage(
        items: ((j['items'] as List?) ?? const [])
            .map((e) => OrderSummary.fromJson((e as Map).cast<String, dynamic>()))
            .toList(growable: false),
        nextCursor: j['nextCursor'] as String?,
        hasMore: (j['hasMore'] ?? false) as bool,
        pawcoinBalance: (j['pawcoinBalance'] as num?)?.toInt() ?? 0,
      );
}
