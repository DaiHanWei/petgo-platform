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

/// 身份证「快照卡」（Story 6.7）。区别于 [IdCardData]（单卡实时从档案渲染），[IdCard] 是一次建卡的
/// **信息快照**：卡信息与档案解耦，独立 [serialId]、独立 [hdUnlocked]、独立 [createdAt]。旧卡保留可看可下载。
///
/// 承接后端多卡端点 `GET/POST /api/v1/me/id-cards`。[serialId] 仅作展示编号，绝不作分享/深链定位键。
@immutable
class IdCard {
  const IdCard({
    required this.id,
    this.serialId,
    this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.avatarUrl,
    this.intro,
    this.hdUnlocked = false,
    this.createdAt,
  });

  /// 卡自身主键（授权态内部用；详情端点 `GET /me/id-cards/{id}` 寻址）。
  final int id;
  final int? serialId;
  final String? name;

  /// 宠物类型枚举原始值（CAT/DOG/OTHER）——展示前本地化，App 绝不渲染后端显示串。
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final String? avatarUrl;
  final String? intro;

  /// 该卡是否已付费解锁高清图（每卡独立，Story 6.7）。
  final bool hdUnlocked;

  /// 建卡时间（UTC ISO8601）。历史列表按此倒序展示。
  final DateTime? createdAt;

  factory IdCard.fromJson(Map<String, dynamic> json) {
    return IdCard(
      id: (json['id'] as num).toInt(),
      serialId: (json['serialId'] as num?)?.toInt(),
      name: json['name'] as String?,
      petType: json['petType'] as String?,
      breed: json['breed'] as String?,
      birthday: json['birthday'] == null ? null : DateTime.tryParse(json['birthday'] as String),
      avatarUrl: json['avatarUrl'] as String?,
      intro: json['intro'] as String?,
      hdUnlocked: json['hdUnlocked'] as bool? ?? false,
      createdAt: json['createdAt'] == null ? null : DateTime.tryParse(json['createdAt'] as String),
    );
  }

  /// 转成 [IdCardData] 以复用 KTP 卡面渲染（`buildKtpFields` / `KtpCardFront`）。快照恒 `generated=true`。
  IdCardData toIdCardData() => IdCardData(
        generated: true,
        serialId: serialId,
        name: name,
        petType: petType,
        breed: breed,
        birthday: birthday,
        avatarUrl: avatarUrl,
        intro: intro,
        hdUnlocked: hdUnlocked,
      );
}

/// 建卡请求（Story 6.7）。`POST /api/v1/me/id-cards`。[name] 必填，其余可空。生日格式 `yyyy-MM-dd`。
@immutable
class CreateIdCardRequest {
  const CreateIdCardRequest({
    required this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.avatarUrl,
    this.intro,
  });

  final String name;
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final String? avatarUrl;
  final String? intro;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'name': name,
        if (petType != null) 'petType': petType,
        if (breed != null) 'breed': breed,
        if (birthday != null) 'birthday': _isoDate(birthday!),
        if (avatarUrl != null) 'avatarUrl': avatarUrl,
        if (intro != null) 'intro': intro,
      };

  static String _isoDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
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
  const HdPurchaseResult({required this.unlocked, this.paymentToken, this.payload});

  final bool unlocked;
  final String? paymentToken;
  final String? payload; // QRIS 二维码串（EMVCo，本地生成二维码；对齐后端 HdPurchaseResponse.payload）

  factory HdPurchaseResult.fromJson(Map<String, dynamic> json) {
    final payment = json['payment'] as Map<String, dynamic>?;
    return HdPurchaseResult(
      unlocked: json['unlocked'] as bool? ?? false,
      paymentToken: payment?['token'] as String?,
      payload: json['payload'] as String?,
    );
  }
}
