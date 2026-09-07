import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/data/shop_review_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_review.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 商品详情页 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// v1 版式的用例在 `product_detail_page_test.dart`，两套互不影响。
///
/// 本类重点看三件**写错会造成真实损失**的事，它们与版式无关、从 v1 原样继承：
/// 多规格不默认选中（买错规格的退货成本平台承担）、库存数不虚构、开封不退必须明示。
void main() {
  Widget host(
    ShopProductDetail detail, {
    ProductReviews? reviews,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        shopProductDetailProvider.overrideWith((ref, token) async => detail),
        productReviewsProvider.overrideWith((ref, token) async =>
            reviews ?? const ProductReviews(total: 0, items: [])),
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
          data: MediaQueryData(size: size, textScaler: TextScaler.linear(textScale)),
          child: const ProductDetailPageV2(token: 'tok1'),
        ),
      ),
    );
  }

  ShopSku sku(String token, {
    String spec = '3 kg',
    int price = 185000,
    StockStatus stock = StockStatus.inStock,
    int? remaining,
    ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen,
  }) =>
      ShopSku(
        token: token,
        specName: spec,
        price: price,
        returnPolicy: policy,
        stockStatus: stock,
        remaining: remaining,
      );

  ShopProductDetail detail({
    required List<ShopSku> skus,
    ShopCategory? category = ShopCategory.makanan,
    ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen,
    List<String> gallery = const [],
    String? mainImage,
  }) =>
      ShopProductDetail(
        token: 'tok1',
        mainImageUrl: mainImage,
        name: 'Royal Canin Adult Dog',
        brand: 'Royal Canin',
        category: category,
        returnPolicy: policy,
        skus: skus,
        galleryUrls: gallery,
        detailHtml: '<p>Makanan kering.</p>',
      );

  /// 🔴 R-1（2026-09-02 产品提出）：商品主图要能点开看全貌。
  ///
  /// 详情页图区固定高 266、`ShopImage(fillWidth: true)`，而 `ShopImage.fit` 默认
  /// **BoxFit.cover（裁切）** —— 商品图素材是 1:1，塞进 266 高的横向框里上下必然被切掉。
  /// 而图区上**原本没有任何点击入口**（本页 6 处 onTap 全不在图区），也没有 InteractiveViewer
  /// ⇒ 用户没有任何办法看到整张商品图。
  ///
  /// ⚠️ **没有改 `ShopImage` 的默认 fit** —— 它的源码注释写明十余处调用方（购物车行、
  /// 订单行、退款选择）都依赖 cover 把图铺满各自的方框，改默认值会一次性波及全部。
  /// 修法是**新增全屏查看器**，列表与图区照旧裁切。
  group('🔴 R-1：主图点击放大', () {
    testWidgets('有图 → 图区可点，点开出全屏查看器且用 contain 展示全貌', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [sku('s1')],
        mainImage: 'https://cdn.test/main.jpg',
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('tokoGalleryZoomV2')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tokoGalleryZoomV2')));
      await tester.pumpAndSettle();

      expect(find.byType(InteractiveViewer), findsOneWidget,
          reason: '不支持双指缩放的话，全屏也只是把同一张图放大一点');
      final img = tester.widget<Image>(find.descendant(
          of: find.byType(InteractiveViewer), matching: find.byType(Image)));
      expect(img.fit, BoxFit.contain, reason: '这个查看器存在的唯一理由就是看全貌');
    });

    testWidgets('🔴 查看器拿的是原图 URL，不是图区那张 266px 缩略图', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [sku('s1')],
        mainImage: 'https://cdn.test/main.jpg',
      )));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tokoGalleryZoomV2')));
      await tester.pumpAndSettle();

      final img = tester.widget<Image>(find.descendant(
          of: find.byType(InteractiveViewer), matching: find.byType(Image)));
      expect((img.image as NetworkImage).url, 'https://cdn.test/main.jpg',
          reason: '把缩略图送进查看器，双指放大只会看到一团糊 —— 等于白做');
    });

    testWidgets('无图 → 不给点击入口（占位斜纹点开一个黑屏更糟）', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1')])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('tokoGalleryZoomV2')), findsNothing);
    });

    testWidgets('🔴 多图 → 整组都进查看器，且从被点的那张打开', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [sku('s1')],
        mainImage: 'https://cdn.test/a.jpg',
        gallery: const ['https://cdn.test/b.jpg', 'https://cdn.test/c.jpg'],
      )));
      await tester.pumpAndSettle();

      // 点的是当前这张（第 1 张）⇒ 查看器停在 1/3，而不是无脑从头开始。
      await tester.tap(find.byKey(const ValueKey('tokoGalleryZoomV2')).hitTestable());
      await tester.pumpAndSettle();

      final indicator = tester.widget<Text>(
          find.byKey(const ValueKey('imageViewerPageIndicator')));
      expect(indicator.data, '1/3', reason: '主图 + 2 张附图 = 3 张整组带进去');
      expect(find.byType(InteractiveViewer), findsWidgets);
    });
  });

  group('🔴 FR-94A：多规格不得默认选中', () {
    testWidgets('两个规格时无选中态，主按钮禁用并提示先选规格', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1'), sku('s2', spec: '7.5 kg')])));
      await tester.pumpAndSettle();

      // 提示文案出现 = 尚未选中任何规格
      expect(find.byKey(const ValueKey('pdpChooseVariantHint')), findsOneWidget,
          reason: '默认选中会让人在没意识到时买错规格 —— 1.5kg 与 7.5kg 差价近 4 倍');

      final buy = tester.widget<ShopButton>(find.byKey(const ValueKey('pdpBuyNow')));
      expect(buy.onTap, isNull, reason: '未选规格时不得可点');
      expect(buy.variant, ShopButtonVariant.disabled);

      final add = tester.widget<ShopButton>(find.byKey(const ValueKey('pdpAddToCart')));
      expect(add.onTap, isNull);
      // 🔴 按钮文案必须恒定 —— 把「先选规格」塞进按钮会在 411dp 上挤成两行（真机实测）。
      expect(add.label, '+ Keranjang');
    });

    testWidgets('单一规格直通，主按钮可用', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1')])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpChooseVariantHint')), findsNothing);
      expect(find.text('+ Keranjang'), findsOneWidget);
      expect(find.text('Beli Sekarang'), findsOneWidget);
    });

    testWidgets('选中某个规格后按钮解禁', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1'), sku('s2', spec: '7.5 kg')])));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('skuChip_s2')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpChooseVariantHint')), findsNothing);
      final add = tester.widget<ShopButton>(find.byKey(const ValueKey('pdpAddToCart')));
      expect(add.onTap, isNotNull, reason: '选中规格后必须解禁');
    });
  });

  group('🔴 FR-95：库存数不虚构', () {
    testWidgets('remaining ≤ 20 显真实数字', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1', remaining: 14)])));
      await tester.pumpAndSettle();

      expect(find.textContaining('Sisa 14'), findsOneWidget);
    });

    testWidgets('remaining > 20 只说「有货」，不暴露备货量', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1', remaining: 340)])));
      await tester.pumpAndSettle();

      expect(find.textContaining('340'), findsNothing,
          reason: '备货量是经营信息，设计稿明确不外露');
      expect(find.textContaining('Stok tersedia'), findsOneWidget);
    });

    testWidgets('remaining 为 null 时不编数字', (tester) async {
      await tester.pumpWidget(host(detail(skus: [sku('s1')])));
      await tester.pumpAndSettle();

      expect(find.textContaining('Sisa'), findsNothing);
    });
  });

  group('🔴 FR-104：开封不退必须在本页明示（三处的第 1 处）', () {
    testWidgets('NO_RETURN_AFTER_OPEN → 橙色警示块出现', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [sku('s1', policy: ReturnPolicy.noReturnAfterOpen)],
        policy: ReturnPolicy.noReturnAfterOpen,
      )));
      await tester.pumpAndSettle();

      expect(find.byType(ShopWarnBlock), findsOneWidget);
    });

    testWidgets('选中的 SKU 规则优先于商品级规则（同商品可不同）', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [
          sku('s1', policy: ReturnPolicy.returnable),
          sku('s2', spec: '7.5 kg', policy: ReturnPolicy.noReturn),
        ],
        policy: ReturnPolicy.returnable,
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('skuChip_s2')));
      await tester.pumpAndSettle();

      // 切到 NON_RETURNABLE 的规格后，标题应换成「不可退」那一条
      expect(find.text('Tidak bisa diretur.'), findsOneWidget);
    });
  });

  group('售罄态（FR-95）', () {
    testWidgets('🔴 主按钮置灰但不消失 —— 传达「同一个页面、只是买不了」', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', stock: StockStatus.outOfStock)]),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpSoldOutDisabled')), findsOneWidget,
          reason: '把置灰按钮 remove 掉会让页面看起来像坏了');
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsNothing);
    });

    testWidgets('🔴 价格转灰 + harga terakhir，不用玫红做买不到的促销刺激', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', stock: StockStatus.outOfStock)]),
      ));
      await tester.pumpAndSettle();

      expect(find.text('harga terakhir'), findsOneWidget);
      final price = tester.widget<Text>(find.text('Rp 185.000'));
      expect(price.style?.color, ShopColors.text4);
      expect(price.style?.color, isNot(ShopColors.accent));
    });

    testWidgets('🔴 不写「segera」这类无信息到货承诺', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', stock: StockStatus.outOfStock)]),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('segera'), findsNothing,
          reason: '设计稿把 segera 列为禁用文案 —— 到货时间必须是区间或不显示');
    });

    testWidgets('品类已知 → 次出口跳同品类列表', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', stock: StockStatus.outOfStock)]),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpSeeAlternatives')), findsOneWidget);
    });

    // ⚠️ 与上一条**必须分成两个 test**：同一个用例里二次 pumpWidget 时，
    //    FutureProvider 的旧结果还挂在容器上，断言到的是上一次的树（首次写成一条时就是这样假绿的）。
    testWidgets('品类未知 → 该按钮不渲染（点了没去处的按钮比不给更糟）', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', stock: StockStatus.outOfStock)], category: null),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpSeeAlternatives')), findsNothing);
    });

    testWidgets('多规格未选时不算售罄 —— 别挡住买得到的其它规格', (tester) async {
      await tester.pumpWidget(host(detail(skus: [
        sku('s1', stock: StockStatus.outOfStock),
        sku('s2', spec: '7.5 kg'),
      ])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('pdpSoldOutDisabled')), findsNothing);
    });
  });

  group('评分与已售数', () {
    // ⚠️ 2026-08-28：v1 的 product_reviews_test.dart 随 v1 详情页一并删除。
    //    那 5 条里在 v2 版式下仍成立的三条（不显示 0 分 / 有评分显示 ★ 与条数 /
    //    不拿评价数冒充已售数）就是本组，覆盖没有丢。
    //    另两条（逐条评价内容、无追评与商家回复结构）测的是 v1 独有的完整评价区 ——
    //    v2 只在标题行显示「★ 评分 · N 条」，那块 UI 不存在，断言无从谈起。
    //    🔴 若将来 v2 补上完整评价区，这两条要一并补回。
    testWidgets('🔴 无评分时整段不显示，不显示 0 分', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1')]),
        reviews: const ProductReviews(total: 0, items: []),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('★'), findsNothing);
      expect(find.textContaining('0.0'), findsNothing);
    });

    testWidgets('有评分时显示 ★ 与评价数', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1')]),
        reviews: const ProductReviews(total: 128, items: [], averageRating: 4.9),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('★ 4.9'), findsOneWidget);
      expect(find.textContaining('128'), findsOneWidget);
    });

    testWidgets('🔴 不拿评价数冒充「已售数」', (tester) async {
      // 设计稿写的是 `128 terjual`（已售），而接口只有评价数 —— 两个不同的量。
      // 贴一个 terjual 标签在评价数上是**造假数据**，不是版式还原。
      await tester.pumpWidget(host(
        detail(skus: [sku('s1')]),
        reviews: const ProductReviews(total: 128, items: [], averageRating: 4.9),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('terjual'), findsNothing);
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 标准字号', (tester) async {
      await tester.pumpWidget(host(detail(
        skus: [sku('s1'), sku('s2', spec: '7.5 kg')],
        gallery: const [],
      )));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号（NFR-13 上限）', (tester) async {
      await tester.pumpWidget(host(
        detail(skus: [sku('s1', remaining: 14)]),
        textScale: 1.3,
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });
  });
}
