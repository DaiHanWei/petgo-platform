import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 1.7：商品详情页（FR-94A / FR-95 / FR-104 / UX-DR10）。
void main() {
  Widget host(ShopProductDetail d) => ProviderScope(
        overrides: [
          shopProductDetailProvider.overrideWith((ref, token) async => d),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('id'),
          home: ProductDetailPage(token: 'tok'),
        ),
      );

  ShopSku sku(String t, {int price = 285000, StockStatus stock = StockStatus.inStock,
          int? remaining, ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen}) =>
      ShopSku(
        token: t,
        specName: t,
        price: price,
        returnPolicy: policy,
        stockStatus: stock,
        remaining: remaining,
      );

  ShopProductDetail detail(List<ShopSku> skus,
          {ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen}) =>
      ShopProductDetail(
        token: 'tok',
        name: 'Royal Canin Medium Adult',
        brand: 'Royal Canin',
        returnPolicy: policy,
        skus: skus,
      );

  /// 详情页内容较长且走 ListView 懒加载 —— 用默认 800x600 画布会让退货规则条、库存行
  /// 根本没被 build 出来，断言失败的原因与被测逻辑无关。放大画布让整页一次性渲染。
  Future<void> open(WidgetTester t, ShopProductDetail d) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host(d));
    await t.pumpAndSettle();
  }

  FilledButton addButton(WidgetTester t) =>
      t.widget<FilledButton>(find.byType(FilledButton));

  group('🔴 FR-94A 多规格不默认选中（防误购）', () {
    testWidgets('三规格进页：加购按钮禁用，且没有任何 chip 处于选中态', (t) async {
      await open(t, detail([sku('1.5 kg', price: 165000), sku('3 kg'), sku('7.5 kg', price: 620000)]));

      // 1.5kg 与 7.5kg 差价近 4 倍，默认选中会让用户在没意识到时买错规格，
      // 而自营模式下误购的退货成本由平台承担。
      expect(addButton(t).onPressed, isNull, reason: '未选规格时加购必须禁用');
      final chips = t.widgetList<ChoiceChip>(find.byType(ChoiceChip));
      expect(chips.where((c) => c.selected), isEmpty, reason: '不得默认选中任何规格');
      expect(find.text('Pilih varian dulu'), findsOneWidget);
    });

    testWidgets('选中一个规格后 → 加购启用，价格切到该规格价', (t) async {
      await open(t, detail([sku('1.5 kg', price: 165000), sku('3 kg', price: 285000)]));
      expect(addButton(t).onPressed, isNull);

      await t.tap(find.textContaining('1.5 kg'));
      await t.pumpAndSettle();

      expect(addButton(t).onPressed, isNotNull);
      expect(find.text('Rp 165.000'), findsOneWidget, reason: '价格应切到所选规格');
      expect(find.text('Tambah ke Keranjang'), findsOneWidget);
    });

    testWidgets('单一规格：不展示选择器，直接可加购', (t) async {
      await open(t, detail([sku('3 kg')]));

      expect(find.byType(ChoiceChip), findsNothing, reason: '单规格无须选择器');
      expect(find.text('Pilih Varian'), findsNothing);
      expect(addButton(t).onPressed, isNotNull);
    });

    testWidgets('切换规格：价格与退货规则标识同步刷新（同商品不同 SKU 可不同）', (t) async {
      await open(t, detail([
        sku('1.5 kg', price: 165000, policy: ReturnPolicy.returnable),
        sku('3 kg', price: 285000, policy: ReturnPolicy.noReturnAfterOpen),
      ]));

      await t.tap(find.textContaining('1.5 kg'));
      await t.pumpAndSettle();
      expect(find.text('Bisa diretur.'), findsOneWidget);

      await t.tap(find.textContaining('3 kg'));
      await t.pumpAndSettle();
      expect(find.text('Tidak dapat dikembalikan setelah dibuka.'), findsOneWidget);
      expect(find.text('Bisa diretur.'), findsNothing, reason: '切换后旧标识必须消失');
      expect(find.text('Rp 285.000'), findsOneWidget);
    });
  });

  group('🔴 FR-95 库存三态，且不虚构数字', () {
    testWidgets('售罄规格不可选，且加购禁用', (t) async {
      await open(t, detail([sku('3 kg', stock: StockStatus.outOfStock)]));

      expect(addButton(t).onPressed, isNull);
      expect(find.text('Stok habis'), findsWidgets);
    });

    testWidgets('低库存展示真实剩余数 Sisa 4', (t) async {
      await open(t, detail([sku('3 kg', stock: StockStatus.lowStock, remaining: 4)]));
      expect(find.text('Sisa 4'), findsOneWidget);
    });

    testWidgets('🔴 remaining 缺失时降级为不带数字的文案，绝不编一个', (t) async {
      await open(t, detail([sku('3 kg', stock: StockStatus.lowStock)]));

      expect(find.text('Stok terbatas'), findsOneWidget);
      // 虚构的紧迫感一旦被戳穿，赔进去的是平台可信度而不是一单
      expect(find.textContaining(RegExp(r'Sisa \d')), findsNothing);
    });

    testWidgets('多规格里售罄那个 chip 不可点', (t) async {
      await open(t, detail([sku('3 kg', stock: StockStatus.outOfStock), sku('7.5 kg')]));
      final chips = t.widgetList<ChoiceChip>(find.byType(ChoiceChip)).toList();
      expect(chips.first.onSelected, isNull, reason: '售罄规格可见但不可选');
      expect(chips.last.onSelected, isNotNull);
    });
  });

  group('🔴 FR-104 / UX-DR10 退货规则明示', () {
    testWidgets('「开封不退」在详情页明示（三处明示的第 1 处）', (t) async {
      await open(t, detail([sku('3 kg')], policy: ReturnPolicy.noReturnAfterOpen));
      expect(find.text('Tidak dapat dikembalikan setelah dibuka.'), findsOneWidget);
      expect(find.textContaining('keamanan pangan'), findsOneWidget);
    });

    // 三值各自一条独立用例 —— 放在同一个 testWidgets 里循环 pumpWidget 会复用同一棵
    // widget 树，第二轮的 ProviderScope override 不生效，失败原因与被测逻辑无关。
    for (final (p, expected) in [
      (ReturnPolicy.returnable, 'Bisa diretur.'),
      (ReturnPolicy.noReturnAfterOpen, 'Tidak dapat dikembalikan setelah dibuka.'),
      (ReturnPolicy.noReturn, 'Tidak bisa diretur.'),
    ]) {
      testWidgets('${p.name}: 措辞独立且不含已作废的「可换」', (t) async {
        await open(t, detail([sku('3 kg', policy: p)], policy: p));
        expect(find.text(expected), findsOneWidget);
        // C-13 砍掉换货：换货零实现，措辞里出现「tukar」就是无法兑现的承诺
        expect(find.textContaining('tukar'), findsNothing);
      });
    }
  });

  group('domain', () {
    test('未知 returnPolicy 落到最保守的一档（宁可少承诺）', () {
      expect(ReturnPolicy.fromApi('SOMETHING_NEW'), ReturnPolicy.noReturn);
      expect(ReturnPolicy.fromApi(null), ReturnPolicy.noReturn);
    });

    test('🔴 未知 stockStatus 落到售罄（宁可挡一次购买，不可放过一次超卖）', () {
      expect(StockStatus.fromApi('WEIRD'), StockStatus.outOfStock);
      expect(StockStatus.fromApi(null), StockStatus.outOfStock);
      expect(StockStatus.outOfStock.purchasable, isFalse);
      expect(StockStatus.lowStock.purchasable, isTrue);
    });

    test('fromJson：缺 skus / feedingGuide 不崩', () {
      final d = ShopProductDetail.fromJson(
          {'token': 't', 'name': 'n', 'brand': 'b', 'returnPolicy': 'RETURNABLE'});
      expect(d.skus, isEmpty);
      expect(d.minPrice, isNull);
      expect(d.isSingleSku, isFalse);
    });

    test('minPrice 取全部 SKU 最低价', () {
      final d = ShopProductDetail.fromJson({
        'token': 't', 'name': 'n', 'brand': 'b', 'returnPolicy': 'RETURNABLE',
        'skus': [
          {'token': 'a', 'specName': 'a', 'price': 620000, 'returnPolicy': 'RETURNABLE', 'stockStatus': 'IN_STOCK'},
          {'token': 'b', 'specName': 'b', 'price': 165000, 'returnPolicy': 'RETURNABLE', 'stockStatus': 'IN_STOCK'},
        ],
      });
      expect(d.minPrice, 165000);
    });
  });
}
