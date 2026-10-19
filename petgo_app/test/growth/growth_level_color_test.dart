import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/growth/domain/growth_level_color.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';

/// V1.4.0 ① Story 1.5 · AC7（L0）：逐级色查表；Lv21+ 取所属段位末色；段位由 levels 决定。
void main() {
  List<GrowthLevelDef> table({int extra = 0}) {
    final t = <GrowthLevelDef>[];
    for (var lv = 1; lv <= 20 + extra; lv++) {
      final tier = lv <= 4
          ? GrowthTier.newbie
          : lv <= 9
              ? GrowthTier.buddy
              : lv <= 14
                  ? GrowthTier.guardian
                  : GrowthTier.star;
      t.add(GrowthLevelDef(level: lv, threshold: lv * 100, tier: tier));
    }
    return t;
  }

  test('Lv1 / Lv4 / Lv5 / Lv20 取 UI 稿色表', () {
    final levels = table();
    expect(growthLevelColor(1, levels), AppColors.growthLevelColors[0]);
    expect(growthLevelColor(4, levels), AppColors.growthLevelColors[3]);
    expect(growthLevelColor(5, levels), AppColors.growthLevelColors[4]);
    expect(growthLevelColor(20, levels), AppColors.growthLevelColors[19]);
    expect(AppColors.growthLevelColors, hasLength(20));
  });

  test('后台追加 Lv21（STAR）→ Bintang 末色，不插值', () {
    expect(growthLevelColor(21, table(extra: 1)), AppColors.growthLevelColors[19]);
  });

  test('等级表为空时按初始分段兜底，段内仍逐级变色', () {
    expect(growthLevelColor(7, const []), AppColors.growthLevelColors[6]);
    expect(growthLevelColor(8, const []), AppColors.growthLevelColors[7]);
  });

  test('段位由 levels 的 tier 决定（不写死区间）', () {
    // 假设运营把 Lv5 划到 NEWBIE：Lv5 应取 Pemula 段的第 5 个色（截断到末色）。
    final levels = [
      for (var lv = 1; lv <= 5; lv++) GrowthLevelDef(level: lv, threshold: lv * 10, tier: GrowthTier.newbie),
      const GrowthLevelDef(level: 6, threshold: 100, tier: GrowthTier.buddy),
    ];
    expect(growthLevelColor(5, levels), AppColors.growthLevelColors[3]);
    expect(growthLevelColor(6, levels), AppColors.growthLevelColors[4]); // Sahabat 首色
  });
}
