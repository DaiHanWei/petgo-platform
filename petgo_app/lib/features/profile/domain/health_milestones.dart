import 'milestone.dart';

/// 「只能自动点亮的健康类里程碑」集合（V1.1.2 Story 5.2 · FR-86，2026-07-31 产品拍板；
/// V1.3.0 Story 1.2 补齐通用宠物、并由后缀改为完整 code）。
///
/// = 猫狗的 **M3 疫苗 · M4 驱虫 · M5 第一次看兽医 · M9 绝育**，
///   加通用宠物的 **G-M1 第一次看兽医 · G-M2 第一次健康检查 / 疫苗**。
/// 这些**取消了打卡路径**：只能由健康记录录入或真人兽医问诊结束自动点亮，
/// 用户不能再靠发一条日记打卡解锁。
///
/// 🔴 **V1.3.0 Story 1.2 起按完整 code 判，不再按后缀**（AD-A4 / AD-A5）。原因就是这两条通用节点：
/// FR-86 当初按后缀 M3/M4/M5/M9 取消打卡，而通用清单把「看兽医」放在 `G-M1`、
/// 「健康检查 / 疫苗」放在 `G-M2`，两个后缀都不命中 —— 同一件事，猫狗要真做才点亮、
/// 其他宠物点一下「已打卡」就行。按后缀判还有反向风险：通用清单的 `G-M3` 是「陪伴满 30 天」、
/// `G-M4` 是「记录满 10 条」，跟健康无关，却会因撞上猫狗号位被判成健康类。
///
/// ⚠️ **必须与后端 `profile/domain/HealthMilestones.java` 的 `CODES` 逐字等长**（AD-A4.6 集合 ④ ↔ ①）：
/// 前端隐藏入口、后端显式拒绝，两半缺一不可 —— 只改一侧会让两边对「这条能不能打卡」的答案分叉。
/// 三张清单成员数不同是正常的（猫狗各 4、通用 2）：通用清单本就没有独立的驱虫 / 绝育节点，
/// **别为了"对齐"给通用宠物硬凑两条。**
///
/// ⚠️ 与后端 `TimelineClassifier.HEALTH_MILESTONE_CODES`（= 本集合 + 三条 S4）**用途不同**：
/// - 本集合是**功能规则**：哪些里程碑禁止打卡；
/// - 那个集合是**展示规则**：哪些里程碑在「当天已有健康条目」时由类④ 胶囊承载、不单独出 banner。
/// 两者刻意分开，合并会让其中一侧悄悄改错。
const Set<String> kAutoOnlyHealthMilestoneCodes = {
  'C-M3', 'C-M4', 'C-M5', 'C-M9',
  'D-M3', 'D-M4', 'D-M5', 'D-M9',
  'G-M1', 'G-M2',
};

/// 该 code 是否为「只能自动点亮」的健康类里程碑（→ 不渲染任何打卡入口）。
bool isAutoOnlyHealthMilestone(String code) =>
    kAutoOnlyHealthMilestoneCodes.contains(code);

/// 同批解锁多条时用于庆祝的那一条：**级别最高**（L > M > S）；同级取第一条（稳定）。
///
/// Story 5.2 · AC3：同批只弹**一次**庆祝、按最高级别；其余条目由弹层底部既有「已解锁收藏」
/// 圆点带（含 +N 溢出）承载 —— **不新增视觉设计**。
MilestoneItem? highestLevelMilestone(Iterable<MilestoneItem> items) {
  MilestoneItem? best;
  for (final it in items) {
    if (best == null || _levelRank(it.level) > _levelRank(best.level)) {
      best = it;
    }
  }
  return best;
}

int _levelRank(MilestoneLevel level) => switch (level) {
      MilestoneLevel.l => 3,
      MilestoneLevel.m => 2,
      MilestoneLevel.s => 1,
    };
