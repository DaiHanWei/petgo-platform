import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/domain/shop_banner.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v3.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'fake_shop_products.dart';

/// L0（V1.4.1 立，v1.4.2 shop Story 8.1 迁到 v3 轮播）：Toko banner 曝光 / 点击埋点。
///
/// - `toko_banner_shown`：当前页露出 > 50% 才报；一次进入页面内同一张只报一次（来回滚不重复）；
///   离开再进来重新计；`position` = **实际位次**（原恒为 1，8.1 按设计改写）。
/// - `toko_banner_tapped`：banner_id + target_type + position。
/// - analytics-pipeline Story 3.1：两者都带 `placement_type = toko_banner`、`placement_token = banner_id`（S5 运营位）。
/// - 8.1 按设计改写：旧「点品类 banner → tapped(category_list) 并切到该品类」→ 旧 `CATEGORY` 值解析为 none、
///   不可点；新增 `TOKO_HOME` → tapped(toko_home) 留在首页。
void main() {
  const localizations = [
    AppLocalizations.delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
  ];

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Iterable<Map<String, Object>?> shown() => events.where((e) => e.$1 == 'toko_banner_shown').map((e) => e.$2);
  Iterable<Map<String, Object>?> tapped() => events.where((e) => e.$1 == 'toko_banner_tapped').map((e) => e.$2);

  Widget wrap(List<ShopBanner> banners, {ValueNotifier<bool>? ticker}) => ProviderScope(
        overrides: [
          ...tokoHomeOverrides(banners: banners),
          fakeShopProducts([
            for (var i = 0; i < 12; i++)
              ShopProductSummary(token: 'tok$i', name: 'n$i', brand: 'b', minPrice: 285000),
          ]),
        ],
        child: MaterialApp(
          localizationsDelegates: localizations,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: ticker == null
              ? const TokoPageV3()
              : ValueListenableBuilder<bool>(
                  valueListenable: ticker,
                  builder: (_, on, child) => TickerMode(enabled: on, child: child!),
                  child: const TokoPageV3(),
                ),
        ),
      );

  const home = ShopBanner(
    imageUrl: 'https://cdn.example/b.jpg',
    imageW: 1600,
    imageH: 900,
    bannerId: 'bnTok123',
    target: ShopBannerTarget.tokoHome,
  );
  const second = ShopBanner(
    imageUrl: 'https://cdn.example/b2.jpg',
    imageW: 1600,
    imageH: 900,
    bannerId: 'bnTok456',
    target: ShopBannerTarget.none,
  );

  test('模型：未知 / 缺值 / 非 https 外链不可点；旧 CATEGORY → none；TOKO_HOME 恒可点；COUPON 有值可点', () {
    final legacy = ShopBanner.fromJson({'imageUrl': 'u', 'imageW': 3, 'imageH': 1});
    expect(legacy.tappable, isFalse);
    expect(legacy.bannerId, isNull);
    final cat = ShopBanner.fromJson({'imageUrl': 'u', 'targetType': 'CATEGORY', 'targetValue': 'CAMILAN'});
    expect(cat.target, ShopBannerTarget.none, reason: '品类跳转已下线（D-16 / D-17），旧值落 none');
    expect(cat.tappable, isFalse);
    expect(ShopBanner.fromJson({'imageUrl': 'u', 'targetType': 'TOKO_HOME'}).tappable, isTrue,
        reason: 'TOKO_HOME 后端不下发值，仍可点（只记一次点击、留在首页）');
    expect(ShopBanner.fromJson({'imageUrl': 'u', 'targetType': 'COUPON', 'targetValue': 'cpT'}).tappable, isTrue);
    expect(ShopBanner.fromJson({'imageUrl': 'u', 'targetType': 'COUPON'}).tappable, isFalse);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.external, targetValue: 'http://a.b').tappable,
        isFalse);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.external, targetValue: 'https://a.b').tappable,
        isTrue);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.product).tappable, isFalse);
  });

  testWidgets('露出即报一次 shown（banner_id + position=1）；来回滚动不重复', (t) async {
    await t.pumpWidget(wrap([home]));
    await t.pump();
    await t.pump();
    expect(shown().toList(), [
      {'banner_id': 'bnTok123', 'position': 1, 'placement_type': 'toko_banner', 'placement_token': 'bnTok123'}
    ]);
    await t.drag(find.byType(CustomScrollView), const Offset(0, -600));
    await t.pump();
    await t.drag(find.byType(CustomScrollView), const Offset(0, 600));
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
  });

  testWidgets('🔴 position 取实际位次：滑到第 2 张报 position=2', (t) async {
    await t.pumpWidget(wrap([home, second]));
    await t.pump();
    await t.pump();
    await t.drag(find.byKey(const ValueKey('tokoBannerPageView')), const Offset(-600, 0));
    await t.pumpAndSettle();
    expect(shown().toList(), [
      {'banner_id': 'bnTok123', 'position': 1, 'placement_type': 'toko_banner', 'placement_token': 'bnTok123'},
      {'banner_id': 'bnTok456', 'position': 2, 'placement_type': 'toko_banner', 'placement_token': 'bnTok456'},
    ]);
  });

  testWidgets('离开页面（TickerMode 关）再回来 → 重新计一次', (t) async {
    final ticker = ValueNotifier(true);
    await t.pumpWidget(wrap([home], ticker: ticker));
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
    ticker.value = false;
    await t.pump();
    ticker.value = true;
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(2));
  });

  testWidgets('滚出视口后离开再回来：露出不足 50% → 不报 shown', (t) async {
    final ticker = ValueNotifier(true);
    await t.pumpWidget(wrap([home], ticker: ticker));
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
    await t.drag(find.byType(CustomScrollView), const Offset(0, -900));
    await t.pump();
    ticker.value = false;
    await t.pump();
    ticker.value = true;
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
  });

  testWidgets('点 TOKO_HOME banner → tapped(toko_home)，留在首页', (t) async {
    await t.pumpWidget(wrap([home]));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
    await t.pump();
    expect(tapped().toList(), [
      {
        'banner_id': 'bnTok123',
        'target_type': 'toko_home',
        'position': 1,
        'placement_type': 'toko_banner',
        'placement_token': 'bnTok123',
      }
    ]);
    expect(find.byType(TokoPageV3), findsOneWidget);
  });

  testWidgets('analytics-pipeline 3.1：旧后端无 bannerId → tapped 不带 banner_id / placement_token、仍带 placement_type；不报 shown',
      (t) async {
    await t.pumpWidget(wrap([
      const ShopBanner(imageUrl: 'https://cdn.example/b.jpg', imageW: 1600, imageH: 900, target: ShopBannerTarget.tokoHome),
    ]));
    await t.pump();
    await t.pump();
    expect(shown(), isEmpty, reason: '无 token 的 banner 无法按运营位归属，曝光不报（原行为）');
    await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
    await t.pump();
    expect(tapped().toList(), [
      {'target_type': 'toko_home', 'position': 1, 'placement_type': 'toko_banner'}
    ]);
  });

  testWidgets('快速双击只算一次点击', (t) async {
    await t.pumpWidget(wrap([home]));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
    await t.pump(const Duration(milliseconds: 100));
    await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
    await t.pump();
    expect(tapped(), hasLength(1));
  });

  testWidgets('不可点的 banner：点了不报 tapped', (t) async {
    await t.pumpWidget(wrap([const ShopBanner(imageUrl: 'https://cdn.example/b.jpg', bannerId: 'x')]));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
    await t.pump();
    expect(tapped(), isEmpty);
  });

  test('事件名与属性词表：snake_case、无 PII 键', () {
    for (final v in ShopBannerTarget.values) {
      expect(v.analytics, matches(RegExp(r'^[a-z_]+$')));
    }
    expect(ShopBannerTarget.values.map((v) => v.analytics).toSet(),
        {'none', 'product_detail', 'coupon', 'toko_home', 'external_link'});
  });
}
