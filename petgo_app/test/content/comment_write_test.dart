import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_composer.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

Comment _c(int id, int authorId) => Comment(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'U$authorId',
      body: 'body $id',
      createdAt: DateTime.utc(2026, 6, 2),
      replyCount: 0,
      replies: const [],
    );

class _RecordingRepo implements DetailRepository {
  _RecordingRepo({this.comments = const [], this.failPost = false});
  final List<Comment> comments;

  /// 注入发送失败（AC3：网络/服务器/422）。
  bool failPost;
  int postCommentCalls = 0;
  int postReplyCalls = 0;
  int deleteCalls = 0;

  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: comments, nextCursor: null, hasMore: false);

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  Future<Comment> postComment(int postId, String body) async {
    postCommentCalls++;
    if (failPost) throw Exception('boom');
    return _c(999, 1);
  }

  @override
  Future<Comment> postReply(int parentId, String body) async {
    postReplyCalls++;
    if (failPost) throw Exception('boom');
    return _c(999, 1);
  }

  @override
  Future<void> deleteComment(int commentId) async {
    deleteCalls++;
  }

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
  testWidgets('AC1: 登录用户发表一级评论 → 调 postComment', (tester) async {
    final repo = _RecordingRepo();
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user(1));

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: CommentComposer(postId: 5)),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hello pets');
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();
    expect(repo.postCommentCalls, 1);
  });

  testWidgets('AC3: 发送失败 → 提示重试 + 保留输入（可直接重试）', (tester) async {
    final repo = _RecordingRepo(failPost: true);
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user(1));

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: CommentComposer(postId: 5)),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'keep me');
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.commentSendFailed), findsOneWidget); // 失败提示
    // 输入保留，可直接重试。
    final field = tester.widget<TextField>(find.byKey(const ValueKey('detailCommentInput')));
    expect(field.controller!.text, 'keep me');

    // 网络恢复后直接重试 → 成功，输入清空。
    repo.failPost = false;
    await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
    await tester.pumpAndSettle();
    final field2 = tester.widget<TextField>(find.byKey(const ValueKey('detailCommentInput')));
    expect(field2.controller!.text, isEmpty);
    expect(repo.postCommentCalls, 2); // 首次失败 + 重试成功
    await tester.pump(const Duration(seconds: 3)); // 走完失败 toast 定时器
  });

  testWidgets('AC2: 游客点评论框 → FR-0C 强登录弹窗', (tester) async {
    final repo = _RecordingRepo();
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose); // 游客

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: CommentComposer(postId: 5)),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('detailCommentBox')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC2: 删除入口仅对评论作者/内容主可见', (tester) async {
    // 当前用户 id=1；评论 10 由 1 发（可删），评论 11 由 2 发（不可删，且非内容主）。
    final repo = _RecordingRepo(comments: [_c(10, 1), _c(11, 2)]);
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: CommentSection(postId: 5, currentUserId: 1, isContentAuthor: false),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('deleteComment_10')), findsOneWidget); // 本人评论可删
    expect(find.byKey(const ValueKey('deleteComment_11')), findsNothing); // 他人评论不可删
  });

  testWidgets('AC2: 内容主可删其内容下任意评论', (tester) async {
    final repo = _RecordingRepo(comments: [_c(11, 2)]);
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: CommentSection(postId: 5, currentUserId: 1, isContentAuthor: true),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('deleteComment_11')), findsOneWidget);
  });
}
