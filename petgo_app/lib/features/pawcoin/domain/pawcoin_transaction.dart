/// PawCoin 余额与流水领域模型（Story 1.4）。对齐后端 `PawCoinWalletView` / `PawCoinTxnItem`
/// （只含展示字段：delta / type / refType / createdAt，无 id/refId/entryGroup）。
library;

/// 单条 PawCoin 流水（只读）。[delta] 正=入账（充值/退款/赠送）、负=消费。
class PawCoinTxnItem {
  const PawCoinTxnItem({
    required this.delta,
    required this.type,
    this.refType,
    this.createdAt,
  });

  /// 金额（+入账 / -消费，koin，1 koin=Rp1）。
  final int delta;

  /// 类型枚举名（TOPUP / SPEND / REFUND / BONUS）——前端按 code 本地化，勿渲染后端串。
  final String type;

  /// 来源类别（可空）。
  final String? refType;

  /// 发生时间（UTC）。
  final DateTime? createdAt;

  factory PawCoinTxnItem.fromJson(Map<String, dynamic> j) => PawCoinTxnItem(
        delta: (j['delta'] as num?)?.toInt() ?? 0,
        type: (j['type'] ?? '') as String,
        refType: j['refType'] as String?,
        createdAt: j['createdAt'] == null ? null : DateTime.tryParse(j['createdAt'] as String),
      );
}

/// 余额 + 流水游标分页页。
class PawCoinWalletPage {
  const PawCoinWalletPage({
    required this.balance,
    required this.items,
    this.nextCursor,
    required this.hasMore,
  });

  /// 当前余额（koin；无钱包→0）。
  final int balance;
  final List<PawCoinTxnItem> items;

  /// 下一页游标（末条 epochMillis；无更多→null）。
  final String? nextCursor;
  final bool hasMore;

  factory PawCoinWalletPage.fromJson(Map<String, dynamic> j) => PawCoinWalletPage(
        balance: (j['balance'] as num?)?.toInt() ?? 0,
        items: ((j['items'] as List?) ?? const [])
            .map((e) => PawCoinTxnItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        nextCursor: j['nextCursor'] as String?,
        hasMore: (j['hasMore'] ?? false) as bool,
      );
}
