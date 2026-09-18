import '../../auth/domain/user_tag.dart';
import 'content_tag.dart';
import 'feed_image_layout.dart';
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
    this.authorTags = const [],
    this.decorationTags = const [],
    this.authorAvatarUrl,
    this.body,
    this.imageUrls = const [],
    this.imageSizes = const [],
    this.visibility = 'PUBLIC',
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

  /// 作者的运营标签（V1.1.6 Story 5.1）。最多 3 个；注销作者恒为空。
  final List<UserTag> authorTags;

  /// 内容装饰标签（V1.1.6 Story 5.2）。有图叠首图角落 / 无图置正文下方。
  final List<ContentTag> decorationTags;
  final String? authorAvatarUrl;
  final String? body;
  final List<String> imageUrls;

  /// 图片原始宽高（V1.3.0 Story 2.1 下发 / 2.2 使用），与 [imageUrls] **同序等长**。
  ///
  /// 测不出来的位置为 `null`；存量内容（V1.1.6 之前发布的）各位均为 `null`，
  /// 走 [kFeedPlaceholderRatio] 占位兜底 —— **占位不可取消**（AD-5：存量不会回填）。
  ///
  /// ⚠️ 只有原始宽高。比例的 clamp 与高度护栏**一律客户端算**，且必须走
  /// [resolveFeedImageAspect] 这一个出口 —— 与 Feed 同一套，否则从首页点进详情会跳变。
  final List<ImageSize?> imageSizes;

  /// 第 [i] 张图的尺寸；越界 / 缺失 → null（交给占位兜底）。
  ///
  /// 按下标安全取：后端已保证同序等长，但老客户端 / 老响应体可能短一截，这里再兜一道。
  ImageSize? sizeAt(int i) =>
      (i >= 0 && i < imageSizes.length) ? imageSizes[i] : null;

  /// 可见性线格式（`PUBLIC` / `PRIVATE`）。老响应体没有这个字段 ⇒ 按 `PUBLIC` 兜底。
  ///
  /// 只有一个用处：埋点 E-11 的 `is_private_diary`。**不要拿它当权限判据** ——
  /// 私密内容照样允许用户自己分享（AD-15 Rule 6），拿它去藏分享按钮就改了产品规则。
  final String visibility;

  /// 是否「私密日记」（埋点 E-11 的加粗属性）。Diary = `GROWTH_MOMENT`。
  bool get isPrivateDiary => type == 'GROWTH_MOMENT' && visibility == 'PRIVATE';

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
      authorTags: UserTag.listFromJson(json['authorTags']),
      decorationTags: ContentTag.listFromJson(json['decorationTags']),
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      body: json['body'] as String?,
      imageUrls: raw is List ? raw.map((e) => e.toString()).toList() : const [],
      imageSizes: ImageSize.listFromJson(json['imageSizes']),
      visibility: (json['visibility'] ?? 'PUBLIC') as String,
    );
  }
}

/// 详情加载多态（UX-DR18 ④⑤⑥）。区分 404 失效 / 403 无权限 / 其他网络错误。
enum ContentLoadErrorKind { gone, forbidden, network }

class ContentLoadError implements Exception {
  const ContentLoadError(this.kind);

  final ContentLoadErrorKind kind;
}
