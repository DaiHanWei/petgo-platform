import 'package:intl/intl.dart';

import '../../../l10n/app_localizations.dart';

/// 距离文案（V1.3.0 batch-b1 Story 1.2 · UI 稿 A1「Kafe · 1.2 km」）。
///
/// 口径：
/// - < 1000 m → 整米（`850 m`）。到店的最后几百米，「0.8 km」不如「850 m」有用；
/// - ≥ 1000 m → 一位小数的公里（`1,0 km` / `1,2 km`），12 km 以上取整（`12 km`）。
///   小数位**固定**：一列数字里位数一致更好扫读，「有时带小数有时不带」会让这一列看起来在跳。
///
/// 🔴 **小数点分隔符跟设备语言走**：印尼语用**逗号**（`1,2 km`），英语用点。
/// 硬写 `.` 会让印尼用户把 `1.2 km` 读成「12 公里」—— 这是会真实误导的错，不是排版偏好
/// （同 `formatIdr` 里千分位用点的理由）。所以这里用 [NumberFormat] 按 locale 格式化，
/// 不用 `toStringAsFixed`。
String formatPlaceDistance(AppLocalizations l10n, int meters) {
  if (meters < 1000) {
    return l10n.placeDistanceMeters(meters);
  }
  final km = meters / 1000;
  final locale = l10n.localeName;
  // 12 km 以上不要小数：列表里「12,4 km」的那个 .4 不改变任何决定，只是噪音。
  final digits = km >= 12 ? 0 : 1;
  final text = NumberFormat.decimalPatternDigits(
    locale: locale,
    decimalDigits: digits,
  ).format(km);
  return l10n.placeDistanceKilometers(text);
}
