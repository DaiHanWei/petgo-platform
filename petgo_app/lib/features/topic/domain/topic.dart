/// 话题领域模型（V1.4.2 social-topic · Story 4.3 · D-13 / R-2）。
///
/// 🔴 对外标识只有**标准名** [TopicDetail.name] / [TopicSummary.name]：路由参数、接口路径、
/// 埋点 `topic_id` 一律用它；界面显示一律用 `displayName`。后端不下发自增 id，这里也不留位置。
library;

/// 话题详情（`GET /topics/{name}`）。
class TopicDetail {
  const TopicDetail({
    required this.name,
    required this.displayName,
    this.official = false,
    this.description,
  });

  /// 标准名（埋点 `topic_id` 同值，R-2）。
  final String name;

  /// 显示写法（后台改大小写后即刻生效，PRD FR-69 AC11）。
  final String displayName;
  final bool official;

  /// 官方说明（后端按 Accept-Language 选好语言）；用户话题 / 未配置 → null（后端 NON_NULL 省略）。
  final String? description;

  /// 有可展示的说明段（仅官方话题，且非空）。
  bool get hasDescription => official && description != null && description!.trim().isNotEmpty;

  factory TopicDetail.fromJson(Map<String, dynamic> json) => TopicDetail(
        name: json['name'] as String,
        displayName: (json['displayName'] ?? json['name']) as String,
        official: (json['official'] ?? false) as bool,
        description: json['description'] as String?,
      );
}

/// 话题列表项：首页话题行 / 空态推荐 / （4.4）# 联想共用（后端 `TopicSummaryResponse`）。
class TopicSummary {
  const TopicSummary({
    required this.name,
    required this.displayName,
    this.official = false,
    this.featured = false,
  });

  final String name;
  final String displayName;
  final bool official;

  /// 运营指定（只有首页话题行下发；其余出口缺键 → false）。埋点 `is_pinned_by_ops`。
  final bool featured;

  factory TopicSummary.fromJson(Map<String, dynamic> json) => TopicSummary(
        name: json['name'] as String,
        displayName: (json['displayName'] ?? json['name']) as String,
        official: (json['official'] ?? false) as bool,
        featured: (json['featured'] ?? false) as bool,
      );

  /// 解析**裸 JSON 数组**（4.2：home-row / related / suggestions 都不包 `{items}`）。
  /// 非数组 / 坏元素 → 跳过，不抛（推荐位不该因一条脏数据整块失败）。
  static List<TopicSummary> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    final out = <TopicSummary>[];
    for (final e in raw) {
      if (e is! Map) continue;
      final m = e.cast<String, dynamic>();
      if (m['name'] is! String) continue;
      out.add(TopicSummary.fromJson(m));
    }
    return out;
  }
}

/// 进入话题页的来源（埋点 `topic_page_viewed.source`，P-9 / PRD §4）。经路由 query `from=` 传入。
///
/// ⚠️ [related] 是本 story 新增取值（PRD §4 只有前四个，见 story「待确认」第 2 条）；
/// [postBody] / [feedCard] 由 4.4 接入。
enum TopicEntrySource {
  postBody('post_body'),
  feedCard('feed_card'),
  homeRow('home_row'),
  pin('pin'),
  related('related');

  const TopicEntrySource(this.wire);

  /// 路由 query 与埋点共用的取值。
  final String wire;

  /// 缺省 / 不认识 → null（埋点不带 `source` 键，不瞎填）。
  static TopicEntrySource? fromWire(String? raw) {
    for (final s in values) {
      if (s.wire == raw) return s;
    }
    return null;
  }
}

/// 话题页排序页签（接口 `?sort=` 与埋点 `topic_sort_switched.sort` 同值）。
enum TopicSort {
  latest('latest'),
  popular('popular');

  const TopicSort(this.wire);

  final String wire;
}

/// 话题不存在 / 已禁用 / 名字非法（接口 404）→ C10。其余失败保持原异常（UI 判 C11）。
class TopicNotFoundException implements Exception {
  const TopicNotFoundException();

  @override
  String toString() => 'TopicNotFoundException';
}
