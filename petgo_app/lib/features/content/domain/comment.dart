/// 评论（对应后端 `CommentResponse`）。两级：一级含 [replyCount] + 内嵌前 3 条 [replies]；
/// 二级回复 [replyCount]/[replies] 为 null。
class Comment {
  const Comment({
    required this.id,
    required this.authorId,
    required this.authorDeleted,
    required this.body,
    required this.createdAt,
    this.authorNickname,
    this.authorAvatarUrl,
    this.replyCount,
    this.replies,
    this.moderationStatus = 'VISIBLE',
  });

  final int id;
  final int authorId;
  final bool authorDeleted;
  final String body;
  final DateTime createdAt;
  final String? authorNickname;
  final String? authorAvatarUrl;

  /// 一级评论的二级回复总数（二级回复为 null）。
  final int? replyCount;

  /// 一级评论内嵌的前 3 条二级回复（二级回复为 null）。
  final List<Comment>? replies;

  /// 审核可见性态（story 3，对应后端 `CommentResponse.moderationStatus`）：
  /// VISIBLE/UNDER_REVIEW 无标签；TAKEN_DOWN/REJECTED 仅作者本人收到 → 渲染「仅你可见」灰标签。
  /// 缺省 VISIBLE（旧后端不下发此字段时向后兼容）。
  final String moderationStatus;

  bool get isTopLevel => replyCount != null;

  /// 仅作者可见的「已被下架/移除」态（读路径已按 viewer 过滤，他人根本收不到该行）。
  bool get isTakenDownForAuthor =>
      moderationStatus == 'TAKEN_DOWN' || moderationStatus == 'REJECTED';

  factory Comment.fromJson(Map<String, dynamic> json) {
    final rawReplies = json['replies'];
    return Comment(
      id: json['id'] as int,
      authorId: json['authorId'] as int,
      authorDeleted: (json['authorDeleted'] ?? false) as bool,
      body: (json['body'] ?? '') as String,
      createdAt: DateTime.parse(json['createdAt'] as String),
      authorNickname: json['authorNickname'] as String?,
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      replyCount: json['replyCount'] as int?,
      replies: rawReplies is List
          ? rawReplies.map((e) => Comment.fromJson((e as Map).cast<String, dynamic>())).toList()
          : null,
      moderationStatus: (json['moderationStatus'] as String?) ?? 'VISIBLE',
    );
  }
}

/// 评论游标分页（对应后端 `CommentPageResponse`）。
class CommentPage {
  const CommentPage({required this.items, this.nextCursor, this.hasMore = false});

  final List<Comment> items;
  final String? nextCursor;
  final bool hasMore;

  factory CommentPage.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    return CommentPage(
      items: raw is List
          ? raw.map((e) => Comment.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
      nextCursor: json['nextCursor'] as String?,
      hasMore: (json['hasMore'] ?? false) as bool,
    );
  }
}
