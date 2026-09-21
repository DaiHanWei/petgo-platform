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
  ///
  /// [mentionedUserIds]：这条评论 @ 到的人（V1.3.0 batch-b1 Story 3.2 · AC4）。
  /// 🔴 **只发 userId，不发昵称** —— body 里那串「@昵称」只是给人读的文本，
  /// 对方改名后它就对不上人了，可点的身份靠这份 id（AD-10 Rule 4）。
  /// 上限 5 人由服务端权威把关（AC5）。
  Future<Comment> postComment(int postId, String body, {List<int> mentionedUserIds});

  /// 回复（二级，归并到一级；Story 3.5）。[mentionedUserIds] 口径同 [postComment]。
  Future<Comment> postReply(int parentId, String body, {List<int> mentionedUserIds});

  /// 删除评论（Story 3.5，作者本人 / 内容主；后端权威）。
  Future<void> deleteComment(int commentId);

  /// 给评论点赞 / 取消（V1.3.0 Story 2.4）。一级、二级共用同一端点。
  ///
  /// **服务端幂等**：重复点赞不产生第二行，没赞过取消也成功 —— 客户端因此不必先查状态，
  /// 直接按本地态翻转即可。端点**不返回赞数**（那是实时聚合值，回来时可能已经变了），
  /// 所以按钮的即时反馈靠本地 ±1，下次拉列表以服务端为准。
  Future<void> likeComment(int commentId);

  Future<void> unlikeComment(int commentId);

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
  Future<Comment> postComment(int postId, String body,
      {List<int> mentionedUserIds = const []}) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.contentPostComments(postId),
      data: _commentBody(body, mentionedUserIds),
    );
    return Comment.fromJson(resp.data!);
  }

  @override
  Future<Comment> postReply(int parentId, String body,
      {List<int> mentionedUserIds = const []}) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.commentReplies(parentId),
      data: _commentBody(body, mentionedUserIds),
    );
    return Comment.fromJson(resp.data!);
  }

  /// 没 @ 人时**整个字段不出现**（Story 3.2）：与老客户端的请求体逐字节一致，
  /// 服务端把省略与空表当同一件事。
  static Map<String, dynamic> _commentBody(String body, List<int> mentionedUserIds) {
    final data = <String, dynamic>{'body': body};
    if (mentionedUserIds.isNotEmpty) data['mentionedUserIds'] = mentionedUserIds;
    return data;
  }

  @override
  Future<void> deleteComment(int commentId) async {
    await dio.delete<void>('${ApiPaths.base}/comments/$commentId');
  }

  @override
  Future<void> likeComment(int commentId) async {
    await dio.post<void>('${ApiPaths.base}/comments/$commentId/likes');
  }

  @override
  Future<void> unlikeComment(int commentId) async {
    await dio.delete<void>('${ApiPaths.base}/comments/$commentId/likes');
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
