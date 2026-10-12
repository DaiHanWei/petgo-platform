import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/follow_state.dart';

/// 关注数据层（V1.3.4 follow · Story 1.3 · AD-4）。
///
/// 路径用**不可枚举用户 token**（D-2），不用数字 id。两个动作服务端都幂等。
/// ⚠️ 错误**不在这里吞**：`DioException` 原样抛给 `FollowButton`，由它按 429 / 其他分流 toast 并回滚。
abstract class FollowRepository {
  Future<FollowResult> follow(String userToken);

  Future<FollowResult> unfollow(String userToken);
}

class DioFollowRepository implements FollowRepository {
  DioFollowRepository(this.dio);

  final Dio dio;

  @override
  Future<FollowResult> follow(String userToken) async {
    final resp = await dio.put<Map<String, dynamic>>(ApiPaths.userFollow(userToken));
    return FollowResult.fromJson(resp.data ?? const <String, dynamic>{});
  }

  @override
  Future<FollowResult> unfollow(String userToken) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.userFollow(userToken));
    return FollowResult.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<FollowRepository> followRepositoryProvider =
    Provider<FollowRepository>((ref) => DioFollowRepository(ref.read(dioProvider)));
