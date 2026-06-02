import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/comment.dart';
import '../domain/content_detail.dart';

/// 内容详情 + 评论只读数据层（Story 3.3）。
///
/// 多态映射：404 → [ContentLoadErrorKind.gone]；403 → forbidden；其余 → network。
abstract class DetailRepository {
  Future<ContentDetail> getDetail(int id);

  Future<CommentPage> getComments(int postId, {String? cursor});

  Future<CommentPage> getReplies(int parentId, {String? cursor});
}

class DioDetailRepository implements DetailRepository {
  DioDetailRepository(this.dio);

  final Dio dio;

  @override
  Future<ContentDetail> getDetail(int id) async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.contentPostDetail(id));
      return ContentDetail.fromJson(resp.data!);
    } on DioException catch (e) {
      throw ContentLoadError(_classify(e));
    }
  }

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.contentPostComments(postId),
      queryParameters: cursor == null ? null : {'cursor': cursor},
    );
    return CommentPage.fromJson(resp.data!);
  }

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.commentReplies(parentId),
      queryParameters: cursor == null ? null : {'cursor': cursor},
    );
    return CommentPage.fromJson(resp.data!);
  }

  ContentLoadErrorKind _classify(DioException e) {
    switch (e.response?.statusCode) {
      case 404:
        return ContentLoadErrorKind.gone;
      case 403:
        return ContentLoadErrorKind.forbidden;
      default:
        return ContentLoadErrorKind.network;
    }
  }
}

final Provider<DetailRepository> detailRepositoryProvider =
    Provider<DetailRepository>((ref) => DioDetailRepository(ref.read(dioProvider)));
