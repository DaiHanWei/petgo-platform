import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260923-545：长按评论菜单的「举报」与「删除」不再互斥，举报对象是**评论作者**（账号举报）；
/// bug 20260923-544：评论点赞上报专用事件 `comment_like_tapped`（liked + comment_level）。
class _Repo implements DetailRepository {
  _Repo(this.items);

  final List<Comment> items;
  final List<int> postReports = [];

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: List.of(items), nextCursor: null, hasMore: false);
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
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
  Future<void> submitReport(int postId, String reasonType) async => postReports.add(postId);
}

class _AccountReports implements AccountReportRepository {
  final List<(int, AccountReportReason)> calls = [];
  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async =>
      calls.add((targetUserId, reason));
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

Comment _c(int id, {int authorId = 2, List<Comment>? replies}) => Comment(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      body: 'body$id',
      createdAt: DateTime.parse('2026-09-11T00:00:00Z'),
      authorNickname: 'u$authorId',
      replyCount: replies?.length ?? 0,
      replies: replies ?? const [],
    );

Comment _reply(int id, {int authorId = 3}) => Comment(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      body: 'reply$id',
      createdAt: DateTime.parse('2026-09-11T00:01:00Z'),
      authorNickname: 'u$authorId',
    );

Future<void> _pump(
  WidgetTester tester,
  _Repo repo, {
  _AccountReports? accountReports,
  int currentUserId = 1,
  bool isContentAuthor = false,
}) async {
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(repo),
    accountReportRepositoryProvider.overrideWithValue(accountReports ?? _AccountReports()),
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
          child: CommentSection(
            postId: 5,
            currentUserId: currentUserId,
            isContentAuthor: isContentAuthor,
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> _longPress(WidgetTester tester, int id) async {
  await tester.longPress(find.byKey(ValueKey('commentItem_$id')));
  await tester.pumpAndSettle();
}

const _report = ValueKey('commentActionReport');
const _delete = ValueKey('commentActionDelete');

void main() {
  group('bug 545 长按菜单：举报 / 删除可并存', () {
    testWidgets('帖主长按他人评论 → 举报与删除两项都有', (tester) async {
      await _pump(tester, _Repo([_c(10, authorId: 2)]), isContentAuthor: true);
      await _longPress(tester, 10);
      expect(find.byKey(_report), findsOneWidget);
      expect(find.byKey(_delete), findsOneWidget);
    });

    testWidgets('长按自己的评论 → 只有删除，没有举报（帖主身份也一样）', (tester) async {
      await _pump(tester, _Repo([_c(10, authorId: 1)]), isContentAuthor: true);
      await _longPress(tester, 10);
      expect(find.byKey(_delete), findsOneWidget);
      expect(find.byKey(_report), findsNothing);
    });

    testWidgets('非帖主长按他人评论 → 只有举报', (tester) async {
      await _pump(tester, _Repo([_c(10, authorId: 2)]));
      await _longPress(tester, 10);
      expect(find.byKey(_report), findsOneWidget);
      expect(find.byKey(_delete), findsNothing);
    });

    testWidgets('举报 → 走账号举报（对象 = 评论作者），不再举报帖子', (tester) async {
      final repo = _Repo([_c(10, authorId: 2)]);
      final accountReports = _AccountReports();
      await _pump(tester, repo, accountReports: accountReports, isContentAuthor: true);
      await _longPress(tester, 10);
      await tester.tap(find.byKey(_report));
      await tester.pumpAndSettle();

      // 弹出的是账号举报抽屉，不是内容举报抽屉。
      expect(find.byKey(const ValueKey('accountReportSubmit')), findsOneWidget);
      expect(find.byKey(const ValueKey('reportSubmit')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();

      expect(accountReports.calls, [(2, AccountReportReason.harassment)]);
      expect(repo.postReports, isEmpty, reason: '不得再调 openReport(postId) 举报整条帖子');
    });
  });

  group('bug 544 comment_like_tapped', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    List<Map<String, Object>?> likeEvents() =>
        [for (final (e, p) in events) if (e == 'comment_like_tapped') p];

    testWidgets('一级评论：点赞 / 取消都报，comment_level = 1；不报 post_like_tapped', (tester) async {
      await _pump(tester, _Repo([_c(10)]));
      final heart = find.byKey(const ValueKey('likeComment_10'));
      await tester.tap(heart);
      await tester.pumpAndSettle();
      await tester.tap(heart);
      await tester.pumpAndSettle();

      expect(likeEvents(), [
        {'liked': true, 'comment_level': 1},
        {'liked': false, 'comment_level': 1},
      ]);
      expect(events.where((e) => e.$1 == 'post_like_tapped'), isEmpty);
    });

    testWidgets('回复：comment_level = 2，属性只有 liked / comment_level', (tester) async {
      await _pump(tester, _Repo([_c(10, replies: [_reply(20)])]));
      await tester.tap(find.byKey(const ValueKey('likeComment_20')));
      await tester.pumpAndSettle();

      expect(likeEvents(), [
        {'liked': true, 'comment_level': 2},
      ]);
    });
  });
}
