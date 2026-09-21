import 'place_comment.dart';
import 'place_summary.dart';

/// 场所详情域模型（V1.3.0 batch-b1 Story 1.5，消费 `GET /api/v1/places/{token}`）。
///
/// 字段与后端 `PlaceDetailResponse` 一一对应（CROSS-STORY C4）。
/// 线上契约由 `test/place/place_detail_wire_contract_test.dart` 守着。
///
/// <h2>🔴 这里**没有**的字段（FR-112.6 / AC6 反向验收）</h2>
/// 没有收藏、没有评分打星、没有营业时间/电话、没有打卡、没有「可编辑」标记。
/// 这不是「还没做」，是**明确不做** —— 后端 DTO 里也没有（那侧有契约测试钉着）。
class PlaceDetail {
  const PlaceDetail({
    required this.token,
    required this.name,
    required this.tags,
    required this.photos,
    required this.addressText,
    required this.latitude,
    required this.longitude,
    required this.markedBy,
    required this.commentCount,
    required this.recommendCount,
    required this.notRecommendCount,
    this.photoSlotsRemaining = maxPhotos,
    this.type,
    this.description,
    this.distanceMeters,
  });

  /// 不可枚举对外标识。
  final String token;
  final String name;

  /// null = 后端给了客户端不认识的类型 → 类型位留空（不兜底成「其他」）。
  final PlaceType? type;
  final List<PlaceTag> tags;

  /// 照片（Story 1.9 起**每张带上传者**，AC2）。
  ///
  /// ⚠️ 这取代了 1.5 的 `photoUrls`（一个字符串数组）—— 数组装不下"这张是谁传的"。
  /// URL 仍然是公开桶 CDN 地址，服务端已附去 EXIF 的 `x-oss-process`（E4）。
  final List<PlacePhoto> photos;

  /// 只要 URL 的场合（灯箱、分享）—— 顺序与 [photos] 一致。
  List<String> get photoUrls => photos.map((p) => p.url).toList(growable: false);

  /// 文字地址。
  ///
  /// 🔴 **纯展示 + 一键复制的字符串**：平台不做地理编码、不校验它与坐标是否一致
  /// （AD-1 Rule 5）。不要拿它去解析、去拼搜索 URL 之外的任何用途。
  final String addressText;
  final String? description;

  /// 坐标 —— 详情页的**定位小地图**与「在地图中打开」要用。
  final double latitude;
  final double longitude;

  /// 距离（米）。null = 没带坐标进来 → 隐藏距离位（不是 0 米）。
  final int? distanceMeters;

  final PlaceMarker markedBy;

  final int commentCount;
  final int recommendCount;
  final int notRecommendCount;

  /// 场所照片总上限（与服务端、与标记表单同一个数）。
  static const int maxPhotos = 9;

  /// 还能补充几张（batch-b1 复审）。
  ///
  /// 🔴 **以服务端下发为准**：占位口径是**所有人**的 VISIBLE + UNDER_REVIEW（REJECTED 不占），
  /// 而 [photos] 里看不到别人审核中的、却含自己被拒的 —— 按 `photos.length` 算，
  /// 要么传完才被 422（照片成了公开桶孤儿），要么服务端还收、「+」已经藏了。
  /// 老后端不下发时退回按本地可见的非拒绝照片估算。
  final int photoSlotsRemaining;

  factory PlaceDetail.fromJson(Map<String, dynamic> json) {
    return PlaceDetail(
      token: json['token']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      type: PlaceType.fromApi(json['type']?.toString()),
      tags: _tags(json['tags']),
      photos: _photos(json['photos']),
      addressText: json['addressText']?.toString() ?? '',
      description: _blankToNull(json['description']?.toString()),
      latitude: _double(json['latitude']),
      longitude: _double(json['longitude']),
      distanceMeters: _nonNegIntOrNull(json['distanceMeters']),
      markedBy: PlaceMarker.fromJson(json['markedBy']),
      commentCount: _nonNegInt(json['commentCount']),
      recommendCount: _nonNegInt(json['recommendCount']),
      notRecommendCount: _nonNegInt(json['notRecommendCount']),
      photoSlotsRemaining: json.containsKey('photoSlotsRemaining')
          ? _nonNegInt(json['photoSlotsRemaining'])
          : _estimateSlots(_photos(json['photos'])),
    );
  }

  static int _estimateSlots(List<PlacePhoto> photos) {
    final occupying =
        photos.where((p) => p.moderation != PlaceCommentModeration.rejected).length;
    return occupying >= maxPhotos ? 0 : maxPhotos - occupying;
  }

  static List<PlaceTag> _tags(Object? raw) => raw is! List
      ? const []
      : raw
          .map((e) => PlaceTag.fromApi(e?.toString()))
          .whereType<PlaceTag>()
          .toList(growable: false);

  static List<PlacePhoto> _photos(Object? raw) => raw is! List
      ? const []
      : raw
          .whereType<Map>()
          .map((e) => PlacePhoto.fromJson(Map<String, dynamic>.from(e)))
          .where((p) => p.url.isNotEmpty)
          .toList(growable: false);

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;

  static double _double(Object? raw) => raw is num ? raw.toDouble() : 0;

  static int _nonNegInt(Object? raw) {
    final n = raw is num ? raw.toInt() : 0;
    return n < 0 ? 0 : n;
  }

  static int? _nonNegIntOrNull(Object? raw) {
    final n = raw is num ? raw.toInt() : null;
    return (n != null && n >= 0) ? n : null;
  }
}

/// 标记人（对应后端 `AuthorView`）。
///
/// ⚠️ 注销后 [nickname] / [avatarUrl] 为 null 而 [userId] 仍在（NFR-8 匿名化）——
/// 前端渲染本地化「已注销用户」+ 默认头像，且**头像不可点**。
class PlaceMarker {
  const PlaceMarker({
    required this.userId,
    required this.deleted,
    this.nickname,
    this.avatarUrl,
  });

  final int userId;
  final String? nickname;
  final String? avatarUrl;
  final bool deleted;

  factory PlaceMarker.fromJson(Object? raw) {
    if (raw is! Map) {
      // 契约里 markedBy 恒有值；拿不到时按已注销渲染（fail-closed：不显示任何身份）。
      return const PlaceMarker(userId: 0, deleted: true);
    }
    final id = raw['userId'];
    return PlaceMarker(
      userId: id is num ? id.toInt() : 0,
      nickname: _blank(raw['nickname']?.toString()),
      avatarUrl: _blank(raw['avatarUrl']?.toString()),
      deleted: raw['deleted'] == true,
    );
  }

  static String? _blank(String? s) => (s == null || s.isEmpty) ? null : s;

  /// 能不能点开这个人（注销 / 拿不到 id → 不可点）。
  bool get tappable => !deleted && userId > 0;
}

/// 场所的一张照片（Story 1.9 · AC2「标注上传者」）。
///
/// 🔴 **非 VISIBLE 的行只会下发给上传者本人**（读路径已按 viewer 过滤）——
/// 所以拿到一张 `underReview` 的照片时，它一定是你自己刚传的那张。
class PlacePhoto {
  const PlacePhoto({
    required this.id,
    required this.url,
    required this.uploaderId,
    required this.uploaderDeleted,
    required this.moderation,
    required this.mine,
    this.uploaderNickname,
  });

  final int id;
  final String url;

  final int uploaderId;

  /// 注销 → null，前端渲染本地化「已注销用户」（NFR-8）。
  final String? uploaderNickname;
  final bool uploaderDeleted;

  final PlaceCommentModeration moderation;

  /// 是不是本人传的 —— 决定要不要给删除入口。**服务端算给的**。
  final bool mine;

  /// 能不能点开这个人。
  bool get uploaderTappable => !uploaderDeleted && uploaderId > 0;

  factory PlacePhoto.fromJson(Map<String, dynamic> json) {
    final id = json['id'];
    final uploaderId = json['uploaderId'];
    return PlacePhoto(
      id: id is num ? id.toInt() : 0,
      url: json['url']?.toString() ?? '',
      uploaderId: uploaderId is num ? uploaderId.toInt() : 0,
      uploaderNickname: _blankToNull(json['uploaderNickname']?.toString()),
      uploaderDeleted: json['uploaderDeleted'] == true,
      moderation: PlaceCommentModeration.fromApi(json['moderationStatus']?.toString()),
      mine: json['mine'] == true,
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}
