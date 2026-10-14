import '../../ops/domain/ops_target.dart';

/// 通知中心条目（对应后端 `NotificationItem`，Story 6.6）。对外只有 token + deepLinkType（无顺序 id）。
class NotificationItem {
  const NotificationItem({
    required this.type,
    this.title,
    this.body,
    this.deepLinkType,
    this.deepLinkToken,
    this.targetRef,
    required this.read,
    this.createdAt,
    this.actorName,
    this.actorCount = 0,
    this.actorUserId,
    this.actorToken,
    this.actorIFollow,
    this.actorFollowsMe,
    this.bodyDeleted = false,
    this.opsTarget,
  });

  /// 后端拼好文案的四种互动类型（V1.3.4 follow · Story 3.3 / 3.4）。
  static const Set<String> serverCopyTypes = {
    'CONTENT_LIKED',
    'CONTENT_COMMENTED',
    'USER_FOLLOWED',
    'PLACE_COMMENTED',
  };

  // VET_REPLY|CONSULT_CLOSED|CONTENT_LIKED|CONTENT_COMMENTED|NEW_CONSULT_REQUEST|PET_BIRTHDAY|COMPANION_ANNIVERSARY|MILESTONE_NODE|OPS_ANNOUNCEMENT（V1.3.4 运营通知）|USER_FOLLOWED|PLACE_COMMENTED|…
  final String type;
  final String? title;
  final String? body;
  final String? deepLinkType;
  final String? deepLinkToken;
  /// id 寻址类跳转标识（帖子 id / 会话 id）。token 用于标记已读；targetRef 用于算目标 location。
  final String? targetRef;
  final bool read;
  final DateTime? createdAt;
  /// 运营通知的跳转目标（V1.3.4 Story 6.3 · AD-16）：后端只对 `OPS_ANNOUNCEMENT` 附带；其它类型为 null。
  /// 解析全部交给 Story 5.2 的 [OpsTarget.fromJson]（未知 / 缺失 type → [OpsTarget.none]，不抛）。
  final OpsTarget? opsTarget;

  /// V1.3.4 follow · Story 3.3：最新可显示的触发人昵称（标题里只加粗它）；存量 / 系统类为 null。
  final String? actorName;

  /// 过滤后的触发人总数（含 [actorName] 那一位）；没有时 0。
  final int actorCount;

  /// 触发人数字 id（跳现有 `/users/:id` 主页路由，D-2）。
  final int? actorUserId;

  /// 触发人不可枚举 token（回关调 `PUT /users/{token}/follow`）。
  final String? actorToken;

  /// 只对 `USER_FOLLOWED`：我是否已关注该触发人（服务端初值）。
  final bool? actorIFollow;

  /// 只对 `USER_FOLLOWED`：该触发人是否仍关注我（T-41，回关按钮三态用）。
  final bool? actorFollowsMe;

  /// 评论已删除：[body] 是后端给的「评论已删除」占位文案，按稿显示灰色斜体（缺键 = false）。
  final bool bodyDeleted;

  /// 「后端文案条目」：四种互动类型且后端给了触发人昵称 —— 只有这类读 [title] / [body]（AC3.2）。
  /// 其余（含无触发人的存量点赞 / 评论，库值是中文兜底串）一律走本地文案。
  bool get hasServerCopy => actorName != null && actorName!.isNotEmpty && serverCopyTypes.contains(type);

  factory NotificationItem.fromJson(Map<String, dynamic> json) => NotificationItem(
        type: (json['type'] ?? '') as String,
        title: json['title'] as String?,
        body: json['body'] as String?,
        deepLinkType: json['deepLinkType'] as String?,
        deepLinkToken: json['deepLinkToken'] as String?,
        targetRef: json['targetRef'] as String?,
        read: (json['read'] ?? false) as bool,
        createdAt: json['createdAt'] == null ? null : DateTime.tryParse(json['createdAt'] as String),
        // V1.3.4 follow · Story 3.3：缺键容错（旧后端 / 全局 NON_NULL 不下发 null 键）。
        actorName: json['actorName'] as String?,
        actorCount: (json['actorCount'] as num?)?.toInt() ?? 0,
        actorUserId: (json['actorUserId'] as num?)?.toInt(),
        actorToken: json['actorToken'] as String?,
        actorIFollow: json['actorIFollow'] as bool?,
        actorFollowsMe: json['actorFollowsMe'] as bool?,
        bodyDeleted: json['bodyDeleted'] == true,
        opsTarget: json['opsTarget'] is Map
            ? OpsTarget.fromJson((json['opsTarget'] as Map).cast<String, dynamic>())
            : null,
      );
}

/// 通知中心分页（对应后端 `NotificationPage`）。
class NotificationPage {
  const NotificationPage({required this.items, this.nextCursor, required this.hasMore});

  final List<NotificationItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory NotificationPage.fromJson(Map<String, dynamic> json) => NotificationPage(
        items: (json['items'] as List? ?? [])
            .map((e) => NotificationItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        nextCursor: json['nextCursor'] as String?,
        hasMore: (json['hasMore'] ?? false) as bool,
      );
}
