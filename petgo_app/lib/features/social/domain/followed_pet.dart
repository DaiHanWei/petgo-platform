/// 「某用户追更的宠物」列表行（V1.4.2 social-topic · Story 1.5 · 对应后端 `FollowedPetItem`，Story 1.1）。
///
/// 关系字段（[iFollow] / [iFollowOwner]）相对**当前查看者**（游客恒 false）。
class FollowedPet {
  const FollowedPet({
    required this.petId,
    required this.name,
    this.petType,
    this.avatarUrl,
    this.ownerNickname,
    this.ownerToken,
    this.iFollow = false,
    this.iFollowOwner = false,
    this.followEpoch = 0,
    this.species,
  });

  /// 宠物 id（与站内访客路由 `/pets/{petId}` 同一标识）。
  final int petId;
  final String name;

  /// 物种线格式（`CAT` / `DOG` / `OTHER`）；缺键 → null。
  final String? petType;

  /// 细分类型（V1.5.0 pet-species，后端 `species`；可空，显示走 `petSpeciesView(species:, petType:)`）。
  final String? species;

  /// 宠物头像；无头像缺键 → null（按物种 emoji 占位）。
  final String? avatarUrl;

  /// 主人昵称；查不到缺键 → null（第二行只剩物种）。
  final String? ownerNickname;

  /// 主人公开 token（R-2 埋点 `owner_id`；封号也给）。
  final String? ownerToken;

  /// 查看者是否追更这只宠物（服务端快照）。
  final bool iFollow;

  /// 查看者是否关注这只宠物的主人（R-2 埋点 `is_following_owner`）。
  final bool iFollowOwner;

  /// 这页快照发请求前的全局追更版本号（不来自 JSON，同 `FollowListItem.followEpoch`）。
  final int followEpoch;

  FollowedPet withFollowEpoch(int epoch) => FollowedPet(
        petId: petId,
        name: name,
        petType: petType,
        species: species,
        avatarUrl: avatarUrl,
        ownerNickname: ownerNickname,
        ownerToken: ownerToken,
        iFollow: iFollow,
        iFollowOwner: iFollowOwner,
        followEpoch: epoch,
      );

  /// 缺键容忍（NON_NULL 省略的可空字段）。
  factory FollowedPet.fromJson(Map<String, dynamic> json) => FollowedPet(
        petId: (json['petId'] as num).toInt(),
        name: (json['name'] ?? '') as String,
        petType: json['petType'] as String?,
        species: json['species'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        ownerNickname: json['ownerNickname'] as String?,
        ownerToken: json['ownerToken'] as String?,
        iFollow: json['iFollow'] == true,
        iFollowOwner: json['iFollowOwner'] == true,
      );
}

/// 分页信封 `{items, nextCursor, hasMore, petFollowingCount?}`（后端 `FollowedPetPage`）。
class FollowedPetPage {
  const FollowedPetPage({required this.items, this.nextCursor, this.hasMore = false, this.petFollowingCount});

  final List<FollowedPet> items;
  final String? nextCursor;
  final bool hasMore;

  /// 该用户追更的宠物总数：**只首页**下发，后续页缺键 → null。
  final int? petFollowingCount;

  factory FollowedPetPage.fromJson(Map<String, dynamic> json) => FollowedPetPage(
        items: ((json['items'] as List?) ?? const [])
            .map((e) => FollowedPet.fromJson((e as Map).cast<String, dynamic>()))
            .toList(growable: false),
        nextCursor: json['nextCursor'] as String?,
        hasMore: (json['hasMore'] ?? false) as bool,
        petFollowingCount: (json['petFollowingCount'] as num?)?.toInt(),
      );
}
