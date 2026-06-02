import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/content/data/detail_repository.dart';
import 'package:petgo/features/content/domain/comment.dart';
import 'package:petgo/features/content/domain/content_detail.dart';
import 'package:petgo/features/content/presentation/content_detail_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/empty_state.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';

ContentDetail _detail({int images = 2, bool isAuthor = false}) => ContentDetail(
      id: 5,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'A lovely pet day',
      imageUrls: List.generate(images, (i) => 'https://cdn/$i.jpg'),
      likeCount: 3,
      commentCount: 2,
      liked: false,
      isAuthor: isAuthor,
      createdAt: DateTime.utc(2026, 6, 2),
    );

Comment _top(int id, {int replyCount = 0, List<Comment>? replies}) => Comment(
      id: id,
      authorId: 10,
      authorDeleted: false,
      authorNickname: 'Bob',
      body: 'comment $id',
      createdAt: DateTime.utc(2026, 6, 2),
      replyCount: replyCount,
      replies: replies ?? const [],
    );

Comment _reply(int id) => Comment(
      id: id,
      authorId: 11,
      authorDeleted: false,
      authorNickname: 'Cara',
      body: 'reply $id',
      createdAt: DateTime.utc(2026, 6, 2),
    );

class _FakeDetailRepo implements DetailRepository {
  _FakeDetailRepo({
    this.detail,
    this.error,
    this.comments = const [],
  });

  final ContentDetail? detail;
  final ContentLoadError? error;
  final List<Comment> comments;

  @override
  Future<ContentDetail> getDetail(int id) async {
    if (error != null) throw error!;
    return detail!;
  }

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: comments, nextCursor: null, hasMore: false);

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  Future<Comment> postComment(int postId, String body) async => _top(999);

  @override
  Future<Comment> postReply(int parentId, String body) async => _reply(999);

  @override
  Future<void> deleteComment(int commentId) async {}
}

Future<void> _pump(WidgetTester tester, _FakeDetailRepo repo) async {
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(repo),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: ContentDetailPage(postId: 5),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 详情渲染正文 + 多图角标 x/y + 互动计数', (tester) async {
    await _pump(tester, _FakeDetailRepo(detail: _detail(images: 2), comments: [_top(1)]));
    expect(find.text('A lovely pet day'), findsOneWidget);
    expect(find.text('1/2'), findsOneWidget); // 多图角标
    expect(find.text('Alice'), findsOneWidget);
    expect(find.byKey(const ValueKey('detailCommentBox')), findsOneWidget);
  });

  testWidgets('AC4: 404 → 「内容已不存在」失效页 + 返回', (tester) async {
    await _pump(tester, _FakeDetailRepo(error: const ContentLoadError(ContentLoadErrorKind.gone)));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.text(l10n.detailBackToFeed), findsOneWidget);
  });

  testWidgets('AC4: 403 → 无权限友好页', (tester) async {
    await _pump(tester, _FakeDetailRepo(error: const ContentLoadError(ContentLoadErrorKind.forbidden)));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.detailForbiddenTitle), findsOneWidget);
  });

  testWidgets('AC3: 评论区渲染 + 「查看全部 X 条回复」', (tester) async {
    await _pump(
      tester,
      _FakeDetailRepo(
        detail: _detail(),
        comments: [_top(1, replyCount: 5, replies: [_reply(100), _reply(101), _reply(102)])],
      ),
    );
    expect(find.text('comment 1'), findsOneWidget);
    expect(find.byKey(const ValueKey('viewReplies_1')), findsOneWidget); // 5 > 3 内嵌
  });

  testWidgets('AC3: 游客点评论框 → FR-0C 强登录弹窗', (tester) async {
    await _pump(tester, _FakeDetailRepo(detail: _detail(), comments: const []));
    await tester.tap(find.byKey(const ValueKey('detailCommentBox')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC1: 作者本人「···」菜单含删除入口位', (tester) async {
    await _pump(tester, _FakeDetailRepo(detail: _detail(isAuthor: true), comments: const []));
    await tester.tap(find.byKey(const ValueKey('detailMenu')));
    await tester.pumpAndSettle();
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.detailMenuDelete), findsOneWidget);
    expect(find.text(l10n.detailMenuReport), findsOneWidget);
  });
}
