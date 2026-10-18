import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 收藏 / 取消收藏结果：`{collected}`。不带计数（场所收藏数不展示；帖子收藏数只在作者看详情时下发）。
class CollectResult {
  const CollectResult({required this.collected});

  final bool collected;

  factory CollectResult.fromJson(Map<String, dynamic>? json) => CollectResult(collected: json?['collected'] == true);
}

/// 收藏数据层（V1.4.0 ④ Story 2.3 建，Story 2.4 在同一处追加列表方法）。需 JWT；游客由 `requireLogin` 先拦。
abstract class CollectRepository {
  Future<CollectResult> collectPost(int postId);

  Future<CollectResult> uncollectPost(int postId);

  Future<CollectResult> collectPlace(String placeToken);

  Future<CollectResult> uncollectPlace(String placeToken);
}

class DioCollectRepository implements CollectRepository {
  DioCollectRepository(this.dio);

  final Dio dio;

  @override
  Future<CollectResult> collectPost(int postId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.contentPostCollect(postId));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> uncollectPost(int postId) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.contentPostCollect(postId));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> collectPlace(String placeToken) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.placeCollect(placeToken));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> uncollectPlace(String placeToken) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.placeCollect(placeToken));
    return CollectResult.fromJson(resp.data);
  }
}

final Provider<CollectRepository> collectRepositoryProvider =
    Provider<CollectRepository>((ref) => DioCollectRepository(ref.read(dioProvider)));
