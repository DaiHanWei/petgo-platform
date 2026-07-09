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

/// story 3（AC-F1）：发一级评论遇审核拦截（422 COMMENT_BLOCKED）→ 保留输入 + 专属 toast
/// `commentModerationBlocked`（区别网络失败 `commentSendFailed`）；网络失败仍走 `commentSendFailed`。
class _BlockingRepo implements DetailRepository {
  _BlockingRepo({required this.commentBlocked});

  /// true = 422 COMMENT_BLOCKED；false = 网络/500 失败。
  final bool commentBlocked;
  int postCommentCalls = 0;

  @override
  Future<Comment> postComment(int postId, String body) async {
    postCommentCalls++;
    if (commentBlocked) {
      throw DioException(
        requestOptions: RequestOptions(path: '/content-posts/$postId/comments'),
        response: Response(
          requestOptions: RequestOptions(path: '/content-posts/$postId/comments'),
          statusCode: 422,
          data: const {
            'status': 422,
            'type': 'https://petgo/errors/comment-blocked',
            'title': 'Unprocessable Entity',
            'detail': '内容包含不当词汇，请修改后重试',
          },
        ),
      );
    }
    throw DioException(
      requestOptions: RequestOptions(path: '/content-posts/$postId/comments'),
      type: DioExceptionType.connectionError,
      error: 'network down',
    );
  }

  @override
  Future<Comment> postReply(int parentId, String body) => throw UnimplementedError();
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

Future<void> _pumpComposer(WidgetTester tester, ProviderContainer container) async {
  container.read(authControllerProvider.notifier).applyLogin(_user(1));
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
}

void main() {
  testWidgets('审核拦截(422 COMMENT_BLOCKED) → 保留输入 + commentModerationBlocked toast', (tester) async {
    final repo = _BlockingRepo(commentBlocked: true);
    final container = ProviderContainer(
      overrides: [detailRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
    await _pumpComposer(tester, container);

    await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'judi online');
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(repo.postCommentCalls, 1);
    expect(find.text(l10n.commentModerationBlocked), findsOneWidget);
    expect(find.text(l10n.commentSendFailed), findsNothing); // 区别于网络失败
    // F13：输入保留（未清空）。
    expect(find.text('judi online'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('网络失败 → 仍走 commentSendFailed（非审核拦截）', (tester) async {
    final repo = _BlockingRepo(commentBlocked: false);
    final container = ProviderContainer(
      overrides: [detailRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
    await _pumpComposer(tester, container);

    await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hello');
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.commentSendFailed), findsOneWidget);
    expect(find.text(l10n.commentModerationBlocked), findsNothing);
    expect(find.text('hello'), findsOneWidget); // 输入保留
    await tester.pump(const Duration(seconds: 3));
  });
}
