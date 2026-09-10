import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/health_milestones.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';

/// V1.3.0 批次 A · Story 1.3（L0）：**灰徽章一律有去处**（AD-A6）。
///
/// 改造前 `healthPresetTypeFor` 只映射了 `*-M3`→VACCINE / `*-M4`→DEWORM，
/// `*-M5`（第一次看兽医）与 `*-M9`（绝育）返回 null → 点开只弹一段只读说明、**没有任何去处**。
/// 用户读完那段话，站在原地，不知道该干什么。
///
/// 本类钉住 AD-A6.5 的收口判据：**门控集合里的每一枚 code 都必须有明确去向**。
/// 这是一条全覆盖断言，不是逐条枚举 —— 日后往门控集合里加 code 而忘了配去向，这里当场红。
void main() {
  group('AC4/AC5 收口判据：健康类灰徽章不存在"没有去处"的分支', () {
    test('🔴 门控集合里每一条都能查到去向', () {
      for (final code in kAutoOnlyHealthMilestoneCodes) {
        expect(
          healthDestinationFor(code),
          isNotNull,
          reason: '$code 是禁止打卡的健康类（不出「已打卡/去发布」按钮），'
              '若又没有跳转去向，用户点开就只剩一段读完无路可走的说明',
        );
      }
    });

    test('去向表不多不少，正好覆盖门控集合', () {
      // 多出来的 code 说明有一条不禁止打卡却被当健康类跳走；少了则回到"没有去处"。
      expect(kHealthMilestoneDestinations.keys.toSet(), kAutoOnlyHealthMilestoneCodes);
    });

    test('走健康记录页的每一条都配了预选类型；走问诊的不需要', () {
      kHealthMilestoneDestinations.forEach((code, dest) {
        switch (dest) {
          case MilestoneDestination.healthRecord:
            expect(healthPresetTypeFor(code), isNotNull,
                reason: '$code 要跳健康记录页，却没给预选类型，用户落地后还得自己找类型');
          case MilestoneDestination.vetConsult:
            expect(healthPresetTypeFor(code), isNull,
                reason: '$code 走问诊入口，不该有健康记录预选类型（去向与类型对不上）');
        }
      });
    });
  });

  group('AC1/AC2/AC3 逐条去向', () {
    test('AC3 回归：疫苗 / 驱虫仍跳健康记录页并预选，与改造前一致', () {
      for (final prefix in ['C', 'D']) {
        expect(healthDestinationFor('$prefix-M3'), MilestoneDestination.healthRecord);
        expect(healthPresetTypeFor('$prefix-M3'), 'VACCINE');
        expect(healthDestinationFor('$prefix-M4'), MilestoneDestination.healthRecord);
        expect(healthPresetTypeFor('$prefix-M4'), 'DEWORM');
      }
    });

    test('AC1 绝育 → 健康记录页，预选 NEUTER（本次补上的映射）', () {
      for (final prefix in ['C', 'D']) {
        expect(healthDestinationFor('$prefix-M9'), MilestoneDestination.healthRecord);
        expect(healthPresetTypeFor('$prefix-M9'), 'NEUTER');
      }
    });

    /// AD-A6.3：M5 与 G-M1 语义与触发源相同（真人兽医咨询结束），**去向必须一致**。
    test('AC2 第一次看兽医（C/D-M5 与 G-M1）→ 真人问诊入口', () {
      expect(healthDestinationFor('C-M5'), MilestoneDestination.vetConsult);
      expect(healthDestinationFor('D-M5'), MilestoneDestination.vetConsult);
      expect(healthDestinationFor('G-M1'), MilestoneDestination.vetConsult);
    });

    test('通用宠物 G-M2「第一次健康检查 / 疫苗」→ 健康记录页，预选 VACCINE（决策 A-1）', () {
      // 决策 A-1：只认疫苗，不新增「体检」类型 —— 已接受「只做体检没打疫苗点不亮」的代价。
      expect(healthDestinationFor('G-M2'), MilestoneDestination.healthRecord);
      expect(healthPresetTypeFor('G-M2'), 'VACCINE');
    });
  });

  group('非健康类不受影响', () {
    test('打卡类 / 系统自动类没有跳转去向，维持 P-33b 徽章弹层', () {
      for (final code in ['C-S1', 'C-S6', 'C-M8', 'C-L2', 'D-S14', 'G-S7']) {
        expect(healthDestinationFor(code), isNull);
        expect(healthPresetTypeFor(code), isNull);
      }
    });

    /// 🔴 按后缀判会踩的坑：通用清单的 M3 / M4 跟健康毫无关系。
    /// 旧实现 `code.endsWith('-M3')` 会把「陪伴满 30 天」一点就跳去录疫苗。
    test('通用宠物 G-M3 / G-M4 不跳健康记录页', () {
      expect(healthDestinationFor('G-M3'), isNull); // 陪伴满 30 天
      expect(healthPresetTypeFor('G-M3'), isNull);
      expect(healthDestinationFor('G-M4'), isNull); // 记录满 10 条
      expect(healthPresetTypeFor('G-M4'), isNull);
    });
  });
}
