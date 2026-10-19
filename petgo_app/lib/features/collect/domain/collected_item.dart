/// 「我的 → Disimpan」列表行（V1.4.0 ④ Story 2.4，消费 Story 2.2 的 `GET /api/v1/me/collections/posts|places`）。
///
/// 🔴 不可见条目（`available = false`）接口**不给**原正文 / 作者 / 封面 / 类型（帖子）或类型 / 地址 / 封面（场所）——
/// App 也**不得**从 Feed / 详情等其它缓存补齐（AD-11）。宽进：缺 `available` 视为 false。
class CollectedPost {
  const CollectedPost({
    required this.postId,
    required this.collectedAt,
    required this.available,
    this.post,
  });

  final int postId;
  final DateTime? collectedAt;
  final bool available;
  final CollectedPostSummary? post;

  /// 防御：`available = true` 但 `post` 缺失也按不可见渲染（不崩、不露空白）。
  bool get showsAsAvailable => available && post != null;

  /// 列表用：缺 / 非法 `postId` → null（该行跳过，不让整页失败）。
  static CollectedPost? tryFromJson(Map<String, dynamic> json) =>
      json['postId'] is num ? CollectedPost.fromJson(json) : null;

  factory CollectedPost.fromJson(Map<String, dynamic> json) {
    final p = json['post'];
    return CollectedPost(
      postId: (json['postId'] as num).toInt(),
      collectedAt: DateTime.tryParse(json['collectedAt']?.toString() ?? ''),
      available: json['available'] == true,
      post: p is Map<String, dynamic> ? CollectedPostSummary.fromJson(p) : null,
    );
  }
}

class CollectedPostSummary {
  const CollectedPostSummary({
    required this.type,
    required this.authorNickname,
    this.excerpt,
    this.coverUrl,
    this.authorAvatarUrl,
  });

  /// 线格式 DAILY / GROWTH_MOMENT / KNOWLEDGE（App 本地化，不渲染后端显示串）。
  final String type;

  /// 正文前 120 code point（帖子没有标题列）；可能为 null。
  final String? excerpt;
  final String? coverUrl;
  final String authorNickname;
  final String? authorAvatarUrl;

  factory CollectedPostSummary.fromJson(Map<String, dynamic> json) {
    final author = json['author'];
    final a = author is Map ? author : const {};
    return CollectedPostSummary(
      type: json['type']?.toString() ?? 'DAILY',
      excerpt: json['excerpt'] as String?,
      coverUrl: json['coverUrl'] as String?,
      authorNickname: a['nickname']?.toString() ?? '',
      authorAvatarUrl: a['avatarUrl'] as String?,
    );
  }
}

class CollectedPlace {
  const CollectedPlace({
    required this.placeToken,
    required this.collectedAt,
    required this.available,
    required this.name,
    this.type,
    this.coverUrl,
    this.addressText,
  });

  /// 解析后的场所 token（MERGED → 保留场所）；移除与跳转都用它。
  final String placeToken;
  final DateTime? collectedAt;
  final bool available;

  /// 不可见时也下发（UI「保留名称置灰」）。
  final String name;
  final String? type;
  final String? coverUrl;
  final String? addressText;

  /// 列表用：缺 / 空 `placeToken` → null（该行跳过：空 token 拼不出详情与移除路径）。
  static CollectedPlace? tryFromJson(Map<String, dynamic> json) {
    final t = json['placeToken'];
    return t is String && t.isNotEmpty ? CollectedPlace.fromJson(json) : null;
  }

  factory CollectedPlace.fromJson(Map<String, dynamic> json) {
    final p = json['place'];
    final place = p is Map ? p : null;
    return CollectedPlace(
      placeToken: json['placeToken']?.toString() ?? '',
      collectedAt: DateTime.tryParse(json['collectedAt']?.toString() ?? ''),
      available: json['available'] == true,
      name: json['name']?.toString() ?? '',
      type: place?['type']?.toString(),
      coverUrl: place?['coverUrl'] as String?,
      addressText: place?['addressText'] as String?,
    );
  }
}
