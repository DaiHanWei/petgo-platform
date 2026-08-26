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
  });

  /// 稳定标识（运营改名不影响它）。
  final String code;

  /// 标签名 —— tooltip 的标题。
  final String name;

  /// 图标（emoji 或图片地址）。
  final String icon;

  /// 一句说明 —— tooltip 的正文，由运营配置。
  final String description;

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
    );
  }

  /// ⚠️ 后端**空标签不下发**（省掉每行一个空数组），所以字段缺失是常态、不是异常。
  static List<UserTag> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    return raw.map(UserTag.fromJson).whereType<UserTag>().toList(growable: false);
  }
}
