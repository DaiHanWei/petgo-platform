import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/domain/growth_popups.dart';

/// V1.4.0 ① Story 2.3：`GET /me/growth/popups` 模型与升级通知 targetRef 解析（L0）。
void main() {
  group('GrowthPopups.fromJson', () {
    test('庆祝 + 汇总完整解析', () {
      final p = GrowthPopups.fromJson({
        'celebration': {
          'fromLevel': 4,
          'toLevel': 5,
          'tierUp': true,
          'toTier': 'BUDDY',
          'lifetimeTotal': 520,
          'unlockedBenefits': ['PET_SLOT_PLUS_ONE', 'FRAME_BASIC', 7],
          'frameAction': 'OFFER_NEW_FRAME',
        },
        'backfill': {
          'level': 6,
          'tier': 'BUDDY',
          'activeDays': 60,
          'milestoneCount': 13,
          'milestoneExp': 95,
          'total': 275,
        },
      });
      final c = p.celebration!;
      expect((c.fromLevel, c.toLevel, c.tierUp, c.toTier), (4, 5, true, GrowthTier.buddy));
      expect(c.lifetimeTotal, 520);
      expect(c.unlockedBenefits, ['PET_SLOT_PLUS_ONE', 'FRAME_BASIC']);
      expect(c.frameAction, GrowthFrameAction.offerNewFrame);
      final b = p.backfill!;
      expect((b.level, b.tier, b.activeDays, b.milestoneCount, b.milestoneExp, b.total),
          (6, GrowthTier.buddy, 60, 13, 95, 275));
      expect(p.isEmpty, isFalse);
    });

    test('缺字段 / 类型不对 → 该项为 null，不抛', () {
      expect(GrowthPopups.fromJson(null).isEmpty, isTrue);
      expect(GrowthPopups.fromJson('x').isEmpty, isTrue);
      final p = GrowthPopups.fromJson({
        'celebration': {'fromLevel': '4', 'toLevel': 5},
        'backfill': {'tier': 'BUDDY'},
      });
      expect(p.celebration, isNull);
      expect(p.backfill, isNull);
      expect(p.isEmpty, isTrue);
    });

    test('frameAction 未知值 → none；AUTO → auto', () {
      expect(GrowthFrameAction.fromCode('AUTO'), GrowthFrameAction.auto);
      expect(GrowthFrameAction.fromCode('WHAT'), GrowthFrameAction.none);
      expect(GrowthFrameAction.fromCode(null), GrowthFrameAction.none);
    });
  });

  group('parseLevelUpRef', () {
    test('"7:BUDDY" → Lv7 / Sahabat', () {
      final r = parseLevelUpRef('7:BUDDY')!;
      expect(r.level, 7);
      expect(r.tier, GrowthTier.buddy);
    });

    test('格式不对 → null', () {
      for (final ref in [null, '', 'abc', '7', '7:', ':BUDDY', '0:BUDDY', '7:XYZ', '7:BUDDY:1']) {
        expect(parseLevelUpRef(ref), isNull, reason: '$ref');
      }
    });
  });
}
