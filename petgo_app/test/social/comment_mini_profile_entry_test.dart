import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/letter_avatar.dart';

/// V1.1.4 Story 1.6：评论区接入迷你卡。
///
/// **本版本最大的闭环缺口**——影子评论 / R1 / R2 / 通知抑制全是为评论区骚扰设计的，
/// 而在此之前评论区根本没有举报/拉黑的入口：只在评论区骚扰、从不发帖的账号，用户拿他没办法。

class _CommentsRepo implements DetailRepository {
  _CommentsRepo(this.items);
  final List<Comment> items;
  int getCommentsCalls = 0;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async {
    getCommentsCalls++;
    return CommentPage(items: items, nextCursor: null, hasMore: false);
  }

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
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

class _FakeMiniRepo implements MiniProfileRepository {
  _FakeMiniRepo(this.profile);
  final MiniProfile profile;
  int calls = 0;

  @override
  Future<MiniProfile> getMiniProfile(int userId) async {
    calls++;
    return profile;
  }
}

class _FakeBlockRepo implements BlockedUsersRepository {
  final List<int> blocked = <int>[];
  @override
  Future<void> block(int userId) async => blocked.add(userId);
  @override
  Future<List<BlockedUser>> list() async => const <BlockedUser>[];
  @override
  Future<void> unblock(int userId) async {}
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 5, nickname: 'Me', onboardingCompleted: true),
      );
}

Comment _comment({
  int id = 1,
  int authorId = 7,
  String? nickname = 'Budi',
  bool deleted = false,
}) =>
    Comment(
      id: id,
      authorId: authorId,
      authorNickname: nickname,
      authorDeleted: deleted,
      body: 'Mahal amat',
      createdAt: DateTime.utc(2026, 8, 14),
    );

Future<_FakeMiniRepo> _pump(WidgetTester tester, List<Comment> comments,
    {MiniProfile? profile}) async {
  final mini = _FakeMiniRepo(profile ??
      const MiniProfile(postCount: 2, isDeactivated: false, nickname: 'Budi', avatarUrl: null));
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(_CommentsRepo(comments)),
    miniProfileRepositoryProvider.overrideWithValue(mini),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: SingleChildScrollView(child: CommentSection(postId: 1, currentUserId: 5)),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return mini;
}

void main() {
  testWidgets('AC1：点一级评论的作者名 → 弹出迷你卡', (tester) async {
    await _pump(tester, [_comment()]);

    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
  });

  testWidgets('AC1：二级回复的作者名同样可点', (tester) async {
    // 一级评论内嵌一条二级回复。
    final reply = _comment(id: 2, authorId: 9, nickname: 'Nadia');
    final top = Comment(
      id: 1,
      authorId: 7,
      authorNickname: 'Budi',
      authorDeleted: false,
      body: 'Mahal amat',
      createdAt: DateTime.utc(2026, 8, 14),
      replyCount: 1,
      replies: [reply],
    );
    await _pump(tester, [top]);

    await tester.tap(find.byKey(const ValueKey('commentAuthor_2')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
  });

  testWidgets('AC2：已注销的评论作者 → 不弹卡，且一个请求都不发（不给任何提示）', (tester) async {
    final mini = await _pump(tester, [_comment(nickname: null, deleted: true)]);

    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
    // 前端就地判断，连网络往返都不发生——否则用户看到的「点了没反应」与网络失败无法区分。
    expect(mini.calls, 0);
  });

  testWidgets('点评论正文仍然是「回复」，不会误弹卡（两个手势不打架）', (tester) async {
    await _pump(tester, [_comment()]);

    await tester.tap(find.text('Mahal amat'));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
  });

  testWidgets('从评论区拉黑成功 → 重拉评论列表（这一屏跟上服务端的 R1 过滤）', (tester) async {
    final mini = _FakeMiniRepo(const MiniProfile(
        postCount: 2, isDeactivated: false, nickname: 'Budi', avatarUrl: null));
    final repo = _CommentsRepo([_comment()]);
    final blocks = _FakeBlockRepo();
    final container = ProviderContainer(overrides: [
      detailRepositoryProvider.overrideWithValue(repo),
      miniProfileRepositoryProvider.overrideWithValue(mini),
      blockedUsersRepositoryProvider.overrideWithValue(blocks),
      authControllerProvider.overrideWith(_LoggedInAuth.new),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(child: CommentSection(postId: 1, currentUserId: 5)),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(repo.getCommentsCalls, 1); // 首屏

    // 走完整链路：评论作者名 → 迷你卡 →「⋯」→ 拉黑 → 确认。
    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMenuBlock')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
    await tester.pumpAndSettle();

    expect(blocks.blocked, <int>[7]);
    // ⚠️ PRD/UI 稿都没定义「评论区拉黑成功后这一屏怎么办」，本 story 取「重拉评论列表」：
    // 与举报侧「立即兑现在当前屏」的产品取向一致，且没有乐观移除的一致性风险。
    // 真正的过滤保证在服务端 R1（Story 1.3），前端只负责让这一屏跟上。
    expect(repo.getCommentsCalls, 2);
    await tester.pump(const Duration(seconds: 3)); // 放掉成功 toast 的定时器
  });

  // ===== 2026-08-16 产品决定：评论行补头像（UI 稿 A6）=====

  testWidgets('评论行渲染头像，点头像同样弹卡（热区不再只有一行小字）', (tester) async {
    await _pump(tester, [_comment()]);

    expect(find.byType(LetterAvatar), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('commentAuthorAvatar_1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
  });

  testWidgets('已注销 → 头像走默认 person 态且不可点', (tester) async {
    final mini = await _pump(tester, [_comment(nickname: null, deleted: true)]);

    expect(tester.widget<LetterAvatar>(find.byType(LetterAvatar)).deleted, isTrue);
    await tester.tap(find.byKey(const ValueKey('commentAuthorAvatar_1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
    expect(mini.calls, 0);
  });
}
