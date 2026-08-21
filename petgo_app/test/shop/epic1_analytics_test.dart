import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page.dart';
import 'package:tailtopia/features/shop/presentation/toko_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 1.8：Epic 1 埋点收口。
///
/// 🔴 **埋点与功能必须同版本发布** —— 功能上线了埋点没跟上，等于这个版本的效果无法度量，
/// 而 V1.4.0 的整个论证（复购引擎值不值得做）都建立在这批数据上。
///
/// 🔒 **埋点禁带 PII**（NFR-5）：本类逐个属性名核查，不靠「写的时候注意点」。
void main() {
  const localizations = [
    AppLocalizations.delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
  ];

  Widget wrap(Widget home, List<Object> overrides) => ProviderScope(
        overrides: overrides.cast(),
        child: MaterialApp(
          localizationsDelegates: localizations,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: home,
        ),
      );

  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  ShopSku sku(String t, {StockStatus stock = StockStatus.inStock}) => ShopSku(
        token: t,
        specName: t,
        price: 285000,
        returnPolicy: ReturnPolicy.noReturnAfterOpen,
        stockStatus: stock,
      );

  testWidgets('Toko 页：toko_tab_viewed + toko_product_shown(zone)', (t) async {
    await t.pumpWidget(wrap(
      const TokoPage(),
      [
        shopProductsProvider.overrideWith((ref, c) async => [
              const ShopProductSummary(
                  token: 'tok1', name: 'n', brand: 'b', minPrice: 285000),
            ]),
      ],
    ));
    await t.pumpAndSettle();

    expect(events.map((e) => e.$1), containsAll(['toko_tab_viewed', 'toko_product_shown']));
    final impression = events.firstWhere((e) => e.$1 == 'toko_product_shown');
    expect(impression.$2?['zone'], 'all_featured',
        reason: 'zone 区分区域②档案推荐与区域④全部精选，Epic 6 接入后才分得清转化来源');
  });

  testWidgets('详情页：toko_product_detail_viewed', (t) async {
    await t.pumpWidget(wrap(
      const ProductDetailPage(token: 'tok1'),
      [
        shopProductDetailProvider.overrideWith((ref, token) async => ShopProductDetail(
              token: 'tok1', name: 'n', brand: 'b',
              returnPolicy: ReturnPolicy.returnable, skus: [sku('3 kg')],
            )),
      ],
    ));
    await t.pumpAndSettle();

    expect(events.map((e) => e.$1), contains('toko_product_detail_viewed'));
  });

  testWidgets('售罄曝光：toko_out_of_stock_shown（转化漏斗上的流失点）', (t) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });

    await t.pumpWidget(wrap(
      const ProductDetailPage(token: 'tok1'),
      [
        shopProductDetailProvider.overrideWith((ref, token) async => ShopProductDetail(
              token: 'tok1', name: 'n', brand: 'b',
              returnPolicy: ReturnPolicy.returnable,
              skus: [sku('3 kg', stock: StockStatus.outOfStock)],
            )),
      ],
    ));
    await t.pumpAndSettle();

    final oos = events.where((e) => e.$1 == 'toko_out_of_stock_shown');
    expect(oos, hasLength(1), reason: '同一 SKU 反复 rebuild 不得重复打点');
    expect(oos.first.$2?['sku_token'], '3 kg');
  });

  group('🔒 NFR-5 埋点禁带 PII', () {
    test('shop 模块所有埋点属性名不含任何 PII 字段', () {
      // 逐个点名禁用字段，而不是「不含 user 字样」这类模糊断言 ——
      // 后者既会误伤（user_type 是合法的），也漏得掉（phone / address 都不含 user）。
      const forbidden = [
        'name', 'phone', 'email', 'address', 'lat', 'lng', 'ktp',
        'receiver', 'recipient', 'nickname', 'avatar',
      ];
      final dir = Directory('lib/features/shop');
      final props = <String>{};
      final propRe = RegExp(r"'([a-z0-9_]+)':\s"); // 埋点属性 map 的键
      for (final f in dir.listSync(recursive: true).whereType<File>()) {
        if (!f.path.endsWith('.dart')) continue;
        final src = f.readAsStringSync();
        // ⚠️ 终止符是 `);` 而不是 `)`（Story 9.2 修）：原先遇到调用内部的第一个 `)`
        //    就收工，于是 `items: [ for (final l in p.lines) {...} ]` 这种嵌套 payload
        //    只被看到最外两个键 —— 恰好是本 story 新加的行级归因整块漏检。
        for (final m in RegExp(r"Analytics\.capture\([^;]*?\);", dotAll: true).allMatches(src)) {
          for (final p in propRe.allMatches(m.group(0)!)) {
            props.add(p.group(1)!);
          }
        }
      }
      expect(props, isNotEmpty, reason: '一个埋点属性都没提取到 —— 提取逻辑坏了，不是真没埋点');
      for (final p in props) {
        for (final bad in forbidden) {
          expect(p.contains(bad), isFalse,
              reason: '埋点属性 "$p" 命中禁用词 "$bad" —— NFR-5 埋点禁带 PII');
        }
      }
      // 🔒 白名单：新增属性时这条会红，**逼你逐个确认它不是 PII**（这正是它红的意义）。
      // Story 3.10 新增两项，已逐个确认：
      // - pay_channel：枚举字面量 QRIS / PAWCOIN / MIXED / UNKNOWN，与个人无关
      // - item_count：整数件数，与个人无关
      // Story 6.4 新增两项，已逐个确认：
      // - trigger_type：枚举字面量 FOOD_LOW / DEWORM / VACCINE，与个人无关
      // - product_id：商品的不可枚举 token，与个人无关
      // 🔴 **被这条挡下来并因此没有上报的**：`reco_reason`（Story 6.5 的 AC 要求带）——
      //    理由文本含宠物的年龄段与体型区间，等于把档案的粗化版本送进三方分析平台。
      //    product_id 足以在服务端 join 回商品维度还原理由，分析能力一点没少。
      // Story 9.2 新增四项（归因链闭合），已逐个确认：
      // - items：行级归因数组本身；其元素同样过 Analytics.scrub 三道规则
      //   （List 递归是本 story 补的，见 test/shop/attribution_closure_test.dart）
      // - sku_id：SKU 的不可枚举 token，与个人无关
      // - qty：整数数量，与个人无关
      // - entry_source / attribution_source：受控词表（toko_featured / toko_repurchase_card /
      //   mixed / unknown …），与个人无关
      // V1.4.0 第 3 批新增一项，已逐个确认：
      // - card_source：复购触发卡出现在哪个界面（`diary` / `home`），受控词表两值。
      //   设计文档要求区分这两处的转化率（记录里的卡 vs 商城里的卡效果差很多），
      //   与个人无关。⚠️ 名字**刻意不叫 `source`**：那个词太泛，看板上分不清
      //   它说的是「卡片在哪」还是「用户从哪来」（后者是 entry_source）。
      expect(props, {
        'product_token', 'sku_token', 'zone', 'pay_channel', 'item_count',
        'trigger_type', 'product_id',
        'items', 'sku_id', 'qty', 'entry_source', 'attribution_source',
        'card_source',
      });
    });

    test('🔴 Epic 1 声明的四个事件在源码里确实存在（声明与实现不许脱节）', () {
      final dir = Directory('lib/features/shop');
      final src = dir
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'))
          .map((f) => f.readAsStringSync())
          .join('\n');

      // epics AC 写的是 product_impression / product_detail_viewed / out_of_stock_viewed，
      // 但仓库埋点命名护栏要求「模块前缀 + 动作词尾」，故统一带 toko_ 前缀并用动作词尾。
      for (final e in [
        'toko_tab_viewed',
        'toko_product_shown',
        'toko_product_detail_viewed',
        'toko_out_of_stock_shown',
      ]) {
        expect(src, contains("'$e'"), reason: 'Epic 1 声明的事件 $e 在源码里不存在');
      }
    });
  });
}
