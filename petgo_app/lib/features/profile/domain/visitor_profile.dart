import 'package:dio/dio.dart';

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
    this.iFollow,
    this.iFollowOwner,
    this.ownerToken,
    this.followEpoch = 0,
    this.species,
  });

  final String name;
  final String petType;

  /// 细分类型（V1.5.0 pet-species，后端 `species`；可空，显示走 `petSpeciesView(species:, petType:)`）。
  final String? species;
  final String? avatarUrl;
  final String? breed;

  /// 性别（V1.1.6 Story 1.1 起）。存量档案为空且不回填，故可空。
  final String? sex;
  final DateTime? birthday;
  final String? intro;

  /// 主人昵称，供顶部「由 {昵称} 分享」横幅用。查不到为 null。
  final String? ownerNickname;

  /// 我是否追更这只宠物（V1.4.2 social-topic · Story 1.1 / 1.4 · D-8）。
  /// **只有站内入口下发**；分享 token 公开路径缺键 → null。
  final bool? iFollow;

  /// 我是否关注这只宠物的主人（R-2，埋点 `is_following_owner`）。缺键 → null。
  final bool? iFollowOwner;

  /// 主人公开 token（R-2，埋点 `owner_id`；不上报自增 id）。缺键 → null。
  final String? ownerToken;

  /// 发请求前读到的追更全局版本号（`petFollowStateProvider.currentEpoch`，V1.4.2 1.4 复审）：
  /// 本会话里比它新的追更覆盖才盖过 [iFollow]。**客户端字段**，不来自 JSON（缺省 0）。
  final int followEpoch;

  /// 记上快照版本号（由 `visitorProfileProvider` 在取数前读、取数后贴）。
  VisitorProfile withFollowEpoch(int epoch) => VisitorProfile(
        name: name,
        petType: petType,
        species: species,
        avatarUrl: avatarUrl,
        breed: breed,
        sex: sex,
        birthday: birthday,
        intro: intro,
        ownerNickname: ownerNickname,
        iFollow: iFollow,
        iFollowOwner: iFollowOwner,
        ownerToken: ownerToken,
        followEpoch: epoch,
      );

  /// 页头视图模型 —— 与作者态用同一个类型，页头组件因此可以原样复用。
  PetHeaderInfo get header => PetHeaderInfo(
        name: name,
        petType: petType,
        species: species,
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
      species: json['species'] as String?,
      avatarUrl: json['avatarUrl'] as String?,
      breed: json['breed'] as String?,
      sex: json['sex'] as String?,
      birthday: rawBirthday == null ? null : DateTime.tryParse(rawBirthday),
      intro: json['intro'] as String?,
      ownerNickname: json['ownerNickname'] as String?,
      iFollow: json['iFollow'] as bool?,
      iFollowOwner: json['iFollowOwner'] as bool?,
      ownerToken: json['ownerToken'] as String?,
    );
  }
}

/// 访客档案加载失败的分类（V1.4.2 social-topic · Story 1.4 · AC4 / AC5，照 `ContentLoadErrorKind`）。
///
/// - [gone]：服务端明确说「不存在」（404：宠物已删 / 主人注销或封号 / 对方拉黑我 / 链接失效；
///   403 `blocked-user`：我拉黑了主人）→ A14，**四种原因共用一屏、文案不区分**。
/// - [network]：没拿到明确业务结论（断网 / 超时 / 5xx / 其它）→ A15，可重试。
enum VisitorLoadErrorKind { gone, network }

class VisitorLoadError implements Exception {
  const VisitorLoadError(this.kind);

  final VisitorLoadErrorKind kind;

  /// 404 / 403 → gone；其余（含没有响应）→ network。
  static VisitorLoadError classify(DioException e) => switch (e.response?.statusCode) {
        404 || 403 => const VisitorLoadError(VisitorLoadErrorKind.gone),
        _ => const VisitorLoadError(VisitorLoadErrorKind.network),
      };

  @override
  String toString() => 'VisitorLoadError(${kind.name})';
}
