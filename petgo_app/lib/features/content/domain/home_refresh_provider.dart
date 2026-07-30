import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 首页刷新信号（Story 1.6 F4）。
///
/// 宠物状态变更后 [bump]，首页 Feed（Epic 3 FR-17 硬过滤）watch 此值 → 按新状态即时刷新。
/// 本 Story 只发信号；硬过滤实现在 Epic 3。
class HomeRefreshNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<HomeRefreshNotifier, int> homeRefreshProvider =
    NotifierProvider<HomeRefreshNotifier, int>(HomeRefreshNotifier.new);

/// 首页「回到顶部」信号（bug 20260709-278）。已在首页时再次点击 Home Tab → [bump]，
/// Feed 视图 watch/listen 此值 → 动画滚回顶部（配合 feed 刷新）。
class HomeScrollTopNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<HomeScrollTopNotifier, int> homeScrollTopProvider =
    NotifierProvider<HomeScrollTopNotifier, int>(HomeScrollTopNotifier.new);
