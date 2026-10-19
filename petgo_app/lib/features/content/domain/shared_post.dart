/// 单条分享内容的**公开只读投影**（Story 9.3 · AD-15 Rule 5）。
///
/// 🛡 **刻意不复用 [ContentDetail]**：后者带着 `id` / `authorId` / `liked` / `isAuthor`
/// 这些站内寻址与互动字段，而这一屏是给**未登录访客**看的 —— 它不该有任何
/// 能用来去看该宠物其它内容的把手。
///
/// 边界画在类型上，不只画在页面上：将来谁想给落地页加个「看更多」，
/// 会发现这里压根没有可以拼路由的东西。服务端投影同理（`SharedPostResponse`）。
class SharedPost {
  const SharedPost({
    required this.authorName,
    required this.authorDeleted,
    required this.type,
    required this.createdAt,
    this.authorAvatarUrl,
    this.body,
    this.imageUrls = const [],
  });

  final String authorName;
  final bool authorDeleted;
  final String type;
  final DateTime createdAt;
  final String? authorAvatarUrl;
  final String? body;
  final List<String> imageUrls;

  factory SharedPost.fromJson(Map<String, dynamic> json, {required String fallbackAuthorName}) {
    final raw = json['imageUrls'];
    final deleted = (json['authorDeleted'] ?? false) as bool;
    return SharedPost(
      authorName: deleted
          ? fallbackAuthorName
          : ((json['authorNickname'] as String?) ?? fallbackAuthorName),
      authorDeleted: deleted,
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      type: (json['type'] ?? 'DAILY') as String,
      body: json['body'] as String?,
      imageUrls: raw is List ? raw.map((e) => e.toString()).toList() : const [],
      createdAt: DateTime.parse(json['createdAt'] as String),
    );
  }
}
