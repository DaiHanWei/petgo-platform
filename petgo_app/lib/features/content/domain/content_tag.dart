/// 运营给内容发的荣誉标签（V1.1.6 Story 5.2 · FR-75）。
///
/// 🛡 与用户标签**完全独立**（AD-10：两套表、两套校验规则），
/// 所以这里也是独立的一个类型，不与用户标签共用 —— 免得日后一方加字段时另一方被牵连。
///
/// ⚠️ 打标不只是发奖：标签生效中时该内容在推荐排序上有 ×1.3 加权。
/// 不过**本版本首页是纯时间倒序、没有排序算法**，那个加权尚无施加处（后端已记录口径）。
class ContentTag {
  const ContentTag({
    required this.code,
    required this.name,
    required this.icon,
    required this.description,
  });

  final String code;

  /// 标签名 —— 胶囊上显示的文字，也是 tooltip 的标题。
  final String name;

  final String icon;

  /// 一句说明 —— tooltip 的正文。
  final String description;

  static ContentTag? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final m = raw.cast<String, dynamic>();
    final code = m['code'];
    final name = m['name'];
    final icon = m['icon'];
    if (code is! String || name is! String || icon is! String) return null;
    return ContentTag(
      code: code,
      name: name,
      icon: icon,
      description: (m['description'] as String?) ?? '',
    );
  }

  /// ⚠️ 后端**空标签不下发**，字段缺失是常态。
  static List<ContentTag> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    return raw.map(ContentTag.fromJson).whereType<ContentTag>().toList(growable: false);
  }
}
