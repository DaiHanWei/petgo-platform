import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/presentation/pet_recommendation_list_controller.dart';
import 'package:tailtopia/features/profile/presentation/pet_recommendation_list_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_recommendation_grid.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：全屏推荐集合页（V1.3.0 batch-b1 Story 4.3 · AC1/AC2/AC3/AC4/AC5）。
///
/// <h3>重心是 AC3 的两个半：**翻页要接得上**，**翻页失败不能掀桌子**</h3>
/// F13 / NFR-10 的原文是「保留已加载内容 + 给重试入口」——
/// 把增量失败做成整屏错误态（用户已经滚过十几只宠物）是这条口径首先要避免的事。
///
/// <h3>🔴 「到底了」只看 hasMore，不看 items 空不空</h3>
/// 服务端会回**空 items + 有游标 + hasMore=true**（这一页的宠物全被拉黑/注销/没头像过滤掉）。
/// 把空 items 当到底的表现是「列表在第二页莫名断掉，而后面明明还有」。
class _PagedRepo implements PetRecommendationRepository {
  _PagedRepo(this.pages, {this.failAfter = -1});

  /// 第 n 次调用回第 n 页。
  final List<RecommendedPetPage> pages;

  /// 第几次调用开始抛（-1 = 从不抛）。
  final int failAfter;

  final List<String?> cursors = [];
  int calls = 0;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    cursors.add(cursor);
    final i = calls++;
    if (failAfter >= 0 && i >= failAfter) {
      throw Exception('boom');
    }
    return i < pages.length ? pages[i] : RecommendedPetPage.empty;
  }
}

RecommendedPet _pet(int id) => RecommendedPet(
      petId: id,
      name: 'Mochi$id',
      avatarUrl: '', // 空 → 走 InitialAvatar 首字母分支，本文件不验图
      petType: 'CAT',
      companionDays: 12,
    );

RecommendedPetPage _page(List<int> ids, {String? next}) => RecommendedPetPage(
      items: ids.map(_pet).toList(),
      nextCursor: next,
      hasMore: next != null,
    );

LoginResponse _user(int id) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(id: id, onboardingCompleted: true),
    );

void main() {
  final captured = <(String, Map<String, Object>?)>[];

  setUp(() {
    captured.clear();
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  /// 把集合页挂起来（带路由，好让点卡片真的能 push 出去）。
  Future<ProviderContainer> pumpPage(WidgetTester tester, PetRecommendationRepository repo) async {
    final container = ProviderContainer(overrides: [
      petRecommendationRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user(1));
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: PetRecommendationListPage.routePath,
          routes: [
            GoRoute(
              path: PetRecommendationListPage.routePath,
              builder: (_, _) => const PetRecommendationListPage(),
            ),
            GoRoute(path: '/pets/:petId', builder: (_, _) => const Scaffold(body: Text('visitor'))),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return container;
  }

  /// 滚到底。⚠️ 一次 4000px 的 drag 不够 —— 20 张卡的网格有 5000+px，
  /// 而触底判定是「离底 400px 以内」。分几次拖到真的到底，别靠一个猜出来的数。
  Future<void> scrollToBottom(WidgetTester tester) async {
    final grid = find.byKey(const ValueKey('petRecommendListGrid'));
    for (int i = 0; i < 6; i++) {
      await tester.drag(grid, const Offset(0, -2000));
      await tester.pumpAndSettle();
    }
  }

  /// 把 Diary 的推荐区挂起来（只为验 AC1 的「查看全部」）。
  Future<void> pumpDiaryGrid(WidgetTester tester, PetRecommendationRepository repo) async {
    final container = ProviderContainer(overrides: [
      petRecommendationRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user(1));
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/diary',
          routes: [
            GoRoute(
              path: '/diary',
              builder: (_, _) => const Scaffold(
                body: SingleChildScrollView(
                  child: PetRecommendationGrid(from: kPetRecommendFromDiaryEmpty),
                ),
              ),
            ),
            GoRoute(
              path: PetRecommendationListPage.routePath,
              builder: (_, _) => const Scaffold(body: Text('collection page')),
            ),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  group('AC1 「查看全部」入口', () {
    testWidgets('在推荐区**最下面**，点了进集合页', (tester) async {
      await pumpDiaryGrid(tester, _PagedRepo([_page([1, 2])]));
      final seeAll = find.byKey(const ValueKey('petRecommendSeeAll'));
      expect(seeAll, findsOneWidget);
      // UX-DR14：位置在网格**下方**（看完这一屏想看更多再点），不是标题右边。
      final gridY = tester.getBottomLeft(find.byType(GridView)).dy;
      expect(tester.getTopLeft(seeAll).dy, greaterThanOrEqualTo(gridY - 1));

      // ⚠️ 它在页面最下面 —— 测试窗口只有 600px 高，先滚到它跟前再点。
      await tester.ensureVisible(seeAll);
      await tester.pumpAndSettle();
      await tester.tap(seeAll);
      await tester.pumpAndSettle();
      expect(find.text('collection page'), findsOneWidget);
    });

    testWidgets('🛡 池子为空时整块不渲染 → 也就没有「查看全部」', (tester) async {
      await pumpDiaryGrid(tester, _PagedRepo([_page(const [])]));
      expect(find.byKey(const ValueKey('petRecommendSeeAll')), findsNothing);
    });
  });

  group('AC2 集合页是 2 列网格，卡片与 4.1 一致', () {
    testWidgets('两列 + 复用同一个卡片组件', (tester) async {
      await pumpPage(tester, _PagedRepo([_page([1, 2, 3])]));
      final grid = tester.widget<GridView>(find.byKey(const ValueKey('petRecommendListGrid')));
      final delegate = grid.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount;
      expect(delegate.crossAxisCount, 2);
      // 🔴 是 4.1 那个组件本身，不是另画一套。
      // ⚠️ 只断言「渲染出了卡」——GridView 是懒构建的，屏幕里能装几张与测试窗口有关。
      expect(find.byType(RecommendedPetCard), findsWidgets);
    });

    testWidgets('UI 稿 E2：AppBar 标题是「其他宠物」，不是分区那句邀约', (tester) async {
      await pumpPage(tester, _PagedRepo([_page([1])]));
      final l10n = AppLocalizations.of(tester.element(find.byType(Scaffold).first));
      expect(find.descendant(of: find.byType(AppBar), matching: find.text(l10n.petRecommendListTitle)),
          findsOneWidget);
      expect(find.text(l10n.petRecommendSectionTitle), findsNothing);
    });
  });

  group('AC3 分页与失败态', () {
    testWidgets('第一页带 limit、不带游标', (tester) async {
      final repo = _PagedRepo([_page([1])]);
      await pumpPage(tester, repo);
      expect(repo.cursors, [null]);
    });

    testWidgets('滚到底 → 追加下一页，**已有的不重拉、列表不回顶**', (tester) async {
      final repo = _PagedRepo([
        _page([for (int i = 1; i <= 20; i++) i], next: 'c1'),
        _page([21, 22]),
      ]);
      final container = await pumpPage(tester, repo);
      expect(find.byKey(const ValueKey('recommendedPet_1')), findsOneWidget);

      await scrollToBottom(tester);

      expect(repo.calls, 2);
      expect(repo.cursors, [null, 'c1']);
      // 🔴 第二页**接在后面**（不是替换）：第一页那 20 只还在，列表也没被打回第一页。
      // ⚠️ 这里看状态而不是看渲染：GridView 懒构建，21/22 号在 600px 高的测试窗口外。
      final items = container.read(petRecommendationListProvider).value!.items;
      expect(items.map((e) => e.petId), [for (int i = 1; i <= 20; i++) i, 21, 22]);
    });

    testWidgets('🛡 翻页失败 → 已加载的一个不动 + 底部重试入口（F13）', (tester) async {
      final repo = _PagedRepo([_page([for (int i = 1; i <= 20; i++) i], next: 'c1')], failAfter: 1);
      final container = await pumpPage(tester, repo);

      await scrollToBottom(tester);

      // 🔴 不是整屏错误态：那 20 只一只没丢。
      expect(find.byKey(const ValueKey('petRecommendListError')), findsNothing);
      expect(find.byKey(const ValueKey('petRecommendListGrid')), findsOneWidget);
      expect(container.read(petRecommendationListProvider).value!.items, hasLength(20));
      // ⚠️ 看状态而不是看渲染：此刻视口停在网格底部，卡片都在屏幕外（懒构建）。
      expect(find.byKey(const ValueKey('petRecommendLoadMoreRetry')), findsOneWidget);
    });

    testWidgets('底部重试点一下 → 真的再取一次', (tester) async {
      final repo = _PagedRepo([_page([for (int i = 1; i <= 20; i++) i], next: 'c1')], failAfter: 1);
      await pumpPage(tester, repo);
      await scrollToBottom(tester);
      final before = repo.calls;

      await tester.tap(find.byKey(const ValueKey('petRecommendLoadMoreRetry')));
      await tester.pumpAndSettle();
      expect(repo.calls, greaterThan(before));
    });

    testWidgets('第一页就失败 → 整屏错误态 + 重试（此时本来也没内容可保留）', (tester) async {
      final repo = _PagedRepo(const [], failAfter: 0);
      await pumpPage(tester, repo);
      expect(find.byKey(const ValueKey('petRecommendListError')), findsOneWidget);
      expect(find.byKey(const ValueKey('petRecommendListEmpty')), findsNothing);
    });

    testWidgets('第一页失败不自动重试（否则页面在错误态与列表之间横跳）', (tester) async {
      final repo = _PagedRepo(const [], failAfter: 0);
      await pumpPage(tester, repo);
      await tester.pump(const Duration(seconds: 3));
      expect(repo.calls, 1);
    });

    testWidgets('🔴 空 items + hasMore=true 时照旧能往下翻（不把空页当到底）', (tester) async {
      // 服务端在「这一页的宠物全被过滤掉」时就是这么回的。
      final repo = _PagedRepo([
        _page([for (int i = 1; i <= 20; i++) i], next: 'c1'),
        RecommendedPetPage(items: const [], nextCursor: 'c2', hasMore: true),
        _page([99]),
      ]);
      final container = await pumpPage(tester, repo);
      // 🔴 **一次触底就该把空页翻过去**：空页不会让列表变长，用户停在底部滚不动、
      //    也就不会再触发下一次 —— 表现是「列表莫名断掉，而后面明明还有」。
      await scrollToBottom(tester);

      expect(repo.cursors, [null, 'c1', 'c2']);
      expect(container.read(petRecommendationListProvider).value!.items.last.petId, 99);
    });

    testWidgets('连抓有上限 —— 服务端一直给空页时不会无限抓', (tester) async {
      // 上限存在是因为「接着抓」不能变成无限抓：真被过滤空一大片时，
      // 与其把用户的流量耗在一串空页上，不如让他自己再往下滑一次。
      final repo = _PagedRepo([
        _page([for (int i = 1; i <= 20; i++) i], next: 'c1'),
        RecommendedPetPage(items: const [], nextCursor: 'c2', hasMore: true),
        RecommendedPetPage(items: const [], nextCursor: 'c3', hasMore: true),
        RecommendedPetPage(items: const [], nextCursor: 'c4', hasMore: true),
        RecommendedPetPage(items: const [], nextCursor: 'c5', hasMore: true),
      ]);
      await pumpPage(tester, repo);
      await scrollToBottom(tester);
      // 一次触底最多连抓 maxChainedFetches 页；停在底部还能再自动续 maxAutoEmptyRounds 轮，
      // 之后彻底收手 —— 总量封顶，不会变成一串没人要的请求。
      expect(
          repo.calls,
          lessThanOrEqualTo(1 +
              PetRecommendationListController.maxAutoEmptyRounds *
                  PetRecommendationListController.maxChainedFetches));
      expect(repo.calls, greaterThan(1));
    });

    testWidgets('到底了就不再发请求', (tester) async {
      final repo = _PagedRepo([_page([1, 2])]); // hasMore=false
      await pumpPage(tester, repo);
      await scrollToBottom(tester);
      expect(repo.calls, 1);
    });
  });

  group('AC3 屏幕没被填满时的续抓（code-review 2026-09-15）', () {
    testWidgets('🔴 第一页只有 1 只 + hasMore → **不等滚动**自己续抓（用户根本滚不动）',
        (tester) async {
      // maxScrollExtent 是 0 → 滚动回调一次都不会发。只靠触底触发的表现是
      // 「集合页里就那么一只，明明还有」。
      final repo = _PagedRepo([
        _page([1], next: 'c1'),
        _page([2, 3]),
      ]);
      final container = await pumpPage(tester, repo);
      await tester.pumpAndSettle();
      expect(repo.cursors, [null, 'c1']);
      expect(container.read(petRecommendationListProvider).value!.items, hasLength(3));
    });

    testWidgets('🔴 第一页空 items + hasMore → 显示加载中而**不是**空态，并继续抓',
        (tester) async {
      // 服务端在「这一页宠物全被拉黑/注销/没头像过滤掉」时就是这么回的。
      // 摆空态的表现是「永久显示『还没有宠物』且再也翻不动」——
      // 空态里既没网格也没滚动，没有任何东西能触发下一页。
      final repo = _PagedRepo([
        RecommendedPetPage(items: const [], nextCursor: 'c1', hasMore: true),
        _page([9]),
      ]);
      await pumpPage(tester, repo);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('petRecommendListEmpty')), findsNothing);
      expect(find.byKey(const ValueKey('recommendedPet_9')), findsOneWidget);
    });

    testWidgets('🛡 自动续抓有上限 —— 服务端一直给空页也不会自己转着圈发请求', (tester) async {
      // 这条路径没有用户动作，所以上限是必须的（否则是个死循环）。
      final repo = _PagedRepo([
        for (int i = 0; i < 40; i++)
          RecommendedPetPage(items: const [], nextCursor: 'c$i', hasMore: true),
      ]);
      await pumpPage(tester, repo);
      await tester.pumpAndSettle();
      expect(
          repo.calls,
          lessThanOrEqualTo(1 +
              PetRecommendationListController.maxAutoEmptyRounds *
                  PetRecommendationListController.maxChainedFetches));
      // 🛡 额度用完还是一只都没有 → 按空态处理，**不是**一个永远转不完的圈。
      expect(find.byKey(const ValueKey('petRecommendListEmpty')), findsOneWidget);
    });

    testWidgets('🛡 翻页失败之后不偷偷自动重试（等用户点那个重试入口）', (tester) async {
      final repo = _PagedRepo([_page([1], next: 'c1')], failAfter: 1);
      await pumpPage(tester, repo);
      await tester.pumpAndSettle();
      final after = repo.calls;
      await tester.pump(const Duration(seconds: 2));
      expect(repo.calls, after);
    });
  });

  group('AC4 空态', () {
    testWidgets('池子为空 → 空态，**不是**错误态', (tester) async {
      // 新站本来就没有宠物满足「有头像 + 公开记录≥3 + 近 14 天活跃」——
      // 摆错误态会让用户以为 App 坏了。
      await pumpPage(tester, _PagedRepo([_page(const [])]));
      expect(find.byKey(const ValueKey('petRecommendListEmpty')), findsOneWidget);
      expect(find.byKey(const ValueKey('petRecommendListError')), findsNothing);
      expect(find.byType(RecommendedPetCard), findsNothing);
    });
  });

  group('AC5 埋点', () {
    testWidgets('从集合页点宠物卡 → pet_card_tapped(from=explore_grid)', (tester) async {
      await pumpPage(tester, _PagedRepo([_page([7])]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'pet_card_tapped').map((e) => e.$2),
          [{'from': 'explore_grid'}]);
    });

    testWidgets('点击落点仍是站内访客入口（复用 2.3，不新建通道）', (tester) async {
      await pumpPage(tester, _PagedRepo([_page([7])]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(find.text('visitor'), findsOneWidget);
    });

    test('三个位置的 from 两两不同', () {
      final values = {
        kPetRecommendFromDiaryEmpty,
        kPetRecommendFromDiaryNonOwner,
        kPetRecommendFromExploreGrid,
      };
      expect(values, hasLength(3));
      expect(kPetRecommendFromExploreGrid, 'explore_grid');
    });
  });

  group('路由归属（Story 4.4 会从首页加入口，游客也能进）', () {
    test('🔴 集合页**不在**受控前缀里 —— 游客进它不会被 redirect 走', () {
      // 塞进 _controlledLocations 的话 4.4 那个入口对游客就是 redirect 回 /home 的死路。
      const guest = AuthState(status: AuthStatus.guest);
      expect(redirectWouldRewrite(guest, PetRecommendationListPage.routePath), isFalse);
    });

    test('🔴 路径不是 /pets/xxx —— 那会被 /pets/:petId 抢先匹配成 petId=xxx', () {
      expect(PetRecommendationListPage.routePath.startsWith('/pets/'), isFalse);
    });
  });

  group('控制器', () {
    test('pageSize 是一页的真实条数（2 列网格 10 行）', () {
      expect(PetRecommendationListController.pageSize, 20);
    });
  });
}
