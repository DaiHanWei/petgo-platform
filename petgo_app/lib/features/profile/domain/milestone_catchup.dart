/// 补庆祝的**纯判定逻辑**（V1.3.0 批次 A · Story 1.5 · FR-111 · AD-A2）。
///
/// 刻意做成不依赖 Flutter / 网络的纯函数：这条链路上最容易出事的两点（该补哪些、要不要抑制）
/// 因此全部 L0 可测，不必等真机。
library;

import 'health_milestones.dart';
import 'milestone.dart';

/// 「刚庆祝过」的 code 集合，在**同一次导航内**从上游页面传给里程碑列表页。
///
/// 🔴 <b>它存在的唯一理由：挡住「去发布」路径的连弹</b>（AD-A2.3c）。
/// 既定时序是「发布成功 → 回填打卡 → **弹庆祝** → 关发布 sheet → **跳里程碑列表页**」，
/// 而庆祝回报是**异步**的（AD-A3.1 失败静默）—— 列表页极可能在回报落库前就读到
/// `celebratedAt == null`，于是**两秒内连弹两次同一条**。
///
/// ⚠️ 收口必须落在客户端这一侧，**不依赖回报是否已落库**；也**不得**把回报改成同步来绕开
/// （那会让发布流程卡在一个可失败的写上）。
///
/// 生命周期只有一次导航：离开列表页即失效，不进 prefs、不做持久化 —— 它不是「已庆祝」的
/// 事实来源（那个在服务端的 `celebrated_at`），只是一次导航内的防抖。
typedef JustCelebratedCodes = Set<String>;

/// 一次补庆祝的决策结果。
class MilestoneCatchup {
  const MilestoneCatchup({required this.toCelebrate, required this.codesToReport});

  /// 要弹的那**一条**（级别最高）；无可补 → null。
  final MilestoneItem? toCelebrate;

  /// 本次庆祝展示**实际覆盖**的全部 code —— 回报就报这一批。
  ///
  /// 包含被 KOLEKSI 圆点带过的那些：它们确实在用户眼前出现过，算已庆祝。
  /// 但**只包含本次覆盖的**，不是「所有未庆祝的」—— 服务端按这份列表置位，
  /// 从而不会吞掉读取到回报之间新解锁的条目（AD-A2.3b）。
  final List<String> codesToReport;

  bool get isEmpty => toCelebrate == null;
}

/// 计算进入里程碑列表页时该不该补弹、补哪一条、回报哪些。
///
/// [items] 为列表页当前全部条目；[justCelebrated] 为本次导航内刚庆祝过的 code（见
/// [JustCelebratedCodes]），会**先扣除**再判定。
///
/// 规则（AD-A2）：
/// - 判据只有一个：`completed && celebratedAt == null`（[MilestoneItem.isUncelebrated]）；
/// - **不按级别过滤，S 级也补** —— 入口是用户主动点进来的，没有打扰问题（AD-A2.4）；
/// - 多条同时未庆祝**只弹级别最高的一条**，其余由 KOLEKSI 圆点带过
///   （1.1.2 既有规则，AD-A2.5 扩展为全路径统一，含补庆祝这条路径）。
MilestoneCatchup resolveCatchup(
  Iterable<MilestoneItem> items, {
  JustCelebratedCodes justCelebrated = const {},
}) {
  final pending = <MilestoneItem>[
    for (final it in items)
      if (it.isUncelebrated && !justCelebrated.contains(it.code)) it,
  ];
  if (pending.isEmpty) {
    return const MilestoneCatchup(toCelebrate: null, codesToReport: []);
  }
  return MilestoneCatchup(
    // 「只弹级别最高的一条」用 health_milestones.dart 里那个既有函数（Story 5.2 · AC3 落地的
    // 同一条规则，AD-A2.5 把它扩展为含补庆祝在内的全路径统一规则）。**不另写一份**。
    toCelebrate: highestLevelMilestone(pending),
    codesToReport: [for (final it in pending) it.code],
  );
}
