import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Toko 首页 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// ⚠️ 2026-08-28：v1 版式整体删除，`toko_page_test.dart` 与「双 UI 开关」那组用例
/// 一并移除 —— 被测机制已不存在。本文件现在是 Toko 首页的唯一用例集。
///
/// 🔴 本类的第一组是**溢出**。那个缺陷是「跑到模拟器上才看见」的类型：
/// `flutter analyze` 绿、单测绿、卡片照常渲染，只是每张卡底部糊了一条黄黑警戒条，
/// 价格被裁掉。widget test 里 `RenderFlex` 溢出会抛异常，所以它**本来就该被测出来** ——
/// 之前没测出来只是因为压根没写这条。
void main() {
  Widget host(
    List<ShopProductSummary> products, {
    Size size = const Size(411, 891), // Pixel 9：溢出正是在这个宽度上出现的
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        // banner 同样必须 override —— 真 provider 会发请求并留下未完成 Timer。
        shopBannerProvider.overrideWith((ref) async => null),
        shopProductsProvider.overrideWith((ref, category) async => products),
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
          data: MediaQueryData(
            size: size,
            textScaler: TextScaler.linear(textScale),
          ),
          child: const TokoPageV2(),
        ),
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

  group('🔴 布局不得溢出（这是跑到真机才发现的那个缺陷）', () {
    testWidgets('411dp 宽 · 标准字号', (tester) async {
      tester.view.physicalSize = const Size(1080, 2424);
      tester.view.devicePixelRatio = 2.625;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(host([
        p('a', price: 185000, name: 'Royal Canin Adult Dog'),
        p('b', price: 78000, name: 'Whiskas Adult Cat'),
        p('c', price: 215000, name: 'Pro Plan Puppy'),
        p('d', price: 35000, name: 'Drontal Plus Obat Cacing'),
      ]));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull,
          reason: '网格卡溢出 —— 卡片内容是定高的，别再用 childAspectRatio 算高度');
    });

    testWidgets('放大到 1.3 倍字号仍不溢出（NFR-13 上限）', (tester) async {
      // app 把 textScaler clamp 在 1.3。定高布局若不跟着字号伸缩，
      // 在上限字号下会把商品名连同价格一起裁掉。
      await tester.pumpWidget(host(
        [p('a', price: 185000, name: 'Royal Canin Adult Dog Premium Nutrition')],
        textScale: 1.3,
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull, reason: '1.3 倍字号下溢出');
    });

    testWidgets('极窄屏（320dp）不溢出', (tester) async {
      await tester.pumpWidget(host(
        [p('a', price: 185000, name: 'Royal Canin Adult Dog')],
        size: const Size(320, 640),
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });
  });

  group('价格与空态', () {
    testWidgets('🔴 minPrice 为 null 时显占位，不显示 Rp 0', (tester) async {
      await tester.pumpWidget(host([p('a')]));
      await tester.pumpAndSettle();

      expect(find.text('Rp 0'), findsNothing,
          reason: '无 SKU 是「缺失价格」，不是「价格为 0」');
      expect(find.text('Harga segera'), findsOneWidget);
    });

    testWidgets('价格按印尼盾格式化（千分位用点）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.text('Rp 185.000'), findsOneWidget);
    });

    testWidgets('🔴 无评分/已售数时整行不渲染（不显示 0）', (tester) async {
      // 列表接口没有这两个字段。设计稿规则：「无数据时整行不显示，不显示 0」。
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.textContaining('terjual'), findsNothing);
      expect(find.textContaining('★'), findsNothing);
    });
  });

  group('游客态（FR-93A：浏览路径零登录墙）', () {
    testWidgets('🔒 不触发任何登录引导 / 弹窗', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
    });

    testWidgets('🔴 顶栏显示 Masuk 胶囊，而不是余额 0', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('tokoLoginCapsule')), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoPawcoinCapsule')), findsNothing,
          reason: '游客显示余额会让人以为账户里本该有钱');
      expect(find.text('Rp 0'), findsNothing);
    });

    testWidgets('🔴 不提供全站搜索框（FR-93，与 v1 同一条战略边界）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byType(TextField), findsNothing);
      expect(find.byIcon(Icons.search), findsNothing);
    });
  });

  group('设计骨架', () {
    testWidgets('页面底色 = 灰缝色（区块靠露出底色分隔）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      final scaffold = tester.widget<Scaffold>(find.byType(Scaffold).first);
      expect(scaffold.backgroundColor, ShopColors.bg);
    });

    testWidgets('四个固定品类 chip 全在', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      for (final c in ['Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan']) {
        expect(find.text(c), findsOneWidget);
      }
    });

    testWidgets('🔴 品类行横滑、不换行 —— 高度与品类数解耦', (tester) async {
      // 2026-08-19 产品决策，**刻意偏离设计稿**（稿里写的是「可换行」）。
      // 真机 360dp 上四个品类正好折两行，把网格整体推下去；品类数一涨行数继续涨。
      await tester.pumpWidget(host([p('a', price: 185000)], size: const Size(360, 780)));
      await tester.pumpAndSettle();

      expect(find.byType(Wrap), findsNothing,
          reason: '换行会让这块的高度随品类数增长，首屏可见商品越来越少');

      // 四个 chip 必须共处一行：纵向位置全部相同。
      final tops = <double>{
        for (final c in ['Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan'])
          tester.getTopLeft(find.text(c)).dy,
      };
      expect(tops.length, 1, reason: '出现第二个 y 值 = 折行了');
    });
  });
}
