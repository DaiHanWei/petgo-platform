import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_banner.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
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
    ShopBanner? banner,
    List<ShopProductsQuery>? seen, // 搜索用例用它观察页面到底按什么族键取数
  }) {
    return ProviderScope(
      overrides: [
        // banner 同样必须 override —— 真 provider 会发请求并留下未完成 Timer。
        shopBannerProvider.overrideWith((ref) async => banner),
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

  /// 🔴 D-1（2026-09-02 stag 电商测试，P1）：顶栏白底白字，整条不可见。
  ///
  /// 现象：控件树里标题 `Shop`、PawCoin 余额、购物车 `Keranjang` 三个元素**都在**，
  /// 截图里该区域却恒为 RGB(255,255,255) —— 只有紫色填充的爪子圆底与购物车角标看得见。
  /// 用户看不到标题、看不到余额，**连购物车入口都只能靠盲点角标位置**。
  ///
  /// 根因：`ShopText.pageTitle` 自带 `color: surface`（白）压过 AppBar.foregroundColor，
  /// 两个胶囊又各自写死白字 + `onInk12`（白 12%）底 —— 三处都绕过了 tone。
  ///
  /// ⚠️ 白色顶栏本身是**设计内的空态**（后台 banner 页写明了），缺陷在于前景没跟着适配。
  /// 所以断言的是「前景跟着 tone 走」，不是「底色必须是深的」。
  group('🔴 D-1：顶栏前景必须跟着 tone 走，不能写死白色', () {
    testWidgets('无 banner（白底）→ 标题与购物车图标是主体色，不是白色', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      final title = tester.widget<Text>(find.text('Toko'));
      expect(title.style?.color, ShopColors.purple,
          reason: '白底上必须用主体色（品牌紫），2026-09-02 产品拍板');
      expect(title.style?.color, isNot(ShopColors.surface),
          reason: '白底白字 —— D-1 的原形');

      final cart = tester.widget<Icon>(find.byIcon(Icons.shopping_cart_outlined));
      expect(cart.color, ShopColors.purple,
          reason: '购物车是**入口**，看不见等于这个功能不存在');
      expect(cart.color, isNot(ShopColors.surface));
    });

    testWidgets('有 banner（图作背景）→ 前景回到白色，压在图上可读', (tester) async {
      await tester.pumpWidget(host(
        [p('a', price: 185000)],
        banner: const ShopBanner(imageUrl: 'https://example.test/b.jpg', imageW: 1200, imageH: 400),
      ));
      await tester.pumpAndSettle();

      final title = tester.widget<Text>(find.text('Toko'));
      expect(title.style?.color, ShopColors.surface,
          reason: '图上压了渐变遮罩，白字才读得出');

      final cart = tester.widget<Icon>(find.byIcon(Icons.shopping_cart_outlined));
      expect(cart.color, ShopColors.surface);
    });

    test('三种 tone 的前景与底色不得同色 —— 任何一组同色都是「当场看不见」', () {
      for (final tone in ShopAppBarTone.values) {
        final c = ShopAppBar.colorsOf(tone);
        if (c.background == Colors.transparent) continue; // 透明底由页面的渐变兜底
        expect(c.foreground, isNot(c.background),
            reason: '$tone 的前景与底色同色 —— 这正是 D-1 的形态');
      }
    });
  });

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

    /// 🔴 2026-08-31 **决策反转**：FR-93 原文「不提供全站搜索框」，理由是
    /// 商品受 SKU 上限约束（C-7）、四分类 + 精选流已够覆盖，且搜索框会把心智
    /// 推向通用货架。产品于本日推翻该条，PRD / epics / decision-log /
    /// architecture-delta 已同步改写。
    ///
    /// 这条用例从「断言不存在」翻成「断言存在」，**是故意留在原位**的 ——
    /// 删掉它只会让下一个人以为搜索框是谁误加的，然后又把它拆了。
    testWidgets('🔴 提供搜索框（2026-08-31 起，FR-93 原「不做搜索」已推翻）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byType(TextField), findsOneWidget);
      expect(find.byIcon(Icons.search), findsOneWidget);
    });

    testWidgets('🔒 搜索框对游客同样可用（浏览路径零登录墙）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // 不弹登录、不拦截
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
    });
  });

  group('搜索（2026-08-31）', () {
    testWidgets('🔴 输入防抖：连续敲不会每个字母都取一次数', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();
      seen.clear(); // 丢掉首屏那次

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

    testWidgets('🔴 关键词与品类是与关系，不是互斥', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Makanan'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(seen.last.category, ShopCategory.makanan,
          reason: '搜索不该把已选品类清掉——那会让用户以为筛选没生效');
      expect(seen.last.keyword, 'royal');
    });

    testWidgets('🔴 清空搜索框 → 族键回到 keyword=null（与从未搜过等价）', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(seen.last.keyword, 'royal');

      await tester.tap(find.byIcon(Icons.close));
      await tester.pumpAndSettle();

      expect(seen.last.keyword, isNull);
    });

    /// 🔴 「搜不到」与「目录是空的」必须是两句话：共用一句会让用户以为整个店没货。
    testWidgets('🔴 搜索无结果的空态带关键词，且不等于目录空态', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(const []));
      await tester.pumpAndSettle();

      // 没搜索时 = 目录空态
      expect(find.text(l10n.tokoEmpty), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'royal');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text(l10n.tokoEmpty), findsNothing);
      expect(find.text(l10n.tokoSearchEmpty('royal')), findsOneWidget);
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
