import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/core/theme/colors.dart';

/// Story 7.4 AC1（NFR-13 / UX-DR15）：文本/disclaimer 对 #FAF8F5 底色对比度守门（CI 防回退）。
///
/// WCAG 2.1 相对亮度 + 对比度公式。正文文本 ≥4.5:1；免责声明（10px 也须可读）≥3:1。
void main() {
  const base = AppColors.base; // #FAF8F5

  test('正文文本对底色 ≥ 4.5:1（AA）', () {
    expect(_contrast(AppColors.textPrimary, base), greaterThanOrEqualTo(4.5));
    expect(_contrast(AppColors.textSecondary, base), greaterThanOrEqualTo(4.5));
  });

  test('免责声明文本对底色 ≥ 3:1（即使 10px 也可读）', () {
    expect(_contrast(AppColors.textDisclaimer, base), greaterThanOrEqualTo(3.0));
  });

  test('三色态危险等级文本对底色 ≥ 3:1（配 icon+text 非颜色单一）', () {
    // 三色态非颜色单一依赖由 red_alert_overlay / triage_result_card 的 icon+text 组件测试守（Epic 4）。
    // 此处守等级色作为「文本/边框」用时对底色可辨识（≥3:1 图形对象阈值）。
    expect(_contrast(AppColors.triageRed, base), greaterThanOrEqualTo(3.0));
  });
}

double _contrast(Color fg, Color bg) {
  final l1 = _luminance(fg);
  final l2 = _luminance(bg);
  final lighter = math.max(l1, l2);
  final darker = math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}

double _luminance(Color c) {
  // 新 Color API：c.r/c.g/c.b 为 sRGB 0..1。
  double channel(double s) {
    return s <= 0.03928 ? s / 12.92 : math.pow((s + 0.055) / 1.055, 2.4).toDouble();
  }

  return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
}
