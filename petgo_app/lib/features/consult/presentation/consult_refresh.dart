import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 评分刷新信号：用户在任意入口（会话页 / 入口补弹 / 历史补弹）评分后 [bump]，
/// 咨询历史列表（triage_page 的 _history）listen 后重拉，实时显示已评分 / 星级。
class ConsultRefreshNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final NotifierProvider<ConsultRefreshNotifier, int> consultRefreshProvider =
    NotifierProvider<ConsultRefreshNotifier, int>(ConsultRefreshNotifier.new);
