/// 月租名额（V1.4.0 multi-pet Story 2.4 · 后端 2.2 `PetSlotOverview.Rental`）。
///
/// [token] 是名额的不可枚举对外标识（续租按它寻址）；[index] 从 1（「Slot sewa {n}」）。
class PetSlotRental {
  const PetSlotRental({
    required this.token,
    required this.index,
    this.source = sourceRent,
    this.startsAt,
    required this.expiresAt,
    this.daysLeft = 0,
  });

  static const String sourceRent = 'RENT';
  static const String sourceAdminGrant = 'ADMIN_GRANT';

  final String token;
  final int index;

  /// `RENT` / `ADMIN_GRANT`（运营补发）。界面统一显示「Sewa」。
  final String source;
  final DateTime? startsAt;

  /// 到期时刻（UTC）。
  final DateTime expiresAt;

  /// 剩余天数（后端向上取整，到期为 0）。
  final int daysLeft;

  /// D-13：≤ 3 天显示提醒色「· {n} hari lagi」。
  bool get endsSoon => daysLeft <= 3;

  /// 续租一次后的新到期（D11 行「{原到期} → {新到期}」，新到期 = 原到期 + [days]）。
  DateTime renewedExpiry(int days) => expiresAt.add(Duration(days: days));

  factory PetSlotRental.fromJson(Map<String, dynamic> json) => PetSlotRental(
        token: json['token'] as String? ?? '',
        index: (json['index'] as num?)?.toInt() ?? 0,
        source: json['source'] as String? ?? sourceRent,
        startsAt: _parse(json['startsAt']),
        // 缺字段（理论上不会）按「已到期」处理：不会被当成生效中的名额去续。
        expiresAt: _parse(json['expiresAt']) ?? DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
        daysLeft: (json['daysLeft'] as num?)?.toInt() ?? 0,
      );

  static DateTime? _parse(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;
}

/// 月租购买单状态（`GET /pet-slots/me/purchases/{purchaseToken}`，后端 2.2 `PetSlotPurchaseStatusView`）。
class PetSlotPurchaseStatus {
  const PetSlotPurchaseStatus({required this.status, this.expiresAt});

  static const String pending = 'PENDING';
  static const String paid = 'PAID';
  static const String failed = 'FAILED';
  static const String expired = 'EXPIRED';

  /// 已到账、发放失败（交人工补发，后端 2.2 复审 #1）。
  static const String orphanPaid = 'ORPHAN_PAID';

  /// 已到账、发放会超 5 个名额而未发（交人工退款，后端 2.2 复审 #2）。
  static const String overpaid = 'OVERPAID';

  final String status;

  /// 本单涉及名额的最晚到期日；只在 PAID 时有（D9 / D12 SnackBar 的 `{date}`）。
  final DateTime? expiresAt;

  bool get isPaid => status == paid;

  /// 钱已到账、但名额要人工处理（ORPHAN_PAID / OVERPAID）：界面按「付款已收到、处理中 / 请联系客服」说，
  /// **不能**说成支付失败（用户确实付了钱）。
  bool get isPaidNeedsSupport => status == orphanPaid || status == overpaid;

  factory PetSlotPurchaseStatus.fromJson(Map<String, dynamic> json) => PetSlotPurchaseStatus(
        status: json['status'] as String? ?? pending,
        expiresAt: json['expiresAt'] is String ? DateTime.tryParse(json['expiresAt'] as String) : null,
      );
}
