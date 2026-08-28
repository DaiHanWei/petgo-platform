import 'package:flutter/painting.dart' show Color;

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
    this.badgeStart,
    this.badgeEnd,
  });

  final String code;

  /// 标签名 —— 胶囊上显示的文字，也是 tooltip 的标题。
  final String name;

  final String icon;

  /// 一句说明 —— tooltip 的正文。
  final String description;

  /// 胶囊渐变的起止色（UI 稿 `.deco-badge` 的 135°，2026-08-28 起由运营配）。
  ///
  /// ⚠️ 后端下发的是**色值**而不是枚举名 —— 运营那边加一档颜色时客户端不必发版。
  /// 任一为空 / 解析不出来 → 回落 UI 稿原始的橙→红。
  final Color? badgeStart;
  final Color? badgeEnd;

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
      badgeStart: _parseHex(m['badgeStart']),
      badgeEnd: _parseHex(m['badgeEnd']),
    );
  }

  /// `#RRGGBB` → [Color]；缺失 / 格式不对 → null（由渲染处回落）。
  ///
  /// 🛡 **不抛异常**：色值是展示层的锦上添花，一个格式不对的字符串
  /// 不该让整条标签（乃至整页 Feed）解析失败。
  static Color? _parseHex(Object? raw) {
    if (raw is! String) return null;
    final v = raw.trim();
    if (v.length != 7 || !v.startsWith('#')) return null;
    final n = int.tryParse(v.substring(1), radix: 16);
    return n == null ? null : Color(0xFF000000 | n);
  }

  /// ⚠️ 后端**空标签不下发**，字段缺失是常态。
  static List<ContentTag> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    return raw.map(ContentTag.fromJson).whereType<ContentTag>().toList(growable: false);
  }
}
