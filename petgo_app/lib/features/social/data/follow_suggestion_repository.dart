import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/follow_list_item.dart';
import '../domain/follow_state.dart';

/// 推荐关注数据层（V1.3.4 follow · Story 2.3 · AC1）：`GET /api/v1/me/follow-suggestions`。
///
/// ⚠️ 单独一个仓库，不挂在 `FollowRepository` / `FollowListRepository` 上：两者都已被多处测试 fake
/// `implements`，加方法会让每个 fake 都得补实现（同 Story 2.2 拆出 `FollowListRepository` 的理由）。
abstract class FollowSuggestionRepository {
  /// 最多 30 行，已按推荐次序排好；行复用 [FollowListItem]。
  Future<List<FollowListItem>> fetch();
}

class DioFollowSuggestionRepository implements FollowSuggestionRepository {
  DioFollowSuggestionRepository(this.dio);

  final Dio dio;

  @override
  Future<List<FollowListItem>> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meFollowSuggestions);
    return ((resp.data?['items'] as List?) ?? const [])
        .map((e) => FollowListItem.fromJson((e as Map).cast<String, dynamic>()))
        .toList(growable: false);
  }
}

final Provider<FollowSuggestionRepository> followSuggestionRepositoryProvider =
    Provider<FollowSuggestionRepository>((ref) => DioFollowSuggestionRepository(ref.read(dioProvider)));

/// 推荐关注结果（Story 2.3 · AC2.3）：**只在需要时请求** —— 只有「我自己 + 某 Tab 为空」的推荐区 watch 它；
/// 两个 Tab 都空时共用这一个实例（同一次结果）。页面离开后释放（autoDispose）。
///
/// 发请求前记全局关注版本号（Story 2.1 AC3.3）：下拉刷新重拉后，旧覆盖对新快照自动失效。
///
/// ⚠️ `retry: null` 关掉 Riverpod 3 的自动重试：失败即静默隐藏推荐区（T-24），不在后台反复请求。
final followSuggestionsProvider = FutureProvider.autoDispose<List<FollowListItem>>(
  (ref) async {
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final items = await ref.read(followSuggestionRepositoryProvider).fetch();
    return [for (final i in items) i.withFollowEpoch(epoch)];
  },
  retry: (_, _) => null,
);
