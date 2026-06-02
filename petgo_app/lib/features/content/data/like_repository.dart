import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 点赞开关结果（对应后端 `LikeResponse`）。
class LikeResult {
  const LikeResult({required this.liked, required this.likeCount});

  final bool liked;
  final int likeCount;

  factory LikeResult.fromJson(Map<String, dynamic> json) => LikeResult(
        liked: (json['liked'] ?? false) as bool,
        likeCount: (json['likeCount'] ?? 0) as int,
      );
}

/// 内容点赞数据层（Story 3.4）。需 JWT；未登录 → 后端 401 → 前端门控触发 FR-0C（不直接调）。
abstract class LikeRepository {
  Future<LikeResult> like(int postId);

  Future<LikeResult> unlike(int postId);
}

class DioLikeRepository implements LikeRepository {
  DioLikeRepository(this.dio);

  final Dio dio;

  @override
  Future<LikeResult> like(int postId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.contentPostLike(postId));
    return LikeResult.fromJson(resp.data!);
  }

  @override
  Future<LikeResult> unlike(int postId) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.contentPostLike(postId));
    return LikeResult.fromJson(resp.data!);
  }
}

final Provider<LikeRepository> likeRepositoryProvider =
    Provider<LikeRepository>((ref) => DioLikeRepository(ref.read(dioProvider)));
