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
