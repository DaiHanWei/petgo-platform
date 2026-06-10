import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_age.dart';
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
