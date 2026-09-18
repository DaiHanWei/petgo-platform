import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/comment_section.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_action_entry.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/letter_avatar.dart';

/// V1.1.4 Story 1.6：评论区接入「点作者 → 看这人是谁」的入口。
///
/// **本版本最大的闭环缺口**——影子评论 / R1 / R2 / 通知抑制全是为评论区骚扰设计的，
/// 而在此之前评论区根本没有举报/拉黑的入口：只在评论区骚扰、从不发帖的账号，用户拿他没办法。
///
/// 🔁 **V1.3.0 batch-b1 Story 2.1 改口**：这个入口从「弹迷你卡」改成「**进完整主页**」
/// （FR-118.1，入口统一收口）。本文件的断言随之从「卡片出现了没」改成「**路由到主页了没**」——
/// 行为契约（哪些地方可点、注销不可点、拉黑后重拉列表）一字未改。
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
  Future<Comment> postComment(int postId, String body, {List<int> mentionedUserIds = const []}) => throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body, {List<int> mentionedUserIds = const []}) => throw UnimplementedError();
  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}

  /// V1.3.0 Story 2.4 新增的点赞通道；本类不验它，记下调用即可。
  final List<int> likedComments = <int>[];
  final List<int> unlikedComments = <int>[];

  @override
  Future<void> likeComment(int commentId) async => likedComments.add(commentId);

  @override
  Future<void> unlikeComment(int commentId) async => unlikedComments.add(commentId);
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

class _FakeProfileRepo implements PublicProfileRepository {
  _FakeProfileRepo(this.profile);
  final PublicProfile profile;
  int calls = 0;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
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

/// 内容区在本文件里不是被验对象 —— 给个恒空页，免得走真网络。
class _EmptyPostsRepo implements PublicUserPostsRepository {
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async =>
      PublicUserPostPage.empty;
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

const PublicProfile _budi = PublicProfile(
  postCount: 2,
  likeCount: 0,
  isDeactivated: false,
  self: false,
  nickname: 'Budi',
  avatarUrl: null,
);

/// 评论区挂在一个**真的 GoRouter** 下 —— 入口现在是 `context.push('/users/:id')`，
/// 没有路由就测不到「点了之后去了哪儿」。
Future<void> _pumpWith(WidgetTester tester, {required ProviderContainer container}) async {
  final router = GoRouter(routes: [
    GoRoute(
      path: '/',
      builder: (_, _) => const Scaffold(
        body: SingleChildScrollView(child: CommentSection(postId: 1, currentUserId: 5)),
      ),
    ),
    GoRoute(
      path: PublicProfilePage.routePattern,
      builder: (_, state) => PublicProfilePage(
        userId: int.parse(state.pathParameters['userId']!),
        entry: accountActionEntryFromWire(state.uri.queryParameters['entry']),
      ),
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<_FakeProfileRepo> _pump(WidgetTester tester, List<Comment> comments,
    {PublicProfile? profile}) async {
  final repo = _FakeProfileRepo(profile ?? _budi);
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(_CommentsRepo(comments)),
    publicProfileRepositoryProvider.overrideWithValue(repo),
    publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPostsRepo()),
    publicProfilePetProvider(7).overrideWith((ref) async => null),
  ]);
  addTearDown(container.dispose);
  await _pumpWith(tester, container: container);
  return repo;
}

/// 「主页开着」的标志：身份区的头像（空态 / 失败态都没有它）。
final _onProfilePage = find.byKey(const ValueKey('profileAvatar'));

void main() {
  testWidgets('AC1：点一级评论的作者名 → 进入完整主页', (tester) async {
    await _pump(tester, [_comment()]);

    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();

    expect(_onProfilePage, findsOneWidget);
    expect(find.text('Budi'), findsOneWidget);
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

    expect(_onProfilePage, findsOneWidget);
  });

  testWidgets('AC2：已注销的评论作者 → 点不动，且一个请求都不发（不给任何提示）', (tester) async {
    final repo = await _pump(tester, [_comment(nickname: null, deleted: true)]);

    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();

    expect(_onProfilePage, findsNothing);
    // 前端就地判断，连网络往返都不发生——否则用户看到的「点了没反应」与网络失败无法区分。
    expect(repo.calls, 0);
  });

  testWidgets('点评论正文仍然是「回复」，不会误跳主页（两个手势不打架）', (tester) async {
    await _pump(tester, [_comment()]);

    await tester.tap(find.text('Mahal amat'));
    await tester.pumpAndSettle();

    expect(_onProfilePage, findsNothing);
  });

  testWidgets('从评论区拉黑成功 → 退回评论区并重拉列表（这一屏跟上服务端的 R1 过滤）', (tester) async {
    final repo = _CommentsRepo([_comment()]);
    final blocks = _FakeBlockRepo();
    final container = ProviderContainer(overrides: [
      detailRepositoryProvider.overrideWithValue(repo),
      publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo(_budi)),
      publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPostsRepo()),
      publicProfilePetProvider(7).overrideWith((ref) async => null),
      blockedUsersRepositoryProvider.overrideWithValue(blocks),
      authControllerProvider.overrideWith(_LoggedInAuth.new),
    ]);
    addTearDown(container.dispose);
    await _pumpWith(tester, container: container);
    expect(repo.getCommentsCalls, 1); // 首屏

    // 走完整链路：评论作者名 → 完整主页 →「···」→ 拉黑 → 确认。
    await tester.tap(find.byKey(const ValueKey('commentAuthor_1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('profileMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('profileMenuBlock')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
    await tester.pumpAndSettle();

    expect(blocks.blocked, <int>[7]);
    // ⚠️ PRD/UI 稿都没定义「评论区拉黑成功后这一屏怎么办」，本 story 取「重拉评论列表」：
    // 与举报侧「立即兑现在当前屏」的产品取向一致，且没有乐观移除的一致性风险。
    // 真正的过滤保证在服务端 R1（Story 1.3），前端只负责让这一屏跟上。
    //
    // 🔴 整页化之后这条尤其要守：收尾回调从「闭包捕获」变成「靠 pop 的返回值派发」，
    // 中间任何一环断了，表现就是「拉黑完回到评论区，那个人的评论还在」。
    expect(repo.getCommentsCalls, 2);
    await tester.pump(const Duration(seconds: 3)); // 放掉成功 toast 的定时器
  });

  // ===== 2026-08-16 产品决定：评论行补头像（UI 稿 A6）=====

  testWidgets('评论行渲染头像，点头像同样进主页（热区不再只有一行小字）', (tester) async {
    await _pump(tester, [_comment()]);

    expect(find.byType(LetterAvatar), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('commentAuthorAvatar_1')));
    await tester.pumpAndSettle();

    expect(_onProfilePage, findsOneWidget);
  });

  testWidgets('已注销 → 头像走默认 person 态且不可点', (tester) async {
    final repo = await _pump(tester, [_comment(nickname: null, deleted: true)]);

    expect(tester.widget<LetterAvatar>(find.byType(LetterAvatar)).deleted, isTrue);
    await tester.tap(find.byKey(const ValueKey('commentAuthorAvatar_1')));
    await tester.pumpAndSettle();

    expect(_onProfilePage, findsNothing);
    expect(repo.calls, 0);
  });
}
