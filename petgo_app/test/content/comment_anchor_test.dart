import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 评论点赞通知 → 详情页评论锚点：
/// - 锚点楼置顶（不在第一页也能看到）；在第一页里的同 id 只出现一次、在顶部；
/// - 目标行短暂高亮后消失；
/// - 锚点接口失败（404 / 断网）静默降级为普通评论区；
/// - 无 anchorCommentId 时根本不调锚点接口；
/// - 刷新时重拉锚点：锚点已删（404）→ 置顶摘掉。
class _Repo implements DetailRepository {
  _Repo(this.items, {this.anchor});

  final List<Comment> items;
  CommentAnchor? anchor;
  final List<int> anchorCalls = [];
  final List<int> repliesCalls = [];

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: List.of(items), nextCursor: null, hasMore: false);

  @override
  Future<CommentAnchor> getCommentAnchor(int commentId) async {
    anchorCalls.add(commentId);
    final a = anchor;
    if (a == null) throw Exception('404');
    return a;
  }

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async {
    repliesCalls.add(parentId);
    return CommentPage(
      items: [_c(201, replyCount: null), _c(202, replyCount: null), _c(203, replyCount: null)],
      hasMore: false,
    );
  }

  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> likeComment(int commentId) async {}
  @override
  Future<void> unlikeComment(int commentId) async {}
  @override
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
  @override
  Future<Comment> postComment(int postId, String body, {List<int> mentionedUserIds = const []}) =>
      throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body, {List<int> mentionedUserIds = const []}) =>
      throw UnimplementedError();
  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

Comment _c(int id, {int? replyCount = 0, List<Comment>? replies}) => Comment(
      id: id,
      authorId: 1,
      authorDeleted: false,
      body: 'body$id',
      createdAt: DateTime.parse('2026-09-11T00:00:00Z'),
      authorNickname: 'u1',
      replyCount: replyCount,
      replies: replyCount == null ? null : (replies ?? const []),
    );

Future<ProviderContainer> _pump(WidgetTester tester, _Repo repo, {int? anchorCommentId}) async {
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(repo),
    authControllerProvider.overrideWith(_Auth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: Scaffold(
        body: SingleChildScrollView(
          child: CommentSection(postId: 5, currentUserId: 1, anchorCommentId: anchorCommentId),
        ),
      ),
    ),
  ));
  await tester.pump();
  await tester.pump();
  return container;
}

Finder _item(int id) => find.byKey(ValueKey('commentItem_$id'));

/// 一级评论在屏幕上的纵向顺序（只看给定 id）。
List<int> _order(WidgetTester tester, List<int> ids) {
  final present = [for (final id in ids) if (_item(id).evaluate().isNotEmpty) id];
  present.sort((a, b) => tester.getTopLeft(_item(a)).dy.compareTo(tester.getTopLeft(_item(b)).dy));
  return present;
}

/// 放完高亮（hold 1.2s + fade 0.8s），避免计时器悬挂。
Future<void> _finishHighlight(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 3));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('一级锚点不在第一页 → 置顶到最前并高亮；高亮约 2 秒后消失', (tester) async {
    final repo = _Repo([_c(1), _c(2)], anchor: CommentAnchor(postId: 5, targetId: 9, thread: _c(9)));
    await _pump(tester, repo, anchorCommentId: 9);
    expect(repo.anchorCalls, [9]);
    expect(_order(tester, [1, 2, 9]), [9, 1, 2]);
    expect(find.byKey(const ValueKey('commentAnchorHighlight_9')), findsOneWidget);
    await _finishHighlight(tester);
    expect(find.byKey(const ValueKey('commentAnchorHighlight_9')), findsNothing);
    // 置顶本身保留（只是高亮放完）。
    expect(_order(tester, [1, 2, 9]), [9, 1, 2]);
  });

  testWidgets('锚点楼也在第一页里 → 只出现一次，且在顶部（去重）', (tester) async {
    final repo = _Repo([_c(1), _c(2), _c(3)], anchor: CommentAnchor(postId: 5, targetId: 3, thread: _c(3)));
    await _pump(tester, repo, anchorCommentId: 3);
    expect(_item(3), findsOneWidget);
    expect(_order(tester, [1, 2, 3]), [3, 1, 2]);
    await _finishHighlight(tester);
  });

  testWidgets('目标是回复 → 父楼置顶、只内嵌目标那条并高亮它；「查看全部」照常展开', (tester) async {
    final thread = _c(1, replyCount: 5, replies: [_c(202, replyCount: null)]);
    final repo = _Repo(
      [_c(1, replyCount: 5, replies: [_c(201, replyCount: null)]), _c(2)],
      anchor: CommentAnchor(postId: 5, targetId: 202, thread: thread),
    );
    await _pump(tester, repo, anchorCommentId: 202);
    expect(_item(1), findsOneWidget);
    expect(_order(tester, [1, 2]), [1, 2]);
    expect(_item(202), findsOneWidget);
    expect(_item(201), findsNothing); // 锚点版本只带目标那条回复
    expect(find.byKey(const ValueKey('commentAnchorHighlight_202')), findsOneWidget);
    expect(find.byKey(const ValueKey('commentAnchorHighlight_1')), findsNothing);
    await _finishHighlight(tester);

    await tester.tap(find.byKey(const ValueKey('viewReplies_1')));
    await tester.pumpAndSettle();
    expect(repo.repliesCalls, [1]);
    expect(_item(201), findsOneWidget);
    expect(_item(203), findsOneWidget);
    // 高亮已放完 → 展开后不重放。
    expect(find.byKey(const ValueKey('commentAnchorHighlight_202')), findsNothing);
  });

  testWidgets('锚点接口失败（404 / 断网）→ 静默降级：服务端原序、无高亮、无提示', (tester) async {
    final repo = _Repo([_c(1), _c(2)]); // anchor == null → 抛
    await _pump(tester, repo, anchorCommentId: 9);
    await tester.pumpAndSettle();
    expect(repo.anchorCalls, [9]);
    expect(_order(tester, [1, 2]), [1, 2]);
    expect(find.byKey(const ValueKey('commentAnchorHighlight_9')), findsNothing);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('帖子 id 对不上的锚点 → 当没有锚点', (tester) async {
    final repo = _Repo([_c(1)], anchor: CommentAnchor(postId: 999, targetId: 9, thread: _c(9)));
    await _pump(tester, repo, anchorCommentId: 9);
    await tester.pumpAndSettle();
    expect(_item(9), findsNothing);
  });

  testWidgets('无 anchorCommentId → 不调锚点接口', (tester) async {
    final repo = _Repo([_c(1)], anchor: CommentAnchor(postId: 5, targetId: 9, thread: _c(9)));
    await _pump(tester, repo);
    await tester.pumpAndSettle();
    expect(repo.anchorCalls, isEmpty);
    expect(_item(9), findsNothing);
  });

  testWidgets('刷新：锚点还在 → 置顶保留；锚点已删（404）→ 置顶摘掉', (tester) async {
    final repo = _Repo([_c(1), _c(2)], anchor: CommentAnchor(postId: 5, targetId: 9, thread: _c(9)));
    final container = await _pump(tester, repo, anchorCommentId: 9);
    await _finishHighlight(tester);

    container.read(commentsRefreshProvider.notifier).bump();
    await tester.pumpAndSettle();
    expect(repo.anchorCalls, [9, 9]);
    expect(_order(tester, [1, 2, 9]), [9, 1, 2]);
    expect(find.byKey(const ValueKey('commentAnchorHighlight_9')), findsNothing); // 不重放

    repo.anchor = null; // 锚点评论被删
    container.read(commentsRefreshProvider.notifier).bump();
    await tester.pumpAndSettle();
    expect(_item(9), findsNothing);
    expect(_order(tester, [1, 2]), [1, 2]);
  });

  test('CommentAnchor.fromJson：thread 复用 Comment 解析', () {
    final a = CommentAnchor.fromJson({
      'postId': 55,
      'targetId': 105,
      'thread': {
        'id': 100,
        'authorId': 7,
        'body': 'top',
        'createdAt': '2026-11-01T00:00:00Z',
        'replyCount': 4,
        'replies': [
          {'id': 105, 'authorId': 8, 'body': 'reply', 'createdAt': '2026-11-01T01:00:00Z'},
        ],
      },
    });
    expect(a.postId, 55);
    expect(a.targetId, 105);
    expect(a.thread.id, 100);
    expect(a.thread.replyCount, 4);
    expect(a.thread.replies!.single.id, 105);
  });
}
