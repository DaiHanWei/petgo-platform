import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/shop_search_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 商品搜索页（2026-09-02 产品定形）。
///
/// 本组用例的**大部分是搬过来的**：bcb5176e 把搜索做成 Toko 顶栏里的内嵌输入框，
/// 用例也就写在 toko_page_v2_test 里。2026-09-02 产品把形态改成
/// 「吸顶行只留放大镜 → 点进独立搜索页」，行为搬家，用例跟着搬 ——
/// 防抖、清空、空态这几条守的是**搜索本身**，与它住在哪一页无关，不该跟着删掉。
///
/// ⚠️ 「关键词与品类是与关系」那条**没有搬过来**：最小形态的搜索页不带页内品类筛选，
/// 端上恒以 `category: null` 取数。该规则现在只由后端
/// `ShopProductQueryServiceTest` 守（q 与 category 在服务层仍是与关系，
/// 日后要加页内筛选不用改接口）。
void main() {
  Widget host(List<ShopProductSummary> products, {List<ShopProductsQuery>? seen}) {
    return ProviderScope(
      overrides: [
        shopProductsProvider.overrideWith((ref, query) async {
          seen?.add(query);
          return products;
        }),
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
        home: const ShopSearchPage(),
      ),
    );
  }

  ShopProductSummary p(String token, {int? price, String name = 'Produk'}) =>
      ShopProductSummary(
        token: token,
        name: name,
        brand: 'BrandX',
        category: ShopCategory.makanan,
        minPrice: price,
      );

  group('落地即可打字', () {
    testWidgets('🔴 输入框自动聚焦 —— 用户是点着放大镜进来的', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.autofocus, isTrue,
          reason: '落地还要再点一次输入框，等于把刚省下的那一步又还回去');
    });

    testWidgets('🔴 未输入时不取数，也不画热词/历史（拍板的最小形态）', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      expect(seen, isEmpty, reason: '空关键词就发请求 = 把整个目录拉一遍');
      expect(find.byType(ListView), findsNothing);
    });
  });

  group('取数口径', () {
    testWidgets('🔴 输入防抖：连续敲不会每个字母都取一次数', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'r');
      await tester.pump(const Duration(milliseconds: 100));
      await tester.enterText(find.byType(TextField), 'ro');
      await tester.pump(const Duration(milliseconds: 100));
      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // 三次输入只落一次取数，且落的是最后那一版
      expect(seen, hasLength(1));
      expect(seen.single.keyword, 'royal');
    });

    testWidgets('🔴 清空 → 回到空态，且不再取数', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(seen.last.keyword, 'royal');

      await tester.tap(find.byKey(const ValueKey('shopSearchClearV2')));
      await tester.pumpAndSettle();

      expect(find.byType(ListView), findsNothing, reason: '清空即回空态');
    });

    testWidgets('🔴 清空是立即生效的，不等防抖', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.byType(ListView), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('shopSearchClearV2')));
      await tester.pump(); // 一帧，远不到 300ms

      expect(find.byType(ListView), findsNothing,
          reason: '让它等 300ms 会显得没点上');
    });

    testWidgets('本页恒以 category=null 取数（最小形态不带页内品类筛选）', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(seen.single.category, isNull);
    });
  });

  group('空态', () {
    /// 🔴 「搜不到」与「目录是空的」必须是两句话：共用一句会让用户以为整个店没货。
    testWidgets('🔴 无结果时报关键词，并给下一步动作', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(const []));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text(l10n.tokoSearchEmpty('royal')), findsOneWidget);
      expect(find.text(l10n.tokoEmpty), findsNothing,
          reason: '目录空态是另一句话，混用会让用户以为整个店没货');
      expect(find.text(l10n.tokoSearchNoResultHint), findsOneWidget,
          reason: '只说「没找到」是个死胡同，要给下一步');
    });
  });
}
