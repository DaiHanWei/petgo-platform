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
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 批次 A · Story 2.6（L0）：**回复态显式化**（AD-A10.4）。
///
/// 这不是全新功能 —— 回复态本来就有，本 story 是四项增量：
/// ① 纯文本行改胶囊 ② 占位随态切换 ③ 清空文字也退出回复态 ④ 回复成功后展开父评论并定位。
///
/// 胶囊长什么样、滚多远、手感如何一律 L2；这里钉住 L0 能钉的**状态迁移**部分：
/// 什么时候进回复态、什么时候退出、发完之后谁被登记为落点。
Comment _c(int id, int authorId, {int replyCount = 0, List<Comment>? replies}) => Comment(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'U$authorId',
      body: 'body $id',
      createdAt: DateTime.utc(2026, 9, 11),
      replyCount: replyCount,
      replies: replies ?? const [],
    );

class _Repo implements DetailRepository {
  _Repo({this.comments = const [], this.replyPages = const []});

  final List<Comment> comments;

  /// 回复区**逐页**返回：最后一页之外都带 hasMore。多页是 AC5 的真实场景 ——
  /// 新回复按时间正序落在最后一页，只展开第一页照样看不见它。
  final List<List<Comment>> replyPages;

  int postCommentCalls = 0;
  int postReplyCalls = 0;

  /// 被展开过的父评论 —— AC5 的「自动展开」就是看这里有没有记录。每翻一页记一次。
  final List<int> expandedParents = <int>[];

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      CommentPage(items: comments, nextCursor: null, hasMore: false);

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async {
    final int index = cursor == null ? 0 : int.parse(cursor);
    expandedParents.add(parentId);
    if (index >= replyPages.length) {
      return const CommentPage(items: [], nextCursor: null, hasMore: false);
    }
    final bool hasMore = index + 1 < replyPages.length;
    return CommentPage(
      items: replyPages[index],
      nextCursor: hasMore ? '${index + 1}' : null,
      hasMore: hasMore,
    );
  }

  @override
  Future<Comment> postComment(int postId, String body) async {
    postCommentCalls++;
    return _c(999, 1);
  }

  @override
  Future<Comment> postReply(int parentId, String body) async {
    postReplyCalls++;
    return _c(newReplyId, 1);
  }

  /// postReply 返回的新回复 id（落点要带着它去翻页）。
  int newReplyId = 999;

  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> likeComment(int commentId) async {}
  @override
  Future<void> unlikeComment(int commentId) async {}
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
  Future<ProviderContainer> mountComposer(WidgetTester tester, _Repo repo) async {
    final container = ProviderContainer(
      overrides: [detailRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
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
    return container;
  }

  Finder pill() => find.byKey(const ValueKey('replyingToPill'));
  Finder input() => find.byKey(const ValueKey('detailCommentInput'));

  group('AC1/AC2 回复态的两处可见标记', () {
    testWidgets('非回复态：没有胶囊，占位是通用文案', (tester) async {
      await mountComposer(tester, _Repo());
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));

      expect(pill(), findsNothing);
      expect(find.text(l10n.detailCommentHint), findsOneWidget);
    });

    testWidgets('回复态：胶囊出现，占位换成「回复 @昵称…」', (tester) async {
      final container = await mountComposer(tester, _Repo());
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));

      expect(pill(), findsOneWidget);
      expect(find.text(l10n.commentReplyingTo('Rina')), findsOneWidget);
      // 🔴 占位必须跟着换。改前它恒为通用文案 ——
      // 那正是用户分不清「这条是发新评论还是回复某人」的直接原因。
      expect(find.text(l10n.commentReplyHint('Rina')), findsOneWidget);
      expect(find.text(l10n.detailCommentHint), findsNothing);
    });
  });

  group('AC3 两种退出方式', () {
    testWidgets('点 ✕ → 退出，占位恢复通用文案', (tester) async {
      final container = await mountComposer(tester, _Repo());
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('cancelReply')));
      await tester.pumpAndSettle();

      expect(container.read(replyTargetProvider), isNull);
      expect(pill(), findsNothing);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.detailCommentHint), findsOneWidget);
    });

    testWidgets('打过字又清空 → 同样退出（改前只有 ✕ 一条路）', (tester) async {
      final container = await mountComposer(tester, _Repo());
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();

      await tester.enterText(input(), 'halo');
      await tester.pump();
      expect(container.read(replyTargetProvider), isNotNull, reason: '打字中还在回复态');

      await tester.enterText(input(), '');
      await tester.pumpAndSettle();

      expect(container.read(replyTargetProvider), isNull);
      expect(pill(), findsNothing);
    });

    /// 🔴 只在「有字 → 没字」这一跳上退出，不是「只要为空就退出」。
    /// 刚点回复时输入框本来就是空的 —— 那时退出会让回复态**当场自毁**，
    /// 用户按了回复却发现指示消失了。
    testWidgets('刚进回复态（输入框本来就空）不会自己退出', (tester) async {
      final container = await mountComposer(tester, _Repo());
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();

      expect(container.read(replyTargetProvider), isNotNull);
      expect(pill(), findsOneWidget);
    });

    /// 只敲空格不算「开始输入」，也就谈不上「清空」——退不退出都不该由空格决定。
    testWidgets('只敲空格再删掉，回复态不受影响', (tester) async {
      final container = await mountComposer(tester, _Repo());
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();

      await tester.enterText(input(), '   ');
      await tester.pump();
      await tester.enterText(input(), '');
      await tester.pumpAndSettle();

      expect(container.read(replyTargetProvider), isNotNull);
    });
  });

  group('AC4 默认是新评论', () {
    testWidgets('没点过任何回复入口 → 走 postComment，不登记落点', (tester) async {
      final repo = _Repo();
      final container = await mountComposer(tester, repo);

      await tester.enterText(input(), 'halo semua');
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();

      expect(repo.postCommentCalls, 1);
      expect(repo.postReplyCalls, 0);
      // 落点只属于回复：一级评论的「发完看得见」靠 Story 2.5 的会话置顶，是另一条路。
      expect(container.read(replyLandingProvider), isNull);
    });
  });

  group('AC5 回复成功后的落点', () {
    testWidgets('回复成功 → 登记该父评论为落点，并退出回复态', (tester) async {
      final repo = _Repo();
      final container = await mountComposer(tester, repo);
      container
          .read(replyTargetProvider.notifier)
          .set(const ReplyTarget(parentId: 86, toName: 'Rina'));
      await tester.pumpAndSettle();

      await tester.enterText(input(), 'balasan');
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();

      expect(repo.postReplyCalls, 1);
      expect(container.read(replyLandingProvider)?.parentId, 86);
      // 🔴 落点带着**新回复的 id**：回复区不止一页时，评论区靠它知道翻到哪儿才算到位。
      expect(container.read(replyLandingProvider)?.replyId, repo.newReplyId);
      expect(container.read(replyTargetProvider), isNull);
    });

    /// 🔴 连着回同一条父评论两次，必须**两次都定位**。
    /// 落点只存 parentId 的话第二次状态没变、监听方收不到通知 —— 所以带了 seq。
    testWidgets('连回同一条两次 → seq 递增（否则第二次不会触发定位）', (tester) async {
      final repo = _Repo();
      final container = await mountComposer(tester, repo);
      final notifier = container.read(replyTargetProvider.notifier);

      Future<void> replyOnce(String text) async {
        notifier.set(const ReplyTarget(parentId: 86, toName: 'Rina'));
        await tester.pumpAndSettle();
        await tester.enterText(input(), text);
        await tester.pump();
        await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
        await tester.pumpAndSettle();
      }

      await replyOnce('satu');
      final first = container.read(replyLandingProvider)!;
      await replyOnce('dua');
      final second = container.read(replyLandingProvider)!;

      expect(second.parentId, first.parentId);
      expect(second.seq, greaterThan(first.seq));
    });
  });

  group('AC5 评论区消费落点', () {
    Future<ProviderContainer> mountSection(WidgetTester tester, _Repo repo) async {
      final container = ProviderContainer(
        overrides: [detailRepositoryProvider.overrideWithValue(repo)],
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
              child: CommentSection(postId: 5, currentUserId: 1),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return container;
    }

    testWidgets('刷新后展开该父评论的回复区，并把落点用掉', (tester) async {
      final repo = _Repo(
        comments: [_c(86, 2, replyCount: 5), _c(87, 3)],
        replyPages: [
          [_c(101, 1), _c(999, 1)],
        ],
      );
      final container = await mountSection(tester, repo);
      expect(repo.expandedParents, isEmpty, reason: '进页面时不该自己展开任何回复区');

      // composer 发完回复做的两件事：登记落点 + bump 刷新信号。
      container.read(replyLandingProvider.notifier).request(parentId: 86, replyId: 999);
      container.read(commentsRefreshProvider.notifier).bump();
      await tester.pumpAndSettle();

      expect(repo.expandedParents, [86]);
      // 🔴 用完即清：留着的话下一次任何刷新都会把它重放一遍。
      expect(container.read(replyLandingProvider), isNull);
    });

    /// 🔴 回复区分页：新回复按时间正序落在**最后一页**。
    /// 只展开第一页就收手的话，「回复超过一页的评论」这个最需要定位的场景恰恰定位不到。
    testWidgets('新回复在第三页 → 一路翻到它出现为止', (tester) async {
      final repo = _Repo(
        comments: [_c(86, 2, replyCount: 6)],
        replyPages: [
          [_c(101, 1), _c(102, 1)],
          [_c(103, 1), _c(104, 1)],
          [_c(105, 1), _c(999, 1)],
        ],
      );
      final container = await mountSection(tester, repo);

      container.read(replyLandingProvider.notifier).request(parentId: 86, replyId: 999);
      container.read(commentsRefreshProvider.notifier).bump();
      await tester.pumpAndSettle();

      expect(repo.expandedParents, [86, 86, 86]);
      expect(find.byKey(const ValueKey('commentItem_999')), findsOneWidget);
    });

    /// 翻页有上限：某条评论有几百条回复时，为了定位把整棵子树拉下来是另一种伤害。
    /// 翻不到就停在已加载的末尾 —— 也比停在第一页强。
    testWidgets('翻页翻不到时不会无限翻下去', (tester) async {
      final repo = _Repo(
        comments: [_c(86, 2, replyCount: 99)],
        // 20 页，且新回复的 id 压根不在任何一页里（最极端的情况）。
        replyPages: [for (var i = 0; i < 20; i++) [_c(200 + i, 1)]],
      );
      final container = await mountSection(tester, repo);

      container.read(replyLandingProvider.notifier).request(parentId: 86, replyId: 999);
      container.read(commentsRefreshProvider.notifier).bump();
      await tester.pumpAndSettle();

      expect(repo.expandedParents.length, lessThan(20));
      expect(container.read(replyLandingProvider), isNull);
    });

    /// AC6 的兜底口径：父评论已不在列表里（比如回复途中被删）→ 什么都不做。
    /// 不报错、不跳、不留悬挂状态。
    testWidgets('父评论已不在列表 → 不展开任何东西，落点照样清掉', (tester) async {
      final repo = _Repo(comments: [_c(87, 3)]);
      final container = await mountSection(tester, repo);

      container.read(replyLandingProvider.notifier).request(parentId: 86, replyId: 999);
      container.read(commentsRefreshProvider.notifier).bump();
      await tester.pumpAndSettle();

      expect(repo.expandedParents, isEmpty);
      expect(container.read(replyLandingProvider), isNull);
    });

    /// 没有落点时刷新是纯刷新 —— 展开状态一律回到默认（折叠），
    /// 不能因为加了 AC5 就把「刷新后全展开」变成常态。
    testWidgets('没有落点的普通刷新不会展开任何回复区', (tester) async {
      final repo = _Repo(comments: [_c(86, 2, replyCount: 5)]);
      final container = await mountSection(tester, repo);

      container.read(commentsRefreshProvider.notifier).bump();
      await tester.pumpAndSettle();

      expect(repo.expandedParents, isEmpty);
    });
  });
}
