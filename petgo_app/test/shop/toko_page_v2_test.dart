import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_banner.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_pressable.dart';
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
/// 筛选行左端的放大镜 —— 它与整行等高（命中区 44 = 行高 44），
/// 所以用它的 topLeft 代表**筛选行的上沿**做几何断言。
const _searchEntry = ValueKey('tokoSearchEntryV2');

void main() {
  Widget host(
    List<ShopProductSummary> products, {
    Size size = const Size(411, 891), // Pixel 9：溢出正是在这个宽度上出现的
    double textScale = 1,
    ShopBanner? banner,
    int cartCount = 0,
    List<ShopProductsQuery>? seen, // 搜索用例用它观察页面到底按什么族键取数
  }) {
    return ProviderScope(
      overrides: [
        cartItemCountProvider.overrideWithValue(cartCount),
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
    /// 🔴 FR-93 原写着「不提供全站搜索框」，C-18 推翻了它。
    /// 2026-09-02 又把入口形态从「顶栏内嵌输入框」改成「放大镜 → 独立搜索页」——
    /// **推翻的是落位，不是搜索本身**。所以这条守的仍是「搜索入口必须存在」。
    testWidgets('🔴 提供搜索入口（放大镜）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.search), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoSearchEntryV2')), findsOneWidget);
    });

    testWidgets('🔒 搜索入口对游客同样可用（浏览路径零登录墙）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      // 入口本身不带任何登录门 —— 游客看得见、点得动。
      final entry = tester.widget<ShopPressable>(
          find.byKey(const ValueKey('tokoSearchEntryV2')));
      expect(entry.onTap, isNotNull);
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
    });
  });

  /// 🔴 R-4 + C-18 的落位合并（2026-09-02 产品定形）。
  ///
  /// 两条需求争同一个吸顶槽：C-18 要给搜索一个常驻位，R-4 要把分类从流里挪上来
  /// （原先它夹在屏幕 45% 处、跟内容一起滚，往下翻两屏就完全离开视野）。
  /// 定下来的形态是**一行**：放大镜 + 分类依次排开，点放大镜进独立搜索页。
  ///
  /// ⚠️ 这推翻了 bcb5176e 的做法（顶栏内嵌完整输入框、就地过滤）。
  /// 那组用例连同行为一起搬到了 shop_search_page_test.dart。
  group('🔴 吸顶筛选行：放大镜 + 分类共用一行', () {
    testWidgets('Toko 顶栏不再有输入框，只留放大镜', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      expect(find.byType(TextField), findsNothing,
          reason: '输入框整条占掉吸顶槽，分类就没地方放了 —— 这正是要收成图标的原因');
      expect(find.byKey(const ValueKey('tokoSearchEntryV2')), findsOneWidget);
    });

    testWidgets('🔴 点放大镜 → 进 /shop/search', (tester) async {
      String? pushed;
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => const TokoPageV2()),
        GoRoute(
          path: '/shop/search',
          builder: (c, s) {
            pushed = '/shop/search';
            return const Scaffold(body: Text('search page'));
          },
        ),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          shopBannerProvider.overrideWith((ref) async => null),
          shopProductsProvider.overrideWith((ref, query) async => [p('a', price: 185000)]),
        ],
        child: MaterialApp.router(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          routerConfig: router,
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('tokoSearchEntryV2')));
      await tester.pumpAndSettle();

      expect(pushed, '/shop/search');
      expect(find.text('search page'), findsOneWidget);
    });

    testWidgets('🔴 分类吸顶：滚到底也还在（原先它跟着内容滚走）', (tester) async {
      await tester.pumpWidget(host([
        for (var i = 0; i < 30; i++) p('p$i', price: 185000, name: 'Produk $i'),
      ]));
      await tester.pumpAndSettle();

      await tester.drag(find.byType(CustomScrollView), const Offset(0, -3000));
      await tester.pumpAndSettle();

      expect(find.text('Makanan'), findsOneWidget,
          reason: '换品类得一路滚回顶部 —— R-4 的问题本质不是「不够醒目」，是「会滚走」');
      expect(find.byKey(const ValueKey('tokoSearchEntryV2')), findsOneWidget);
    });

    testWidgets('🔴 显式「全部」标签，默认选中', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      expect(find.text('Semua'), findsOneWidget,
          reason: '没有它时取消筛选只能「再点一次已选中的标签」—— 隐藏交互，用户不会主动试');
      expect(seen.single.category, isNull, reason: '默认即全部');

      // 选一个品类再点「全部」→ 回到无筛选
      await tester.tap(find.text('Makanan'));
      await tester.pumpAndSettle();
      expect(seen.last.category, ShopCategory.makanan);

      await tester.tap(find.text('Semua'));
      await tester.pumpAndSettle();
      expect(seen.last.category, isNull);
    });

    testWidgets('🔴 选中态是品牌紫实心，与顶栏主体色同源（R-4 ②）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Makanan'));
      await tester.pumpAndSettle();

      final chips = tester.widgetList<ShopChip>(find.byType(ShopChip));
      final picked = chips.firstWhere((c) => c.label == 'Makanan');
      expect(picked.selected, isTrue);
      expect(picked.selectedColor, ShopColors.purple,
          reason: '选中态读不出来，等于不知道自己在看哪个品类');
    });

    testWidgets('本页取数恒不带关键词（搜索是另一条路径）', (tester) async {
      final seen = <ShopProductsQuery>[];
      await tester.pumpWidget(host([p('a', price: 185000)], seen: seen));
      await tester.pumpAndSettle();

      expect(seen.every((q) => q.keyword == null), isTrue);
    });
  });

  /// 🔴 2026-09-03 产品反馈：搜索与分类**浮在 banner 之上**，且恒定吸顶 ——
  /// 图被压掉一截、滚动时那一行又纹丝不动，看着像两个图层没对齐。
  ///
  /// 定下来的形态：**有 banner 时筛选行落在图下方，跟着内容往上走，碰到顶栏才吸住**。
  /// 没有 banner 时不变（顶栏白条 + bottom 槽，本来就贴着顶）。
  ///
  /// ⚠️ 这组用例守的是**几何关系**，不是控件是否存在 —— 缺陷正是「三个控件都在、
  /// 位置全错」那一类，只断言 findsOneWidget 抓不住。
  group('🔴 有 banner：筛选行在图下方，滚到顶再吸住', () {
    const banner = ShopBanner(
        imageUrl: 'https://example.test/b.jpg', imageW: 1200, imageH: 400);

    // 411dp 宽 · 3:1 ⇒ 图高 137；测试 MediaQuery 无状态栏内边距，顶栏就是 0~48。
    const bannerHeight = 411 / 3;
    const filterRow = _searchEntry;

    testWidgets('初始：筛选行整行在 banner 图**下方**，不压在图上', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)], banner: banner));
      await tester.pumpAndSettle();

      expect(tester.getTopLeft(find.byKey(filterRow)).dy,
          greaterThanOrEqualTo(bannerHeight),
          reason: '压在图上就是这次要修的形态（图被盖掉一截，滚动时两层不同步）');
    });

    testWidgets('🔴 滚动时先跟着内容上移，到顶栏下沿吸住', (tester) async {
      await tester.pumpWidget(host([
        for (var i = 0; i < 30; i++) p('p$i', price: 185000, name: 'Produk $i'),
      ], banner: banner));
      await tester.pumpAndSettle();

      final atRest = tester.getTopLeft(find.byKey(filterRow)).dy;

      // ① 小幅滚动（不足以吃掉整张图）→ 必须跟着往上走
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -40));
      await tester.pumpAndSettle();
      final afterNudge = tester.getTopLeft(find.byKey(filterRow)).dy;
      expect(afterNudge, lessThan(atRest),
          reason: '恒定吸顶（原实现）时这一行纹丝不动 —— 那正是"滑动很奇怪"的来源');

      // ② 一路滚到底 → 停在顶栏下沿，不再继续上移、也不滚出视野
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -3000));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.byKey(filterRow)).dy, kShopAppBarHeight,
          reason: '吸的是顶栏下沿：吸到 y=0 就会被浮在上面的顶栏盖住');
      expect(find.text('Makanan'), findsOneWidget,
          reason: '分类是页面级导航（R-4），滚到底也得能换品类');
    });

    testWidgets('🔴 顶栏本身仍吸顶：图滚走后标题与购物车还在', (tester) async {
      await tester.pumpWidget(host([
        for (var i = 0; i < 30; i++) p('p$i', price: 185000, name: 'Produk $i'),
      ], banner: banner));
      await tester.pumpAndSettle();

      await tester.drag(find.byType(CustomScrollView), const Offset(0, -3000));
      await tester.pumpAndSettle();

      expect(find.text('Toko'), findsOneWidget);
      expect(find.byIcon(Icons.shopping_cart_outlined), findsOneWidget,
          reason: '购物车是入口，不能跟着 banner 一起滚走');
      expect(tester.getTopLeft(find.text('Toko')).dy, lessThan(kShopAppBarHeight),
          reason: '收起后的顶栏必须仍贴在屏幕顶');
    });

    testWidgets('🔴 图滚走后顶栏是实心的 —— 商品不能从标题底下穿过去', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)], banner: banner));
      await tester.pumpAndSettle();

      // 透明顶栏（原实现）+ 会收起的 banner = 商品流直接穿过标题行。
      final appBar = tester.widget<AppBar>(find.byType(AppBar));
      expect(appBar.backgroundColor, isNot(Colors.transparent));
      expect(appBar.backgroundColor,
          ShopAppBar.colorsOf(ShopAppBarTone.dark).background);
    });
  });

  /// 🔴 2026-09-03 产品反馈：「顶部的 coin 和 cart 图标不是很清晰，虽然有毛玻璃框，
  /// 但是白色的字看不清楚，且整体很小」。
  ///
  /// 实测（stag banner 截图取样，1080px）：白字压在 `onInk12`（白 12%）底上只有
  /// **1.78:1**，购物车图标 2.23:1 —— AA 要 4.5 / 3.0，双双不达标。
  /// 根因和 D-1 同族：底色跟着 tone 走，而 tone 说的是「顶栏是墨底」，
  /// 有 banner 时顶栏的底其实是一张**亮度不可控的运营图**。
  ///
  /// ⚠️ 这组断言的是**底色与尺寸**，不是「控件在不在」—— 三个控件一直都在。
  group('🔴 顶栏胶囊：压在 banner 图上要自带底，且不能太小', () {
    const banner = ShopBanner(
        imageUrl: 'https://example.test/b.jpg', imageW: 1200, imageH: 400);

    /// 胶囊外层容器（[ShopPressable] 把命中区撑到 44，视觉盒子是里面这层）。
    Container shell(WidgetTester t, Finder capsule) => t.widget<Container>(
        find.descendant(of: capsule, matching: find.byType(Container)).first);

    testWidgets('有 banner → 底色是图上专用的深色，不是白 12%', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)], banner: banner));
      await tester.pumpAndSettle();

      final deco = shell(tester, find.byKey(const ValueKey('tokoLoginCapsule')))
          .decoration! as BoxDecoration;
      expect(deco.color, ShopColors.imageCapsuleScrim,
          reason: '白字要在任意运营图上过 AA，只能靠胶囊自己的深底');
      expect(deco.color, isNot(ShopColors.onInk12),
          reason: '白 12% 压在亮图上等于没有底 —— 1.78:1');
      expect(deco.border, isNotNull,
          reason: '底深到接近墨色后，收起态里胶囊会与顶栏同色，靠描边才看得出是个框');
    });

    testWidgets('无 banner（白底顶栏）→ 仍用 tone 给的浅紫底', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)]));
      await tester.pumpAndSettle();

      final deco = shell(tester, find.byKey(const ValueKey('tokoLoginCapsule')))
          .decoration! as BoxDecoration;
      expect(deco.color, ShopAppBar.colorsOf(ShopAppBarTone.light).capsule,
          reason: '白底顶栏上深色胶囊会变成一块突兀的黑砖，这里不该改');
    });

    testWidgets('🔴 尺寸：胶囊与购物车同高 32，图标 19、文字 13', (tester) async {
      await tester.pumpWidget(host([p('a', price: 185000)], banner: banner));
      await tester.pumpAndSettle();

      final capsule = shell(tester, find.byKey(const ValueKey('tokoLoginCapsule')));
      expect(tester.getSize(find.byWidget(capsule)).height, 32);

      final cart = tester.widget<Container>(find
          .ancestor(
              of: find.byIcon(Icons.shopping_cart_outlined),
              matching: find.byType(Container))
          .first);
      final cartSize = tester.getSize(find.byWidget(cart));
      expect(cartSize, const Size(32, 32),
          reason: '两个胶囊并排，高度不一致时右上角会看着歪');

      expect(tester.widget<Icon>(find.byIcon(Icons.shopping_cart_outlined)).size, 19);
      // ⚠️ 必须限定在胶囊内：建档引导行里还有一个同文案的 Masuk 按钮。
      final label = find.descendant(
          of: find.byKey(const ValueKey('tokoLoginCapsule')), matching: find.text('Masuk'));
      expect(tester.widget<Text>(label).style?.fontSize, 13,
          reason: '余额与 Masuk 是要读的信息，不是角标；11px 在 1080p 上约 2mm');
    });

    /// 🔴 顶栏底色 2026-09-03 换成品牌紫后，它与 [ShopColors.accent] **完全同色**。
    /// 购物车角标正是 accent 底，且有一多半悬在顶栏上 —— 不套白环就只剩一个飘着的
    /// 白数字，读不出这是件数角标。
    testWidgets('🔴 购物车角标压在紫顶栏上必须自带白环', (tester) async {
      await tester.pumpWidget(
          host([p('a', price: 185000)], banner: banner, cartCount: 3));
      await tester.pumpAndSettle();

      final ring = tester.widget<Container>(find
          .ancestor(
              of: find.byKey(const ValueKey('cartBadgeV2')),
              matching: find.byType(Container))
          .first);
      expect((ring.decoration! as BoxDecoration).color, ShopColors.surface,
          reason: '角标底与顶栏底同为 #845EC9，紫压紫');
    });

    /// 🔴 这条不是"要求两者同色"，是把**碰撞事实**钉在原地。
    /// 它一旦变红，说明有人改了顶栏底色或 accent —— 那么当初为这次碰撞打的两个补丁
    /// （订单列表 Tab 下划线改白、购物车角标加白环）都要回去重估，别留着当无谓的特例。
    test('顶栏底色与 accent 同色 —— 压在顶栏上的元素不得直接用 accent', () {
      expect(ShopAppBar.colorsOf(ShopAppBarTone.dark).background, ShopColors.accent);
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

      // 「全部」是 2026-09-02 加的第 5 个（R-4 ③），排最左。
      for (final c in ['Semua', 'Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan']) {
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
        for (final c in ['Semua', 'Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan'])
          tester.getTopLeft(find.text(c)).dy,
      };
      expect(tops.length, 1, reason: '出现第二个 y 值 = 折行了');
    });
  });
}
