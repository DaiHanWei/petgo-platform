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
    required this.photoUrls,
    required this.addressText,
    required this.latitude,
    required this.longitude,
    required this.markedBy,
    required this.commentCount,
    required this.recommendCount,
    required this.notRecommendCount,
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

  /// 照片（公开桶 CDN URL，服务端已附去 EXIF 的 `x-oss-process`）。
  final List<String> photoUrls;

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

  factory PlaceDetail.fromJson(Map<String, dynamic> json) {
    return PlaceDetail(
      token: json['token']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      type: PlaceType.fromApi(json['type']?.toString()),
      tags: _tags(json['tags']),
      photoUrls: _strings(json['photoUrls']),
      addressText: json['addressText']?.toString() ?? '',
      description: _blankToNull(json['description']?.toString()),
      latitude: _double(json['latitude']),
      longitude: _double(json['longitude']),
      distanceMeters: _nonNegIntOrNull(json['distanceMeters']),
      markedBy: PlaceMarker.fromJson(json['markedBy']),
      commentCount: _nonNegInt(json['commentCount']),
      recommendCount: _nonNegInt(json['recommendCount']),
      notRecommendCount: _nonNegInt(json['notRecommendCount']),
    );
  }

  static List<PlaceTag> _tags(Object? raw) => raw is! List
      ? const []
      : raw
          .map((e) => PlaceTag.fromApi(e?.toString()))
          .whereType<PlaceTag>()
          .toList(growable: false);

  static List<String> _strings(Object? raw) => raw is! List
      ? const []
      : raw.map((e) => e?.toString() ?? '').where((s) => s.isNotEmpty).toList(growable: false);

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
