/// 设计 token —— 动效时长（UX-DR2/DR11/DR13）。
class AppMotion {
  AppMotion._();

  /// Tab 内容区切换淡入淡出（UX-DR2）。
  static const Duration tabFade = Duration(milliseconds: 120);

  /// active Tab 区域圆入场 spring（UX-DR13）。
  static const Duration tabActiveSpring = Duration(milliseconds: 150);

  /// modal / bottom sheet 自底上滑（UX-DR11，备 Story 1.4）。
  static const Duration sheet = Duration(milliseconds: 300);
}

/// 兼容旧引用：Tab 内容淡入淡出时长（= [AppMotion.tabFade]）。
const Duration kTabFade = Duration(milliseconds: 120);
