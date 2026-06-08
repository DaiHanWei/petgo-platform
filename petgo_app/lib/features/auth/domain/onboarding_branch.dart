/// 宠物状态选择后的分叉去向（AC2，纯函数）。
///
/// A 我有宠物 → 档案创建引导（Story 1.7）；B/C → 直接进首页（不显示档案提示条）。
String petStatusBranchLocation(String petStatus) {
  return petStatus.toUpperCase() == 'A' ? '/onboarding/profile' : '/home';
}

/// 运行期改宠物状态的后续动作（Story 1.6 R2 / AC5 · FR-21 · 决策 F15 邻接）。
///
/// 「我的→宠物状态」/「成长档案 Tab」运行期改状态的语义分支（纯函数，供 Epic 7/Epic 2
/// 入口复用）。无论哪个分支，调用方都应发出首页刷新信号（FR-17 在 Epic 3 消费）。
enum PetStatusChangeAction {
  /// B/C→A 且**从未建过宠物档案** → 触发 FR-0G 建档引导（跳过后激活 FR-0H 首页提示条，
  /// 计数沿用 A 态既有持久计数不重置）。与注册时选 A 完全等价（复用 Story 1.7）。
  toProfileOnboarding,

  /// B/C→A 且**已有宠物档案**（曾是 A、档案未删）→ 档案直接恢复可见，
  /// 不再触发 FR-0G 引导 / 不再显示 FR-0H 提示条（留存资产无需重复引导）。
  restoreExistingProfile,

  /// A→B/C → 宠物档案**保留不删**（后端不级联删除），成长档案 Tab 显「有宠用户专属」
  /// 非 A 态占位；首页按新状态刷新。
  switchAwayFromPet,

  /// 其它（A→A、B↔C 等非 A 维度变化）→ 仅刷新首页。
  refreshOnly,
}

/// 给定旧/新状态与是否已建档，判定运行期改状态的后续动作（AC5）。
///
/// 注意：本函数只决定「引导/恢复/保留」语义；首页刷新信号由调用方对所有变更统一发出。
PetStatusChangeAction petStatusChangeAction({
  required String oldStatus,
  required String newStatus,
  required bool hasPetProfile,
}) {
  final wasA = oldStatus.toUpperCase() == 'A';
  final isA = newStatus.toUpperCase() == 'A';
  if (!wasA && isA) {
    return hasPetProfile
        ? PetStatusChangeAction.restoreExistingProfile
        : PetStatusChangeAction.toProfileOnboarding;
  }
  if (wasA && !isA) {
    return PetStatusChangeAction.switchAwayFromPet;
  }
  return PetStatusChangeAction.refreshOnly;
}
