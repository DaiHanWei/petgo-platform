import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/video/video_spec.dart';
import 'package:tailtopia/features/content/video/video_spec_messages.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：专题线 video Story 1.5 code review —— 超限的「实际值」不能被四舍五入成看起来合格的值。
void main() {
  final l10n = lookupAppLocalizations(const Locale('en'));

  VideoSpecResult r({int? ms, int? bytes}) => VideoSpecResult(
        failures: const [],
        durationMs: ms,
        sizeBytes: bytes,
      );

  test('略超 90 秒向上取整（90.04s → 90.1）', () {
    expect(videoSpecFailureMessage(l10n, VideoSpecFailure.duration, r(ms: 90040)), contains('90.1'));
  });

  test('略低于 3 秒向下取整（2.96s → 2.9）', () {
    expect(videoSpecFailureMessage(l10n, VideoSpecFailure.duration, r(ms: 2960)), contains('2.9'));
  });

  test('略超 100MB 向上取整（→ 100.1）', () {
    expect(videoSpecFailureMessage(l10n, VideoSpecFailure.size, r(bytes: 104857700)), contains('100.1'));
  });
}
