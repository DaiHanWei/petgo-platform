/// 设计 token —— 间距（UX-DR1）。xxs → section + 常用布局量。
class AppSpacing {
  AppSpacing._();

  static const double xxs = 2;
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;
  static const double section = 48;

  // --- 布局量（备 Epic 2/3 复用）---
  /// 屏幕左右边距。
  static const double screenEdge = 16;

  /// 列表项间距。
  static const double listGap = 8;
}
