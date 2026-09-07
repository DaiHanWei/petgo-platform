import 'pet_header_info.dart';

/// 宠物档案（后端 `PetProfileResponse` 的客户端不可变模型）。
///
/// 对外名片路径只用 [cardToken]（不可枚举）；[id] 仅授权态内部使用。
class PetProfile {
  const PetProfile({
    required this.id,
    required this.name,
    required this.cardToken,
    this.petType,
    this.avatarUrl,
    this.breed,
    this.birthday,
    this.sex,
    this.intro,
    this.createdAt,
    this.isSystemDefaultName = false,
    this.weightKg,
    this.neuterStatus,
  });

  final int id;
  final String name;
  final String cardToken;

  /// 宠物名当前是否为违规重置的系统默认编码名（内容审核 cm-4，D-CM4）。后端下发即解析；无 UI 强制。
  final bool isSystemDefaultName;

  /// 宠物类型（F6：CAT/DOG/OTHER）。创建后不可改——copyWith 不暴露此字段。
  final String? petType;
  final String? avatarUrl;
  final String? breed;
  final DateTime? birthday;

  /// 性别（V1.1.6 Story 1.1）：wire 值 `MALE` / `FEMALE`；**null = 未填**（编辑页显示「请选择」）。
  /// ⚠️ 只有两值，没有 `UNKNOWN` —— 身份证那套（[IdCard.gender]）才是三值，两者独立不联动。
  final String? sex;
  final String? intro;
  final DateTime? createdAt;

  /// 🔒 体重（kg），Story 6.1。**PII 邻近的健康数据 —— 禁止写入任何日志或埋点属性**（NFR-5）。
  ///
  /// null = 用户还没填（可跳过是刻意的：设必填会挡住建档转化）。
  /// 前端据此在推荐区尾部展示「补全档案，推荐更准」引导卡 —— 那是存量用户回填的唯一入口。
  final double? weightKg;

  /// NEUTERED / INTACT / UNKNOWN。null = 没填过（与 UNKNOWN「说不知道」不是一回事）。
  final String? neuterStatus;

  /// 档案是否完整到能给出精准推荐（有生日 + 有体重）。
  bool get isCompleteForReco => birthday != null && weightKg != null;
  /// 页头视图模型（V1.1.6 Story 2.3）。页头只需要这几样，不需要 id / cardToken。
  PetHeaderInfo get header => PetHeaderInfo(
        name: name,
        petType: petType,
        avatarUrl: avatarUrl,
        breed: breed,
        birthday: birthday,
        sex: sex,
        intro: intro,
      );

  factory PetProfile.fromJson(Map<String, dynamic> json) => PetProfile(
        id: json['id'] as int,
        name: json['name'] as String,
        cardToken: json['cardToken'] as String,
        petType: json['petType'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        breed: json['breed'] as String?,
        birthday: _parseDate(json['birthday']),
        // 未填时后端 NON_NULL 会让整个键消失 → 这里自然拿到 null。
        sex: json['sex'] as String?,
        intro: json['intro'] as String?,
        createdAt: _parseDate(json['createdAt']),
        isSystemDefaultName: (json['isSystemDefaultName'] ?? false) as bool,
        weightKg: (json['weightKg'] as num?)?.toDouble(),
        neuterStatus: json['neuterStatus'] as String?,
      );

  PetProfile copyWith({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    bool? isSystemDefaultName,
    double? weightKg,
    String? neuterStatus,
  }) =>
      PetProfile(
        id: id,
        name: name ?? this.name,
        cardToken: cardToken,
        petType: petType, // 创建后不可改，恒保留
        avatarUrl: avatarUrl ?? this.avatarUrl,
        breed: breed ?? this.breed,
        birthday: birthday ?? this.birthday,
        sex: sex ?? this.sex,
        intro: intro ?? this.intro,
        createdAt: createdAt,
        isSystemDefaultName: isSystemDefaultName ?? this.isSystemDefaultName,
        weightKg: weightKg ?? this.weightKg,
        neuterStatus: neuterStatus ?? this.neuterStatus,
      );

  static DateTime? _parseDate(Object? raw) {
    if (raw is! String || raw.isEmpty) return null;
    return DateTime.tryParse(raw);
  }
}
