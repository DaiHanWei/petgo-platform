import 'package:flutter/foundation.dart';

/// 宠物身份证数据（Story 6.2，FR-49A/49B）。承接后端 6-1 `GET/POST /api/v1/pet-profiles/me/id-card`。
///
/// [generated] = 是否已分配流水号（老用户/未生成为 false → 前端渲染「尚未生成」引导态）。
/// [serialId] 仅作展示编号（如 KTP 的 NIK），**绝不**作分享/深链定位键（6-1 AC3 红线）。
@immutable
class IdCardData {
  const IdCardData({
    required this.generated,
    this.serialId,
    this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.avatarUrl,
    this.intro,
    this.hdUnlocked = false,
  });

  final bool generated;
  final int? serialId;
  final String? name;

  /// 宠物类型枚举原始值（CAT/DOG/OTHER）——展示前本地化，App 绝不渲染后端显示串。
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final String? avatarUrl;
  final String? intro;

  /// 是否已付费解锁高清图（Story 6.3）。驱动前端 paywall vs 直接下载。
  final bool hdUnlocked;

  factory IdCardData.fromJson(Map<String, dynamic> json) {
    return IdCardData(
      generated: json['generated'] as bool? ?? false,
      serialId: (json['serialId'] as num?)?.toInt(),
      name: json['name'] as String?,
      petType: json['petType'] as String?,
      breed: json['breed'] as String?,
      birthday: json['birthday'] == null
          ? null
          : DateTime.tryParse(json['birthday'] as String),
      avatarUrl: json['avatarUrl'] as String?,
      intro: json['intro'] as String?,
      hdUnlocked: json['hdUnlocked'] as bool? ?? false,
    );
  }
}

/// 身份证高清图付费下载渠道（Story 6.3）。DANA 已取消，仅 QRIS 现金 + PawCoin 余额。
enum HdPayChannel {
  qris('QRIS'),
  pawcoin('PAWCOIN');

  const HdPayChannel(this.wire);

  /// 后端枚举名（UPPER_SNAKE）。
  final String wire;
}

/// HD 购买结果（Story 6.3）。[unlocked] 同步已解锁（PawCoin/已购买）；否则 [paymentToken] 为 QRIS 待支付订单号。
class HdPurchaseResult {
  const HdPurchaseResult({required this.unlocked, this.paymentToken});

  final bool unlocked;
  final String? paymentToken;

  factory HdPurchaseResult.fromJson(Map<String, dynamic> json) {
    final payment = json['payment'] as Map<String, dynamic>?;
    return HdPurchaseResult(
      unlocked: json['unlocked'] as bool? ?? false,
      paymentToken: payment?['token'] as String?,
    );
  }
}
