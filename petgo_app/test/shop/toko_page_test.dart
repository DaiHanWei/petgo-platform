import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/presentation/toko_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 1.6：Toko 首页（FR-93 / FR-93A）。
///
/// 本类看住的多数是**「不该出现的东西」**——没有搜索框、没有区域①② 的残留、没有登录引导。
/// 这类断言的特点是：功能写错了它才红，功能没写它也绿，所以每条都做过变异验证（见 story）。
void main() {
  Widget host(List<ShopProductSummary> products) {
    return ProviderScope(
      overrides: [
        shopProductsProvider.overrideWith((ref, category) async => products),
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
        home: TokoPage(),
      ),
    );
  }

  ShopProductSummary p(String token, {int? price, String? img}) => ShopProductSummary(
        token: token,
        name: 'Produk $token',
        brand: 'BrandX',
        category: ShopCategory.makanan,
        mainImageUrl: img,
        minPrice: price,
      );

  group('AC1 页面结构', () {
    testWidgets('🔴 不提供全站搜索框（FR-93）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 285000)]));
      await tester.pumpAndSettle();

      // 搜索框会把产品心智推向通用货架，与「精选不做货架」的战略边界冲突。
      // 这是永久性决策，不是暂缓 —— 所以这条断言不该有被放宽的一天。
      expect(find.byType(TextField), findsNothing);
      expect(find.byType(TextFormField), findsNothing);
      expect(find.byIcon(Icons.search), findsNothing);
    });

    testWidgets('区域③ 渲染四个固定品类 chip', (tester) async {
      await tester.pumpWidget(host([]));
      await tester.pumpAndSettle();

      expect(find.text('Makanan'), findsOneWidget);
      expect(find.text('Obat & Vitamin'), findsOneWidget);
      expect(find.text('Camilan'), findsOneWidget);
      expect(find.text('Perawatan'), findsOneWidget);
      expect(find.byType(ChoiceChip), findsNWidgets(4));
    });

    testWidgets('🔴 区域①② 整区不渲染，不留空标题 —— 首个 section 必须是 Kategori', (tester) async {
      await tester.pumpWidget(host([p('a', price: 1000)]));
      await tester.pumpAndSettle();

      expect(find.text('Kategori'), findsOneWidget);
      expect(find.text('Semua Pilihan'), findsOneWidget);

      // 原型注释原文：「①② 区域整体不渲染，无空标题」。
      // 写成「留个标题 + 空列表」就违反 AC1 —— 这是合法状态而非待补占位。
      final texts = tester.widgetList<Text>(find.byType(Text)).map((t) => t.data).toList();
      final kategoriIdx = texts.indexOf('Kategori');
      expect(kategoriIdx, isNonNegative);
      // Kategori 之前只允许出现 AppBar 标题「Toko」，不得有任何其他 section 标题
      expect(texts.sublist(0, kategoriIdx).where((t) => t != 'Toko'), isEmpty,
          reason: 'Kategori 之前出现了额外文案，说明区域①② 留了残留标题');
    });
  });

  group('AC2 游客态', () {
    testWidgets('🔒 游客态渲染不触发任何登录引导 / 弹窗 / 跳转（FR-93A）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 285000)]));
      await tester.pumpAndSettle();

      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
      // 页面本体仍正常渲染（不是被挡住之后的空白）
      expect(find.text('Semua Pilihan'), findsOneWidget);
    });

    // ✏️ Story 3.6：购物车页已落地，这条从「不可点」翻面成「可点且不是登录墙」。
    //    原断言（onPressed == null）当时的意思是「不得跳到不存在的页面/登录页」，
    //    而不是「购物车图标永远不能点」—— 页面存在之后继续禁用它才是死链。
    //    「游客点进去不被踢到登录页」由 cart_page_test.dart 的游客态用例接手看守。
    testWidgets('购物车图标可点（Story 3.6 起），且游客态不显示角标', (tester) async {
      await tester.pumpWidget(host([]));
      await tester.pumpAndSettle();

      final cart = tester.widget<IconButton>(
        find.ancestor(of: find.byIcon(Icons.shopping_cart_outlined),
            matching: find.byType(IconButton)),
      );
      expect(cart.onPressed, isNotNull, reason: '购物车页已存在，禁用即死链');
      // 游客无购物车 → 件数 0 → 不渲染角标（绝不显示占位数字）。
      expect(find.byKey(const ValueKey('cartBadge')), findsNothing);
    });
  });

  group('AC4 商品卡的空值降级', () {
    testWidgets('🔴 minPrice 为 null 时显示占位，不显示 Rp 0', (tester) async {
      await tester.pumpWidget(host([p('a')]));
      await tester.pumpAndSettle();

      expect(find.text('Rp 0'), findsNothing, reason: '无 SKU 是「缺失价格」，不是「价格为 0」');
      expect(find.text('Harga segera'), findsOneWidget);
    });

    testWidgets('mainImageUrl 为 null 时不崩、走占位块', (tester) async {
      await tester.pumpWidget(host([p('a', price: 1000)]));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byType(Image), findsNothing);
    });

    testWidgets('价格按印尼盾格式化（千分位用点、无小数）', (tester) async {
      await tester.pumpWidget(host([p('a', price: 285000)]));
      await tester.pumpAndSettle();
      expect(find.text('Rp 285.000'), findsOneWidget);
    });
  });

  group('AC5 埋点', () {
    testWidgets('上报 toko_tab_viewed 与 toko_product_shown(zone=all_featured)', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, props) => events.add((e, props));
      addTearDown(() => Analytics.debugCaptureSink = null);

      await tester.pumpWidget(host([p('tok1', price: 1000)]));
      await tester.pumpAndSettle();

      expect(events.map((e) => e.$1), contains('toko_tab_viewed'));
      final impression = events.firstWhere((e) => e.$1 == 'toko_product_shown');
      expect(impression.$2?['zone'], 'all_featured');
      expect(impression.$2?['product_token'], 'tok1');
    });
  });

  group('🔒 源码护栏：守住「没发生的改动」', () {
    // 这两条守的是「本 Story 不该动的东西」。它们在功能测试里永远不会红，
    // 只能靠直接读源码断言 —— 变异验证方式见 story（把 /shop 塞进受控名单 → 必须红）。

    // ⚠️ 本条原为「AppTab 仍是既有 4 值（DEP-1 未闭合，不得动 Tab）」。
    //    2026-08-21 DEP-1 由产品闭合：Toko 顶替 Health 占第 2 位，问诊入口移入健康记录页。
    //    护栏**翻转而非删除** —— 守的对象从「别动」变成「别改回去、别把问诊改丢」。
    test('🔴 AppTab 第 2 位是 Toko，且问诊没有被改丢（DEP-1 已闭合）', () {
      final src = File('lib/shared/widgets/bottom_tab_bar.dart').readAsStringSync();
      final enumBody = src.substring(src.indexOf('enum AppTab'));
      final decl = enumBody.substring(0, enumBody.indexOf(';'));

      for (final expected in ["profile('/profile'", "shop('/shop'", "home('/home'", "me('/me'"]) {
        expect(decl, contains(expected), reason: 'Tab 值被改动');
      }
      expect(decl, isNot(contains("triage('/triage'")),
          reason: 'Health 已让出 Tab 位给 Toko');

      // 🔴 问诊让出 Tab 位后必须仍有界面入口，否则「问诊已并入健康记录」只是说法。
      //    这是 DEP-1 闭合的前置条件，删掉入口 = 让一个付费功能失去唯一通路。
      final health = File('lib/features/profile/presentation/health_list_page.dart').readAsStringSync();
      expect(health, contains("ValueKey('healthStartConsult')"),
          reason: '健康记录页的「发起问诊」入口被移除 —— 问诊将无任何界面通路');
      expect(health, contains("push('/triage')"),
          reason: '发起问诊应 push（/triage 已非 shell 分支根，push 保留返回栈）');

      // 🔴 /triage 不再是 shell 分支，但十余处 context.go('/triage') 仍以它为返回落点。
      final router = File('lib/core/router/app_router.dart').readAsStringSync();
      expect(router, contains("GoRoute(path: '/triage'"),
          reason: '/triage 顶层路由被删 —— 问诊流程结束后的返回落点会 404');
    });

    test('🔴 通向 /shop 的跳转一律用 go，不得 push（shell 分支根的 GlobalKey 陷阱）', () {
      // push 一个 shell 分支根会二次构建 StatefulShellRoute → GlobalKey 撞车 → release 白屏，
      // 且此后该分支 goBranch 持续抛异常（bug 20260729-纪念日通知白屏）。
      // DEP-1 闭合把 /shop 变成了分支根，健康记录页的 FR-110 跳转当场从无害变成踩雷，
      // 已同批改为 go。本条防它被改回去，也防新增跳转重蹈覆辙。
      final roots = File('lib/core/router/deep_link_routes.dart').readAsStringSync();
      expect(roots, contains("'/shop'"), reason: '/shop 应登记为 shell 分支根');
      expect(roots, isNot(contains("'/triage', '/me'")), reason: '/triage 已不是分支根');

      for (final f in Directory('lib').listSync(recursive: true).whereType<File>()
          .where((f) => f.path.endsWith('.dart'))) {
        final src = f.readAsStringSync();
        expect(src, isNot(contains("push('/shop?")),
            reason: '${f.path}: 用 push 打开 Toko 会白屏，改用 context.go');
        expect(src, isNot(contains("push('/shop')")),
            reason: '${f.path}: 用 push 打开 Toko 会白屏，改用 context.go');
      }
    });

    test('🔴 受控路由白名单未被改动，且 /shop 未被加入（游客须能直接浏览）', () {
      final src = File('lib/core/router/app_router.dart').readAsStringSync();

      // 逐个点名既有 6 个前缀 —— 不用「数量 == 6」，那样删一个再加一个就能蒙混过去
      final start = src.indexOf('_controlledLocations = {');
      expect(start, isNonNegative);
      final list = src.substring(start, src.indexOf('}', start));
      for (final p in ["'/profile'", "'/triage'", "'/me'", "'/consult'", "'/notifications'", "'/publish'"]) {
        expect(list, contains(p), reason: '受控前缀 $p 被移除 —— 安全规则只升不降');
      }
      expect(list, isNot(contains("'/shop'")),
          reason: 'FR-93A 要求游客可浏览；把 /shop 加进受控名单会让游客被 redirect 回 /home');

      // 精确例外集也不该被本 Story 动过
      final exStart = src.indexOf('_controlledExactExceptions = {');
      expect(exStart, isNonNegative);
      final ex = src.substring(exStart, src.indexOf('}', exStart));
      expect(ex, contains("'/profile'"));
      expect(ex, isNot(contains("'/shop'")),
          reason: '游客可访问应靠「不进受控名单」达成，而不是「加一条例外」');
    });
  });

  group('domain', () {
    test('formatIdr：千分位用点、无小数', () {
      expect(formatIdr(285000), 'Rp 285.000');
      expect(formatIdr(1000000), 'Rp 1.000.000');
      expect(formatIdr(999), 'Rp 999');
      expect(formatIdr(0), 'Rp 0');
    });

    test('fromJson：缺字段不崩，minPrice/mainImageUrl 可空', () {
      final s = ShopProductSummary.fromJson({'token': 't', 'name': 'n', 'brand': 'b'});
      expect(s.minPrice, isNull);
      expect(s.mainImageUrl, isNull);
      expect(s.category, isNull);
    });

    test('fromJson：空字符串图片 URL 视为无图', () {
      final s = ShopProductSummary.fromJson(
          {'token': 't', 'name': 'n', 'brand': 'b', 'mainImageUrl': ''});
      expect(s.mainImageUrl, isNull);
    });
  });
}
