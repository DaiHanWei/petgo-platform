import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// batch-a 复审（2026-09-18）评论区三条的回归：
/// - 删掉本会话置顶的评论后，它不得靠本地副本回到列表顶部；
/// - 只存在于置顶副本里的评论（不在服务端这一页）点赞，界面要跟着变、再点能取消；
/// - 游客点评论心形：不乐观翻转、不发请求，走登录引导（与帖子 LikeButton 同一入口）。
class _Repo implements DetailRepository {
  _Repo(this.items);

  /// 服务端「这一页」。删评论时从这里摘掉 —— 模拟服务端已无此条。
  final List<Comment> items;
  final List<int> liked = [];
  final List<int> unliked = [];

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: List.of(items), nextCursor: null, hasMore: false);
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<void> deleteComment(int commentId) async =>
      items.removeWhere((c) => c.id == commentId);
  @override
  Future<void> likeComment(int commentId) async => liked.add(commentId);
  @override
  Future<void> unlikeComment(int commentId) async => unliked.add(commentId);

  @override
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
  @override
  Future<Comment> postComment(int postId, String body) => throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body) => throw UnimplementedError();
  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

class _Auth extends AuthController {
  _Auth(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;
  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
  }
}

Comment _c(int id, {int authorId = 1, int likeCount = 0}) => Comment(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      body: 'body$id',
      createdAt: DateTime.parse('2026-09-11T00:00:00Z'),
      authorNickname: 'u$authorId',
      replyCount: 0,
      replies: const [],
      likeCount: likeCount,
    );

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Repo repo, {
  bool loggedIn = true,
  _Guide? guide,
}) async {
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(repo),
    authControllerProvider.overrideWith(() => _Auth(loggedIn
        ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
        : const AuthState.guest())),
    if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: Scaffold(
        body: SingleChildScrollView(
          child: CommentSection(postId: 5, currentUserId: 1),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets('删除本会话置顶的评论 → 不再出现在列表里（不靠本地副本补回）', (tester) async {
    // 热门评论 10 占第一页；自己刚发的 99 只在置顶集合里。
    final repo = _Repo([_c(10, authorId: 2, likeCount: 5), _c(99)]);
    final container = await _pump(tester, repo);
    container.read(sessionPinnedCommentsProvider.notifier).add(_c(99));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('commentItem_99')), findsOneWidget);

    await tester.longPress(find.byKey(const ValueKey('commentItem_99')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('commentActionDelete')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirmDeleteComment')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('commentItem_99')), findsNothing,
        reason: '服务端已删、本地副本也必须摘掉');
    expect(container.read(sessionPinnedCommentsProvider).containsKey(99), isFalse);
  });

  testWidgets('只在置顶副本里的评论：点赞界面跟着变，再点能取消', (tester) async {
    // 服务端这一页**没有** 99（热度序下 0 赞新评论不在第一页）。
    final repo = _Repo([_c(10, authorId: 2, likeCount: 5)]);
    final container = await _pump(tester, repo);
    container.read(sessionPinnedCommentsProvider.notifier).add(_c(99));
    await tester.pumpAndSettle();

    final heart = find.byKey(const ValueKey('likeComment_99'));
    await tester.tap(heart);
    await tester.pumpAndSettle();
    expect(repo.liked, [99]);
    expect(container.read(sessionPinnedCommentsProvider)[99]!.liked, isTrue,
        reason: '界面渲染的就是这份副本，不改它心形就不动');
    expect(
        find.descendant(of: heart, matching: find.byIcon(Icons.favorite_rounded)), findsOneWidget);

    await tester.tap(heart);
    await tester.pumpAndSettle();
    expect(repo.unliked, [99], reason: '第二下必须是取消，而不是再发一次点赞');
    expect(container.read(sessionPinnedCommentsProvider)[99]!.liked, isFalse);
  });

  testWidgets('游客点评论心形 → 登录引导，不翻转、不发请求', (tester) async {
    final repo = _Repo([_c(10, authorId: 2, likeCount: 5)]);
    final guide = _Guide();
    await _pump(tester, repo, loggedIn: false, guide: guide);

    final heart = find.byKey(const ValueKey('likeComment_10'));
    await tester.tap(heart);
    await tester.pumpAndSettle();

    expect(guide.hardDialogs, 1);
    expect(repo.liked, isEmpty);
    expect(find.descendant(of: heart, matching: find.byIcon(Icons.favorite_border_rounded)),
        findsOneWidget);
  });
}
