/// 宠物状态选择后的分叉去向（AC2，纯函数）。
///
/// A 我有宠物 → 档案创建引导（Story 1.7）；B/C → 直接进首页（不显示档案提示条）。
String petStatusBranchLocation(String petStatus) {
  return petStatus.toUpperCase() == 'A' ? '/onboarding/profile' : '/home';
}
