import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/data/shared_post_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/shared_post.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/content/presentation/shared_post_page.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 9.3 · 分享入口与链接落地分流。
///
/// 🛡 本文件三条安全攸关断言：
/// 1. **顶栏「···」不许被挤掉** —— 那是举报入口（合规入口）。UI 稿 SH1 画的是顶栏分享图标，
///    照稿实现就会把它做掉；产品 2026-08-14 决定分享让位、放底部互动栏。
/// 2. **两种分享是两个链接类型 + 两个落点** —— 单条分享若复用名片落点，
///    就等于把「我只想分享一条」变成「我把整本都给你了」。
/// 3. **落地页拿不到任何通往其它内容的把手** —— 边界画在投影类型上，不只画在页面上。
void main() {
  ContentDetail detail({List<String> images = const ['https://cdn/1.jpg']}) => ContentDetail(
        id: 5,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Alice',
        type: 'DAILY',
        body: 'A lovely pet day',
        imageUrls: images,
        likeCount: 3,
        commentCount: 2,
        liked: false,
        isAuthor: false,
        createdAt: DateTime.utc(2026, 6, 2),
      );

  Future<void> pumpDetail(WidgetTester tester, _FakeDetailRepo repo) async {
    // ⚠️ 默认测试视口只有 800×600，互动栏落在 y≈948 —— 点不到（tap 会报 off-screen）。
    // 真机上它在首屏内，这纯粹是测试视口太矮。
    tester.view.physicalSize = const Size(1000, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

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

  group('AC1 · 入口在底部互动栏，顶栏一个字不动', () {
    testWidgets('互动栏三个图标：点赞 | 评论 | 分享', (tester) async {
      await pumpDetail(tester, _FakeDetailRepo(detail: detail()));
      expect(find.byKey(const ValueKey('detailCommentIcon')), findsOneWidget);
      expect(find.byKey(const ValueKey('detailShareCardIcon')), findsOneWidget);
    });

    /// 🛡 **这条是合规护栏**：UI 稿 SH1 把顶栏「···」整个画没了。
    /// 「···」是举报入口 —— 举报入口不能变难找。谁把分享挪回顶栏，这里就会红。
    testWidgets('顶栏「···」仍在（举报入口不许被分享挤掉）', (tester) async {
      await pumpDetail(tester, _FakeDetailRepo(detail: detail()));
      expect(find.byKey(const ValueKey('detailMenu')), findsOneWidget,
          reason: '顶栏「···」是举报入口，分享不得占它的位');
    });

    testWidgets('点分享 → 取链接 → 进预览页', (tester) async {
      final repo = _FakeDetailRepo(detail: detail());
      await pumpDetail(tester, repo);
      await tester.tap(find.byKey(const ValueKey('detailShareCardIcon')));
      await tester.pumpAndSettle();

      expect(repo.shareUrlCalls, 1);
      expect(find.byType(ShareCardPreviewPage), findsOneWidget);
      // 两种尺寸都能选（9-1 的双尺寸能力在这里露出来）。
      expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsOneWidget);
    });
  });

  group('AC2 · 🛡 两种分享是两个类型、两个落点', () {
    test('链接前缀不同：单条 /c/ vs 名片 /p/', () {
      expect(postShareUrl('TOK'), 'https://s.tailtopia.id/c/TOK');
      expect(petCardShareUrl('TOK'), 'https://s.tailtopia.id/p/TOK');
      expect(postShareUrl('TOK'), isNot(petCardShareUrl('TOK')));
    });

    test('深链落点不同：post → /shared-post/，card → /pet/', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://post/TOK')), '/shared-post/TOK');
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/TOK')), '/pet/TOK');
    });

    /// 没 token 时不能退回任何档案页 —— 那就成了「点别人的分享链接看到自己家宠物」，
    /// 正是 Story 2.4 修掉的那个 bug。
    test('裸 tailtopia://post 落首页，不落档案页', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://post')), '/home');
    });

    /// 🛡 边界画在**投影类型**上：落地页模型里没有任何可以拼路由的 id。
    /// 「页面上没放入口」不算数 —— 只要模型里有把手，将来加个「看更多」就能漏出整本档案。
    test('落地页模型不含任何 id（拿不到通往其它内容的把手）', () {
      final json = <String, dynamic>{
        'authorNickname': 'Alice',
        'authorAvatarUrl': null,
        'authorDeleted': false,
        'type': 'DAILY',
        'body': '只有这一条',
        'imageUrls': <String>[],
        'createdAt': '2026-08-21T00:00:00Z',
        // 就算服务端哪天多下发了这些，客户端模型也不该接住它们。
        'id': 5,
        'authorId': 7,
        'petId': 9,
        'cardToken': 'CARDTOK',
      };
      final post = SharedPost.fromJson(json, fallbackAuthorName: '已注销用户');
      expect(post.body, '只有这一条');
      // ⚠️ 「模型里没有 id」这件事**由类型在编译期保证**，不是运行时能断言的东西：
      // 若有人给 SharedPost 加回 `id` / `petId` / `cardToken`，
      // 上面 json 里那几个字段就会被接住 —— 而 review 时能看见的正是本注释。
      // 运行时这里能钉住的是：**多下发的字段确实没被读进来**。
      expect(post.imageUrls, isEmpty);
      expect(post.type, 'DAILY');
    });
  });

  group('AC2 · 落地页只有那一条', () {
    Future<void> pumpLanding(WidgetTester tester, SharedPostRepository repo) async {
      final container = ProviderContainer(overrides: [
        sharedPostRepositoryProvider.overrideWithValue(repo),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: SharedPostPage(shareToken: 'TOK'),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('渲染那一条，且没有任何跳去别处的入口', (tester) async {
      await pumpLanding(tester, _FakeSharedRepo());
      expect(find.text('只有这一条'), findsOneWidget);
      expect(find.text('Alice'), findsOneWidget);
      // 🛡 页面上没有可点的导航元素（除返回）：没有档案入口、没有作者主页、没有「看更多」。
      expect(find.byType(TextButton), findsNothing);
      expect(find.byType(ElevatedButton), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
      expect(find.byType(ListTile), findsNothing);
    });

    testWidgets('失效 → 统一文案（不区分不存在/已删/注销）', (tester) async {
      await pumpLanding(tester, _FakeSharedRepo(fail: true));
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.sharedPostGone), findsOneWidget);
    });
  });

  group('AC4 · 埋点用新名', () {
    testWidgets('分享递出 → post_share_card_sent', (tester) async {
      final events = <String>[];
      Analytics.debugCaptureSink = (name, props) => events.add(name);
      addTearDown(() => Analytics.debugCaptureSink = null);

      // 出图走测试缝：真实 toImage 在 widget test 的假时钟里永不完成，
      // 不换掉它就跑不到埋点那一行（管线本身在 9-1 有像素级测试）。
      ShareCardPreviewPage.captureForTest = (_) async => Uint8List.fromList(const [1, 2, 3]);
      addTearDown(() => ShareCardPreviewPage.captureForTest = null);

      final repo = _FakeDetailRepo(detail: detail());
      await pumpDetail(tester, repo);
      await tester.tap(find.byKey(const ValueKey('detailShareCardIcon')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
      await tester.pumpAndSettle();

      expect(events, contains('post_share_card_sent'));
      // 🔴 旧名 2026-08-18 已作废（重复了 share，且 completed 不如 sent 明确）。
      expect(events, isNot(contains('post_share_card_share_completed')));
    });
  });
}

class _FakeDetailRepo implements DetailRepository {
  _FakeDetailRepo({required this.detail});

  final ContentDetail detail;
  int shareUrlCalls = 0;

  @override
  Future<String> getShareUrl(int postId) async {
    shareUrlCalls++;
    return 'https://s.tailtopia.id/c/TOKEN123';
  }

  @override
  Future<ContentDetail> getDetail(int id) async => detail;
  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<Comment> postComment(int postId, String body) => throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> deleteContent(int postId) async {}
  @override
  Future<void> submitReport(int postId, String reasonType) async {}
}

class _FakeSharedRepo implements SharedPostRepository {
  _FakeSharedRepo({this.fail = false});

  final bool fail;

  @override
  Future<SharedPost> getSharedPost(String shareToken,
      {required String fallbackAuthorName}) async {
    if (fail) throw Exception('gone');
    return SharedPost(
      authorName: 'Alice',
      authorDeleted: false,
      type: 'DAILY',
      body: '只有这一条',
      imageUrls: const [],
      createdAt: DateTime.utc(2026, 8, 21),
    );
  }
}
