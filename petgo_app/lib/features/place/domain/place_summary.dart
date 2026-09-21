/// 场所域模型（V1.3.0 batch-b1 Story 1.1，消费后端 `GET /api/v1/places`）。
///
/// 字段与后端 `PlaceListItemResponse` / `PlaceListResponse` 一一对应（CROSS-STORY C4：
/// 契约后端主导，客户端是镜像，**不自创字段、不在客户端兜底转换抹平差异**）。
/// 线上契约由 `test/place/place_wire_contract_test.dart` 守着。
library;

/// 场所类型（7 类全集，对应后端 `PlaceType`）。
///
/// 🔴 **固定 7 值**：类型是 FR-112.1 的信息架构，不是运营可配项。
/// UI 稿 A5 里只画了几个是示意省略（UX-DR4），不是真实清单。
enum PlaceType {
  cafe('CAFE'),
  restaurant('RESTAURANT'),
  park('PARK'),
  mall('MALL'),
  hotel('HOTEL'),
  petService('PET_SERVICE'),
  other('OTHER');

  const PlaceType(this.api);

  /// 后端枚举字面量（UPPER_SNAKE，命名映射链）。
  final String api;

  /// 未知值返回 null（而不是落到 [other]）。
  ///
  /// 🔴 **不要改成兜底 other**：后端加了新类型而客户端没跟上时，落 other 会让界面
  /// 一本正经地显示「其他」，没人看得出契约漂了；返回 null 则类型位直接留空，能被看见。
  static PlaceType? fromApi(String? raw) {
    if (raw == null) return null;
    for (final t in PlaceType.values) {
      if (t.api == raw) return t;
    }
    return null;
  }
}

/// 宠物友好标签（6 个全集，对应后端 `PlaceTag`）。
enum PlaceTag {
  petsAllowedInside('PETS_ALLOWED_INSIDE'),
  outdoorSeating('OUTDOOR_SEATING'),
  petMenu('PET_MENU'),
  petPlayArea('PET_PLAY_AREA'),
  leashRequired('LEASH_REQUIRED'),
  largeDogFriendly('LARGE_DOG_FRIENDLY');

  const PlaceTag(this.api);

  final String api;

  static PlaceTag? fromApi(String? raw) {
    if (raw == null) return null;
    for (final t in PlaceTag.values) {
      if (t.api == raw) return t;
    }
    return null;
  }
}

/// 列表排序路径（由**服务端**下发，见后端 `PlaceListResponse.sortMode`）。
enum PlaceSortMode {
  /// 按创建时间倒序（没带坐标 / 无定位权限）。
  recent('recent'),

  /// 按直线距离升序（Story 1.2 接上）。
  distance('distance');

  const PlaceSortMode(this.api);

  final String api;

  /// 未知/缺失一律当 [recent] —— 这是**兜底方向正确**的那一侧：
  /// 按最新是 FR-112.2 明定的正常态，而误当成距离序会让界面去显示一个不存在的距离。
  static PlaceSortMode fromApi(String? raw) {
    for (final m in PlaceSortMode.values) {
      if (m.api == raw) return m;
    }
    return PlaceSortMode.recent;
  }
}

/// 场所列表项。
class PlaceSummary {
  const PlaceSummary({
    required this.token,
    required this.name,
    required this.tags,
    required this.photoCount,
    required this.commentCount,
    required this.recommendCount,
    required this.notRecommendCount,
    this.type,
    this.firstPhotoUrl,
    this.distanceMeters,
  });

  /// 不可枚举对外标识（NFR-1）。🔴 后端不下发自增 id，客户端也不该需要。
  final String token;
  final String name;

  /// null = 后端给了客户端不认识的类型（见 [PlaceType.fromApi]）→ 类型位留空。
  final PlaceType? type;

  /// 标签**全量**下发，`≤2 个 +N` 的截断在客户端做（服务端截断会让详情页与列表页两套标签集）。
  /// 认不出的值已在解析时丢弃。
  final List<PlaceTag> tags;

  /// 首图（公开桶 CDN 全 URL）。null → 占位缩略图，不白屏。
  final String? firstPhotoUrl;
  final int photoCount;

  /// 距离（米）。
  ///
  /// 🔴 **null = 不知道**（按最新分支 / 无定位权限），不是 0 米。界面此时**隐藏距离位**，
  /// 不要显示「0 m」也不要显示「-」。
  final int? distanceMeters;

  final int commentCount;

  /// 推荐 👍 / 不推荐 👎 的累计（B1-D11：列表页就出数）。
  ///
  /// ⚠️ Story 1.1 期间后端**恒为 0**（场所评论 Story 1.7 建表、计数 Story 1.8 接上）。
  /// 字段现在就在契约里，免得 1.8 上线时客户端再改一次 DTO。
  final int recommendCount;
  final int notRecommendCount;

  factory PlaceSummary.fromJson(Map<String, dynamic> json) {
    return PlaceSummary(
      token: json['token']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      type: PlaceType.fromApi(json['type']?.toString()),
      tags: _tags(json['tags']),
      firstPhotoUrl: _blankToNull(json['firstPhotoUrl']?.toString()),
      photoCount: _nonNegInt(json['photoCount']),
      distanceMeters: _posOrZeroIntOrNull(json['distanceMeters']),
      commentCount: _nonNegInt(json['commentCount']),
      recommendCount: _nonNegInt(json['recommendCount']),
      notRecommendCount: _nonNegInt(json['notRecommendCount']),
    );
  }

  static List<PlaceTag> _tags(Object? raw) {
    if (raw is! List) return const [];
    return raw
        .map((e) => PlaceTag.fromApi(e?.toString()))
        .whereType<PlaceTag>()
        .toList(growable: false);
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;

  static int _nonNegInt(Object? raw) {
    final n = raw is num ? raw.toInt() : 0;
    return n < 0 ? 0 : n;
  }

  /// 距离：只接受 ≥0 的数；缺失 / 负数 / 非数字一律 null（= 不显示距离位）。
  static int? _posOrZeroIntOrNull(Object? raw) {
    final n = raw is num ? raw.toInt() : null;
    return (n != null && n >= 0) ? n : null;
  }
}

/// 列表响应（信封）。
///
/// ⚠️ 叫 `Result` 而不是 `Page`：`PlaceListPage` 是**页面 widget** 的名字
/// （`presentation/place_list_page.dart`）。同名会让任何同时 import 两者的库
/// （Story 1.2 的页面、AC5/AC6 的 widget 测试）直接 ambiguous import 编译失败。
class PlaceListResult {
  const PlaceListResult({required this.items, required this.sortMode});

  final List<PlaceSummary> items;

  /// 🔴 读服务端下发的值，**不要按「我有没有带坐标」自己推**：坐标非法、粗筛半径内没有
  /// 任何场所时服务端会回落到按最新，客户端自己推的话距离位会莫名一片空白。
  final PlaceSortMode sortMode;

  factory PlaceListResult.fromJson(Map<String, dynamic> json) {
    final rows = json['items'];
    return PlaceListResult(
      items: rows is List
          ? rows
              .whereType<Map<String, dynamic>>()
              .map(PlaceSummary.fromJson)
              .toList(growable: false)
          : const [],
      sortMode: PlaceSortMode.fromApi(json['sortMode']?.toString()),
    );
  }
}
