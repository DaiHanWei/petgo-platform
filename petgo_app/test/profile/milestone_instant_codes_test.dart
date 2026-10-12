import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone_badge_assets.dart';
import 'package:tailtopia/features/profile/domain/milestone_instant_codes.dart';
import 'package:tailtopia/features/profile/domain/milestone_titles.dart';

import 'milestone_instant_fakes.dart';

/// V1.3.4 Story 4.3 · AC1.2 / AC3.2：当场庆祝的纯函数判定。
void main() {
  const emptyLocal = <String>{};

  group('findMilestoneByCodes', () {
    test('猫清单含 C-S17 → 命中', () {
      final list = instantMilestoneList('C-S17', completed: false);
      expect(findMilestoneByCodes(list, kPlaceCheckinMilestoneCodes)?.code, 'C-S17');
    });

    test('通用清单含 G-S10 → 命中', () {
      final list = instantMilestoneList('G-S10', completed: true);
      expect(findMilestoneByCodes(list, kPlaceCheckinMilestoneCodes)?.code, 'G-S10');
    });

    test('清单无任何目标 code → null', () {
      final list = instantMilestoneList('C-S5', completed: true);
      expect(findMilestoneByCodes(list, kPlaceCheckinMilestoneCodes), isNull);
      expect(findMilestoneByCodes(list, kTailsonalityMilestoneCodes), isNull);
    });
  });

  group('pickInstantCelebration', () {
    test('completed=true, celebratedAt=null → 命中', () {
      final list = instantMilestoneList('D-S18', completed: true);
      expect(pickInstantCelebration(list, kTailsonalityMilestoneCodes, emptyLocal)?.code, 'D-S18');
    });

    test('celebratedAt 非空 → null', () {
      final list = instantMilestoneList('D-S18', completed: true, celebrated: true);
      expect(pickInstantCelebration(list, kTailsonalityMilestoneCodes, emptyLocal), isNull);
    });

    test('completed=false → null', () {
      final list = instantMilestoneList('D-S18', completed: false);
      expect(pickInstantCelebration(list, kTailsonalityMilestoneCodes, emptyLocal), isNull);
    });

    test('在本机已庆祝集合中 → null', () {
      final list = instantMilestoneList('D-S18', completed: true);
      expect(pickInstantCelebration(list, kTailsonalityMilestoneCodes, {'D-S18'}), isNull);
    });
  });

  group('instantCelebrationSettled', () {
    test('不在列表 → true', () {
      final list = instantMilestoneList('C-S5', completed: false);
      expect(instantCelebrationSettled(list, kPlaceCheckinMilestoneCodes, emptyLocal), isTrue);
    });

    test('已完成已庆祝 → true', () {
      final list = instantMilestoneList('C-S17', completed: true, celebrated: true);
      expect(instantCelebrationSettled(list, kPlaceCheckinMilestoneCodes, emptyLocal), isTrue);
    });

    test('本机已庆祝 → true', () {
      final list = instantMilestoneList('C-S17', completed: true);
      expect(instantCelebrationSettled(list, kPlaceCheckinMilestoneCodes, {'C-S17'}), isTrue);
    });

    test('未完成 → false（需要轮询）', () {
      final list = instantMilestoneList('C-S17', completed: false);
      expect(instantCelebrationSettled(list, kPlaceCheckinMilestoneCodes, emptyLocal), isFalse);
    });

    test('已完成未庆祝 → false（交给 pickInstantCelebration 直接弹）', () {
      final list = instantMilestoneList('C-S17', completed: true);
      expect(instantCelebrationSettled(list, kPlaceCheckinMilestoneCodes, emptyLocal), isFalse);
    });
  });

  group('常量集合', () {
    test('恰为 AC 列出的三个 code，且标题 / 徽章键齐全', () {
      expect(kPlaceCheckinMilestoneCodes, {'C-S17', 'D-S17', 'G-S10'});
      expect(kTailsonalityMilestoneCodes, {'C-S18', 'D-S18', 'G-S11'});
      for (final c in kPlaceCheckinMilestoneCodes) {
        expect(kMilestoneTitles.containsKey(c), isTrue, reason: c);
        expect(kMilestoneBadgeKeys[c], 'place_checkin', reason: c);
      }
      for (final c in kTailsonalityMilestoneCodes) {
        expect(kMilestoneTitles.containsKey(c), isTrue, reason: c);
        expect(kMilestoneBadgeKeys[c], 'tailsonality', reason: c);
      }
    });

    test('两集合不相交', () {
      expect(kPlaceCheckinMilestoneCodes.intersection(kTailsonalityMilestoneCodes), isEmpty);
    });
  });
}
