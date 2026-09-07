import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_repurchase_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_repurchase.dart';
import 'package:tailtopia/features/shop/presentation/widgets/repurchase_zones_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 复购触发卡 + 档案推荐区 · **设计稿版式**（V1.4.0 第 3 批）。
///
/// v1 版式的用例在 `repurchase_zones_test.dart`，两套互不影响。
///
/// 本类看的是**信任面**的规则 —— 它们比转化更要紧：
/// 推算依据缺一不可、泛推荐不得伪装成个性化、埋点不得带 PII。
void main() {
  Widget host(Widget child, {
    List<RepurchaseCard> cards = const [],
    Recommendations? reco,
    // 🔴 默认带上**真实设备的状态栏 inset**。
    //    这一项原先恒为零（`MediaQueryData()` 的默认值），于是
    //    「内层 GridView 会自动继承 MediaQuery.padding」这个 Flutter 行为
    //    在测试里根本不会发生 —— 单测全绿，真机上白白多出一条状态栏高度的空白
    //    （2026-08-19 模拟器实测 54dp）。夹具不还原真实 inset，护栏就守了个空。
    EdgeInsets viewPadding = const EdgeInsets.only(top: 54, bottom: 24),
  }) =>
      ProviderScope(
        overrides: [
          repurchaseCardsProvider.overrideWith((ref) async => cards),
          recommendationsProvider.overrideWith((ref) async =>
              reco ?? const Recommendations(degraded: true, missing: 'GUEST', items: [])),
        ],
        child: MaterialApp(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: MediaQuery(
            data: MediaQueryData(size: const Size(411, 891), padding: viewPadding),
            child: Scaffold(body: SingleChildScrollView(child: child)),
          ),
        ),
      );

  RepurchaseCard card({
    int id = 1,
    int daysLeft = 5,
    int? dailyGrams = 45,
    int? remainingGrams = 225,
    DateTime? purchasedOn,
    // ⚠️ 单独一个开关，而不是「传 null 即缺失」——
    //    `purchasedOn ?? 默认值` 会让显式传 null 落回默认值，
    //    那条「缺购买日期 → 整卡不渲染」的断言就变成空的了（本类第一版就是这么假绿的）。
    bool noPurchaseDate = false,
  }) =>
      RepurchaseCard(
        triggerId: id,
        triggerType: 'FOOD_LOW',
        productToken: 'prod1',
        productName: 'Royal Canin Adult Dog 2kg',
        petName: 'Miko',
        daysLeft: daysLeft,
        dailyGrams: dailyGrams,
        remainingGrams: remainingGrams,
        purchasedOn: noPurchaseDate ? null : (purchasedOn ?? DateTime(2026, 7, 22)),
      );

  group('🔴 触发卡：推算依据缺一不可', () {
    testWidgets('三个数齐全 → 渲染，且依据行含全部三项', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(), cards: [card()]));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBasis_1')), findsOneWidget);
      final basis = tester.widget<Text>(find.byKey(const ValueKey('recoBasis_1')));
      // 日均用量 · 剩余量 · 购买日期 —— 三个数都要在同一行里
      expect(basis.data, contains('45'));
      expect(basis.data, contains('225'));
      expect(basis.data, contains('22 Jul'));
    });

    testWidgets('🔴 缺日均用量 → 整卡不渲染（不退化成没有依据的推荐）', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(),
          cards: [card(dailyGrams: null)]));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBasis_1')), findsNothing);
      expect(find.byKey(const ValueKey('recoBuyAgain_1')), findsNothing,
          reason: '依据是它区别于普通广告位的唯一凭据 —— 缺了就整卡不给');
    });

    testWidgets('缺购买日期 → 整卡不渲染', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(),
          cards: [card(noPurchaseDate: true)]));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBuyAgain_1')), findsNothing);
    });

    testWidgets('依据齐全与不齐的卡混在一起 → 只渲染齐全的那张', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(),
          cards: [card(id: 1), card(id: 2, remainingGrams: null)]));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBuyAgain_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('recoBuyAgain_2')), findsNothing);
    });
  });

  group('🔴 触发卡：文案给估算而非断言', () {
    testWidgets('未过期 → 「±N 天」', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(),
          cards: [card(daysLeft: 5)]));
      await tester.pumpAndSettle();

      expect(find.textContaining('±5'), findsOneWidget,
          reason: '档案体重不准或混喂时会有偏差 —— 把估算说成事实会直接损伤信任');
    });

    testWidgets('已过预估耗尽日 → 「可能已经吃完」而不是「已经吃完了」', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(),
          cards: [card(daysLeft: -2)]));
      await tester.pumpAndSettle();

      expect(find.textContaining('kemungkinan'), findsOneWidget);
    });

    testWidgets('🔴 可解释性入口是文字不是图标', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(), cards: [card()]));
      await tester.pumpAndSettle();

      // 一个问号图标传达不了「这里能看到算法口径和关闭入口」（合规与信任要求）。
      expect(find.text('Kenapa ini muncul?'), findsOneWidget);
    });
  });

  group('🔒 埋点不得带 PII', () {
    testWidgets('曝光只带 trigger_type 与 card_source，不带宠物名/克数', (tester) async {
      final events = <(String, Map<String, Object?>?)>[];
      Analytics.debugCaptureSink = (n, p) => events.add((n, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(source: 'diary'),
          cards: [card()]));
      await tester.pumpAndSettle();

      final shown = events.where((e) => e.$1 == 'toko_repurchase_card_shown');
      expect(shown, hasLength(1));
      expect(shown.first.$2?.keys.toSet(), {'trigger_type', 'card_source'},
          reason: '宠物名与克数都是档案数据，不能进三方分析平台');
      expect(shown.first.$2?['card_source'], 'diary');
    });
  });

  group('🔴 档案推荐区：泛推荐不得伪装成个性化', () {
    Recommendations reco({int items = 2, bool degraded = true}) => Recommendations(
          degraded: degraded,
          missing: 'NONE',
          petName: 'Miko',
          items: [
            for (var i = 0; i < items; i++)
              RecommendationItem(
                productToken: 'p$i',
                name: 'Produk $i',
                minPrice: 185000,
                reason: 'Untuk anjing dewasa 10–25 kg',
              ),
          ],
        );

    testWidgets('恒走降级态：MODE DASAR 角标必须在', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: reco()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBasicBadge')), findsOneWidget,
          reason: '推荐理由是规则维度（年龄/体型）而非某条记录 —— '
              '挂上「来自你的记录」的标签就是把泛推荐伪装成个性化');
    });

    testWidgets('🔴 降级说明必须给脱困路径，不写「数据不足」了事', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: reco()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoDegradedHint')), findsOneWidget);
      expect(find.textContaining('Tambah 3 catatan lagi'), findsOneWidget);
    });

    testWidgets('🔴 降级态的卡片不带来源标签', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: reco()));
      await tester.pumpAndSettle();

      // 它本来就不是按某条记录挑的 —— 带标签即撒谎。
      expect(find.text('Untuk anjing dewasa 10–25 kg'), findsNothing);
    });

    testWidgets('游客 → 整区不渲染（不是空态、不是建档卡）', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoBasicBadge')), findsNothing);
      expect(find.byKey(const ValueKey('recoCreateProfileCardV2')), findsNothing,
          reason: '游客看到建档卡，点下去就是登录墙 —— FR-93A 要防的正是这个');
    });

    testWidgets('已登录未建档 → 整区换成建档引导卡', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(),
          reco: const Recommendations(degraded: true, missing: 'PROFILE', items: [])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('recoCreateProfileCardV2')), findsOneWidget);
    });

    testWidgets('🔴 不放一个没有持久化的假开关', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: reco()));
      await tester.pumpAndSettle();

      // 设置项没有服务端持久化。一个关了之后重装 app 又自己打开的开关，比没有更伤信任。
      expect(find.byType(Switch), findsNothing);
    });
  });

  group('🔴 降级态网格：不得白吞一条状态栏', () {
    Recommendations degradedReco() => const Recommendations(
          degraded: true,
          missing: 'RECORDS',
          petName: 'Miko',
          items: [
            RecommendationItem(
                productToken: 'p0', name: 'Produk 0', minPrice: 185000, reason: 'x'),
            RecommendationItem(
                productToken: 'p1', name: 'Produk 1', minPrice: 78000, reason: 'x'),
          ],
        );

    testWidgets('说明块与第一张卡之间只有设计给的间距', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: degradedReco()));
      await tester.pumpAndSettle();

      final gap = tester.getTopLeft(find.text('Produk 0')).dy -
          tester.getBottomLeft(find.byKey(const ValueKey('recoDegradedHint'))).dy;

      // 代码给的是 11dp + 图 96dp + 6dp；容差放到 130dp。
      // 带 bug 时会再多一整条状态栏（+54dp）。
      expect(gap, lessThan(130),
          reason: 'GridView 的 padding 为 null 时会把 MediaQuery.padding 当自己的内边距');
    });

    testWidgets('网格显式声明 padding（null 才是 bug 本身）', (tester) async {
      await tester.pumpWidget(host(const ProfileRecoZoneV2(), reco: degradedReco()));
      await tester.pumpAndSettle();

      final grid = tester.widget<GridView>(find.byType(GridView));
      expect(grid.padding, isNotNull,
          reason: 'padding 缺省 → BoxScrollView 自动套用 MediaQuery 的主轴 padding');
      expect(grid.padding, EdgeInsets.zero);
    });
  });

  group('布局不得溢出', () {
    testWidgets('触发卡 · 411dp', (tester) async {
      await tester.pumpWidget(host(const RepurchaseTriggerCardV2(), cards: [card()]));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('降级推荐区 · 411dp', (tester) async {
      await tester.pumpWidget(host(
        const ProfileRecoZoneV2(),
        reco: const Recommendations(
          degraded: true,
          missing: 'NONE',
          petName: 'Miko',
          items: [
            RecommendationItem(
                productToken: 'p1',
                name: 'Royal Canin Adult Dog Premium',
                minPrice: 185000,
                reason: 'x'),
            RecommendationItem(
                productToken: 'p2', name: 'Whiskas', minPrice: 78000, reason: 'x'),
          ],
        ),
      ));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}
