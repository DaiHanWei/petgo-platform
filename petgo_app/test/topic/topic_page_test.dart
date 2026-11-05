import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_skeleton.dart';
import 'package:tailtopia/features/content/presentation/pinned_badge.dart';
import 'package:tailtopia/features/content/presentation/promo_pinned_card.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/features/topic/domain/topic.dart';
import 'package:tailtopia/features/topic/presentation/topic_page.dart';
import 'package:tailtopia/features/topic/presentation/widgets/topic_empty_view.dart';
import 'package:tailtopia/features/topic/presentation/widgets/topic_official_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

import '../support/fake_feed_repository.dart';
import 'fake_topic_repository.dart';

/// L0：话题详情页各状态（V1.4.2 social-topic · Story 4.3 · AC2 / AC3 / AC7 · UI C6–C11）。
class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _User extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;
  String? lastEntry;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
    lastEntry = entrySource;
  }
}

const _official = TopicDetail(
  name: 'harikucingsedunia',
  displayName: 'HariKucingSedunia',
  official: true,
  description: 'Rayakan Hari Kucing Sedunia bareng TailTopia!',
);

PinnedSlot _contentPin() => PinnedSlot(pinConfigId: 5, pinType: 'CONTENT', item: feedItem(99, body: 'pinned post'));

PinnedSlot _promoPin() => const PinnedSlot(
      pinConfigId: 6,
      pinType: 'PROMO',
      item: null,
      promo: PromoCard(imageUrl: 'https://cdn.example.com/x.jpg', title: 'Lomba Foto'),
    );

void main() {
  final events = <(String, Map<String, Object>?)>[];

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    TopicEmptyView.debugPublishOpener = null;
  });

  List<Map<String, Object>?> eventsOf(String name) =>
      [for (final e in events) if (e.$1 == name) e.$2];

  Future<(ProviderContainer, GoRouter)> pumpTopic(
    WidgetTester tester,
    FakeTopicRepository repo, {
    bool loggedIn = true,
    String? location,
    _Guide? guide,
    bool settle = true,
  }) async {
    final c = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(loggedIn ? _User.new : _Guest.new),
      topicRepositoryProvider.overrideWithValue(repo),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
      if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
    ]);
    addTearDown(c.dispose);
    final router = GoRouter(
      initialLocation: '/home',
      routes: [
        GoRoute(path: '/home', builder: (_, _) => const Scaffold(body: Text('home'))),
        GoRoute(
          path: TopicPage.routePattern,
          builder: (c, s) => TopicPage(
            name: s.pathParameters['name']!,
            source: TopicEntrySource.fromWire(s.uri.queryParameters['from']),
          ),
        ),
        GoRoute(path: '/content/:id', builder: (_, s) => Scaffold(body: Text('detail ${s.pathParameters['id']}'))),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();
    router.push(location ?? TopicPage.routeFor('kucinglucu', from: TopicEntrySource.homeRow));
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
    }
    return (c, router);
  }

  test('routeFor：标准名编码 + from wire 值；无 from 不带 query', () {
    expect(TopicPage.routeFor('kucinglucu', from: TopicEntrySource.homeRow), '/topics/kucinglucu?from=home_row');
    expect(TopicPage.routeFor('kucing lucu'), '/topics/kucing%20lucu');
    expect(TopicPage.routeFor('x', from: TopicEntrySource.pin), '/topics/x?from=pin');
    expect(TopicEntrySource.fromWire('related'), TopicEntrySource.related);
    expect(TopicEntrySource.fromWire('nope'), isNull);
    expect(TopicEntrySource.fromWire(null), isNull);
  });

  testWidgets('C6 加载中：标题骨架 + 说明骨架 + 页签可见 + 两张卡骨架', (tester) async {
    final repo = FakeTopicRepository()
      ..detailGate = Completer<void>()
      ..postsGate = Completer<void>();
    await pumpTopic(tester, repo, settle: false);
    await tester.pump(const Duration(milliseconds: 400)); // push 转场
    expect(find.byKey(const ValueKey('topicTitleSkeleton')), findsOneWidget);
    expect(find.byKey(const ValueKey('topicHeaderSkeleton')), findsOneWidget);
    expect(find.byKey(const ValueKey('topicTabLatest')), findsOneWidget);
    expect(find.byKey(const ValueKey('topicTabPopular')), findsOneWidget);
    expect(find.byType(FeedSkeleton), findsOneWidget);
    expect(eventsOf('topic_page_viewed'), isEmpty);
    repo.detailGate!.complete();
    repo.postsGate!.complete();
    await tester.pumpAndSettle();
    expect(find.byType(FeedSkeleton), findsNothing);
    expect(find.text('post 1'), findsOneWidget);
  });

  testWidgets('C7 官方话题：#displayName + Resmi + 说明，无分享 / ⋯，内容顶置带 Disematkan，两个页签同一张', (tester) async {
    final repo = FakeTopicRepository(detail: _official, pinned: _contentPin());
    await pumpTopic(tester, repo, location: TopicPage.routeFor('harikucingsedunia'));

    final appBar = find.byType(AppBar);
    expect(find.descendant(of: appBar, matching: find.text('#HariKucingSedunia')), findsOneWidget);
    expect(find.descendant(of: appBar, matching: find.byType(TopicOfficialBadge)), findsOneWidget);
    expect(tester.widget<AppBar>(appBar).actions, isNull, reason: '无分享、无 ⋯');
    final desc = find.byKey(const ValueKey('topicDescription'));
    expect(desc, findsOneWidget);
    final descStyle = tester.widget<Text>(desc).style!;
    expect(descStyle.fontSize, 13);
    expect(descStyle.color, AppColors.ink2);
    expect(find.byKey(const ValueKey('feedPinnedCard')), findsOneWidget);
    // 角标挂在图片区（无图的文字卡不画）——这里断言传进去的就是「Disematkan」角标。
    expect(tester.widget<MasonryCard>(find.byKey(const ValueKey('feedPinnedCard'))).pinnedBadge,
        isA<PinnedBadge>());
    // 顶置在页签之下。
    expect(tester.getTopLeft(find.byKey(const ValueKey('feedPinnedCard'))).dy,
        greaterThan(tester.getBottomLeft(find.byKey(const ValueKey('topicTabLatest'))).dy));

    await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('feedPinnedCard')), findsOneWidget);
    expect(find.text('post 2'), findsOneWidget);
    expect(repo.pinnedCalls, 1, reason: '切页签不重取顶置');
  });

  testWidgets('C7 推广卡顶置渲染 PromoPinnedCard', (tester) async {
    await pumpTopic(tester, FakeTopicRepository(detail: _official, pinned: _promoPin()));
    expect(find.byType(PromoPinnedCard), findsOneWidget);
    expect(find.text('Lomba Foto'), findsOneWidget);
  });

  testWidgets('C8 用户话题：无 Resmi、无说明；无顶置时不渲染 PinnedBadge', (tester) async {
    await pumpTopic(tester, FakeTopicRepository());
    expect(find.descendant(of: find.byType(AppBar), matching: find.text('#KucingLucu')), findsOneWidget);
    expect(find.byType(TopicOfficialBadge), findsNothing);
    expect(find.byKey(const ValueKey('topicDescription')), findsNothing);
    expect(find.byType(PinnedBadge), findsNothing);
    expect(find.byKey(const ValueKey('feedPinnedCard')), findsNothing);
  });

  testWidgets('顶置取数失败 → 当作无顶置，页面照常', (tester) async {
    await pumpTopic(tester, FakeTopicRepository()..pinnedFails = true);
    expect(find.text('post 1'), findsOneWidget);
    expect(find.byType(PinnedBadge), findsNothing);
    expect(find.byKey(const ValueKey('topicLoadFailed')), findsNothing);
  });

  testWidgets('切页签：主动切才报 topic_sort_switched；重复点同一页签不报', (tester) async {
    await pumpTopic(tester, FakeTopicRepository());
    expect(eventsOf('topic_sort_switched'), isEmpty, reason: '进页默认选中不报');
    await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
    await tester.pumpAndSettle();
    expect(eventsOf('topic_sort_switched'), [{'sort': 'popular'}]);
    await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
    await tester.pumpAndSettle();
    expect(eventsOf('topic_sort_switched'), hasLength(1));
    await tester.tap(find.byKey(const ValueKey('topicTabLatest')));
    await tester.pumpAndSettle();
    expect(eventsOf('topic_sort_switched').last, {'sort': 'latest'});
  });

  group('C9 空态', () {
    FakeTopicRepository emptyRepo({List<TopicSummary>? related}) => FakeTopicRepository(
          pages: {TopicSort.latest: [const FeedPage(items: [])], TopicSort.popular: [const FeedPage(items: [])]},
          related: related ?? topics(10, official: {1}),
        );

    testWidgets('标题 / 副文 / 按钮 + 10 行推荐，前 3 序号 mint600、其余 muted', (tester) async {
      tester.view.physicalSize = const Size(1080, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await pumpTopic(tester, emptyRepo());
      expect(find.text('Belum ada postingan di topik ini'), findsOneWidget);
      expect(find.text('Yuk, jadi yang pertama berbagi di sini!'), findsOneWidget);
      expect(find.text('Buat Postingan'), findsOneWidget);
      expect(find.text('Topik populer lainnya'), findsOneWidget);
      for (var i = 1; i <= 10; i++) {
        expect(find.byKey(ValueKey('topicRelated_topik$i')), findsOneWidget, reason: 'row $i');
        expect(tester.getSize(find.byKey(ValueKey('topicRelated_topik$i'))).height, 44);
      }
      Color? rankColor(int r) => tester.widget<Text>(find.byKey(ValueKey('topicRelatedRank_$r'))).style!.color;
      expect(rankColor(1), AppColors.mint600);
      expect(rankColor(3), AppColors.mint600);
      expect(rankColor(4), AppColors.muted);
      expect(find.descendant(of: find.byKey(const ValueKey('topicRelated_topik1')),
          matching: find.byType(TopicOfficialBadge)), findsOneWidget);
      // 两个页签下同一份推荐。
      await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('topicRelated_topik1')), findsOneWidget);
    });

    testWidgets('推荐为空 / 取数失败 → 推荐整段不渲染', (tester) async {
      await pumpTopic(tester, emptyRepo(related: const []));
      expect(find.text('Belum ada postingan di topik ini'), findsOneWidget);
      expect(find.byKey(const ValueKey('topicRelatedTitle')), findsNothing);
    });

    testWidgets('推荐取数失败 → 推荐整段不渲染', (tester) async {
      await pumpTopic(tester, emptyRepo()..relatedFails = true);
      expect(find.byKey(const ValueKey('topicRelatedTitle')), findsNothing);
      expect(find.byKey(const ValueKey('topicLoadFailed')), findsNothing);
    });

    testWidgets('登录态点「Buat Postingan」→ 打开发布页，预填「#显示写法 」', (tester) async {
      final opened = <String>[];
      TopicEmptyView.debugPublishOpener = (ctx, text) async => opened.add(text);
      await pumpTopic(tester, emptyRepo());
      await tester.tap(find.byKey(const ValueKey('emptyStateAction')));
      await tester.pumpAndSettle();
      expect(opened, ['#KucingLucu ']);
    });

    testWidgets('游客点「Buat Postingan」→ 强登录引导，不打开发布页', (tester) async {
      final opened = <String>[];
      TopicEmptyView.debugPublishOpener = (ctx, text) async => opened.add(text);
      final guide = _Guide();
      await pumpTopic(tester, emptyRepo(), loggedIn: false, guide: guide);
      await tester.tap(find.byKey(const ValueKey('emptyStateAction')));
      await tester.pumpAndSettle();
      expect(guide.hardDialogs, 1);
      expect(guide.lastEntry, 'topic_empty');
      expect(opened, isEmpty);
    });

    testWidgets('点推荐行 → 进对应话题页（from=related）', (tester) async {
      final repo = emptyRepo();
      final (_, router) = await pumpTopic(tester, repo);
      await tester.tap(find.byKey(const ValueKey('topicRelated_topik2')));
      await tester.pumpAndSettle();
      expect(router.state.uri.toString(), '/topics/topik2?from=related');
      expect(eventsOf('topic_page_viewed').last, {'topic_id': 'kucinglucu', 'source': 'related'});
    });
  });

  testWidgets('C10 不存在：search_off + 两行文案 + Kembali ke Sosial（返回上一页）；不报 topic_page_viewed', (tester) async {
    final repo = FakeTopicRepository()..detailError = const TopicNotFoundException();
    await pumpTopic(tester, repo);
    expect(find.byIcon(Icons.search_off_rounded), findsOneWidget);
    expect(find.text('Topik tidak ditemukan'), findsOneWidget);
    expect(find.text('Topik ini sudah tidak tersedia'), findsOneWidget);
    expect(find.byKey(const ValueKey('topicTabLatest')), findsNothing, reason: '不显示页签');
    expect(eventsOf('topic_page_viewed'), isEmpty);
    await tester.tap(find.text('Kembali ke Sosial'));
    await tester.pumpAndSettle();
    expect(find.text('home'), findsOneWidget);
  });

  testWidgets('C11 失败：cloud_off + Coba lagi，点后重新请求', (tester) async {
    final repo = FakeTopicRepository()..detailError = Exception('net');
    await pumpTopic(tester, repo);
    expect(find.byIcon(Icons.cloud_off_rounded), findsOneWidget);
    expect(find.text('Gagal memuat, silakan coba lagi'), findsOneWidget);
    expect(eventsOf('topic_page_viewed'), isEmpty);
    repo.detailError = null;
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(repo.detailCalls, 2);
    expect(repo.pinnedCalls, 2, reason: '重试同时重发顶置');
    expect(find.text('post 1'), findsOneWidget);
    expect(eventsOf('topic_page_viewed'), hasLength(1));
  });

  testWidgets('C11：首屏失败也整页 C11（详情成功）', (tester) async {
    final repo = FakeTopicRepository()..postsError[TopicSort.latest] = Exception('net');
    await pumpTopic(tester, repo);
    expect(find.byKey(const ValueKey('topicLoadFailed')), findsOneWidget);
    repo.postsError.clear();
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(find.text('post 1'), findsOneWidget);
  });

  testWidgets('🔴 AC7.1：详情成功但首屏失败（C11）→ 不报 topic_page_viewed；重试成功后才报一次', (tester) async {
    final repo = FakeTopicRepository()..postsError[TopicSort.latest] = Exception('net');
    await pumpTopic(tester, repo);
    expect(find.byKey(const ValueKey('topicLoadFailed')), findsOneWidget);
    expect(eventsOf('topic_page_viewed'), isEmpty);
    repo.postsError.clear();
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(eventsOf('topic_page_viewed'), [{'topic_id': 'kucinglucu', 'source': 'home_row'}]);
  });

  testWidgets('首屏 404（详情还没回来）→ C10，不报 topic_page_viewed', (tester) async {
    final repo = FakeTopicRepository()..postsError[TopicSort.latest] = const TopicNotFoundException();
    await pumpTopic(tester, repo);
    expect(find.byKey(const ValueKey('topicNotFound')), findsOneWidget);
    expect(eventsOf('topic_page_viewed'), isEmpty);
  });

  testWidgets('下拉刷新时详情 404（话题刚被禁用）→ 即便有旧值也进 C10', (tester) async {
    final repo = FakeTopicRepository();
    await pumpTopic(tester, repo);
    expect(find.text('post 1'), findsOneWidget);
    repo.detailError = const TopicNotFoundException();
    await tester.fling(find.text('post 1'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(repo.detailCalls, 2);
    expect(find.byKey(const ValueKey('topicNotFound')), findsOneWidget);
    expect(find.text('post 1'), findsNothing);
  });

  testWidgets('下拉刷新时详情网络失败 → 保留旧内容，不整页报错', (tester) async {
    final repo = FakeTopicRepository();
    await pumpTopic(tester, repo);
    repo.detailError = Exception('net');
    await tester.fling(find.text('post 1'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(repo.detailCalls, 2);
    expect(find.text('post 1'), findsOneWidget);
    expect(find.descendant(of: find.byType(AppBar), matching: find.text('#KucingLucu')), findsOneWidget);
    expect(find.byKey(const ValueKey('topicLoadFailed')), findsNothing);
    expect(find.byKey(const ValueKey('topicNotFound')), findsNothing);
  });

  testWidgets('已出详情后切页签首屏失败 → 列表区失败态，页签仍在', (tester) async {
    final repo = FakeTopicRepository()..postsError[TopicSort.popular] = Exception('net');
    await pumpTopic(tester, repo);
    await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('topicListFailed')), findsOneWidget);
    expect(find.byKey(const ValueKey('topicLoadFailed')), findsNothing);
    expect(find.byKey(const ValueKey('topicTabLatest')), findsOneWidget);
    repo.postsError.clear();
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(find.text('post 2'), findsOneWidget);
  });

  group('topic_page_viewed', () {
    testWidgets('只报一次、topic_id = 服务端标准名、source 随 from', (tester) async {
      await pumpTopic(tester, FakeTopicRepository(), location: '/topics/KucingLucu?from=home_row');
      await tester.tap(find.byKey(const ValueKey('topicTabPopular')));
      await tester.pumpAndSettle();
      expect(eventsOf('topic_page_viewed'), [{'topic_id': 'kucinglucu', 'source': 'home_row'}]);
    });

    testWidgets('from=pin → source pin', (tester) async {
      await pumpTopic(tester, FakeTopicRepository(), location: '/topics/kucinglucu?from=pin');
      expect(eventsOf('topic_page_viewed'), [{'topic_id': 'kucinglucu', 'source': 'pin'}]);
    });

    testWidgets('缺省 / 不认识的 from → 不带 source 键', (tester) async {
      await pumpTopic(tester, FakeTopicRepository(), location: '/topics/kucinglucu');
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await pumpTopic(tester, FakeTopicRepository(), location: '/topics/kucinglucu?from=bogus');
      final viewed = eventsOf('topic_page_viewed');
      expect(viewed, hasLength(2));
      for (final p in viewed) {
        expect(p, {'topic_id': 'kucinglucu'});
      }
    });
  });

  testWidgets('点卡片进详情页', (tester) async {
    await pumpTopic(tester, FakeTopicRepository());
    await tester.tap(find.text('post 1'));
    await tester.pumpAndSettle();
    expect(find.text('detail 1'), findsOneWidget);
  });
}
