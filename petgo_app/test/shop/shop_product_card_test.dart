import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_product_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 商品卡（v1.4.2 shop Story 8.1 AC6.3）：角标、占位价、「＋」热区 ≥ 44。
void main() {
  Future<void> pump(WidgetTester t, ShopProductSummary p, {bool dense = false, double width = 180}) async {
    await t.pumpWidget(ProviderScope(
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: Scaffold(
          body: Builder(
            builder: (context) => Center(
              child: SizedBox(
                width: width,
                height: ShopProductCard.extentFor(context, width, dense: dense),
                child: ShopProductCard.fromSummary(p, zone: 'all_featured', entrySource: 'X', dense: dense),
              ),
            ),
          ),
        ),
      ),
    ));
    await t.pumpAndSettle();
  }

  testWidgets('promo=true → 「Lagi promo」角标（不写金额）', (t) async {
    await pump(t, const ShopProductSummary(token: 'a', name: 'n', brand: 'b', minPrice: 1000, promo: true));
    expect(find.byKey(const ValueKey('shopProductPromoBadge')), findsOneWidget);
    expect(find.text('Lagi promo'), findsOneWidget);
    expect(find.byIcon(Icons.local_fire_department), findsOneWidget);
  });

  testWidgets('promo=false → 无角标', (t) async {
    await pump(t, const ShopProductSummary(token: 'a', name: 'n', brand: 'b', minPrice: 1000));
    expect(find.byKey(const ValueKey('shopProductPromoBadge')), findsNothing);
  });

  testWidgets('🔴 minPrice null → 占位「Harga segera」，不显示 Rp 0；有价为紫色', (t) async {
    await pump(t, const ShopProductSummary(token: 'a', name: 'n', brand: 'b'));
    expect(find.text('Harga segera'), findsOneWidget);
    expect(find.text('Rp 0'), findsNothing);
    await pump(t, const ShopProductSummary(token: 'b', name: 'n', brand: 'b', minPrice: 185000));
    expect(t.widget<Text>(find.text('Rp 185.000')).style?.color, AppColors.mint);
  });

  for (final dense in [false, true]) {
    testWidgets('「＋」热区 ≥ 44（dense=$dense）且定高不溢出', (t) async {
      await pump(
          t,
          const ShopProductSummary(
              token: 'a', name: 'Royal Canin Adult Dog Premium Nutrition Extra Long', brand: 'b', minPrice: 185000),
          dense: dense,
          width: dense ? 96 : 180);
      final hit = t.getSize(find.byKey(const ValueKey('shopProductCardAdd_a')));
      expect(hit.width, greaterThanOrEqualTo(44));
      expect(hit.height, greaterThanOrEqualTo(44));
      expect(t.takeException(), isNull);
    });
  }

  testWidgets('图区 1:1 且 BoxFit.contain 不裁切', (t) async {
    await pump(t,
        const ShopProductSummary(token: 'a', name: 'n', brand: 'b', minPrice: 1, mainImageUrl: 'https://x.test/a.jpg'));
    final img = t.widget<Image>(find.byType(Image));
    expect(img.fit, BoxFit.contain);
    final ar = t.widget<AspectRatio>(find.byType(AspectRatio));
    expect(ar.aspectRatio, 1);
  });
}
