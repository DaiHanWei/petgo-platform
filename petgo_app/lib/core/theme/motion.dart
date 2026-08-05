/// 设计 token —— 动效时长（UX-DR2/DR11/DR13）。
class AppMotion {
  AppMotion._();

  /// Tab 内容区切换淡入淡出（UX-DR2）。
  static const Duration tabFade = Duration(milliseconds: 120);

  /// active Tab 区域圆入场 spring（UX-DR13）。
  static const Duration tabActiveSpring = Duration(milliseconds: 150);

  /// Tab 激活态萌化装饰的一次轻弹跳（FR-78A 方案A；**硬上限 150ms**，NFR-9）。
  /// 系统开启「减少动态效果」时取 [Duration.zero]（icon-system.md：状态照常切换，仅去动画）。
  static const Duration tabCharmBounce = Duration(milliseconds: 150);

  /// modal / bottom sheet 自底上滑（UX-DR11，备 Story 1.4）。
  static const Duration sheet = Duration(milliseconds: 300);
}

/// 兼容旧引用：Tab 内容淡入淡出时长（= [AppMotion.tabFade]）。
const Duration kTabFade = Duration(milliseconds: 120);
