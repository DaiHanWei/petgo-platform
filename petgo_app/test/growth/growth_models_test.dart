import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';

/// V1.4.0 ① Story 1.5 · AC6（L0）：模型解析容忍缺字段 / 未知枚举；满级 null。
void main() {
  test('GrowthSummary 全字段解析', () {
    final s = GrowthSummary.fromJson({
      'participating': true,
      'level': 7,
      'tier': 'BUDDY',
      'lifetimeTotal': 1420,
      'currentThreshold': 1250,
      'nextLevel': 8,
      'nextThreshold': 1800,
      'levels': [
        {'level': 1, 'threshold': 0, 'tier': 'NEWBIE'},
        {'level': 7, 'threshold': 1250, 'tier': 'BUDDY'},
      ],
      'frames': {
        'effective': 'BASIC',
        'manual': false,
        'options': [
          {'frame': 'NONE', 'unlocked': true, 'unlockLevel': 1},
        ],
      },
      'rules': [
        {'code': 'LIKE_GIVEN', 'points': 1, 'dailyCap': 5, 'todayCount': 3, 'todayEarned': 3, 'maxedToday': false},
      ],
      'passiveGroup': {'dailyCap': 50, 'todayEarned': 18, 'maxedToday': false},
      'tierBenefits': [
        {'tier': 'STAR', 'fromLevel': 15, 'benefits': ['FEED_BOOST', 'SOMETHING_NEW']},
      ],
      'recentLedger': [
        {'ruleCode': 'LIKE_GIVEN', 'delta': 1, 'createdAt': '2026-10-18T05:00:00Z'},
      ],
    });
    expect(s.participating, isTrue);
    expect(s.tier, GrowthTier.buddy);
    expect(s.nextThreshold, 1800);
    expect(s.isMaxLevel, isFalse);
    expect(s.levels, hasLength(2));
    expect(s.frames!.effective, 'BASIC');
    expect(s.rules.single.todayCount, 3);
    expect(s.passiveGroup!.todayEarned, 18);
    expect(s.tierBenefits.single.toLevel, isNull);
    expect(s.tierBenefits.single.benefits, ['FEED_BOOST', 'SOMETHING_NEW']);
    expect(s.recentLedger.single.createdAt, DateTime.utc(2026, 10, 18, 5));
  });

  test('满级 nextLevel / nextThreshold 缺省 → null；未知 tier → null', () {
    final s = GrowthSummary.fromJson({'participating': true, 'level': 20, 'tier': 'DIAMOND', 'lifetimeTotal': 50000});
    expect(s.isMaxLevel, isTrue);
    expect(s.nextLevel, isNull);
    expect(s.tier, isNull);
    expect(s.rules, isEmpty);
    expect(s.frames, isNull);
  });

  test('不参与者只有 participating=false', () {
    final s = GrowthSummary.fromJson({'participating': false});
    expect(s.participating, isFalse);
  });

  test('GrowthBrief：缺 level 视为无效 → null；UserProfile 带上 growth', () {
    expect(GrowthBrief.fromJson(null), isNull);
    expect(GrowthBrief.fromJson({'tier': 'BUDDY'}), isNull);
    final p = UserProfile.fromJson({
      'id': 1,
      'growth': {'level': 7, 'tier': 'BUDDY', 'frame': 'BASIC', 'lifetimeTotal': 1420, 'nextThreshold': 1800},
    });
    expect(p.growth!.level, 7);
    expect(p.growth!.tier, GrowthTier.buddy);
    expect(p.growth!.nextThreshold, 1800);
    expect(UserProfile.fromJson({'id': 1}).growth, isNull);
    expect(p.copyWith(nickname: 'x').growth!.level, 7);
  });

  test('GrowthLedgerPage 未知 ruleCode / 缺字段不崩', () {
    final page = GrowthLedgerPage.fromJson({
      'items': [
        {'ruleCode': 'FUTURE_RULE', 'delta': 4},
        {'delta': -2, 'reason': 'REFUND'},
      ],
    });
    expect(page.items, hasLength(2));
    expect(page.items[1].ruleCode, '');
    expect(page.nextCursor, isNull);
  });
}
