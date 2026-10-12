import 'dart:ui' show Locale;

/// 计数缩写（V1.3.4 follow · Story 1.3 · 决策 D-3 / T-11）：主页四格等处的大数字。
///
/// - `n < 1000` → 原数（负数按 0）；`1000 ≤ n < 1 000 000` → `K`；`n ≥ 1 000 000` → `M`；
/// - 最多一位小数、去掉末尾 0（1000 → `1K`、1 000 000 → `1M`）；
/// - 小数点随语言：`id` 用「,」（1234 → `1,2K`），其余用「.」（`1.2K`）；
/// - **向下截断**到一位小数（T-11）：999 999 → `999,9K`，不会出现「1000K」，也不夸大数字。
///
/// 纯整数运算，不走 `double` —— 浮点截断在 1299 这类边界上会出 `1,2999…` 的舍入噪声。
String formatCompactCount(int n, Locale locale) {
  if (n < 1000) return '${n < 0 ? 0 : n}';
  final (unit, suffix) = n >= 1000000 ? (1000000, 'M') : (1000, 'K');
  final tenths = n * 10 ~/ unit; // 向下截断到一位小数（以 0.1 为单位）
  final whole = tenths ~/ 10;
  final frac = tenths % 10;
  if (frac == 0) return '$whole$suffix';
  final sep = locale.languageCode == 'id' ? ',' : '.';
  return '$whole$sep$frac$suffix';
}
