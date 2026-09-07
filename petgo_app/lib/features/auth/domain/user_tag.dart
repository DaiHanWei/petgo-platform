import 'package:flutter/painting.dart' show Color;

/// 运营给用户挂的身份标签（V1.1.6 Story 5.1 · FR-74）。
///
/// 随作者信息一起下发（首页卡 / 详情页作者区 / 评论区 / 迷你主页四处同一份形状）。
///
/// 🛡 **同时最多 3 个** —— 后端已按分配时间倒序截断，客户端只管展示。
/// 注销作者恒为空（匿名化之后不该再挂着身份标识）。
class UserTag {
  const UserTag({
    required this.code,
    required this.name,
    required this.icon,
    required this.description,
    this.badgeColor,
  });

  /// 稳定标识（运营改名不影响它）。
  final String code;

  /// 标签名 —— tooltip 的标题。
  final String name;

  /// 图标（emoji 或图片地址）。
  final String icon;

  /// 一句说明 —— tooltip 的正文，由运营配置。
  final String description;

  /// 徽章圆底色值（UI 稿 `.utag-icon` 按标签分色：官方号金、最佳新人紫）。
  ///
  /// ⚠️ 后端下发的是**色值**（`#F6A609`）而不是枚举名 —— 运营那边加一档颜色时
  /// 客户端不必发版。解析不出来 / 缺字段 → [AppColors.gold]（稿子的默认值）。
  final Color? badgeColor;

  static UserTag? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final m = raw.cast<String, dynamic>();
    final code = m['code'];
    final name = m['name'];
    final icon = m['icon'];
    if (code is! String || name is! String || icon is! String) return null;
    return UserTag(
      code: code,
      name: name,
      icon: icon,
      description: (m['description'] as String?) ?? '',
      badgeColor: _parseHex(m['badgeColor']),
    );
  }

  /// `#RRGGBB` → [Color]；缺失 / 格式不对 → null（由渲染处回落金色）。
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

  /// ⚠️ 后端**空标签不下发**（省掉每行一个空数组），所以字段缺失是常态、不是异常。
  static List<UserTag> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    return raw.map(UserTag.fromJson).whereType<UserTag>().toList(growable: false);
  }
}
