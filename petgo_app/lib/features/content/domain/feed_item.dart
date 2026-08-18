/// Feed 分类 Tab（Story 3.2，AC3）。`all` 是浏览态语义，对应后端 category=ALL。
enum FeedCategory {
  all('ALL'),
  daily('DAILY'),
  growthMoment('GROWTH_MOMENT'),
  knowledge('KNOWLEDGE');

  const FeedCategory(this.wire);

  /// 后端 category 查询参（UPPER_SNAKE）。
  final String wire;
}

/// Feed 卡片条目（对应后端 `FeedItemResponse`）。
///
/// ⚠️ V1.1.6 Story 3.2 起**含点赞态与评论数** —— FR-93 明确推翻了 V1.0.0 FR-17
/// 「点赞/评论数不在卡片展示」那条限制（首页要能直接点赞、看到有多少条评论）。
/// 本注释此前写的是「不含点赞/评论数（FR-17）」，别照着旧注释判断。
class FeedItem {
  const FeedItem({
    required this.id,
    required this.authorId,
    required this.authorDeleted,
    required this.type,
    this.authorNickname,
    this.authorAvatarUrl,
    this.body,
    this.firstImageUrl,
    required this.createdAt,
    this.visibility = kVisibilityPublic,
    this.likeCount = 0,
    this.liked = false,
    this.commentCount = 0,
  });

  final int id;
  final int authorId;

  /// 作者已注销（NFR-8）：前端渲染本地化「已注销用户」+ 默认头像，且头像不可点（Story 3.8）。
  final bool authorDeleted;
  final String? authorNickname;
  final String? authorAvatarUrl;

  /// 内容类型线格式（DAILY/GROWTH_MOMENT/KNOWLEDGE）。
  final String type;

  /// 正文全文（前端截前 2 行）；可空。
  final String? body;

  /// 可见范围线格式（V1.1.2 Story 4.1 · FR-83）：`PUBLIC` / `PRIVATE`。
  ///
  /// Feed 里恒为 `PUBLIC`（后端按 `visibility = PUBLIC` 过滤平台分发）；
  /// **「我的发布」复用同一 DTO**，私密内容会带 `PRIVATE` —— 我的页据此打「仅自己可见」标识
  /// （Story 4.2）。缺省按 `PUBLIC`（老后端不下发该字段时不至于把内容误标成私密）。
  final String visibility;

  /// 是否仅作者自己可见。
  bool get isPrivate => visibility == kVisibilityPrivate;

  /// 首图（无图 → 纯文字卡）。
  final String? firstImageUrl;

  final DateTime createdAt;

  /// 点赞数（V1.1.6 Story 3.2）。
  ///
  /// ⚠️ 后端**一直在下发**，只是当年 FR-17 的口径是"卡片不展示"，客户端从没读过。
  final int likeCount;

  /// 当前用户是否已赞（V1.1.6 Story 3.2）。未登录恒为 false。
  final bool liked;

  /// 评论数（V1.1.6 Story 3.2）。
  ///
  /// 🔴 口径与内容详情页**完全一致**（含自己那条尚未对外可见的评论）——
  /// 两处显示的是同一个数字，不一致用户只会以为出 bug（后端 Story 3.1 已对齐）。
  final int commentCount;

  bool get hasImage => firstImageUrl != null && firstImageUrl!.isNotEmpty;

  factory FeedItem.fromJson(Map<String, dynamic> json) => FeedItem(
        id: json['id'] as int,
        authorId: json['authorId'] as int,
        authorDeleted: (json['authorDeleted'] ?? false) as bool,
        authorNickname: json['authorNickname'] as String?,
        authorAvatarUrl: json['authorAvatarUrl'] as String?,
        type: (json['type'] ?? 'DAILY') as String,
        body: json['body'] as String?,
        firstImageUrl: json['firstImageUrl'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
        visibility: (json['visibility'] ?? kVisibilityPublic) as String,
        // 三项均为原始类型、后端恒下发；给默认值只是防老后端/测试夹具缺字段。
        likeCount: (json['likeCount'] ?? 0) as int,
        liked: (json['liked'] ?? false) as bool,
        commentCount: (json['commentCount'] ?? 0) as int,
      );
}

/// 可见范围线格式常量（与后端 `ContentVisibility` 同名同值；不新建枚举以免与既有 `type` 字符串风格分叉）。
const String kVisibilityPublic = 'PUBLIC';
const String kVisibilityPrivate = 'PRIVATE';

/// Feed 游标分页（对应后端 `{items, nextCursor, hasMore}`）。
class FeedPage {
  const FeedPage({required this.items, this.nextCursor, this.hasMore = false});

  final List<FeedItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory FeedPage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    return FeedPage(
      items: rawItems is List
          ? rawItems.map((e) => FeedItem.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
      nextCursor: json['nextCursor'] as String?,
      hasMore: (json['hasMore'] ?? false) as bool,
    );
  }
}
