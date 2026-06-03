import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/app.dart';

/// Story 7.4 AC3（NFR-13）：动态字体放大上限 clamp（防超大字号破布局）。
void main() {
  test('系统超大字号被 clamp 到 ≤ maxTextScale', () {
    final clamped = const TextScaler.linear(3.0).clamp(maxScaleFactor: PetGoApp.maxTextScale);
    // 3.0 → 封顶 1.3
    expect(clamped.scale(100), closeTo(100 * PetGoApp.maxTextScale, 0.01));
    expect(PetGoApp.maxTextScale, lessThanOrEqualTo(1.3));
  });

  test('正常/缩小字号不被放大（仅封上限）', () {
    final normal = const TextScaler.linear(1.0).clamp(maxScaleFactor: PetGoApp.maxTextScale);
    expect(normal.scale(100), closeTo(100, 0.01));
  });
}
