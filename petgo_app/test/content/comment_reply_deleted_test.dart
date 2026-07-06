import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_composer.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 回复一条已被删除的评论 → 后端返回 404（生产实测 type=not-found / detail=评论不存在）。
/// 期望：给专属提示「你回复的评论已删除」（非中文），退出回复态，而非通用「发送失败/重试」。
class _ReplyDeletedRepo implements DetailRepository {
  int postReplyCalls = 0;

  @override
  Future<Comment> postReply(int parentId, String body) async {
    postReplyCalls++;
    throw DioException(
      requestOptions: RequestOptions(path: '/comments/$parentId/replies'),
      response: Response(
        requestOptions: RequestOptions(path: '/comments/$parentId/replies'),
        statusCode: 404,
        data: const {
          'status': 404,
          'type': 'https://petgo/errors/not-found',
          'title': 'Not Found',
          'detail': '评论不存在',
        },
      ),
    );
  }

  @override
  Future<Comment> postComment(int postId, String body) => throw UnimplementedError();
  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

LoginResponse _user(int id) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(id: id, onboardingCompleted: true),
    );

void main() {
  testWidgets('回复已删除评论(404) → 专属提示且退出回复态', (tester) async {
    final repo = _ReplyDeletedRepo();
    final container = ProviderContainer(
      overrides: [detailRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user(1));
    // 进入回复态（回复评论 86）。
    container.read(replyTargetProvider.notifier).set(const ReplyTarget(parentId: 86, toName: 'U2'));

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: Scaffold(body: CommentComposer(postId: 5)),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'reply to ghost');
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(repo.postReplyCalls, 1);
    expect(find.text(l10n.commentReplyTargetDeleted), findsOneWidget);
    expect(find.text(l10n.commentSendFailed), findsNothing); // 不再是通用失败提示
    expect(container.read(replyTargetProvider), isNull); // 已退出回复态
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });
}
