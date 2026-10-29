import '../../auth/domain/user_tag.dart';
import '../../growth/domain/growth_badge.dart';
import '../../mention/domain/mention_view.dart';
import '../../social/domain/follow_state.dart';
import 'content_pet_ref.dart';
import '../video/video_info.dart';
import 'content_tag.dart';
import 'feed_image_layout.dart';
import '../../place/domain/checkin_place_ref.dart';
import '../../gift/domain/gift_showcase.dart';
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
    this.authorBadge,
    this.decorationTags = const [],
    this.authorAvatarUrl,
    this.body,
    this.imageUrls = const [],
    this.imageSizes = const [],
    this.imageCropOffsets = const [],
    this.visibility = 'PUBLIC',
    this.mentions = const [],
    this.checkinPlace,
    this.authorToken,
    this.authorFollowState = FollowState.none,
    this.followEpoch = 0,
    this.pets = const [],
    this.giftPets = const [],
    this.collected = false,
    this.collectCount,
    this.mediaType = kMediaTypeImage,
    this.video,
  });

  /// Diary 关联的宠物（V1.4.0 multi-pet Story 3.3 · AC4.1），按创建顺序；Moment / Tips / 无关联 → 空表。
  final List<ContentPetRef> pets;

  /// Diary 礼物区（V1.4.0 gift Story 2.1 下发 / 2.2 使用）：这条 Diary 关联的宠物各自收到的礼物汇总 + 墙前 3 行。
  /// 缺键（Moment / Tips / 无关联宠物 / 老后端）→ 空表 → 不渲染礼物区。
  final List<GiftPetSummary> giftPets;

  /// 当前用户是否已收藏（V1.4.0 ④ Story 2.3 · AD-10）。游客 / 老后端缺键 → false。
  final bool collected;

  /// 收藏数（不计作者自己）：**仅作者本人看时**后端才下发；非作者 / 游客 / 老后端 → null（不显示）。
  final int? collectCount;

  /// 媒体类型线格式（专题线 video Story 2.3）；缺省 / 未知 → 按图文。
  final String mediaType;

  /// 视频信息（只在视频帖下发）。判断视频帖用 [isVideo]。
  final VideoInfo? video;

  /// 是否视频帖：以 `mediaType` 为准；缺 `video` 时按图文降级。
  bool get isVideo => isVideoMediaType(mediaType) && video != null;

  /// 作者对外 token（V1.3.4 follow · Story 2.1）；作者已注销 / 封号 / 老后端 → null（不显示关注按钮）。
  final String? authorToken;

  /// 当前查看者与作者的关注关系（快照）。缺字段 → [FollowState.none]。
  final FollowState authorFollowState;

  /// 这份快照发请求前读到的全局关注版本号（不来自 JSON，见 `FeedItem.followEpoch`）。
  final int followEpoch;

  /// 带上快照版本号的拷贝（`detailProvider` 用；其余字段原样）。
  ContentDetail withFollowEpoch(int epoch) => ContentDetail(
        id: id,
        authorId: authorId,
        authorDeleted: authorDeleted,
        type: type,
        likeCount: likeCount,
        commentCount: commentCount,
        liked: liked,
        isAuthor: isAuthor,
        createdAt: createdAt,
        authorNickname: authorNickname,
        authorTags: authorTags,
        authorBadge: authorBadge,
        decorationTags: decorationTags,
        authorAvatarUrl: authorAvatarUrl,
        body: body,
        imageUrls: imageUrls,
        imageSizes: imageSizes,
        imageCropOffsets: imageCropOffsets,
        visibility: visibility,
        mentions: mentions,
        checkinPlace: checkinPlace,
        authorToken: authorToken,
        authorFollowState: authorFollowState,
        followEpoch: epoch,
        pets: pets,
        giftPets: giftPets,
        // 🔴 V1.4.0 ④：漏拷 → 进详情收藏态恒为 false、作者看不到收藏数。
        collected: collected,
        collectCount: collectCount,
        mediaType: mediaType,
        video: video,
      );

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

  /// 作者等级角标 + 头像框（V1.4.0 ① Story 3.2）；注销 / 停用 / 字段缺失为 null（不显示）。
  final GrowthBadge? authorBadge;

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

  /// 每张图的露出位置（V1.3.4 Story 2.1 下发 / 2.2 使用），与 [imageUrls] 同序；
  /// 元素 0~1，null = 居中；存量整列为空。与 Feed 同走 [feedImageAlignment]。
  final List<double?> imageCropOffsets;

  /// 第 [i] 张图的露出位置；越界 / 缺失 → null（居中）。
  double? cropOffsetAt(int i) =>
      (i >= 0 && i < imageCropOffsets.length) ? imageCropOffsets[i] : null;

  /// 可见性线格式（`PUBLIC` / `PRIVATE`）。老响应体没有这个字段 ⇒ 按 `PUBLIC` 兜底。
  ///
  /// 只有一个用处：埋点 E-11 的 `is_private_diary`。**不要拿它当权限判据** ——
  /// 私密内容照样允许用户自己分享（AD-15 Rule 6），拿它去藏分享按钮就改了产品规则。
  /// 例外（V1.4.0 ④ P-2）：收藏按钮按 P-2 对 PRIVATE 隐藏 —— 这是产品规则（只收藏公开帖），不是权限判据；
  /// 分享仍不得据此隐藏。
  final String visibility;

  /// 正文里的 @（V1.3.0 batch-b1 Story 3.3）。
  ///
  /// 🔴 每一项的「能不能点、显示什么昵称」都是**后端算好的**（拉黑 AC3 / 注销 AC4）——
  /// 渲染侧只照做，不自己判。空表 = 这段文字里没有可点的 @。
  final List<MentionView> mentions;

  /// 打卡场所条（V1.3.2 Story 1.5 · AD-10）：帖子关联了打卡才有；普通帖 null。
  final CheckinPlaceRef? checkinPlace;

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
      authorBadge: GrowthBadge.fromFlat(json),
      decorationTags: ContentTag.listFromJson(json['decorationTags']),
      authorAvatarUrl: json['authorAvatarUrl'] as String?,
      body: json['body'] as String?,
      imageUrls: raw is List ? raw.map((e) => e.toString()).toList() : const [],
      imageSizes: ImageSize.listFromJson(json['imageSizes']),
      imageCropOffsets: cropOffsetListFromJson(json['imageCropOffsets']),
      visibility: (json['visibility'] ?? 'PUBLIC') as String,
      mentions: MentionView.listFromJson(json['mentions']),
      checkinPlace: CheckinPlaceRef.fromJson(json['checkinPlace']),
      // V1.3.4 follow Story 2.1：缺失 / null 都按默认。
      authorToken: json['authorToken'] as String?,
      authorFollowState: FollowState.fromJson(json['authorFollowState']),
      // V1.4.0 multi-pet Story 3.1 / 3.3：缺省 = 空表。
      pets: ContentPetRef.listFromJson(json['pets']),
      giftPets: GiftPetSummary.listFromJson(json['giftPets']),
      // V1.4.0 ④ Story 2.3：缺键（游客 / 老后端 / 非作者的 collectCount）按默认。
      collected: json['collected'] == true,
      collectCount: json['collectCount'] is int ? json['collectCount'] as int : null,
      mediaType: (json['mediaType'] as String?) ?? kMediaTypeImage,
      video: VideoInfo.fromJson(json['video']),
    );
  }
}

/// 详情加载多态（UX-DR18 ④⑤⑥）。区分 404 失效 / 403 无权限 / 其他网络错误。
enum ContentLoadErrorKind { gone, forbidden, network }

class ContentLoadError implements Exception {
  const ContentLoadError(this.kind);

  final ContentLoadErrorKind kind;
}
