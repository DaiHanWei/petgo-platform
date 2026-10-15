import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../social/domain/follow_list_item.dart';

/// 帖子点赞人列表数据层（V1.3.4）。行结构与分页信封复用关注列表的
/// [FollowListPageData]。帖子对我不可见 → 后端 404（抛 [DioException]，由 controller 映射为 gone）。
abstract class PostLikersRepository {
  Future<FollowListPageData> getLikers(int postId, {String? cursor});
}

class DioPostLikersRepository implements PostLikersRepository {
  DioPostLikersRepository(this.dio);

  final Dio dio;

  @override
  Future<FollowListPageData> getLikers(int postId, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.postLikers(postId),
        queryParameters: {'cursor': ?cursor});
    return FollowListPageData.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<PostLikersRepository> postLikersRepositoryProvider =
    Provider<PostLikersRepository>((ref) => DioPostLikersRepository(ref.read(dioProvider)));
