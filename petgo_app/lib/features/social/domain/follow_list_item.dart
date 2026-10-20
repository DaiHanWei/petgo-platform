import '../../growth/domain/growth_badge.dart';
import 'follow_state.dart';

/// 关注 / 粉丝列表的 Tab（V1.3.4 follow · Story 2.2）。
enum FollowListTab {
  following('following'),
  followers('followers');

  const FollowListTab(this.wire);

  /// 路由 `?tab=` 与埋点 `tab` 的取值。
  final String wire;

  /// 缺省或非法 → [following]。
  static FollowListTab fromWire(String? raw) =>
      FollowListTab.values.firstWhere((t) => t.wire == raw, orElse: () => FollowListTab.following);
}

/// 列表的一行（对应后端 `FollowListItem`）。关系字段相对当前查看者（游客全 false）。
class FollowListItem {
  const FollowListItem({
    required this.userId,
    required this.deleted,
    this.userToken,
    this.nickname,
    this.avatarUrl,
    this.badge,
    this.followState = FollowState.none,
    this.blockedByMe = false,
    this.self = false,
    this.followEpoch = 0,
    this.petType,
    this.petBirthday,
  });

  /// 对方数字 id（跳现有主页路由）。
  final int userId;

  /// 对方不可枚举 token（关注操作用）；已注销 → null。
  final String? userToken;
  final String? nickname;
  final String? avatarUrl;

  /// 等级角标 + 头像框（V1.4.0 ① Story 3.2）；注销 / 停用 / 字段缺失为 null（不显示）。
  final GrowthBadge? badge;

  /// 已注销：显示「已注销用户」+ 默认头像，不可点、无按钮。
  final bool deleted;

  /// 我与对方的关注关系（快照）。
  final FollowState followState;

  /// 我主动拉黑了对方（只认 BLOCK，T-20）→ 该行无按钮。
  final bool blockedByMe;

  /// 这一行是我自己 → 无按钮。
  final bool self;

  /// 这页快照发请求前的全局关注版本号（不来自 JSON，Story 2.1 AC3）。
  final int followEpoch;

  /// 推荐关注行的宠物物种（`CAT` / `DOG` / `OTHER`）；无宠物 → null。
  final String? petType;

  /// 推荐关注行的宠物生日；无宠物 / 缺省 → null。
  final DateTime? petBirthday;

  FollowListItem withFollowEpoch(int epoch) => FollowListItem(
        userId: userId,
        deleted: deleted,
        userToken: userToken,
        nickname: nickname,
        avatarUrl: avatarUrl,
        badge: badge,
        followState: followState,
        blockedByMe: blockedByMe,
        self: self,
        followEpoch: epoch,
        petType: petType,
        petBirthday: petBirthday,
      );

  /// 缺键容忍（注销行身份键整个不下发）。
  factory FollowListItem.fromJson(Map<String, dynamic> json) => FollowListItem(
        userId: (json['userId'] as num).toInt(),
        deleted: (json['deleted'] ?? false) as bool,
        userToken: json['userToken'] as String?,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        badge: GrowthBadge.fromFlat(json, prefix: ''),
        followState: FollowState.fromJson(json),
        blockedByMe: (json['blockedByMe'] ?? false) as bool,
        self: (json['self'] ?? false) as bool,
        petType: json['petType'] as String?,
        petBirthday: DateTime.tryParse((json['petBirthday'] as String?) ?? ''),
      );
}

/// 列表分页信封 `{items, nextCursor, hasMore}`。
class FollowListPageData {
  const FollowListPageData({required this.items, this.nextCursor, this.hasMore = false});

  final List<FollowListItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory FollowListPageData.fromJson(Map<String, dynamic> json) => FollowListPageData(
        items: ((json['items'] as List?) ?? const [])
            .map((e) => FollowListItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(growable: false),
        nextCursor: json['nextCursor'] as String?,
        hasMore: (json['hasMore'] ?? false) as bool,
      );
}
