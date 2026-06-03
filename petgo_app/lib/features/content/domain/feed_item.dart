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

/// Feed 卡片条目（对应后端 `FeedItemResponse`）。**不含点赞/评论数**（FR-17）。
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
    this.likeCount = 0,
    required this.createdAt,
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

  /// 首图（无图 → 纯文字卡）。
  final String? firstImageUrl;

  /// 卡片点赞数（PRD-642）。
  final int likeCount;
  final DateTime createdAt;

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
        likeCount: (json['likeCount'] ?? 0) as int,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

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
