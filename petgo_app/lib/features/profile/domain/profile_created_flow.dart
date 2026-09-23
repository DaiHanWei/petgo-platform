/// 建档完成来源（决定是否展示「创建成功」庆祝页 / 在此触发推送时机）。
///
/// Story 1.7 R2 / AC4 · 决策 F15。仅 FR-0G 正常建档展示庆祝页 + 推送时机（庆祝页后、进首页前）；
/// 经 FR-16（问诊存档，Story 2.5）/ FR-12（B/C 灰选发布，Story 2.3）触发的建档完成**跳过庆祝页**，
/// 直接回原流程（存档回灌 / 返回发布页预选成长日历），不展示庆祝页、不在此触发推送。
enum BuildOrigin {
  /// FR-0G 正常建档（注册引导 / 我的引导卡 / 成长档案入口）→ 展示庆祝页 + 推送时机。
  onboarding,

  /// FR-16 问诊存档触发建档（Story 2.5）→ 跳过庆祝页，回存档回灌。
  triageArchive,

  /// FR-12 B/C 灰选发布触发建档（Story 2.3）→ 跳过庆祝页，回发布页预选成长日历。
  graySelectPublish,
}

/// 是否展示「创建成功」庆祝页（同时决定是否在此触发推送时机——二者同进同退）。
bool showsBuildCelebration(BuildOrigin origin) => origin == BuildOrigin.onboarding;

/// 由路由 query 字符串解析来源；未知/缺省 → onboarding（正常建档，展示庆祝页）。
BuildOrigin buildOriginFromName(String? name) {
  switch (name) {
    case 'triageArchive':
      return BuildOrigin.triageArchive;
    case 'graySelectPublish':
      return BuildOrigin.graySelectPublish;
    default:
      return BuildOrigin.onboarding;
  }
}

/// 建档即自动完成的那条里程碑「档案创建完成」（S1）的 code：猫 `C-S1` / 狗 `D-S1` / 其他与未选 `G-S1`
/// （与后端 `MilestoneCatalog` 的 S1 号位一致，三清单 S1 同义）。
///
/// 用途（bug 20260921-505）：建档庆祝页的「第一个里程碑已解锁」卡**就是**这条的庆祝展示，
/// 离开庆祝页时须按 AD-A3.1 回报 `celebrated_at`；不报的话首次进里程碑列表页会把它当「未庆祝」再补弹一次。
String profileCreatedMilestoneCode(String? petType) => switch (petType) {
      'CAT' => 'C-S1',
      'DOG' => 'D-S1',
      _ => 'G-S1',
    };
