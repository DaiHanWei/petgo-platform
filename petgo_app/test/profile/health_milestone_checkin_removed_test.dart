import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/health_milestones.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';

/// Story 5.2 · L0：健康类里程碑取消打卡路径 + 同批解锁只弹一次（按最高级别）。
///
/// ⚠️ 前端隐藏入口只是**一半**：后端另有显式拒绝护栏（`MilestoneCheckInService`，NFR-11）——
/// 绕过 UI 直接调接口仍能打卡就等于规则没落地。两侧必须同批交付。
void main() {
  group('AC0/AC1 只能自动点亮的健康类四条', () {
    test('M3 / M4 / M5 / M9 命中（C/D/G 三系）；其余里程碑不受影响', () {
      for (final prefix in ['C', 'D', 'G']) {
        for (final suffix in ['M3', 'M4', 'M5', 'M9']) {
          expect(isAutoOnlyHealthMilestone('$prefix-$suffix'), isTrue);
        }
        // 打卡路径保留的那些
        expect(isAutoOnlyHealthMilestone('$prefix-S1'), isFalse);
        expect(isAutoOnlyHealthMilestone('$prefix-S6'), isFalse);
        expect(isAutoOnlyHealthMilestone('$prefix-M8'), isFalse);
        expect(isAutoOnlyHealthMilestone('$prefix-L2'), isFalse);
      }
    });

    test('与时间线展示用集合刻意不同：本集合含 M9、不含 S4', () {
      // 展示规则那份（TimelineClassifier）含 S4、不含 M9 —— 两者用途不同，不得合并。
      expect(kAutoOnlyHealthMilestoneSuffixes.contains('M9'), isTrue);
      expect(kAutoOnlyHealthMilestoneSuffixes.contains('S4'), isFalse);
      expect(kAutoOnlyHealthMilestoneSuffixes, {'M3', 'M4', 'M5', 'M9'});
    });
  });

  group('AC3 同批解锁：只庆祝最高级别那一条', () {
    MilestoneItem item(String code, MilestoneLevel level) => MilestoneItem(
          code: code,
          title: code,
          level: level,
          trigger: MilestoneTrigger.systemAuto,
          completed: true,
        );

    test('L > M > S', () {
      final picked = highestLevelMilestone([
        item('C-S6', MilestoneLevel.s),
        item('C-M3', MilestoneLevel.m),
        item('C-L2', MilestoneLevel.l),
      ]);
      expect(picked?.code, 'C-L2');
    });

    test('同级取第一条（稳定，不随集合顺序抖动）', () {
      final picked = highestLevelMilestone([
        item('C-M3', MilestoneLevel.m),
        item('C-M8', MilestoneLevel.m),
      ]);
      expect(picked?.code, 'C-M3');
    });

    test('单条 → 就是它；空集 → null（不弹庆祝）', () {
      expect(highestLevelMilestone([item('C-S1', MilestoneLevel.s)])?.code, 'C-S1');
      expect(highestLevelMilestone(const []), isNull);
    });
  });
}
