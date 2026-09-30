import 'dart:ui' show Locale;

/// Tailsonality 内容表的双语文本（V1.3.2 Story 2.2）：`en` 英语 / `id` 印尼语。
///
/// 大块内容走 Dart 常量表、不进 ARB（与 `profile/domain/milestone_titles.dart` 同一路线）；
/// ARB 只放界面 chrome。唯一允许的占位符是 `{pet}`（宠物名），`**…**` 是加粗标记，由展示层解析。
typedef TsText = ({String en, String id});

extension TsTextOf on TsText {
  /// `languageCode == 'id'` 取印尼语，其余一律英语（与 App 的 locale 回退一致）。
  String of(Locale locale) => locale.languageCode == 'id' ? id : en;
}

/// 把 `{pet}` 替换成宠物名。名字为空时退化成代词式写法由调用方决定，这里只做字面替换。
String tsFillPet(String s, String petName) => s.replaceAll('{pet}', petName);
