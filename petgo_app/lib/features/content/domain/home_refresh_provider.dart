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
