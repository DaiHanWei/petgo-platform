import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_age.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';

void main() {
  group('computePetAge', () {
    final now = DateTime(2026, 6, 2);

    test('整 2 岁', () {
      final a = computePetAge(DateTime(2024, 6, 2), now: now);
      expect(a.years, 2);
      expect(a.months, 0);
    });

    test('2 岁 3 个月', () {
      final a = computePetAge(DateTime(2024, 3, 2), now: now);
      expect(a.years, 2);
      expect(a.months, 3);
    });

    test('未满月按日回退', () {
      final a = computePetAge(DateTime(2026, 5, 20), now: now);
      expect(a.years, 0);
      expect(a.months, 0);
    });

    test('null / 未来日期 → (0,0)', () {
      expect(computePetAge(null).years, 0);
      expect(computePetAge(DateTime(2030, 1, 1), now: now).months, 0);
    });
  });

  // V1.4.0 L2 验收 P1-3：原先一律「{y}th {m}bln」→ 出现「0th 3bln」「1th 1bln」；稿为「3 bln」「2 thn」「1 thn 1 bln」。
  group('formatPetAge（年龄文案）', () {
    final now = DateTime(2026, 10, 19);
    late AppLocalizations id;
    late AppLocalizations en;

    setUpAll(() async {
      id = await AppLocalizations.delegate.load(const Locale('id'));
      en = await AppLocalizations.delegate.load(const Locale('en'));
    });

    test('0 年 → 只显示月', () {
      expect(formatPetAge(id, DateTime(2026, 7, 19), now: now), '3 bln');
      expect(formatPetAge(en, DateTime(2026, 7, 19), now: now), '3 mo');
    });

    test('0 年 11 个月 → 11 bln（不进位）', () {
      expect(formatPetAge(id, DateTime(2025, 11, 19), now: now), '11 bln');
    });

    test('整年 → 只显示年', () {
      expect(formatPetAge(id, DateTime(2024, 10, 19), now: now), '2 thn');
      expect(formatPetAge(en, DateTime(2024, 10, 19), now: now), '2 yr');
    });

    test('年 + 月 → 1 thn 1 bln', () {
      expect(formatPetAge(id, DateTime(2025, 9, 19), now: now), '1 thn 1 bln');
      expect(formatPetAge(en, DateTime(2025, 9, 19), now: now), '1 yr 1 mo');
    });

    test('不满 1 个月仍按天、生日当天 / 无生日为 null', () {
      expect(formatPetAge(id, DateTime(2026, 10, 9), now: now), '10 hari');
      expect(formatPetAge(id, DateTime(2026, 10, 19), now: now), isNull);
      expect(formatPetAge(id, null), isNull);
    });

    test('任何情况都不出现旧格式「th」「0 thn」', () {
      for (final b in [DateTime(2026, 7, 19), DateTime(2024, 10, 19), DateTime(2025, 9, 19)]) {
        final s = formatPetAge(id, b, now: now)!;
        expect(s.contains('th '), isFalse);
        expect(s.startsWith('0 '), isFalse);
      }
    });
  });

  group('TimelineItem.fromJson', () {
    test('快乐时刻', () {
      final i = TimelineItem.fromJson({
        'kind': 'HAPPY_MOMENT',
        'date': '2026-06-02T10:00:00Z',
        'postId': 9,
        'imageUrls': ['a', 'b'],
        'text': 'hi',
      });
      expect(i.kind, TimelineKind.happyMoment);
      expect(i.postId, 9);
      expect(i.imageUrls, ['a', 'b']);
    });

    test('健康事件', () {
      final i = TimelineItem.fromJson({
        'kind': 'HEALTH_EVENT',
        'date': '2026-06-03T10:00:00Z',
        'aiLevel': 'YELLOW',
        'symptomSummary': '咳嗽',
      });
      expect(i.kind, TimelineKind.healthEvent);
      expect(i.aiLevel, 'YELLOW');
      expect(i.symptomSummary, '咳嗽');
    });

    test('TimelinePage.fromJson 分页字段', () {
      final p = TimelinePage.fromJson({
        'items': [
          {'kind': 'HAPPY_MOMENT', 'date': '2026-06-02T10:00:00Z'}
        ],
        'nextCursor': '2026-06-01T10:00:00Z',
        'hasMore': true,
      });
      expect(p.items, hasLength(1));
      expect(p.hasMore, isTrue);
      expect(p.nextCursor, '2026-06-01T10:00:00Z');
    });
  });
}
