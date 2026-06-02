import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/feed_item.dart';

/// Feed 读取数据层（Story 3.2）。游标分页读 `GET /content-posts`。
///
/// 游客无 token 也可调（auth_interceptor 放行只读，后端 GET 放行）；硬过滤在后端按 JWT 宠物状态权威执行。
abstract class FeedRepository {
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20});
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
    );
    return FeedPage.fromJson(resp.data!);
  }
}

final Provider<FeedRepository> feedRepositoryProvider =
    Provider<FeedRepository>((ref) => DioFeedRepository(ref.read(dioProvider)));
