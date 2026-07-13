/// 兽医收入（Story 3.7，后端 `GET /vet/income`）。到手金额恒为订单 `vet_payout` 快照（成交冻结）。金额 IDR。
class VetIncome {
  const VetIncome({required this.currentMonth, this.history = const []});

  /// 当月待结算实时聚合（本月 COMPLETED 订单，status 恒 PENDING）。
  final VetIncomePeriod currentMonth;

  /// 历史月结（`vet_settlements` 已生成行），后端已按 period 倒序。
  final List<VetIncomePeriod> history;

  factory VetIncome.fromJson(Map<String, dynamic> json) => VetIncome(
        currentMonth: VetIncomePeriod.fromJson(
            ((json['currentMonth'] as Map?) ?? const {}).cast<String, dynamic>()),
        history: ((json['history'] as List?) ?? const [])
            .map((e) => VetIncomePeriod.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}

/// 一个月的收入聚合（当月待结算 或 历史月结）。
class VetIncomePeriod {
  const VetIncomePeriod({
    required this.period,
    this.orderCount = 0,
    this.grossAmount = 0,
    this.payoutAmount = 0,
    this.status = 'PENDING',
  });

  final String period; // YYYY-MM（WIB）
  final int orderCount;
  final int grossAmount; // 成交额合计 IDR
  final int payoutAmount; // 到手合计 IDR
  final String status; // PENDING（待结算）| SETTLED（已结算）

  bool get isSettled => status == 'SETTLED';

  factory VetIncomePeriod.fromJson(Map<String, dynamic> json) => VetIncomePeriod(
        period: (json['period'] ?? '') as String,
        orderCount: (json['orderCount'] as num?)?.toInt() ?? 0,
        grossAmount: (json['grossAmount'] as num?)?.toInt() ?? 0,
        payoutAmount: (json['payoutAmount'] as num?)?.toInt() ?? 0,
        status: (json['status'] ?? 'PENDING') as String,
      );
}
