import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/feed_item.dart';
import 'anon_feed_session.dart';
import '../domain/pinned_slot.dart';

/// Feed 读取数据层（Story 3.2）。游标分页读 `GET /content-posts`。
///
/// 游客无 token 也可调（auth_interceptor 放行只读，后端 GET 放行）；过滤在后端权威执行。
///
/// V1.1.6 Story 16.3：ALL Tab 走**推荐序**，分类 Tab 仍是纯时间倒序 —— 客户端**无需区分**，
/// 分流在后端。客户端只多做一件事：游客带上匿名会话 id（[AnonFeedSession]），
/// 好让推荐序有缓存键可用、翻页不重复。
abstract class FeedRepository {
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20});

  /// 顶置坑位（V1.1.6 Story 4.2）。
  ///
  /// 🛡 **独立取数**：与首页取数分开，首页的分页形态一点不变；
  /// 本请求失败时调用方当作没有顶置，首页照常显示。
  Future<PinnedSlot?> getPinnedSlot();
}

class DioFeedRepository implements FeedRepository {
  DioFeedRepository(this.dio);

  final Dio dio;

  @override
  Future<FeedPage> getFeed({
    FeedCategory category = FeedCategory.all,
    String? cursor,
    int limit = 20,
  }) async {
    final query = <String, dynamic>{'category': category.wire};
    if (cursor != null) query['cursor'] = cursor;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.contentPosts,
      queryParameters: query,
      // 🛡 只挂在首页取数上，不做成全局请求头 —— 它长得像跟踪 id，别让它出现在无关请求里。
      options: Options(headers: {'X-Anon-Session': AnonFeedSession.id}),
    );
    return FeedPage.fromJson(resp.data!);
  }

  @override
  Future<PinnedSlot?> getPinnedSlot() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.contentPinnedSlot);
    return PinnedSlot.fromJson(resp.data ?? const {});
  }
}

final Provider<FeedRepository> feedRepositoryProvider =
    Provider<FeedRepository>((ref) => DioFeedRepository(ref.read(dioProvider)));
