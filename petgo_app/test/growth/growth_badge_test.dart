import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/domain/growth_badge.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';

/// V1.4.0 ① Story 3.2 · AC5.1：等级角标模型解析（L0）。
void main() {
  test('fromFlat：author 前缀', () {
    expect(GrowthBadge.fromFlat({'authorLevel': 12, 'authorTier': 'GUARDIAN', 'authorFrame': 'UPGRADED'}),
        const GrowthBadge(level: 12, tier: GrowthTier.guardian, frame: GrowthFrame.upgraded));
  });

  test('fromFlat：空前缀读 level / tier / frame', () {
    expect(GrowthBadge.fromFlat({'level': 15, 'tier': 'STAR', 'frame': 'ANIMATED'}, prefix: ''),
        const GrowthBadge(level: 15, tier: GrowthTier.star, frame: GrowthFrame.animated));
  });

  test('fromJson：/me 嵌套 growth；NONE → 无框', () {
    expect(GrowthBadge.fromJson({'level': 7, 'tier': 'BUDDY', 'frame': 'NONE', 'lifetimeTotal': 1420}),
        const GrowthBadge(level: 7, tier: GrowthTier.buddy));
    expect(GrowthBadge.fromJson(null), isNull);
    expect(GrowthBadge.fromJson('x'), isNull);
  });

  test('缺 level / 非数字 → null；未知 tier → null', () {
    expect(GrowthBadge.fromFlat({'authorTier': 'BUDDY'}), isNull);
    expect(GrowthBadge.fromFlat({'authorLevel': '7', 'authorTier': 'BUDDY'}), isNull);
    expect(GrowthBadge.fromFlat({'authorLevel': 7, 'authorTier': 'GOLD'}), isNull);
    expect(GrowthBadge.fromFlat(const {}), isNull);
  });

  test('frame 为 NONE / 未知 / 缺失 → null', () {
    for (final f in ['NONE', 'SPARKLE', null]) {
      expect(GrowthBadge.fromFlat({'authorLevel': 7, 'authorTier': 'BUDDY', 'authorFrame': f})!.frame, isNull,
          reason: '$f');
    }
  });

  test('fromBrief：复用 1.5 已解析的 GrowthBrief', () {
    expect(GrowthBadge.fromBrief(const GrowthBrief(level: 7, tier: GrowthTier.buddy, frame: 'BASIC')),
        const GrowthBadge(level: 7, tier: GrowthTier.buddy, frame: GrowthFrame.basic));
    expect(GrowthBadge.fromBrief(null), isNull);
    expect(GrowthBadge.fromBrief(const GrowthBrief(level: 7)), isNull, reason: '段位未知不显示');
  });
}
