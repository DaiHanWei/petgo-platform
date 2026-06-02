/// 内容类型（与后端 `ContentType` 对齐）。「全部」是浏览态语义，不在此枚举（发布须落具体类型）。
enum ContentType {
  daily('DAILY'),
  growthMoment('GROWTH_MOMENT'),
  knowledge('KNOWLEDGE');

  const ContentType(this.wire);

  /// 后端枚举线格式（UPPER_SNAKE）。
  final String wire;
}
