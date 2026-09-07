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
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/shared_post.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/content/presentation/share_card/open_share_card.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';
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
  ContentDetail detail({
    List<String> images = const ['https://cdn/1.jpg'],
    String type = 'DAILY',
    String visibility = 'PUBLIC',
  }) =>
      ContentDetail(
        id: 5,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Alice',
        type: type,
        visibility: visibility,
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

  /// bug 20260826 · **信息流里也要有分享入口**。
  ///
  /// 此前分享只在详情页底部互动栏（2026-08-14 决策 X-22 把它从顶栏挪下来的那个位置）。
  /// 而信息流才是内容被看到的地方 —— 要分享得先点进详情，白白掉一层漏斗。
  group('bug 20260826 · 信息流分享入口', () {
    FeedItem item() => FeedItem(
          id: 5,
          authorId: 9,
          authorDeleted: false,
          type: 'DAILY',
          authorNickname: 'Sari',
          body: 'halo',
          createdAt: DateTime.utc(2026, 8, 26),
        );

    Future<void> pumpCard(WidgetTester tester, _FakeDetailRepo repo,
        {required bool withShare}) async {
      tester.view.physicalSize = const Size(1000, 2000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final container = ProviderContainer(overrides: [
        detailRepositoryProvider.overrideWithValue(repo),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: Consumer(builder: (context, ref, _) => MasonryCard(
              item: item(),
              deletedUserLabel: 'Pengguna dihapus',
              onShare: withShare
                  ? () => ShareCardEntry.openForPostId(context, ref, item().id)
                  : null,
            )),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('信息流卡片上有分享图标', (tester) async {
      await pumpCard(tester, _FakeDetailRepo(detail: detail()), withShare: true);
      expect(find.byKey(const ValueKey('feedCardShare_5')), findsOneWidget);
    });

    /// 🛡 `MasonryCard` 是共享件：没有分享语义的调用方不该凭空多一个按钮。
    testWidgets('未传 onShare 时不渲染该图标', (tester) async {
      await pumpCard(tester, _FakeDetailRepo(detail: detail()), withShare: false);
      expect(find.byKey(const ValueKey('feedCardShare_5')), findsNothing);
    });

    /// 🔴 流里点分享 **先取详情再出卡**。
    /// 直接拿流里那份轻量数据拼卡，得到的会与从详情页分享出去的**不是同一张**
    /// （正文是摘要、首图是裁过的），而没有任何东西会提示这件事。
    testWidgets('点分享 → 取详情 → 取链接 → 进预览页', (tester) async {
      final repo = _FakeDetailRepo(detail: detail());
      await pumpCard(tester, repo, withShare: true);
      await tester.tap(find.byKey(const ValueKey('feedCardShare_5')));
      await tester.pumpAndSettle();

      expect(repo.detailCalls, 1, reason: '没取详情 ⇒ 卡是拿流里的摘要拼的');
      expect(repo.shareUrlCalls, 1);
      expect(find.byType(ShareCardPreviewPage), findsOneWidget);
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
    testWidgets('出图完成 → post_share_card_generated（E-13 要等系统回调，此刻不报）', (tester) async {
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

      // 🔴 Story 10.1 订正：出图这一刻报的是 **E-12 `post_share_card_generated`**，
      //    不是 E-13。E-13 的定义是「系统分享菜单**回调成功**」，报在出图那刻等于
      //    把「看了一眼预览就退出」的人也算成分享成功 —— 只会高估，且事后无法修正。
      // E-11（Story 10.1 补齐）：**漏斗起点**，点击那一刻就报。
      expect(events, contains('post_share_card_tapped'));
      expect(events, contains('post_share_card_generated'));
      expect(events, isNot(contains('post_share_card_sent')),
          reason: '还没走到系统分享面板的回调，不该出现 E-13');
      // 🔴 旧名 2026-08-18 已作废（重复了 share，且 completed 不如 sent 明确）。
      expect(events, isNot(contains('post_share_card_share_completed')));
    });

    /// 🔴 `content_type` 走**显式映射**，不把线格式发上去：清单 §3 把
    /// `GROWTH_MOMENT`/`DAILY`/`KNOWLEDGE` 与 `diary`/`moment`/`tips` 并列标了
    /// 「需与工程统一」，而看板维度一旦发版就改不动了。
    testWidgets('E-11 的 content_type 是埋点词表，不是后端枚举线格式', (tester) async {
      final captured = <String, Map<String, Object>?>{};
      Analytics.debugCaptureSink = (name, props) => captured[name] = props;
      addTearDown(() => Analytics.debugCaptureSink = null);

      await pumpDetail(tester,
          _FakeDetailRepo(detail: detail(type: 'GROWTH_MOMENT', visibility: 'PRIVATE')));
      await tester.tap(find.byKey(const ValueKey('detailShareCardIcon')));
      await tester.pumpAndSettle();

      expect(captured['post_share_card_tapped'], {
        'content_type': 'diary',
        // 私密日记**允许**分享（AD-15 Rule 6）—— 这个属性是产品判断依据，不是拦人的闸。
        'is_private_diary': true,
        'has_image': true,
      });
    });

    testWidgets('公开的 Diary → is_private_diary=false（不是"只要是 Diary 就算私密"）',
        (tester) async {
      final captured = <String, Map<String, Object>?>{};
      Analytics.debugCaptureSink = (name, props) => captured[name] = props;
      addTearDown(() => Analytics.debugCaptureSink = null);

      await pumpDetail(tester, _FakeDetailRepo(detail: detail(type: 'GROWTH_MOMENT')));
      await tester.tap(find.byKey(const ValueKey('detailShareCardIcon')));
      await tester.pumpAndSettle();

      expect(captured['post_share_card_tapped']?['is_private_diary'], false);
    });
  });
}

class _FakeDetailRepo implements DetailRepository {
  _FakeDetailRepo({required this.detail});

  final ContentDetail detail;
  int shareUrlCalls = 0;
  int detailCalls = 0;

  @override
  Future<String> getShareUrl(int postId) async {
    shareUrlCalls++;
    return 'https://s.tailtopia.id/c/TOKEN123';
  }

  @override
  Future<ContentDetail> getDetail(int id) async {
    detailCalls++;
    return detail;
  }
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
