import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/collect_target.dart';

/// 收藏变更信号（V1.4.0 ④ Story 2.3 · AD-16）：收藏或取消**成功**（后端已确认）后由按钮 [bump]，
/// 「我的」页收藏列表（Story 2.4）`watch` 它自动重拉。
///
/// 只是计数器、不含用户数据 → **不登记** `resetUserScopedCaches`（列表 provider 自己登记）。
class CollectionsRevisionNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

final collectionsRevisionProvider = NotifierProvider.family<CollectionsRevisionNotifier, int, CollectTarget>(
    (_) => CollectionsRevisionNotifier());
