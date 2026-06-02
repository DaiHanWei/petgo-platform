/// 内容详情（对应后端 `ContentDetailResponse`）。
class ContentDetail {
  const ContentDetail({
    required this.id,
    required this.authorId,
    required this.authorDeleted,
    required this.type,
    required this.likeCount,
    required this.commentCount,
    required this.liked,
    required this.isAuthor,
    required this.createdAt,
    this.authorNickname,
    this.authorAvatarUrl,
    this.body,
    this.imageUrls = const [],
  });

  final int id;
  final int authorId;
  final bool authorDeleted;
  final String type;
  final int likeCount;
  final int commentCount;
  final bool liked;

  /// 当前用户是否作者（「···」删除入口可见性，行为在 3.6）。
  final bool isAuthor;
  final DateTime createdAt;
  final String? authorNickname;
  final String? authorAvatarUrl;
  final String? body;
  final List<String> imageUrls;

  factory ContentDetail.fromJson(Map<String, dynamic> json) {
    final raw = json['imageUrls'];
    return ContentDetail(
      id: json['id'] as int,
      authorId: json['authorId'] as int,
      authorDeleted: (json['authorDeleted'] ?? false) as bool,
      type: (json['type'] ?? 'DAILY') as String,
      likeCount: (json['likeCount'] ?? 0) as int,
      commentCount: (json['commentCount'] ?? 0) as int,
      liked: (json['liked'] ?? false) as bool,
      isAuthor: (json['isAuthor'] ?? false) as bool,
      createdAt: DateTime.parse(json['createdAt'] as String),
      authorNickname: json['authorNickname'] as String?,
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      body: json['body'] as String?,
      imageUrls: raw is List ? raw.map((e) => e.toString()).toList() : const [],
    );
  }
}

/// 详情加载多态（UX-DR18 ④⑤⑥）。区分 404 失效 / 403 无权限 / 其他网络错误。
enum ContentLoadErrorKind { gone, forbidden, network }

class ContentLoadError implements Exception {
  const ContentLoadError(this.kind);

  final ContentLoadErrorKind kind;
}
