import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/follow_list_item.dart';

/// 关注 / 粉丝列表数据层（V1.3.4 follow · Story 2.2）。
///
/// ⚠️ 与 `FollowRepository`（关注 / 取关）**分开**：后者已被多处测试 fake `implements`，
/// 往它上面加方法会让每个 fake 都得补实现；两者也是读写两件事。
abstract class FollowListRepository {
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor});
}

class DioFollowListRepository implements FollowListRepository {
  DioFollowListRepository(this.dio);

  final Dio dio;

  @override
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor}) async {
    final path = tab == FollowListTab.following
        ? ApiPaths.userFollowing(userToken)
        : ApiPaths.userFollowers(userToken);
    final resp = await dio.get<Map<String, dynamic>>(path,
        queryParameters: {'cursor': ?cursor});
    return FollowListPageData.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<FollowListRepository> followListRepositoryProvider =
    Provider<FollowListRepository>((ref) => DioFollowListRepository(ref.read(dioProvider)));
