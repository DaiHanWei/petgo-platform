import 'milestone.dart';

/// 「只能自动点亮的健康类里程碑」集合（V1.1.2 Story 5.2 · FR-86，2026-07-31 产品拍板）。
///
/// = **M3 疫苗 · M4 驱虫 · M5 第一次看兽医 · M9 绝育**（猫 C / 狗 D / 通用 G 三系同规则）。
/// 这四条**取消了打卡路径**：只能由健康记录录入或真人兽医问诊结束自动点亮，
/// 用户不能再靠发一条日记打卡解锁。
///
/// ⚠️ 与 `TimelineClassifier.HEALTH_MILESTONE_SUFFIXES`（含 S4、不含 M9）**不是同一个集合，刻意分开**：
/// - 本集合是**功能规则**：哪些里程碑禁止打卡（前端隐藏入口 + 后端拒绝，后端同名集合在
///   `profile/domain/HealthMilestones.java`）；
/// - 那个集合是**展示规则**：哪些里程碑在「当天已有健康条目」时由类④ 胶囊承载、不单独出 banner。
/// 两者用途不同，合并会让其中一侧悄悄改错。
const Set<String> kAutoOnlyHealthMilestoneSuffixes = {'M3', 'M4', 'M5', 'M9'};

/// 该 code 是否为「只能自动点亮」的健康类里程碑（→ 不渲染任何打卡入口）。
bool isAutoOnlyHealthMilestone(String code) {
  final dash = code.lastIndexOf('-');
  final suffix = dash >= 0 ? code.substring(dash + 1) : code;
  return kAutoOnlyHealthMilestoneSuffixes.contains(suffix);
}

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
