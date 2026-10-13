import 'dart:ui' show Locale;

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/utils/compact_count.dart';

/// V1.3.4 follow · Story 1.3 · AC9（D-3 / T-11）：≥1000 缩写、一位小数、去末尾 0、向下截断、小数点随语言。
void main() {
  const id = Locale('id');
  const en = Locale('en');

  test('小于 1000 原样显示，负数按 0', () {
    expect(formatCompactCount(0, id), '0');
    expect(formatCompactCount(999, id), '999');
    expect(formatCompactCount(-5, en), '0');
  });

  test('K：一位小数、去末尾 0、向下截断', () {
    expect(formatCompactCount(1000, id), '1K');
    expect(formatCompactCount(1234, id), '1,2K');
    expect(formatCompactCount(1234, en), '1.2K');
    expect(formatCompactCount(1250, id), '1,2K');
    expect(formatCompactCount(1299, en), '1.2K');
    expect(formatCompactCount(9999, id), '9,9K');
    expect(formatCompactCount(10000, en), '10K');
    expect(formatCompactCount(12345, id), '12,3K');
    expect(formatCompactCount(999999, id), '999,9K');
    expect(formatCompactCount(999999, en), '999.9K');
  });

  test('M', () {
    expect(formatCompactCount(1000000, id), '1M');
    expect(formatCompactCount(1234567, id), '1,2M');
    expect(formatCompactCount(1234567, en), '1.2M');
  });
}
