/// 终结性表述守卫（Story 4.4 · F4，FR-2 信任边界）。
///
/// 黄色/绿色观察文案**禁止**「不严重/可以放心/没事了/无需就医」等终结措辞——无论来自固定文案还是
/// 模型产出。模型若吐出终结词，必须被拦截降级（不原样渲染），改用中性回退提示。
class TriageWordingGuard {
  TriageWordingGuard._();

  /// 终结性词表（中 / 印尼 / 英），小写比对。刻意从严，宁可多拦也不放过软化措辞。
  static const List<String> terminalPhrases = <String>[
    // zh
    '不严重', '可以放心', '请放心', '没事了', '没事儿', '无需就医', '不用就医', '不用看医生',
    '不用担心', '不必担心', '没有问题', '没问题',
    // id
    'tidak serius', 'tidak perlu khawatir', 'jangan khawatir', 'tidak perlu ke dokter',
    'tidak perlu ke dokter hewan', 'tidak ada masalah',
    // en
    'not serious', "don't worry", 'do not worry', 'no need to see', 'no need to visit',
    'nothing to worry', 'no need to worry', 'no problem', "you can relax",
  ];

  /// 文案是否含终结性表述。
  static bool hasTerminalWording(String? text) {
    if (text == null || text.isEmpty) return false;
    final lower = text.toLowerCase();
    return terminalPhrases.any(lower.contains);
  }

  /// 守卫：含终结措辞则返回 [fallback]（中性提示），否则原样返回。
  static String sanitize(String? text, {required String fallback}) {
    if (text == null || text.isEmpty || hasTerminalWording(text)) {
      return fallback;
    }
    return text;
  }
}
