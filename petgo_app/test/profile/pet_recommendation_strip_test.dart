import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/features/place/presentation/place_entry_row.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../support/fake_feed_repository.dart';

/// L0：首页顶部宠物横滑行（V1.3.0 batch-b1 Story 4.4 · AC1/AC2/AC3/AC4）。
///
/// <h3>🔴 游客态是本 story 自己定的（story Dev Notes 要求「不要留成未定义行为」）</h3>
/// 选的是「整行不渲染，且**一个请求都不发**」——理由是硬约束而非偏好：推荐接口在
/// `/api/v1/me` 下、仅登录可用，游客发过去拿 401，而 401 会弹**全局强登录窗**。
/// 所以这里有一条用例专门钉「游客不发请求」，不是只钉「游客看不见」。
class _FakeRepo implements PetRecommendationRepository {
  _FakeRepo(this.pets, {this.fail = false});

  final List<RecommendedPet> pets;
  final bool fail;
  int calls = 0;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    calls++;
    if (fail) throw Exception('boom');
    return RecommendedPetPage(items: pets, hasMore: false);
  }
}

RecommendedPet _pet(int id) => RecommendedPet(
      petId: id,
      name: 'Mochi$id',
      avatarUrl: '', // 空 → 走 InitialAvatar 首字母分支，本文件不验图
      petType: 'CAT',
      companionDays: 12,
    );

LoginResponse _user() => const LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(
          nickname: 'Aurel', petStatus: 'PLANNING', hasPetProfile: true, onboardingCompleted: true),
    );

void main() {
  final captured = <(String, Map<String, Object>?)>[];

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    captured.clear();
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  /// 把真首页挂起来（AC2 要求「其它一处不改」，所以验的是真 `HomePage`）。
  Future<ProviderContainer> pumpHome(
    WidgetTester tester,
    PetRecommendationRepository repo, {
    bool loggedIn = true,
    List<FeedItem> feed = const [],
  }) async {
    final container = ProviderContainer(overrides: [
      petRecommendationRepositoryProvider.overrideWithValue(repo),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository(feed)),
    ]);
    addTearDown(container.dispose);
    if (loggedIn) {
      container.read(authControllerProvider.notifier).applyLogin(_user());
    }
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/home',
          routes: [
            GoRoute(path: '/home', builder: (_, _) => const HomePage()),
            GoRoute(
                path: '/pet-recommendations',
                builder: (_, _) => const Scaffold(body: Text('collection page'))),
            GoRoute(path: '/pets/:petId', builder: (_, _) => const Scaffold(body: Text('visitor'))),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return container;
  }

  group('AC1/AC2 位置与布局', () {
    testWidgets('在场所入口行**之下**、分类 chips **之上**', (tester) async {
      await pumpHome(tester, _FakeRepo([for (int i = 1; i <= 8; i++) _pet(i)]));
      final strip = find.byKey(const ValueKey('petRecommendationStrip'));
      expect(strip, findsOneWidget);

      final placeY = tester.getBottomLeft(find.byType(PlaceEntryRow)).dy;
      final stripY = tester.getTopLeft(strip).dy;
      final chipsY = tester.getTopLeft(find.byType(FeedTabRow)).dy;
      expect(stripY, greaterThanOrEqualTo(placeY - 1));
      expect(stripY, lessThan(chipsY));
    });

    testWidgets('横着滑的一行（6~10 张），是 UI 稿 B1 的紧凑小卡（不是网格大卡）', (tester) async {
      // 🔁 2026-09-21 还原度修正（B1）：原先直接塞 4.1 的网格卡（宽 150 / 三行字 / 行高 216），
      //    在首页最显眼的位置顶出一整堵墙。现在是 72×72 方图 + 一行「名字 · 天数」的小卡；
      //    **点击行为**仍与网格卡同源（openRecommendedPet），由下面 AC4 的埋点用例钉住。
      await pumpHome(tester, _FakeRepo([for (int i = 1; i <= 8; i++) _pet(i)]));
      final strip = find.byKey(const ValueKey('petRecommendationStrip'));
      final list = find.descendant(of: strip, matching: find.byType(ListView));
      expect(tester.widget<ListView>(list).scrollDirection, Axis.horizontal);
      // ⚠️ 懒构建：只断言「渲染出了卡」，不数个数（一屏露几张与测试窗口有关）。
      expect(find.descendant(of: strip, matching: find.byKey(const ValueKey('recommendedPet_1'))),
          findsOneWidget);
      expect(find.descendant(of: strip, matching: find.byType(RecommendedPetCard)), findsNothing);
      // 行高 ≈100（稿），不再是网格卡那 216。
      expect(tester.getSize(list).height, lessThanOrEqualTo(100));
      // 一行「名字 · 陪伴天数」，天数复用名片页的「N hari」出口。
      expect(find.text('Mochi1 · 12 hari'), findsOneWidget);
      // 大图与小圆头像仍是两个字段、两个来源（UX-DR15）：头像在，大图空 → 占位，不拿头像顶。
      expect(find.byKey(const ValueKey('recommendedPetAvatar_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('recommendedPetCoverPlaceholder_1')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('AC2 既有顶部区域一处不改（场所入口行 + 分类 chips 都还在）', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(1)]));
      expect(find.byType(PlaceEntryRow), findsOneWidget);
      expect(find.byType(FeedTabRow), findsOneWidget);
      expect(find.bySemanticsLabel('TailTopia'), findsOneWidget); // AppBar 品牌标
    });

    testWidgets('「查看全部」→ Story 4.3 的集合页', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(1)]));
      await tester.tap(find.byKey(const ValueKey('petRecommendStripSeeAll')));
      await tester.pumpAndSettle();
      expect(find.text('collection page'), findsOneWidget);
    });

    testWidgets('🔴 「查看全部」热区 ≥44×44（UX-DR16）', (tester) async {
      // 用 key 点击的用例钉不出这条：找得到就点得到。裸文字只有 22 高，手指点不中。
      await pumpHome(tester, _FakeRepo([_pet(1)]));
      final size = tester.getSize(find.byKey(const ValueKey('petRecommendStripSeeAll')));
      expect(size.height, greaterThanOrEqualTo(44));
      expect(size.width, greaterThanOrEqualTo(44));
    });
  });

  group('AC3 空池不渲染', () {
    testWidgets('池子为空 → 整行不渲染（不留空占位、不摆空态）', (tester) async {
      // 首页最显眼的位置摆一句「暂无推荐」等于告诉用户「这儿什么都没有」。
      await pumpHome(tester, _FakeRepo(const []));
      expect(find.byKey(const ValueKey('petRecommendationStrip')), findsNothing);
      // 其余顶部区域照旧。
      expect(find.byType(PlaceEntryRow), findsOneWidget);
      expect(find.byType(FeedTabRow), findsOneWidget);
    });

    testWidgets('取不到 → 同样整行不渲染，不摆错误态', (tester) async {
      await pumpHome(tester, _FakeRepo(const [], fail: true));
      expect(find.byKey(const ValueKey('petRecommendationStrip')), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });

  group('🔴 游客态（本 story 定的行为）', () {
    testWidgets('游客：整行不渲染', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(1)]), loggedIn: false);
      expect(find.byKey(const ValueKey('petRecommendationStrip')), findsNothing);
    });

    testWidgets('🔴 游客：**一个请求都不发**（否则 401 会弹全局强登录窗糊在首页上）', (tester) async {
      // 只钉「看不见」是不够的：先 watch 再判空，请求已经发出去了。
      final repo = _FakeRepo([_pet(1)]);
      await pumpHome(tester, repo, loggedIn: false);
      expect(repo.calls, 0);
    });

    testWidgets('游客态首页其余部分照旧（场所入口行对游客开放）', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(1)]), loggedIn: false);
      expect(find.byType(PlaceEntryRow), findsOneWidget);
      expect(find.byType(FeedTabRow), findsOneWidget);
    });
  });

  group('🛡 什么时候重新取数（code-review 2026-09-15）', () {
    testWidgets('🔴 下拉刷新要把推荐行一起刷', (tester) async {
      // 这条 provider 关掉了自动重试、又被**常驻**的首页钉着不回收：
      // 断网启动那一次失败会让整行永久消失，漏掉这个 invalidate 就只能杀进程才恢复。
      final repo = _FakeRepo([_pet(1)]);
      await pumpHome(tester, repo, feed: [
        FeedItem(
          id: 1,
          authorId: 2,
          authorDeleted: false,
          authorNickname: 'A',
          authorAvatarUrl: null,
          type: 'DAILY',
          body: 'hello',
          firstImageUrl: null,
          createdAt: DateTime.utc(2026, 9, 1),
        ),
      ]);
      expect(repo.calls, 1);

      await tester.fling(find.text('hello'), const Offset(0, 400), 1000);
      await tester.pumpAndSettle();
      expect(repo.calls, 2);
    });

    test('🔴 每一处拉黑成功都要让推荐位重算（源码级守卫）', () {
      // 「互相拉黑不互推」是服务端取数时算的 —— 漏一处的表现是「拉黑完他家的宠物卡还在，
      // 点进去撞 403」。⚠️ 场所评论区那条路径**不走** author_moderation_callbacks，
      // 所以判据是「每个真正调 block() 的地方」，不是某一个入口。
      for (final f in const [
        'lib/shared/widgets/mini_profile_sheet.dart',
        'lib/features/user_profile/presentation/public_profile_page.dart',
        'lib/features/content/presentation/author_moderation_callbacks.dart',
      ]) {
        expect(File(f).readAsStringSync().contains('invalidatePetRecommendations'), isTrue,
            reason: '$f 拉黑成功之后没有让推荐位重算');
      }
    });
  });

  group('AC4 埋点', () {
    testWidgets('从横滑行点宠物卡 → pet_card_tapped(from=explore_strip)', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(7)]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'pet_card_tapped').map((e) => e.$2),
          [{'from': 'explore_strip'}]);
    });

    testWidgets('属性里仍然只有 from', (tester) async {
      await pumpHome(tester, _FakeRepo([_pet(7)]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.firstWhere((e) => e.$1 == 'pet_card_tapped').$2!.keys, ['from']);
    });

    test('四个位置的 from 两两不同', () {
      final values = {
        kPetRecommendFromDiaryEmpty,
        kPetRecommendFromDiaryNonOwner,
        kPetRecommendFromExploreGrid,
        kPetRecommendFromExploreStrip,
      };
      expect(values, hasLength(4));
      expect(kPetRecommendFromExploreStrip, 'explore_strip');
    });
  });
}
