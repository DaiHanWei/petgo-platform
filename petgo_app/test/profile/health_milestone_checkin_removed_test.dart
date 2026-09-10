import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/health_milestones.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';

/// Story 5.2 · L0：健康类里程碑取消打卡路径 + 同批解锁只弹一次（按最高级别）。
///
/// ⚠️ 前端隐藏入口只是**一半**：后端另有显式拒绝护栏（`MilestoneCheckInService`，NFR-11）——
/// 绕过 UI 直接调接口仍能打卡就等于规则没落地。两侧必须同批交付。
void main() {
  group('AC0/AC1 只能自动点亮的健康类里程碑', () {
    test('猫狗 M3 / M4 / M5 / M9 命中；其余里程碑不受影响', () {
      for (final prefix in ['C', 'D']) {
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

    /// V1.3.0 Story 1.2（AD-A5）：通用宠物的两条终于补进来了。
    ///
    /// 🔴 这一组是本 story 的核心断言。FR-86 当初按后缀取消打卡，通用清单把「看兽医」放在 G-M1、
    /// 「健康检查 / 疫苗」放在 G-M2，后缀都不命中 —— 同一件事猫狗要真做才点亮、其他宠物点一下就行。
    test('通用宠物 G-M1 / G-M2 命中（V1.3.0 Story 1.2 补齐的规则漏网）', () {
      expect(isAutoOnlyHealthMilestone('G-M1'), isTrue); // 第一次看兽医
      expect(isAutoOnlyHealthMilestone('G-M2'), isTrue); // 第一次健康检查 / 疫苗
    });

    /// 🔴 按后缀判会踩的反向坑：通用清单的 M3 / M4 跟健康毫无关系。
    test('通用宠物 G-M3 / G-M4 不是健康类 —— 它们是陪伴满 30 天 / 记录满 10 条', () {
      expect(isAutoOnlyHealthMilestone('G-M3'), isFalse);
      expect(isAutoOnlyHealthMilestone('G-M4'), isFalse);
      // 通用清单压根没有这两个 code，也不该因为后缀撞号被判中。
      expect(isAutoOnlyHealthMilestone('G-M5'), isFalse);
      expect(isAutoOnlyHealthMilestone('G-M9'), isFalse);
    });

    test('与时间线展示用集合刻意不同：本集合含 M9、不含 S4', () {
      // 展示规则那份（后端 TimelineClassifier）= 本集合 + 三条 S4 —— 用途不同，不得合并。
      expect(kAutoOnlyHealthMilestoneCodes.contains('C-M9'), isTrue);
      expect(kAutoOnlyHealthMilestoneCodes.contains('C-S4'), isFalse);
      expect(kAutoOnlyHealthMilestoneCodes.contains('G-S4'), isFalse);
    });

    /// ⚠️ 与后端 `HealthMilestones.CODES` 逐字等长（AD-A4.6 集合 ④ ↔ ①）。
    /// 后端拒绝、前端隐藏，两半缺一不可 —— 改一侧不改另一侧，两边对「能不能打卡」的答案就分叉。
    test('集合内容与后端 HealthMilestones.CODES 逐字一致', () {
      expect(kAutoOnlyHealthMilestoneCodes, {
        'C-M3', 'C-M4', 'C-M5', 'C-M9',
        'D-M3', 'D-M4', 'D-M5', 'D-M9',
        'G-M1', 'G-M2',
      });
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
