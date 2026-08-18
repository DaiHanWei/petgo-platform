import 'pet_header_info.dart';
import 'pet_profile.dart';

/// 访客看到的宠物档案（V1.1.6 Story 2.3）。
///
/// 服务端给的是**白名单**：名字 · 头像 · 物种 · 品种 · 性别 · 生日 · 自述 · 主人昵称。
/// 作者态的 [PetProfile] 还带着内部 id、`cardToken`、`ownerId` 等 —— 访客那边**根本没下发**。
///
/// ⚠️ 这里刻意**不复用** [PetProfile]：它的 `id` / `cardToken` 是必填的，
/// 硬套过来只能塞假值，而假值会在将来某个地方被当真。
class VisitorProfile {
  const VisitorProfile({
    required this.name,
    required this.petType,
    this.avatarUrl,
    this.breed,
    this.sex,
    this.birthday,
    this.intro,
    this.ownerNickname,
  });

  final String name;
  final String petType;
  final String? avatarUrl;
  final String? breed;

  /// 性别（V1.1.6 Story 1.1 起）。存量档案为空且不回填，故可空。
  final String? sex;
  final DateTime? birthday;
  final String? intro;

  /// 主人昵称，供顶部「由 {昵称} 分享」横幅用。查不到为 null。
  final String? ownerNickname;

  /// 页头视图模型 —— 与作者态用同一个类型，页头组件因此可以原样复用。
  PetHeaderInfo get header => PetHeaderInfo(
        name: name,
        petType: petType,
        avatarUrl: avatarUrl,
        breed: breed,
        birthday: birthday,
        sex: sex,
        intro: intro,
      );

  static VisitorProfile fromJson(Map<String, dynamic> json) {
    final rawBirthday = json['birthday'] as String?;
    return VisitorProfile(
      name: (json['name'] as String?) ?? '',
      petType: (json['petType'] as String?) ?? 'OTHER',
      avatarUrl: json['avatarUrl'] as String?,
      breed: json['breed'] as String?,
      sex: json['sex'] as String?,
      birthday: rawBirthday == null ? null : DateTime.tryParse(rawBirthday),
      intro: json['intro'] as String?,
      ownerNickname: json['ownerNickname'] as String?,
    );
  }
}
