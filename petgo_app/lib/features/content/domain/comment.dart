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

  bool get isTopLevel => replyCount != null;

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
