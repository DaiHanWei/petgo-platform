import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 「切入 Diary Tab」信号（PR#34 次要项 3）。
///
/// Diary 分支根在 `StatefulShellRoute.indexedStack` 里保活——来回切 Tab 不重挂载、
/// `initState` 不再跑，靠 initState 上报的曝光每会话最多一条、`session_first` 恒 true。
/// AppShell 在用户**真正切入** profile Tab（非重复点当前 Tab）时 bump 本信号，
/// 页面 `ref.listen` 补报重复曝光。
class ProfileTabEnteredNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<ProfileTabEnteredNotifier, int> profileTabEnteredProvider =
    NotifierProvider<ProfileTabEnteredNotifier, int>(ProfileTabEnteredNotifier.new);
