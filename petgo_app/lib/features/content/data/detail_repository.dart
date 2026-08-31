import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/comment.dart';
import '../../profile/domain/card_link.dart';
import '../domain/content_detail.dart';
import 'anon_feed_session.dart';

/// 内容详情 + 评论只读数据层（Story 3.3）。
///
/// 多态映射：404 → [ContentLoadErrorKind.gone]；403 → forbidden；其余 → network。
abstract class DetailRepository {
  Future<ContentDetail> getDetail(int id);

  Future<CommentPage> getComments(int postId, {String? cursor});

  Future<CommentPage> getReplies(int parentId, {String? cursor});

  /// 发表一级评论（Story 3.5，≤200 字服务端权威）。
  Future<Comment> postComment(int postId, String body);

  /// 回复（二级，归并到一级；Story 3.5）。
  Future<Comment> postReply(int parentId, String body);

  /// 删除评论（Story 3.5，作者本人 / 内容主；后端权威）。
  Future<void> deleteComment(int commentId);

  /// 删除内容（Story 3.6，仅作者；软删 + 级联清；后端权威）。
  Future<void> deleteContent(int postId);

  /// 举报内容（Story 3.7，单选类型 wire；需登录；无自动下架）。
  Future<void> submitReport(int postId, String reasonType);

  /// 取该条内容的**对外分享链接**（V1.1.6 Story 9.3）。
  ///
  /// 返回完整 URL。后端只回不可枚举 token，域名由客户端按 H5 子域拼
  /// （与名片 / 里程碑同约定，见 `postShareUrl`）。
  /// 幂等：同一条内容重复分享复用同一 token。
  Future<String> getShareUrl(int postId);
}

class DioDetailRepository implements DetailRepository {
  DioDetailRepository(this.dio);

  final Dio dio;

  @override
  Future<ContentDetail> getDetail(int id) async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(
        ApiPaths.contentPostDetail(id),
        // 2026-08-31：详情打开计入浏览统计，游客靠这个头去重「浏览人数」。
        // 🛡 与首页取数同一约定：只挂在这一个请求上，不做成全局请求头。
        options: Options(headers: {'X-Anon-Session': AnonFeedSession.id}),
      );
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

  @override
  Future<Comment> postComment(int postId, String body) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.contentPostComments(postId),
      data: {'body': body},
    );
    return Comment.fromJson(resp.data!);
  }

  @override
  Future<Comment> postReply(int parentId, String body) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.commentReplies(parentId),
      data: {'body': body},
    );
    return Comment.fromJson(resp.data!);
  }

  @override
  Future<void> deleteComment(int commentId) async {
    await dio.delete<void>('${ApiPaths.base}/comments/$commentId');
  }

  @override
  Future<void> deleteContent(int postId) async {
    await dio.delete<void>(ApiPaths.contentPostDetail(postId));
  }

  @override
  Future<void> submitReport(int postId, String reasonType) async {
    await dio.post<void>(
      '${ApiPaths.contentPostDetail(postId)}/reports',
      data: {'reasonType': reasonType},
    );
  }

  @override
  Future<String> getShareUrl(int postId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.contentPostShareLink(postId));
    return postShareUrl(resp.data!['shareToken'] as String);
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
