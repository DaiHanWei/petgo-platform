import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// story 3（AC-F2）：CommentResponse.moderationStatus 解析入 domain；TAKEN_DOWN 渲染「仅你可见」灰标签，
/// VISIBLE/UNDER_REVIEW 无标签（D-CM2）。读路径已保证仅作者本人收到非 VISIBLE 行。
class _CommentsRepo implements DetailRepository {
  _CommentsRepo(this.items);
  final List<Comment> items;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: items, nextCursor: null, hasMore: false);

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<Comment> postComment(int postId, String body) => throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body) => throw UnimplementedError();
  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

Comment _c(int id, String status) => Comment(
      id: id,
      authorId: 1,
      authorDeleted: false,
      body: 'body$id',
      createdAt: DateTime.parse('2026-06-05T00:00:00Z'),
      authorNickname: 'me',
      replyCount: 0,
      replies: const [],
      moderationStatus: status,
    );

Future<void> _pumpSection(WidgetTester tester, List<Comment> items) async {
  final container = ProviderContainer(
    overrides: [detailRepositoryProvider.overrideWithValue(_CommentsRepo(items))],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: Scaffold(
        body: SingleChildScrollView(
          child: CommentSection(postId: 5, currentUserId: 1, isContentAuthor: false),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  test('Comment.fromJson 解析 moderationStatus（缺省 VISIBLE）', () {
    final visible = Comment.fromJson({
      'id': 1,
      'authorId': 2,
      'body': 'x',
      'createdAt': '2026-06-05T00:00:00Z',
      'moderationStatus': 'TAKEN_DOWN',
    });
    expect(visible.moderationStatus, 'TAKEN_DOWN');
    expect(visible.isTakenDownForAuthor, isTrue);

    final legacy = Comment.fromJson({
      'id': 2,
      'authorId': 2,
      'body': 'x',
      'createdAt': '2026-06-05T00:00:00Z',
    });
    expect(legacy.moderationStatus, 'VISIBLE'); // 向后兼容缺省
    expect(legacy.isTakenDownForAuthor, isFalse);
  });

  testWidgets('TAKEN_DOWN 评论渲染「仅你可见」灰标签', (tester) async {
    await _pumpSection(tester, [_c(10, 'TAKEN_DOWN')]);
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.byKey(const ValueKey('commentTakenDown_10')), findsOneWidget);
    expect(find.text(l10n.commentTakenDownSelfOnly), findsOneWidget);
  });

  testWidgets('VISIBLE / UNDER_REVIEW 评论无标签（D-CM2 挂起期无「审核中」标签）', (tester) async {
    await _pumpSection(tester, [_c(11, 'VISIBLE'), _c(12, 'UNDER_REVIEW')]);
    expect(find.byKey(const ValueKey('commentTakenDown_11')), findsNothing);
    expect(find.byKey(const ValueKey('commentTakenDown_12')), findsNothing);
  });
}
