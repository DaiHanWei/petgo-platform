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
    this.cardNo,
    this.passportNo,
    this.gender,
    this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.avatarUrl,
    this.intro,
    this.hdUnlocked = false,
    this.birthCity,
    this.address,
    this.occupation,
    this.maritalStatus,
    this.school,
    this.faculty,
  });

  final bool generated;
  final int? serialId;

  /// TT 开头 14 位身份码（新编码规则，spec ktp-pet-idcode-numbering）。旧卡为 null → 卡面走旧拼号。
  final String? cardNo;

  /// TT 开头 12 位护照号（新编码规则）。旧卡为 null → 护照面走旧拼号。
  final String? passportNo;

  /// 性别 wire 原始值（MALE/FEMALE/UNKNOWN）。旧卡为 null → 卡面维持旧默认展示。
  final String? gender;
  final String? name;

  /// 宠物类型枚举原始值（CAT/DOG/OTHER）——展示前本地化，App 绝不渲染后端显示串。
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final String? avatarUrl;
  final String? intro;

  /// 是否已付费解锁高清图（Story 6.3）。驱动前端 paywall vs 直接下载。
  final bool hdUnlocked;

  /// 卡面趣味字段快照（bug 20260729-409：Edit Info 与卡面字段对齐）。
  /// null = 渲染趣味默认（BANDUNG / JL. MELATI... / CHIEF HAPPINESS OFFICER / LAJANG）。
  final String? birthCity;
  final String? address;
  final String? occupation;
  final String? maritalStatus;

  /// 学生卡专属快照（bug 20260730-429）。null = 渲染趣味默认。
  final String? school;
  final String? faculty;

  factory IdCardData.fromJson(Map<String, dynamic> json) {
    return IdCardData(
      generated: json['generated'] as bool? ?? false,
      serialId: (json['serialId'] as num?)?.toInt(),
      cardNo: json['cardNo'] as String?,
      passportNo: json['passportNo'] as String?,
      gender: json['gender'] as String?,
      name: json['name'] as String?,
      petType: json['petType'] as String?,
      breed: json['breed'] as String?,
      birthday: json['birthday'] == null
          ? null
          : DateTime.tryParse(json['birthday'] as String),
      avatarUrl: json['avatarUrl'] as String?,
      intro: json['intro'] as String?,
      hdUnlocked: json['hdUnlocked'] as bool? ?? false,
      birthCity: json['birthCity'] as String?,
      address: json['address'] as String?,
      occupation: json['occupation'] as String?,
      maritalStatus: json['maritalStatus'] as String?,
      school: json['school'] as String?,
      faculty: json['faculty'] as String?,
    );
  }
}

/// 身份证「快照卡」（Story 6.7）。区别于 [IdCardData]（单卡实时从档案渲染），[IdCard] 是一次建卡的
/// **信息快照**：卡信息与档案解耦，独立 [serialId]、独立 [hdUnlocked]、独立 [createdAt]。旧卡保留可看可下载。
///
/// 承接后端多卡端点 `GET/POST /api/v1/pet-profiles/me/id-cards`。[serialId] 仅作展示编号，绝不作分享/深链定位键。
@immutable
class IdCard {
  const IdCard({
    required this.id,
    this.serialId,
    this.cardNo,
    this.passportNo,
    this.gender,
    this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.avatarUrl,
    this.intro,
    this.hdUnlocked = false,
    this.createdAt,
    this.birthCity,
    this.address,
    this.occupation,
    this.maritalStatus,
    this.cardType,
    this.school,
    this.faculty,
  });

  /// 卡自身主键（授权态内部用；详情端点 `GET /pet-profiles/me/id-cards/{id}` 寻址）。
  final int id;
  final int? serialId;

  /// TT 开头 14 位身份码（新编码规则）。旧卡为 null → 展示走旧拼号。仅展示，不作定位键。
  final String? cardNo;

  /// TT 开头 12 位护照号（新编码规则）。旧卡为 null。仅展示，不作定位键。
  final String? passportNo;

  /// 性别 wire 原始值（MALE/FEMALE/UNKNOWN）。旧卡为 null。
  final String? gender;
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

  /// 卡面趣味字段快照（bug 20260729-409）。null = 渲染趣味默认。
  final String? birthCity;
  final String? address;
  final String? occupation;
  final String? maritalStatus;

  /// 卡种 KTP/PASSPORT/STUDENT（bug 20260730-430：一卡一面）。旧后端/存量卡 null → 视同 KTP。
  final String? cardType;

  /// 学生卡专属快照（bug 20260730-429）。null = 渲染趣味默认。
  final String? school;
  final String? faculty;

  factory IdCard.fromJson(Map<String, dynamic> json) {
    return IdCard(
      id: (json['id'] as num).toInt(),
      serialId: (json['serialId'] as num?)?.toInt(),
      cardNo: json['cardNo'] as String?,
      passportNo: json['passportNo'] as String?,
      gender: json['gender'] as String?,
      name: json['name'] as String?,
      petType: json['petType'] as String?,
      breed: json['breed'] as String?,
      birthday: json['birthday'] == null ? null : DateTime.tryParse(json['birthday'] as String),
      avatarUrl: json['avatarUrl'] as String?,
      intro: json['intro'] as String?,
      hdUnlocked: json['hdUnlocked'] as bool? ?? false,
      createdAt: json['createdAt'] == null ? null : DateTime.tryParse(json['createdAt'] as String),
      birthCity: json['birthCity'] as String?,
      address: json['address'] as String?,
      occupation: json['occupation'] as String?,
      maritalStatus: json['maritalStatus'] as String?,
      cardType: json['cardType'] as String?,
      school: json['school'] as String?,
      faculty: json['faculty'] as String?,
    );
  }

  /// 归一化卡种（bug 430）：旧后端/存量卡 null → KTP。
  String get effectiveCardType => cardType ?? 'KTP';

  /// 转成 [IdCardData] 以复用 KTP 卡面渲染（`buildKtpFields` / `KtpCardFront`）。快照恒 `generated=true`。
  IdCardData toIdCardData() => IdCardData(
        generated: true,
        serialId: serialId,
        cardNo: cardNo,
        passportNo: passportNo,
        gender: gender,
        name: name,
        petType: petType,
        breed: breed,
        birthday: birthday,
        avatarUrl: avatarUrl,
        intro: intro,
        hdUnlocked: hdUnlocked,
        birthCity: birthCity,
        address: address,
        occupation: occupation,
        maritalStatus: maritalStatus,
        school: school,
        faculty: faculty,
      );
}

/// 建卡请求（Story 6.7）。`POST /api/v1/pet-profiles/me/id-cards`。[name] 必填，其余可空。生日格式 `yyyy-MM-dd`
/// （后端已改必填，表单层保证非空）。[gender] wire 值 MALE/FEMALE/UNKNOWN，空视同 UNKNOWN。
@immutable
class CreateIdCardRequest {
  const CreateIdCardRequest({
    required this.name,
    this.petType,
    this.breed,
    this.birthday,
    this.gender,
    this.avatarUrl,
    this.intro,
    this.birthCity,
    this.address,
    this.occupation,
    this.maritalStatus,
    this.cardType,
    this.school,
    this.faculty,
  });

  final String name;
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final String? gender;
  final String? avatarUrl;
  final String? intro;

  /// 卡面趣味字段（bug 20260729-409）。null/空 = 后端落 null → 渲染趣味默认。
  final String? birthCity;
  final String? address;
  final String? occupation;
  final String? maritalStatus;

  /// 卡种 KTP/PASSPORT/STUDENT（bug 20260730-430：建卡即绑定单卡面）。null → 后端默认 KTP。
  final String? cardType;

  /// 学生卡专属（bug 20260730-429）。null/空 = 后端落 null → 渲染趣味默认。
  final String? school;
  final String? faculty;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'name': name,
        if (petType != null) 'petType': petType,
        if (breed != null) 'breed': breed,
        if (birthday != null) 'birthday': _isoDate(birthday!),
        if (gender != null) 'gender': gender,
        if (avatarUrl != null) 'avatarUrl': avatarUrl,
        if (intro != null) 'intro': intro,
        if (birthCity != null) 'birthCity': birthCity,
        if (address != null) 'address': address,
        if (occupation != null) 'occupation': occupation,
        if (maritalStatus != null) 'maritalStatus': maritalStatus,
        if (cardType != null) 'cardType': cardType,
        if (school != null) 'school': school,
        if (faculty != null) 'faculty': faculty,
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
  const HdPurchaseResult(
      {required this.unlocked, this.paymentToken, this.paymentDisplayNo, this.payload});

  final bool unlocked;
  final String? paymentToken;
  final String? paymentDisplayNo; // 可读支付号（bug 326，PAYHD-日期-序号；旧后端为 null 退 token）
  final String? payload; // QRIS 二维码串（EMVCo，本地生成二维码；对齐后端 HdPurchaseResponse.payload）

  /// 展示用支付号：优先可读号，旧后端无 displayNo 退 token。
  String? get paymentRef => paymentDisplayNo ?? paymentToken;

  factory HdPurchaseResult.fromJson(Map<String, dynamic> json) {
    final payment = json['payment'] as Map<String, dynamic>?;
    return HdPurchaseResult(
      unlocked: json['unlocked'] as bool? ?? false,
      paymentToken: payment?['token'] as String?,
      paymentDisplayNo: payment?['displayNo'] as String?,
      payload: json['payload'] as String?,
    );
  }
}
