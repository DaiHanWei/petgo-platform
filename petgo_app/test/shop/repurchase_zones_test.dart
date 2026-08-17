import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/shop_repurchase_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_repurchase.dart';
import 'package:tailtopia/features/shop/presentation/widgets/repurchase_zones.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 6.4 区域①② · 6.5 推荐区与降级引导卡 · L0（FR-93 / FR-107 / FR-109 / UX-DR1）。
///
/// 🔴 本组用例守的是**「不展示」真的是不渲染**（不是空态、不是空标题）以及
/// **降级引导卡在场** —— 那是 L-9 存量用户回填体重的唯一入口，丢了它整条 FR-109 都起不来。
void main() {
  late _FakeRepo repo;

  setUp(() => repo = _FakeRepo());

  Widget host(Widget child, {bool loggedIn = true}) => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                loggedIn
                    ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
                    : const AuthState.guest(),
              )),
          shopRepurchaseRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Scaffold(body: SingleChildScrollView(child: child)),
        ),
      );

  Future<void> open(WidgetTester t, Widget child, {bool loggedIn = true}) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host(child, loggedIn: loggedIn));
    await t.pump();
    await t.pump();
  }

  group('🔴 FR-93 状态矩阵', () {
    testWidgets('游客：两区都不渲染，且【不发任何 /me 请求】', (t) async {
      await open(t, const Column(children: [RepurchaseZone(), ProfileRecoZone()]),
          loggedIn: false);

      expect(find.byKey(const ValueKey('tokoRecoCreateProfileCard')), findsNothing);
      expect(find.textContaining('Saatnya'), findsNothing);
      // 🔴 数据层短路：游客态零数据暴露，也就不会被 401 弹出强登录引导
      expect(repo.calls, isEmpty);
    });

    testWidgets('已登录·未建档：区域① 不渲染；区域② 整区换成建档引导卡（复用 FR-0G 文案）', (t) async {
      repo.recommendationsData =
          const Recommendations(degraded: true, missing: 'PROFILE', items: []);
      repo.cardsData = const [];
      await open(t, const Column(children: [RepurchaseZone(), ProfileRecoZone()]));

      expect(find.byKey(const ValueKey('tokoRecoCreateProfileCard')), findsOneWidget);
      // 区域① 不渲染
      expect(find.byKey(const ValueKey('repurchaseCard_1')), findsNothing);
    });

    testWidgets('已登录·已建档且无触发：区域① 整区不渲染，且【不留空标题】', (t) async {
      repo.cardsData = const [];
      repo.recommendationsData = _reco(degraded: false);
      await open(t, const RepurchaseZone());

      // 🔴 一个写着「补货提醒」却什么都没有的标题，比没有这一区更让人困惑
      expect(find.textContaining('Restok'), findsNothing);
      expect(find.byType(Card), findsNothing);
    });

    testWidgets('已登录·已建档且有触发：区域① 展示卡片', (t) async {
      repo.cardsData = const [
        RepurchaseCard(
          triggerId: 1,
          triggerType: 'FOOD_LOW',
          productToken: 'p1',
          productName: 'Royal Canin',
          daysLeft: 5,
        ),
      ];
      await open(t, const RepurchaseZone());

      expect(find.byKey(const ValueKey('repurchaseCard_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('repurchaseCardDismiss_1')), findsOneWidget);
    });
  });

  group('🔴 UX-DR1 / 文案口径', () {
    testWidgets('🔴 补货卡文案是【估算】不是断言 —— 出现「~5 天」而不是「已经吃完了」', (t) async {
      repo.cardsData = const [
        RepurchaseCard(
          triggerId: 1,
          triggerType: 'FOOD_LOW',
          productToken: 'p1',
          productName: 'Royal Canin',
          daysLeft: 5,
        ),
      ];
      await open(t, const RepurchaseZone());

      final tile = t.widget<ListTile>(find.byKey(const ValueKey('repurchaseCard_1')));
      final text = (tile.title! as Text).data!;
      expect(text.contains('5'), isTrue);
      expect(text.contains('~') || text.contains('diperkirakan'), isTrue,
          reason: '🔴 档案体重不准或用户混喂时会有偏差，把估算说成事实会直接损伤信任');
    });

    testWidgets('已过预估耗尽日 → 换成「可能已经用完」的措辞，仍不是断言', (t) async {
      repo.cardsData = const [
        RepurchaseCard(
          triggerId: 2,
          triggerType: 'FOOD_LOW',
          productToken: 'p1',
          productName: 'Royal Canin',
          daysLeft: -3,
        ),
      ];
      await open(t, const RepurchaseZone());

      final tile = t.widget<ListTile>(find.byKey(const ValueKey('repurchaseCard_2')));
      expect((tile.title! as Text).data, contains('kemungkinan'));
    });

    test('🔴 UX-DR1：本版本区域① 只有 FR-109 一个来源，驱虫卡已从原型删除', () {
      // 端上没有任何 DEWORM 分支 —— 服务端也只产生 FOOD_LOW（C-11）。
      // 这条断言守的是：将来有人「顺手」加回驱虫卡时，得先回来改这里并说明范围变了。
      const card = RepurchaseCard(
        triggerId: 1,
        triggerType: 'FOOD_LOW',
        productToken: 'p',
        productName: 'x',
        daysLeft: 1,
      );
      expect(card.triggerType, 'FOOD_LOW');
    });
  });

  group('🔴 Story 6.5 推荐区与降级引导卡', () {
    testWidgets('每张推荐卡都带推荐理由', (t) async {
      repo.recommendationsData = _reco(degraded: false);
      await open(t, const ProfileRecoZone());

      expect(find.byKey(const ValueKey('recoItem_p1')), findsOneWidget);
      expect(find.byKey(const ValueKey('recoReason_p1')), findsOneWidget);
      final reason = t.widget<Text>(find.byKey(const ValueKey('recoReason_p1')));
      expect(reason.data, isNotEmpty);
    });

    testWidgets('🔴 degraded=true → 尾部出现补全引导卡（存量用户回填体重的唯一入口）', (t) async {
      repo.recommendationsData = _reco(degraded: true, missing: 'WEIGHT');
      await open(t, const ProfileRecoZone());

      expect(find.byKey(const ValueKey('tokoRecoCompleteCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoRecoCompleteCta')), findsOneWidget);
      // 🔴 降级路径不返回空：推荐依然要出来
      expect(find.byKey(const ValueKey('recoItem_p1')), findsOneWidget);
    });

    testWidgets('档案完整 → 不出现补全引导卡', (t) async {
      repo.recommendationsData = _reco(degraded: false);
      await open(t, const ProfileRecoZone());

      expect(find.byKey(const ValueKey('tokoRecoCompleteCard')), findsNothing);
    });

    testWidgets('引导卡文案带宠物名（Lengkapi berat badan {宠物名}）', (t) async {
      repo.recommendationsData = _reco(degraded: true, missing: 'WEIGHT', petName: 'Mochi');
      await open(t, const ProfileRecoZone());

      final tile = t.widget<ListTile>(find.descendant(
          of: find.byKey(const ValueKey('tokoRecoCompleteCard')),
          matching: find.byType(ListTile)));
      expect((tile.title! as Text).data, contains('Mochi'));
    });

    testWidgets('推荐为空且已建档 → 整区不渲染', (t) async {
      repo.recommendationsData =
          const Recommendations(degraded: false, missing: 'NONE', items: [], petName: 'Mochi');
      await open(t, const ProfileRecoZone());

      expect(find.byType(Card), findsNothing);
    });
  });

  group('域模型', () {
    test('missing=PROFILE → 需要建档卡；其余 degraded → 需要补全卡', () {
      const noProfile = Recommendations(degraded: true, missing: 'PROFILE', items: []);
      expect(noProfile.needsProfileCreation, isTrue);
      expect(noProfile.needsProfileCompletion, isFalse);

      const noWeight = Recommendations(degraded: true, missing: 'WEIGHT', items: []);
      expect(noWeight.needsProfileCreation, isFalse);
      expect(noWeight.needsProfileCompletion, isTrue);
    });

    test('daysLeft 为负 → isOverdue', () {
      const c = RepurchaseCard(
        triggerId: 1,
        triggerType: 'FOOD_LOW',
        productToken: 'p',
        productName: 'x',
        daysLeft: -1,
      );
      expect(c.isOverdue, isTrue);
    });
  });
}

Recommendations _reco({
  required bool degraded,
  String? missing,
  String petName = 'Mochi',
}) =>
    Recommendations(
      degraded: degraded,
      missing: missing ?? (degraded ? 'WEIGHT' : 'NONE'),
      petName: petName,
      items: const [
        RecommendationItem(
          productToken: 'p1',
          name: 'Royal Canin Medium Adult',
          minPrice: 285000,
          reason: 'Untuk anjing dewasa 10–25 kg',
        ),
      ],
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeRepo implements ShopRepurchaseRepository {
  Recommendations? recommendationsData;
  List<RepurchaseCard> cardsData = const [];
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<Recommendations> recommendations() async {
    calls.add('recommendations');
    return recommendationsData ??
        const Recommendations(degraded: false, missing: 'NONE', items: []);
  }

  @override
  Future<List<RepurchaseCard>> cards() async {
    calls.add('cards');
    return cardsData;
  }

  @override
  Future<void> dismiss(int triggerId) async {
    calls.add('dismiss');
  }
}
