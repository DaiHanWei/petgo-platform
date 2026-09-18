import '../../auth/domain/user_tag.dart';
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
    this.authorTags = const [],
    this.authorAvatarUrl,
    this.replyCount,
    this.replies,
    this.moderationStatus = 'VISIBLE',
    this.likeCount = 0,
    this.liked = false,
  });

  final int id;
  final int authorId;
  final bool authorDeleted;
  final String body;
  final DateTime createdAt;
  final String? authorNickname;

  /// 作者的运营标签（V1.1.6 Story 5.1）。
  ///
  /// ⚠️ 评论区是最容易退化成逐条查的地方，但取数在后端的作者投影里整批完成，
  /// 这里只是把结果读出来。
  final List<UserTag> authorTags;
  final String? authorAvatarUrl;

  /// 一级评论的二级回复总数（二级回复为 null）。
  final int? replyCount;

  /// 一级评论内嵌的前 3 条二级回复（二级回复为 null）。
  final List<Comment>? replies;

  /// 审核可见性态（story 3，对应后端 `CommentResponse.moderationStatus`）：
  /// VISIBLE/UNDER_REVIEW 无标签；TAKEN_DOWN/REJECTED 仅作者本人收到 → 渲染「仅你可见」灰标签。
  /// 缺省 VISIBLE（旧后端不下发此字段时向后兼容）。
  final String moderationStatus;

  /// 点赞数（V1.3.0 Story 2.4）。**后端实时聚合，库里没有计数列** ——
  /// 所以这个数每次拉取都是当下的真值，不存在「对不上账」的历史包袱。
  final int likeCount;

  /// 当前查看者是否已赞（游客恒 false）。
  final bool liked;

  /// 乐观更新用：本地翻转点赞态，不等服务端回包。
  ///
  /// 服务端点赞端点**不返回赞数**（那是聚合值，回来时可能已经变了），所以本地
  /// ±1 是唯一能让按钮立刻有反馈的办法。下次拉列表时以服务端为准。
  Comment toggleLikedLocally() => copyWith(
        liked: !liked,
        likeCount: liked ? (likeCount - 1).clamp(0, 1 << 30) : likeCount + 1,
      );

  Comment copyWith({int? likeCount, bool? liked, List<Comment>? replies}) => Comment(
        id: id,
        authorId: authorId,
        authorDeleted: authorDeleted,
        body: body,
        createdAt: createdAt,
        authorNickname: authorNickname,
        authorTags: authorTags,
        authorAvatarUrl: authorAvatarUrl,
        replyCount: replyCount,
        replies: replies ?? this.replies,
        moderationStatus: moderationStatus,
        likeCount: likeCount ?? this.likeCount,
        liked: liked ?? this.liked,
      );

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
      authorTags: UserTag.listFromJson(json['authorTags']),
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      replyCount: json['replyCount'] as int?,
      replies: rawReplies is List
          ? rawReplies.map((e) => Comment.fromJson((e as Map).cast<String, dynamic>())).toList()
          : null,
      moderationStatus: (json['moderationStatus'] as String?) ?? 'VISIBLE',
      likeCount: (json['likeCount'] ?? 0) as int,
      liked: (json['liked'] ?? false) as bool,
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
