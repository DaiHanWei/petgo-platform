/// 宠物档案（后端 `PetProfileResponse` 的客户端不可变模型）。
///
/// 对外名片路径只用 [cardToken]（不可枚举）；[id] 仅授权态内部使用。
class PetProfile {
  const PetProfile({
    required this.id,
    required this.name,
    required this.cardToken,
    this.avatarUrl,
    this.breed,
    this.birthday,
    this.intro,
    this.createdAt,
  });

  final int id;
  final String name;
  final String cardToken;
  final String? avatarUrl;
  final String? breed;
  final DateTime? birthday;
  final String? intro;
  final DateTime? createdAt;

  factory PetProfile.fromJson(Map<String, dynamic> json) => PetProfile(
        id: json['id'] as int,
        name: json['name'] as String,
        cardToken: json['cardToken'] as String,
        avatarUrl: json['avatarUrl'] as String?,
        breed: json['breed'] as String?,
        birthday: _parseDate(json['birthday']),
        intro: json['intro'] as String?,
        createdAt: _parseDate(json['createdAt']),
      );

  PetProfile copyWith({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? intro,
  }) =>
      PetProfile(
        id: id,
        name: name ?? this.name,
        cardToken: cardToken,
        avatarUrl: avatarUrl ?? this.avatarUrl,
        breed: breed ?? this.breed,
        birthday: birthday ?? this.birthday,
        intro: intro ?? this.intro,
        createdAt: createdAt,
      );

  static DateTime? _parseDate(Object? raw) {
    if (raw is! String || raw.isEmpty) return null;
    return DateTime.tryParse(raw);
  }
}
