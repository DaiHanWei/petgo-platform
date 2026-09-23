import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/presentation/author_moderation_callbacks.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_recommendation_grid.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/empty_state.dart';

import '../support/fake_feed_repository.dart';

/// L0：推荐宠物卡与 2 列网格（V1.3.0 batch-b1 Story 4.1 · AC4/AC5/AC6/AC7）。
///
/// <h3>🔴 本文件的重心是 AC4 那句「两个不同字段、不同来源」</h3>
/// 大图 = 该宠物最近一张**公开照片**（帖子配图）；左下角小圆头像 = **宠物档案自身**的头像。
/// UI 稿 UX-DR15 专门点过 —— 做成同一张图重复摆放是明显 bug，而这在 L0 完全可查。
class _FakeRepo implements PetRecommendationRepository {
  _FakeRepo(this.pets, {this.fail = false});

  final List<RecommendedPet> pets;
  final bool fail;
  int calls = 0;

  /// 每一页的游标（本 fake 只回一页：Diary 那两个位置不翻页）。
  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    calls++;
    if (fail) throw Exception('boom');
    return RecommendedPetPage(items: pets, hasMore: false);
  }
}

RecommendedPet _pet(
  int id, {
  String avatar = 'https://cdn/avatar.jpg',
  String? cover = 'https://cdn/cover.jpg',
  int days = 238,
  DateTime? birthday,
  String petType = 'CAT',
}) =>
    RecommendedPet(
      petId: id,
      name: 'Mochi$id',
      avatarUrl: avatar,
      petType: petType,
      companionDays: days,
      birthday: birthday,
      coverImageUrl: cover,
    );

LoginResponse _user(int id) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(id: id, onboardingCompleted: true),
    );

/// Diary 未建档态（状态 A + 无档案）那一屏的桩：只需要 auth + 档案两个 provider。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

Widget _wrapDiaryEmptyProfile(PetRecommendationRepository repo) => ProviderScope(
      overrides: [
        petRecommendationRepositoryProvider.overrideWithValue(repo),
        authControllerProvider.overrideWith(() => _TestAuthController(const AuthState(
              status: AuthStatus.authenticated,
              role: 'USER',
              profile: UserProfile(petStatus: 'HAS_PET'),
            ))),
        petProfileProvider.overrideWith((ref) async => null),
      ],
      child: const MaterialApp(
        locale: Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: GrowthArchivePage(),
      ),
    );

void main() {
  final captured = <(String, Map<String, Object>?)>[];

  setUp(() {
    captured.clear();
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  /// `testWidgets` 的变体：给 body 装一个「任何图片请求都回 1x1 PNG」的 HttpClient。
  ///
  /// ⚠️ **必须在 body 结束前复位**：framework 在 body 跑完、tearDown 之前就会校验
  /// 「painting 调试变量有没有被改过」，放 tearDown 里复位来不及（实测每个用例都红）。
  /// 🔴 为什么非要真能解码：本 story 的核心断言是 AC4 的「两个不同的图片 URL」——
  /// 把 URL 都换成 null 就把要验的东西验掉了。
  void testWidgetsWithImages(String description, Future<void> Function(WidgetTester) body) {
    testWidgets(description, (tester) async {
      debugNetworkImageHttpClientProvider = () => _FakeHttpClient();
      try {
        await body(tester);
      } finally {
        debugNetworkImageHttpClientProvider = null;
      }
    });
  }

  /// 只为验 `diary_visitor_viewed`（它在 initState 里报）而把访客视图挂起来。
  ///
  /// ⚠️ 打桩成**全部抛错**的仓储：本用例不关心页面内容，而真 dio 会留下 pending timer
  /// 把用例带崩。错误态下 initState 照样已经跑过了。
  Future<void> pumpVisitorView(WidgetTester tester, {String? from}) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [timelineRepositoryProvider.overrideWithValue(_ThrowingTimelineRepo())],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: VisitorArchiveView(
          scope: const ArchiveScope.inAppVisitor(7),
          analyticsFrom: from,
        ),
      ),
    ));
    await tester.pump();
  }

  /// 造一个带路由的宿主，好让点击真的能 push 出去。
  Future<ProviderContainer> pumpGrid(
    WidgetTester tester,
    PetRecommendationRepository repo, {
    bool loggedIn = true,
  }) async {
    final container = ProviderContainer(overrides: [
      petRecommendationRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    if (loggedIn) {
      container.read(authControllerProvider.notifier).applyLogin(_user(1));
    }
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
            GoRoute(path: '/pets/:petId', builder: (_, _) => const Scaffold(body: Text('visitor'))),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return container;
  }

  group('AC4 卡片字段', () {
    testWidgetsWithImages('🔴 大图与小圆头像是两张不同的图', (tester) async {
      await pumpGrid(tester, _FakeRepo([_pet(7)]));
      // 大图存在（帖子配图）……
      expect(find.byKey(const ValueKey('recommendedPetCover_7')), findsOneWidget);
      // ……小圆头像也存在（宠物档案头像），两者是两个 widget。
      expect(find.byKey(const ValueKey('recommendedPetAvatar_7')), findsOneWidget);

      final cover = tester.widget<Image>(find.byKey(const ValueKey('recommendedPetCover_7')));
      final coverUrl = (cover.image as dynamic).url as String;
      expect(coverUrl, contains('cover.jpg'));
      expect(coverUrl, isNot(contains('avatar.jpg')));
    });

    testWidgetsWithImages('🛡 没有公开照片时渲染占位，**绝不拿头像顶上去**', (tester) async {
      // 拿头像顶上去正好做成了「同一张图重复摆放」的样子（UX-DR15 点名的 bug）。
      await pumpGrid(tester, _FakeRepo([_pet(7, cover: null)]));
      expect(find.byKey(const ValueKey('recommendedPetCoverPlaceholder_7')), findsOneWidget);
      expect(find.byKey(const ValueKey('recommendedPetCover_7')), findsNothing);
      // 小圆头像照旧在（它是另一个字段）。
      expect(find.byKey(const ValueKey('recommendedPetAvatar_7')), findsOneWidget);
    });

    // UI 稿 E2：名字下面只有**一行** micro 灰字「物种 · 陪伴天数」（Kucing · 238 hari）。
    testWidgetsWithImages('陪伴天数与物种同一行（「Kucing · 238 hari」）', (tester) async {
      await pumpGrid(tester, _FakeRepo([_pet(7, days: 238)]));
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      final meta = tester.widget<Text>(find.byKey(const ValueKey('recommendedPetDays_7')));
      expect(meta.data, '${l10n.petTypeCat} · ${l10n.petCardDays(238)}');
      // 灰字（micro 默认色），不再是单独一行品牌色。
      expect(meta.style?.color, AppColors.textTertiary);
    });

    testWidgetsWithImages('卡下文字只有两行：名字 + 「物种 · 天数」，不再单列年龄', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      await pumpGrid(tester, _FakeRepo([
        _pet(7, birthday: DateTime.now().subtract(const Duration(days: 5))),
      ]));
      final card = find.byKey(const ValueKey('recommendedPet_7'));
      expect(find.descendant(of: card, matching: find.byType(Text)), findsNWidgets(2));
      expect(find.text('Mochi7'), findsOneWidget);
      expect(find.text('${l10n.petTypeCat} · ${l10n.petCardDays(238)}'), findsOneWidget);
      // 年龄不在卡上（UI 稿 E2），更不会出现「0 岁 0 月」。
      expect(find.textContaining(l10n.growthArchiveAge(0, 0)), findsNothing);
    });

    testWidgetsWithImages('物种不认识时只显示天数，不留下一个孤零零的分隔符', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      await pumpGrid(tester, _FakeRepo([_pet(7, petType: 'UNKNOWN')]));
      final meta = tester.widget<Text>(find.byKey(const ValueKey('recommendedPetDays_7')));
      expect(meta.data, l10n.petCardDays(238));
    });
  });

  group('AC5 点击落点', () {
    testWidgetsWithImages('点卡片 → 站内访客入口 /pets/{petId}（复用 Story 2.3，不新建通道）', (tester) async {
      await pumpGrid(tester, _FakeRepo([_pet(7)]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(find.text('visitor'), findsOneWidget);
    });

    testWidgetsWithImages('游客点卡片 → 强登录引导，**不跳**访客视图', (tester) async {
      // 站内访客接口仅登录可用，先跳过去只会拿到 401（与公开主页那张宠物卡同一套）。
      await pumpGrid(tester, _FakeRepo([_pet(7)]), loggedIn: false);
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(find.text('visitor'), findsNothing);
    });
  });

  group('AC6 2 列网格', () {
    testWidgetsWithImages('两列', (tester) async {
      await pumpGrid(tester, _FakeRepo([for (int i = 1; i <= 4; i++) _pet(i)]));
      final grid = tester.widget<GridView>(find.byType(GridView));
      final delegate = grid.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount;
      expect(delegate.crossAxisCount, 2);
      for (int i = 1; i <= 4; i++) {
        expect(find.byKey(ValueKey('recommendedPet_$i')), findsOneWidget);
      }
    });

    testWidgetsWithImages('🛡 池子为空 → 整块不渲染（这一屏的主体是去建档）', (tester) async {
      await pumpGrid(tester, _FakeRepo(const []));
      expect(find.byKey(const ValueKey('petRecommendationGrid')), findsNothing);
    });

    testWidgetsWithImages('🛡 取数失败 → 同样整块不渲染，不摆错误态', (tester) async {
      // 摆一个错误态会让用户以为「我这个页面坏了」，而他要做的那件事（建档）明明是好的。
      await pumpGrid(tester, _FakeRepo(const [], fail: true));
      expect(find.byKey(const ValueKey('petRecommendationGrid')), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgetsWithImages('取数失败不自动重试（否则页面在有网格与没网格之间横跳）', (tester) async {
      final repo = _FakeRepo(const [], fail: true);
      await pumpGrid(tester, repo);
      await tester.pump(const Duration(seconds: 3));
      expect(repo.calls, 1);
    });

    // code-review 2026-09-15：大图锁死 1:1 时，网格的固定 childAspectRatio 撑不住
    // 「名字 + 物种·年龄 + 陪伴天数」三行，最窄的 360dp 上底部被裁 7.8px。
    // 现在大图吃剩余高度（Expanded），任何宽度都不会溢出。
    // ⚠️ 三个宽度**各一个用例**：一个用例里反复 pumpWidget 会把上一棵树的 Consumer 卸下来，
    //    autoDispose 的回收任务留成 pending timer 把用例带红（与被测行为无关）。
    for (final width in <double>[360, 375, 390]) {
      testWidgetsWithImages('🛡 窄屏 ${width.toInt()}dp 卡片不溢出，陪伴天数不被裁掉', (tester) async {
        addTearDown(tester.view.reset);
        tester.view.devicePixelRatio = 1.0;
        tester.view.physicalSize = Size(width, 800);
        await pumpGrid(tester, _FakeRepo([_pet(1), _pet(2)]));
        expect(find.byKey(const ValueKey('recommendedPetDays_1')), findsOneWidget);
        expect(tester.takeException(), isNull, reason: '${width.toInt()}dp 上卡片溢出了');
      });
    }

    testWidgetsWithImages('「查看全部」由 Story 4.3 一并加上（4.1 交付时刻意没有它）', (tester) async {
      // 🔴 本条随 4.3 一起从「不许有」翻成「必须有」：
      //    4.1 交付时集合页还不存在，挂一个点不动的入口比没有更糟；
      //    4.3 把集合页与这个入口**同时**加上（AC1 明写，避免前向依赖）。
      //    位置与跳转由 4.3 的用例验（pet_recommendation_list_page_test.dart）。
      await pumpGrid(tester, _FakeRepo([_pet(7)]));
      expect(find.byKey(const ValueKey('petRecommendSeeAll')), findsOneWidget);
    });
  });

  group('AC6 Diary 未建档态：版面只在真有卡时才换', () {
    Finder guidanceIn(Type container) => find.ancestor(
        of: find.byKey(const ValueKey('growthCreateButton')), matching: find.byType(container));

    testWidgetsWithImages('🔴 池子为空 → 这一屏与改动前逐像素相同（Center 居中，不换成滚动容器）',
        (tester) async {
      // code-review 2026-09-15：无条件换版面等于拿一个常态（新站池子几乎必然为空）
      // 换一个边角态。与 PetRecommendationGrid 的「整块不渲染」是同一条纪律的两半。
      await tester.pumpWidget(_wrapDiaryEmptyProfile(_FakeRepo(const [])));
      await tester.pumpAndSettle();
      expect(guidanceIn(Center), findsOneWidget);
      expect(guidanceIn(SingleChildScrollView), findsNothing);
      expect(find.byType(PetRecommendationGrid), findsNothing);
    });

    testWidgetsWithImages('取数失败也不换版面', (tester) async {
      await tester.pumpWidget(_wrapDiaryEmptyProfile(_FakeRepo(const [], fail: true)));
      await tester.pumpAndSettle();
      expect(guidanceIn(Center), findsOneWidget);
      expect(guidanceIn(SingleChildScrollView), findsNothing);
    });

    testWidgetsWithImages('真有卡 → 换可滚动容器，且原有两个操作一个不删（AC6）', (tester) async {
      await tester.pumpWidget(_wrapDiaryEmptyProfile(_FakeRepo([_pet(7)])));
      await tester.pumpAndSettle();
      expect(guidanceIn(SingleChildScrollView), findsOneWidget);
      // AC6：「+ 建档」与「Ubah status」原样保留、一个不删。
      expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('growthChangeStatusButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('petRecommendationGrid')), findsOneWidget);
    });

    testWidgetsWithImages('UI 稿 E1：有卡时引导压成紧凑横条 + 分隔线，主按钮在「Ubah status」之上',
        (tester) async {
      await tester.pumpWidget(_wrapDiaryEmptyProfile(_FakeRepo([_pet(7)])));
      await tester.pumpAndSettle();
      final l10n = AppLocalizations.of(tester.element(find.byType(Scaffold).first));
      // 紧凑版不删字：标题与「为什么先建档」副文案都还在。
      expect(find.text(l10n.growthArchiveEmptyTitle), findsOneWidget);
      expect(find.text(l10n.growthArchiveEmptyBody), findsOneWidget);
      expect(find.byType(EmptyState), findsNothing, reason: '有卡时不再是竖排大空态');
      expect(find.byType(Divider), findsOneWidget);
      final create = tester.getRect(find.byKey(const ValueKey('growthCreateButton')));
      final change = tester.getRect(find.byKey(const ValueKey('growthChangeStatusButton')));
      // 各占一行，且拉开距离（AC6：避免手滑误触）。
      expect(change.top, greaterThanOrEqualTo(create.bottom + 8));
    });
  });

  group('AC4 图挂了的兜底', () {
    testWidgets('🛡 大图加载失败 → 落回爪印占位（不是空白一片）', (tester) async {
      // 无 errorBuilder 时 Image 会画一块白、并把 NetworkImageLoadException 抛给
      // FlutterError —— 表现是「卡片上方一块白，没人知道为什么」（code-review 2026-09-15）。
      debugNetworkImageHttpClientProvider = () => _FailingHttpClient();
      try {
        // ⚠️ URL 要唯一：ImageCache 跨用例共享，撞上前面成功解码过的 URL 就验不到失败分支。
        final url = 'https://cdn/broken-${DateTime.now().microsecondsSinceEpoch}.jpg';
        await pumpGrid(tester, _FakeRepo([_pet(7, cover: url)]));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('recommendedPetCoverPlaceholder_7')), findsOneWidget);
      } finally {
        debugNetworkImageHttpClientProvider = null;
      }
      tester.takeException(); // 图挂了本身不该把用例带红
    });
  });

  group('AC3 拉黑收尾要让推荐位重算', () {
    testWidgets('🔴 在别处拉黑某人 → 推荐池 invalidate 重取（那几条过滤只在服务端算）',
        (tester) async {
      // code-review 2026-09-15：provider 常驻 + 从不 invalidate 的表现是
      // 「在 Feed 里拉黑某人后回到 Diary，他家的宠物卡还在，点进去撞 403」。
      final repo = _FakeRepo([_pet(7)]);
      late WidgetRef capturedRef;
      await tester.pumpWidget(ProviderScope(
        overrides: [
          petRecommendationRepositoryProvider.overrideWithValue(repo),
          feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Consumer(builder: (context, ref, _) {
            capturedRef = ref;
            // 这一屏一直活着（拉黑发生在别的屏）——  autoDispose 兜不到，只能靠 invalidate。
            ref.watch(petRecommendationsProvider);
            return const Scaffold(body: SizedBox.shrink());
          }),
        ),
      ));
      await tester.pumpAndSettle();
      expect(repo.calls, 1);

      onAuthorHidden(capturedRef, 110)();
      await tester.pumpAndSettle();
      expect(repo.calls, 2, reason: '拉黑收尾之后必须重取推荐池');
    });

    test('🔴 provider 必须是 autoDispose（离开这一屏再回来要重取）', () {
      // 常驻的表现是拉黑/封号/注销过滤只在本进程第一次取数时生效。
      final src = File('lib/features/profile/data/pet_recommendation_repository.dart')
          .readAsStringSync();
      expect(src.contains('FutureProvider.autoDispose<List<RecommendedPet>>'), isTrue);
    });
  });

  group('AC7 埋点', () {
    testWidgetsWithImages('点卡片 → pet_card_tapped(from=diary_empty)', (tester) async {
      await pumpGrid(tester, _FakeRepo([_pet(7)]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'pet_card_tapped').map((e) => e.$2),
          [{'from': 'diary_empty'}]);
    });

    testWidgetsWithImages('🔴 属性里只有 from —— 没有宠物名、没有图片 URL', (tester) async {
      // 埋点层的兜底黑名单会把 name 整键丢掉，但 AC 的要求是**源头就不要传**。
      await pumpGrid(tester, _FakeRepo([_pet(7)]));
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      final props = captured.firstWhere((e) => e.$1 == 'pet_card_tapped').$2;
      expect(props!.keys, ['from']);
    });

    testWidgetsWithImages('游客点了也报（漏斗的分子不该少掉游客那一截）', (tester) async {
      await pumpGrid(tester, _FakeRepo([_pet(7)]), loggedIn: false);
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'pet_card_tapped'), hasLength(1));
    });

    testWidgetsWithImages('打开访客视图 → diary_visitor_viewed(from=diary_empty)', (tester) async {
      // AC7 的第二个事件。⚠️ 它在 VisitorArchiveView 的 initState 里报 ——
      //    放 build 里的话一次浏览会随数据到达报出三四条。
      await pumpVisitorView(tester, from: kPetRecommendFromDiaryEmpty);
      await tester.pump();
      expect(captured.where((e) => e.$1 == 'diary_visitor_viewed').map((e) => e.$2),
          [{'from': 'diary_empty'}]);
    });

    testWidgetsWithImages('分享链接落地不带 from → 一条都不报', (tester) async {
      // 分享链接落地不算站内访客浏览。⚠️ 公开主页宠物卡已于 bug 20260922-534 补上
      // from=profile（E-17 取值之一），不再属于「不报」的这一类。
      await pumpVisitorView(tester);
      await tester.pump();
      expect(captured.where((e) => e.$1 == 'diary_visitor_viewed'), isEmpty);
    });

    test('from 取值是常量而不是散落字面量', () {
      expect(kPetRecommendFromDiaryEmpty, 'diary_empty');
    });
  });
}

/// 任何图片请求都回一张 1x1 透明 PNG —— 让 NetworkImage 在 widget test 里能解码成功。
/// 1x1 透明 PNG 的字节。
final Uint8List _tinyPng = Uint8List.fromList(<int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
  0x42, 0x60, 0x82,
]);

class _FakeHttpClient implements HttpClient {
  @override
  Future<HttpClientRequest> getUrl(Uri url) async => _FakeHttpClientRequest();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的 HttpClient 成员');
}

class _FakeHttpClientRequest implements HttpClientRequest {
  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() async => _FakeHttpClientResponse();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的请求成员');
}

class _FakeHttpClientResponse implements HttpClientResponse {
  @override
  int get statusCode => HttpStatus.ok;

  @override
  int get contentLength => _tinyPng.length;

  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;

  @override
  StreamSubscription<List<int>> listen(void Function(List<int>)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    return Stream<List<int>>.fromIterable(<List<int>>[_tinyPng])
        .listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的响应成员');
}

/// 任何图片请求都回 404 —— 用来验「图挂了落回占位」。
class _FailingHttpClient implements HttpClient {
  @override
  Future<HttpClientRequest> getUrl(Uri url) async => _FailingHttpClientRequest();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的 HttpClient 成员');
}

class _FailingHttpClientRequest implements HttpClientRequest {
  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() async => _FailingHttpClientResponse();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的请求成员');
}

class _FailingHttpClientResponse implements HttpClientResponse {
  @override
  int get statusCode => HttpStatus.notFound;

  @override
  int get contentLength => 0;

  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;

  @override
  StreamSubscription<List<int>> listen(void Function(List<int>)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    return const Stream<List<int>>.empty()
        .listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的响应成员');
}

class _FakeHttpHeaders implements HttpHeaders {
  @override
  noSuchMethod(Invocation invocation) => null;
}

/// 访客视图那几个 provider 的桩：一律抛错（本文件只验 initState 里的埋点）。
class _ThrowingTimelineRepo implements TimelineRepository {
  @override
  noSuchMethod(Invocation invocation) => Future<Never>.error(Exception('stub'));
}
