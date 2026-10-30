import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_banner.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'fake_shop_products.dart';

/// L0（V1.4.1）：Toko banner 曝光 / 点击埋点 + 单张可点击。
///
/// - `toko_banner_shown`：露出 > 50% 才报；一次进入页面内只报一次（来回滚不重复）；离开再进来重新计。
/// - `toko_banner_tapped`：banner_id + target_type + position（恒 1）。
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

  Widget wrap(ShopBanner banner, {ValueNotifier<bool>? ticker}) => ProviderScope(
        overrides: [
          shopBannerProvider.overrideWith((ref) async => banner),
          fakeShopProducts([
            for (var i = 0; i < 12; i++)
              ShopProductSummary(token: 'tok$i', name: 'n$i', brand: 'b', minPrice: 285000),
          ]),
        ].cast(),
        child: MaterialApp(
          localizationsDelegates: localizations,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: ticker == null
              ? const TokoPageV2()
              : ValueListenableBuilder<bool>(
                  valueListenable: ticker,
                  builder: (_, on, child) => TickerMode(enabled: on, child: child!),
                  child: const TokoPageV2(),
                ),
        ),
      );

  const banner = ShopBanner(
    imageUrl: 'https://cdn.example/b.jpg',
    imageW: 1600,
    imageH: 900,
    bannerId: 'bnTok123',
    target: ShopBannerTarget.category,
    targetValue: 'CAMILAN',
  );

  test('模型：未知 / 缺值 / 非 https 外链一律不可点；旧后端无字段 = 不可点、无 banner_id', () {
    final legacy = ShopBanner.fromJson({'imageUrl': 'u', 'imageW': 3, 'imageH': 1});
    expect(legacy.tappable, isFalse);
    expect(legacy.bannerId, isNull);
    expect(ShopBanner.fromJson({'imageUrl': 'u', 'targetType': 'COUPON', 'targetValue': 'x'}).tappable, isFalse);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.external, targetValue: 'http://a.b').tappable,
        isFalse);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.external, targetValue: 'https://a.b').tappable,
        isTrue);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.product).tappable, isFalse);
    // 品类本包不认识（后端新增了品类）→ 不可点，免得点了没反应却记一次点击。
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.category, targetValue: 'BARU').tappable,
        isFalse);
    expect(const ShopBanner(imageUrl: 'u', target: ShopBannerTarget.category, targetValue: 'CAMILAN').tappable,
        isTrue);
  });

  testWidgets('露出即报一次 shown（banner_id + position=1）；来回滚动不重复', (t) async {
    await t.pumpWidget(wrap(banner));
    await t.pump();
    await t.pump();
    expect(shown().toList(), [
      {'banner_id': 'bnTok123', 'position': 1}
    ]);
    // 往下滚到 banner 收起，再滚回来：仍只有一条。
    await t.drag(find.byType(CustomScrollView), const Offset(0, -600));
    await t.pump();
    await t.drag(find.byType(CustomScrollView), const Offset(0, 600));
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
  });

  testWidgets('离开页面（TickerMode 关）再回来 → 重新计一次', (t) async {
    final ticker = ValueNotifier(true);
    await t.pumpWidget(wrap(banner, ticker: ticker));
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

  testWidgets('点品类 banner → tapped(category_list) 并切到该品类', (t) async {
    await t.pumpWidget(wrap(banner));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage')));
    await t.pump();
    expect(tapped().toList(), [
      {'banner_id': 'bnTok123', 'target_type': 'category_list', 'position': 1}
    ]);
  });

  testWidgets('扁图收起后再回到页面：图已不在可视区 → 不报 shown（露出按图本身算，不含顶栏）', (t) async {
    // 10:1 扁图：展开高 80、收起只剩 48 高的顶栏。旧算法 48/80 = 0.6 > 0.5，会误报。
    const flat = ShopBanner(
        imageUrl: 'https://cdn.example/b.jpg', imageW: 1000, imageH: 100, bannerId: 'bnFlat');
    final ticker = ValueNotifier(true);
    await t.pumpWidget(wrap(flat, ticker: ticker));
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
    await t.drag(find.byType(CustomScrollView), const Offset(0, -600));
    await t.pump();
    ticker.value = false;
    await t.pump();
    ticker.value = true;
    await t.pump();
    await t.pump();
    expect(shown(), hasLength(1));
  });

  testWidgets('快速双击只算一次点击', (t) async {
    await t.pumpWidget(wrap(banner));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage')));
    await t.pump(const Duration(milliseconds: 100));
    await t.tap(find.byKey(const ValueKey('tokoBannerImage')));
    await t.pump();
    expect(tapped(), hasLength(1));
  });

  testWidgets('不可点的 banner：点了不报 tapped', (t) async {
    await t.pumpWidget(wrap(const ShopBanner(imageUrl: 'https://cdn.example/b.jpg', bannerId: 'x')));
    await t.pump();
    await t.pump();
    await t.tap(find.byKey(const ValueKey('tokoBannerImage')));
    await t.pump();
    expect(tapped(), isEmpty);
  });

  test('事件名与属性词表：snake_case、无 PII 键', () {
    for (final v in ShopBannerTarget.values) {
      final a = v.analytics;
      if (a != null) expect(a, matches(RegExp(r'^[a-z_]+$')));
    }
    expect(ShopCategory.fromApi('CAMILAN'), ShopCategory.camilan);
  });
}
