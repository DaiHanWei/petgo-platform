import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/presentation/cart_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 购物车 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// v1 版式的用例在 `cart_page_test.dart`，两套互不影响。
///
/// 本类看的是**会造成资损或误导**的几件事：失效行不得计入合计、库存不明时不得加、
/// 单店模型不得长出店铺分组，以及 V1.3.0 起的行选择（Story 4-2）——
/// 底栏金额必须来自后端的 `selectedSubtotal`、取消勾选不得走 `remove`、
/// 失效行的勾选框必须**占位且如实反映服务端的 selected**。这三条是当年
/// 「勾选框刻意不实现」那个决定的三条理由，能力补齐之后它们并没有消失，只是换了形态。
///
/// ⚠️ 第三条在 2026-09-18 的复审里被纠正过一次：它曾经写作「必须**禁用**」，
/// 而服务端的 `selected` 缺省是 TRUE —— 画成「没勾」会让结算恒 409 且无从解除。
/// 详见「Story 4-2：行选择」那一组里 ③ 的说明。
void main() {
  Widget host(
    CartView cart, {
    Size size = const Size(411, 891),
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        // 已登录态：游客分支走的是软性引导页，跟本类要测的渲染无关。
        authControllerProvider.overrideWith(() => _TestAuthController(
              const AuthState(status: AuthStatus.authenticated, role: 'USER'),
            )),
        // 直接覆写 controller 的初始值：本类只测渲染，不测拉取。
        cartProvider.overrideWith(() => _FakeCartController(cart)),
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
          child: const CartPageV2(),
        ),
      ),
    );
  }

  CartLine line(
    String token, {
    String name = 'Royal Canin Adult Dog',
    String spec = '3 kg',
    int price = 185000,
    int qty = 1,
    int? stock = 10,
    CartInvalidReason? invalid,
    bool selected = true,
  }) =>
      CartLine(
        skuToken: token,
        productName: name,
        specName: spec,
        price: price,
        qty: qty,
        availableStock: stock,
        invalidReason: invalid,
        selected: selected,
      );

  /// Story 4-2：默认按「勾选且有效」算选中口径，与后端 CartService.view 同一条规则。
  ///
  /// ⚠️ 这里刻意**不**让 selectedSubtotal 等于 subtotal ——
  /// 两者相等只是「全选」这一个特例，假实现若把它写死，
  /// 「取消一行后底栏变小」的用例就永远测不出东西来。
  CartView cartOf({
    List<CartLine> valid = const [],
    List<CartLine> invalid = const [],
    int? subtotal,
    int? itemCount,
    int? selectedSubtotal,
    int? selectedCount,
  }) {
    final picked = valid.where((l) => l.selected);
    return CartView(
      lines: valid,
      invalidLines: invalid,
      subtotal: subtotal ?? valid.fold(0, (a, l) => a + l.lineTotal),
      itemCount: itemCount ?? valid.fold(0, (a, l) => a + l.qty),
      selectedSubtotal:
          selectedSubtotal ?? picked.fold(0, (a, l) => a + l.lineTotal),
      selectedCount: selectedCount ?? picked.fold(0, (a, l) => a + l.qty),
    );
  }

  group('🔴 单店模型：没有店铺分组（设计稿关键原则 1）', () {
    testWidgets('多行商品下只有一个总计，不出现分店铺小计', (tester) async {
      await tester.pumpWidget(host(cartOf(valid: [
        line('a', qty: 2),
        line('b', name: 'Whiskas Adult Cat', price: 78000),
      ])));
      await tester.pumpAndSettle();

      // 单店模型：整车一个总计。多一个 = 长出了分组小计。
      expect(_totalOf(tester), 'Rp 448.000');
      expect(find.byType(ShopBottomBarWithTotal), findsOneWidget);
    });
  });

  group('🔴 失效行：沉底、不计入合计、不静默消失', () {
    testWidgets('失效行渲染在独立分组里，且合计不含它', (tester) async {
      await tester.pumpWidget(host(cartOf(
        valid: [line('a', qty: 1)],
        invalid: [line('x', name: 'Vitamin', price: 95000, invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      expect(find.text('Vitamin'), findsOneWidget,
          reason: '悄悄删掉失效行会让用户以为自己记错了');
      expect(find.textContaining('Tidak Tersedia'), findsOneWidget);
      // 🔴 直接断言底部条的金额，而不是 find.text —— 单价与合计可能同值，
      //    那样的断言会在「合计错算成含失效行」时依然绿。
      expect(_totalOf(tester), 'Rp 185.000',
          reason: '把卖不了的东西放进合计 = 用户付了钱才发现');
    });

    testWidgets('🔴 失效行的主出口是「找相似」而不是只有删除', (tester) async {
      await tester.pumpWidget(host(cartOf(
        invalid: [line('x', invalid: CartInvalidReason.delisted)],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('cartFindSimilar_x')), findsOneWidget,
          reason: '失效不等于流失 —— 买不到时用户真正想要的是「有没有别的」');
      expect(find.byKey(const ValueKey('cartRemoveInvalid_x')), findsOneWidget);
    });

    testWidgets('失效行价格转灰，不用玫红', (tester) async {
      await tester.pumpWidget(host(cartOf(
        invalid: [line('x', price: 95000, invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      final price = tester.widget<Text>(find.text('Rp 95.000'));
      expect(price.style?.color, ShopColors.text4);
      expect(price.style?.color, isNot(ShopColors.accent));
    });

    testWidgets('认不出的失效原因照样算失效（不当成有效行）', (tester) async {
      // 后端加了新的原因值而 App 未升级时的情形。
      await tester.pumpWidget(host(cartOf(
        valid: [line('a')],
        invalid: [line('x', price: 95000, invalid: CartInvalidReason.unavailable)],
      )));
      await tester.pumpAndSettle();

      expect(find.textContaining('Tidak Tersedia'), findsOneWidget);
      expect(_totalOf(tester), 'Rp 185.000');
    });
  });

  group('🔴 库存：不明时不得加', () {
    testWidgets('availableStock 为 null → + 不可点', (tester) async {
      await tester.pumpWidget(host(cartOf(valid: [line('a', qty: 2, stock: null)])));
      await tester.pumpAndSettle();

      final stepper = tester.widget<ShopStepper>(find.byType(ShopStepper));
      expect(stepper.max, 2,
          reason: '库存不明时上限=当前数量 → 加不了。宁可挡一次购买，不可放过一次超卖');
    });

    testWidgets('触顶时出现剩余库存提示', (tester) async {
      await tester.pumpWidget(host(cartOf(valid: [line('a', qty: 3, stock: 3)])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('cartStockTag')), findsOneWidget);
    });

    testWidgets('未触顶时不显示库存提示 —— 常驻会退化成背景噪音', (tester) async {
      await tester.pumpWidget(host(cartOf(valid: [line('a', qty: 1, stock: 10)])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('cartStockTag')), findsNothing);
    });
  });

  // ⚠️ 这里原本有一组「不渲染任何勾选框」的用例，断言的是本页刻意**不**实现行选择
  //    —— 因为当时下单接口整车下单，画一个不影响下单的勾选框是「能造成资损的谎」。
  //    V1.3.0 的 4-1 把后端能力补上了（shop_cart_items.selected + 两个选择端点），
  //    那组用例连同它守的那个缺席一起被下面这组取代。
  group('🔴 Story 4-2：行选择（SHOP-FR-04）', () {
    /// 造一个「controller 可被断言」的宿主。`host()` 把 controller 藏在 override 里，
    /// 而这一组要看「点了之后调的是谁」。
    Future<_FakeCartController> pumpCart(WidgetTester tester, CartView cart) async {
      final ctrl = _FakeCartController(cart);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          cartProvider.overrideWith(() => ctrl),
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
          home: const CartPageV2(),
        ),
      ));
      await tester.pumpAndSettle();
      return ctrl;
    }

    testWidgets('① 三行全选：每行一个勾选框，底栏显示 selectedSubtotal', (tester) async {
      await tester.pumpWidget(host(cartOf(valid: [
        line('a', price: 100000),
        line('b', price: 50000),
        line('c', price: 30000),
      ])));
      await tester.pumpAndSettle();

      // 三行 + 一个全选 = 四个勾选框。
      expect(find.byType(ShopCheckbox), findsNWidgets(4));
      expect(find.byKey(const ValueKey('cartSelectAllV2')), findsOneWidget);
      expect(find.text('Rp 180.000'), findsOneWidget,
          reason: '全选时 selectedSubtotal 等于全车合计');
      expect(find.text('Total (3 barang)'), findsOneWidget);
    });

    testWidgets('🔴 ② 点掉一行：底栏数字变小，setSelected 调一次、remove 零次',
        (tester) async {
      // ⚠️ 数值刻意让「行单价」与「底栏合计」不重合：a 的单价 100.000 但 qty=2，
      //    所以底栏是 200.000 —— 否则 findsOneWidget 会同时命中行里那个价格。
      final ctrl = await pumpCart(
          tester,
          cartOf(valid: [
            line('a', price: 100000, qty: 2),
            line('b', price: 50000),
          ]));
      expect(find.text('Rp 250.000'), findsOneWidget);
      expect(find.text('Total (3 barang)'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('cartLineCheckbox_b')));
      await tester.pumpAndSettle();

      expect(find.text('Rp 200.000'), findsOneWidget,
          reason: '底栏必须改读 selectedSubtotal，否则勾选框就是个不影响结果的谎');
      expect(find.text('Total (2 barang)'), findsOneWidget);
      expect(ctrl.selectedCalls, [(sku: 'b', selected: false)]);
      // 🔴 AC5 的可测化：取消勾选是「这次不买」，不是「不要了」。
      expect(ctrl.removed, isEmpty,
          reason: '用 DELETE 模拟「不买这件」= 用破坏性操作实现查询语义，取消结算就丢数据');
    });

    /// 🔴🔴 ③ 失效行的勾选框：**占位、如实、可点**（2026-09-18 复审 #4 改写）。
    ///
    /// 这一条原本断言的是「禁用」，代码也就写死了 `value:false, enabled:false`。
    /// 但服务端 `shop_cart_items.selected` 缺省是 **TRUE**，失效行照样勾着 ——
    /// 于是界面说「没勾」、`CheckoutService` 按「勾着」在结算时抛 409，
    /// 而用户手上**没有任何控件**能取消那个勾：Story 4-1 的「跳过失效行」对失效行
    /// 完全失效，唯一出路又变回破坏性的「清空失效商品」。
    ///
    /// 🎯 **变异靶子**：把 `_InvalidLine` 的勾选框改回
    /// `ShopCheckbox(value: false, enabled: false, onChanged: null)`，本组必须变红。
    testWidgets('🔴 ③ 失效行勾选框占位且如实反映服务端的 selected', (tester) async {
      await tester.pumpWidget(host(cartOf(
        valid: [line('a')],
        invalid: [line('x', invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      // 全选 + 有效行 + 失效行 = 三个；一个都不能少（少了列表左边缘会参差）。
      expect(find.byType(ShopCheckbox), findsNWidgets(3),
          reason: '失效行要占位，不能整个隐藏');

      final box = tester.widget<ShopCheckbox>(
          find.byKey(const ValueKey('cartInvalidLineCheckbox_x')));
      expect(box.value, isTrue,
          reason: '服务端 selected 缺省 TRUE —— 界面画成没勾就是在说谎，'
              '而结算会因为这个看不见的勾恒 409');
      expect(box.onChanged, isNotNull,
          reason: '不可点的话用户没有任何办法取消那个勾');
      expect(box.enabled, isTrue);
    });

    testWidgets('🎯 ③b 取消勾选失效行 → 走 setSelected(false)，不走 remove',
        (tester) async {
      final ctrl = await pumpCart(
          tester,
          cartOf(
            valid: [line('a', price: 100000)],
            invalid: [line('x', invalid: CartInvalidReason.delisted)],
          ));

      await tester.tap(find.byKey(const ValueKey('cartInvalidLineCheckbox_x')));
      await tester.pumpAndSettle();

      expect(ctrl.selectedCalls, [(sku: 'x', selected: false)],
          reason: '这是用户让结算放行的唯一出口 —— 少了它就只剩「清空失效商品」');
      // 🔴 取消勾选是「这次不买」，不是「不要了」：商品得留在车里等补货。
      expect(ctrl.removed, isEmpty);
      expect(find.text('Rp 100.000'), findsWidgets,
          reason: '失效行本来就不计入合计，取消勾选不该让有效行的钱跟着变');

      // 改完之后界面要跟着变成「没勾」（不做乐观更新，读的是端点返回的整份车）。
      final box = tester.widget<ShopCheckbox>(
          find.byKey(const ValueKey('cartInvalidLineCheckbox_x')));
      expect(box.value, isFalse);
    });

    testWidgets('③c 失效行的勾选**不进任何合计**（可点 ≠ 可买）', (tester) async {
      await tester.pumpWidget(host(cartOf(
        valid: [line('a', price: 100000)],
        invalid: [line('x', price: 95000, invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      expect(_totalOf(tester), 'Rp 100.000',
          reason: '勾着的失效行一旦进合计，用户付了钱才发现买不到');
      expect(find.text('Total (1 barang)'), findsOneWidget);
    });

    testWidgets('🔴 ④ 全不选：结算按钮禁用，且点击不发任何请求', (tester) async {
      final ctrl = await pumpCart(
          tester,
          cartOf(valid: [
            line('a', price: 100000, selected: false),
            line('b', price: 50000, selected: false),
          ]));

      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('cartCheckoutV2')));
      expect(btn.onTap, isNull, reason: '禁用态不能靠后端 422 来告诉用户');
      // 光 onTap 为 null 不够：ShopButton 的配色只看 variant，不切的话按钮照样实心紫（2026-09-21 stag 验收）。
      expect(btn.variant, ShopButtonVariant.disabled, reason: '禁用态必须看得出来');
      expect(find.text('Pilih minimal satu produk untuk checkout'), findsOneWidget);
      expect(find.text('Rp 0'), findsOneWidget);

      // 点下去什么都不该发生（既不跳转也不发请求）。
      await tester.tap(find.byKey(const ValueKey('cartCheckoutV2')), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(ctrl.selectedCalls, isEmpty);
      expect(ctrl.removed, isEmpty);
    });

    testWidgets('🔴 ⑤ 车里有失效行时，全选框仍呈现「已全选」', (tester) async {
      // 🔴 若把失效行算进全选判定，车里只要有一件下架商品这个框就永远点不亮 ——
      //    用户会以为控件坏了，而他其实什么都没做错。
      await tester.pumpWidget(host(cartOf(
        valid: [line('a'), line('b')],
        invalid: [line('x', invalid: CartInvalidReason.delisted)],
      )));
      await tester.pumpAndSettle();

      final selectAll =
          tester.widget<ShopCheckbox>(find.byKey(const ValueKey('cartSelectAllV2')));
      expect(selectAll.value, isTrue);
    });

    testWidgets('全选框点击走 setAllSelected，不是逐行 setSelected', (tester) async {
      final ctrl = await pumpCart(tester, cartOf(valid: [line('a'), line('b')]));

      await tester.tap(find.byKey(const ValueKey('cartSelectAllV2')));
      await tester.pumpAndSettle();

      expect(ctrl.selectAllCalls, [false], reason: '当前已全选，点一下是全不选');
      expect(ctrl.selectedCalls, isEmpty, reason: 'N 次请求做一件事，中途失败还会留下半选态');
    });

    /// ⚠️ 这里原本有一条「勾选一个失效行不可能发生：它的勾选框根本点不动」。
    /// 它守的正是 2026-09-18 复审 #4 判定为缺陷的那个行为（见 ③ 的说明），
    /// 已随修复一并改写成下面这条：**重新勾上**同样要能走通 ——
    /// 用户补货回来想买，不该被迫先删掉再重新加购。
    testWidgets('失效行取消勾选后还能再勾回来（等补货）', (tester) async {
      final ctrl = await pumpCart(
          tester,
          cartOf(
            valid: [line('a')],
            invalid: [
              line('x', invalid: CartInvalidReason.outOfStock, selected: false)
            ],
          ));

      await tester.tap(find.byKey(const ValueKey('cartInvalidLineCheckbox_x')));
      await tester.pumpAndSettle();

      expect(ctrl.selectedCalls, [(sku: 'x', selected: true)]);
      expect(ctrl.removed, isEmpty);
    });
  });

  group('计数与空态', () {
    testWidgets('标题计数含失效商品', (tester) async {
      await tester.pumpWidget(host(cartOf(
        valid: [line('a'), line('b')],
        invalid: [line('x', invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      expect(find.text('Keranjang (3)'), findsOneWidget,
          reason: '不算失效项会让用户以为自己漏加了');
    });

    testWidgets('空车不渲染底部结算条', (tester) async {
      await tester.pumpWidget(host(cartOf()));
      await tester.pumpAndSettle();

      expect(find.text('Checkout'), findsNothing);
      expect(find.text('Keranjang masih kosong'), findsOneWidget);
    });

    testWidgets('只有失效行时也不渲染结算条', (tester) async {
      await tester.pumpWidget(host(cartOf(
        invalid: [line('x', invalid: CartInvalidReason.outOfStock)],
      )));
      await tester.pumpAndSettle();

      expect(find.text('Checkout'), findsNothing,
          reason: '一车都是买不了的东西，结算按钮点下去必然失败');
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 标准字号', (tester) async {
      await tester.pumpWidget(host(cartOf(
        valid: [line('a', qty: 2), line('b', name: 'Whiskas Adult Cat')],
        invalid: [line('x', invalid: CartInvalidReason.delisted)],
      )));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号（NFR-13 上限）', (tester) async {
      await tester.pumpWidget(host(
        cartOf(
          valid: [line('a', qty: 3, stock: 3, name: 'Royal Canin Adult Dog Premium Nutrition')],
          invalid: [line('x', invalid: CartInvalidReason.delisted)],
        ),
        textScale: 1.3,
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });
  });

  /// 🔴 R-3（2026-09-02 产品拍板）：删除必须可撤销。
  ///
  /// 风险不在「删除」本身，在**按钮位复用**：[ShopStepper] 在 `value <= min` 时
  /// 把同一个位置的「−」换成垃圾桶。用户连点减号往下收数量，**最后一下必然落在
  /// 已经变成垃圾桶的同一坐标上** —— 这不是手滑，是控件设计决定的必然结果。
  ///
  /// ⚠️ 产品明确否掉了二次确认弹窗：那会拖慢每一次正常的收数量操作，
  /// 而误删的代价用 undo 完全覆盖得住。
  ///
  /// ⚠️ 数量固定是 1：经步进器删除时必然已收到底（这正是误删路径本身）。
  /// 代码里仍按 `line.qty` 传而不是写死 1 —— 将来若多出别的删除入口，这里不必再改。
  group('🔴 R-3：删除后可撤销', () {
    Future<_FakeCartController> pumpOneLine(WidgetTester tester,
        {bool addFails = false}) async {
      final ctrl = _FakeCartController(CartView(
        lines: [line('sku-a', qty: 1)],
        invalidLines: const [],
        itemCount: 1,
        subtotal: 185000,
        selectedCount: 1,
        selectedSubtotal: 185000,
      ))..addFailsOnStock = addFails;
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          cartProvider.overrideWith(() => ctrl),
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
          home: const MediaQuery(
            data: MediaQueryData(size: Size(411, 891)),
            child: CartPageV2(),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return ctrl;
    }

    testWidgets('🔴 删除后弹出撤销条', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();

      expect(ctrl.removed, ['sku-a']);
      expect(find.byKey(const ValueKey('appToastAction')), findsOneWidget,
          reason: '删完什么都不说，误删的用户只能重新去找那件商品');
      expect(find.text('Urungkan'), findsOneWidget);
      // 提示条里带商品名 —— 连着删两件时，用户得看得出撤销的是哪一件
      expect(find.textContaining('dihapus'), findsOneWidget);

      await tester.pump(const Duration(seconds: 6)); // 放掉 toast 的自动消失 Timer
    });

    testWidgets('🔴 点撤销 → 按删除时的数量加回来', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();

      expect(ctrl.added, hasLength(1));
      expect(ctrl.added.single.sku, 'sku-a');
      expect(ctrl.added.single.qty, 1);
      // 点完动作 toast 立刻收起 —— 动作已经执行，再挂 5 秒只是挡视线
      expect(find.byKey(const ValueKey('appToastAction')), findsNothing);
    });

    testWidgets('撤销带自述的归因值，而不是留 null', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();

      // 后端购物车接口**刻意不下发**归因（CartView.CartLine 的注释写明了理由），
      // 端上无从还原原值。null 与「归因上线前的老数据」长得一样、事后分不清；
      // CART_UNDO 至少可解释、可筛掉。
      expect(ctrl.added.single.entrySource, 'CART_UNDO');
    });

    testWidgets('🔴 撤销条可点 —— 纯提示的 toast 是穿透的，带动作这条不能穿透',
        (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();

      // app_toast 默认包着 IgnorePointer（让点击穿透到底下的页面）。
      // 带动作时那层必须去掉，否则「撤销」按钮点不动 —— 而它是这条提示的全部意义。
      expect(find.ancestor(
        of: find.byKey(const ValueKey('appToastAction')),
        matching: find.byType(IgnorePointer),
      ), findsNothing);

      await tester.tap(find.byKey(const ValueKey('appToastAction')));
      await tester.pumpAndSettle();
      expect(ctrl.added, hasLength(1));
    });

    testWidgets('🔴 撤销失败（货被买走）必须出声，不能静默', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await pumpOneLine(tester, addFails: true);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();

      // 静默失败会让用户以为撤销成功了，直到结算才发现少了一件。
      expect(find.text(l10n.cartStockError), findsOneWidget);

      // toast 挂在 root Overlay 上、带一个 2.6s 的自动消失 Timer；
      // 不 pump 过去，测试结束时会因为「Timer is still pending」而红。
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('🔴 有效行必须删得掉（2026-08-21 默认变体翻到 v2 后一度删不掉）', () {
    // v1 的 `cart_page.dart` 一直是「qty=1 时 − 变垃圾桶」，v2 改版时漏了，
    // 于是默认版式翻过来之后，加错东西的用户只能整单买下或放弃结算。
    // 这条守的是**页面上那个入口**；步进器本身的行为在 shop_widgets_test.dart。
    testWidgets('qty=1 时行内出现删除图标，点击调用 remove()', (tester) async {
      final ctrl = _FakeCartController(CartView(
        lines: [line('sku-a', qty: 1)],
        invalidLines: const [],
        itemCount: 1,
        subtotal: 185000,
        selectedCount: 1,
        selectedSubtotal: 185000,
      ));
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          cartProvider.overrideWith(() => ctrl),
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
          home: const MediaQuery(
            data: MediaQueryData(size: Size(411, 891)),
            child: CartPageV2(),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.delete_outline), findsOneWidget,
          reason: '数量为 1 时「−」必须变成删除 —— 否则这一行没有任何删除入口');
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      expect(ctrl.removed, ['sku-a']);

      // 删除现在会弹 5 秒的撤销 toast（R-3），它带一个自动消失 Timer；
      // 不 pump 过去，测试结束时会因为「Timer is still pending」而红。
      await tester.pump(const Duration(seconds: 6));
    });
  });
}

/// 固定返回给定购物车的 controller。
///
/// 🔴 覆写整个 controller 而不是底层 repository：本类测的是渲染，
/// 走真 controller 会连带触发登录态判断与网络层，把「渲染对不对」和
/// 「拉取对不对」两件事搅在一起，红了分不清是哪边。
class _FakeCartController extends CartController {
  _FakeCartController(this._cart);

  CartView _cart;

  /// 被 `remove()` 掉的 skuToken，供用例断言。
  final removed = <String>[];

  /// 被 `add()` 加回来的行（撤销用例断言数量与归因有没有带对）。
  final added = <({String sku, int qty, String? entrySource})>[];

  /// Story 4-2：被 `setSelected()` 改过的行（AC5 断言「没走 remove」用）。
  final selectedCalls = <({String sku, bool selected})>[];

  /// 被 `setAllSelected()` 调用的值。
  final selectAllCalls = <bool>[];

  /// 置真则 `add()` 抛库存错误 —— 模拟「删掉到撤销之间货被别人买走」。
  bool addFailsOnStock = false;

  @override
  Future<CartView> build() async => _cart;

  /// 🔴 **必须真的把行从状态里去掉**（2026-09-03 修）。
  ///
  /// 原实现只记一笔 `removed.add(...)`、状态一动不动 —— 于是 `_ValidLine` 在测试里
  /// 永远挂在树上，而线上它删完就被卸载。差别正是 R-3「撤销点了没反应」能一路绿着
  /// 上线的原因：撤销回调当初挂在这一行的 State 上，真机里 State 已 defunct、
  /// `setState` 当场抛异常，测试里却活得好好的。
  /// ⚠️ 假实现可以简陋，但**不能比真实现更宽容** —— 那是在给缺陷发通行证。
  @override
  Future<void> remove(String skuToken) async {
    removed.add(skuToken);
    final rest = _cart.lines.where((l) => l.skuToken != skuToken).toList();
    _cart = _rebuild(rest);
    state = AsyncData(_cart);
  }

  /// Story 4-2（**C5 ④** 的落点）：勾选 / 取消勾选。
  ///
  /// 🔴 <b>真的改状态，并真的重算选中口径</b> —— 沿用本类 `remove` 那条教训：
  /// 假实现可以简陋，但**不能比真实现更宽容**。只记一笔调用而不动状态，
  /// 「取消一行后底栏数字变小」这条用例就会在一个永远不变的界面上绿着。
  ///
  /// 🔴 <b>本方法绝不碰 `removed`</b>：AC5 的可测化就是
  /// 「取消勾选后 `setSelected` 被调一次、`remove` 零次」。
  /// 🔴 <b>失效行同样要改到</b>（2026-09-18 复审 #4）：服务端的选择端点按 skuToken
  /// 寻址，压根不分这一行当下有效没有效。假实现只翻 `lines` 的话，
  /// 「取消勾选失效行」点下去界面纹丝不动，而那正是本次要修的缺陷所在的那一格。
  @override
  Future<void> setSelected(String skuToken, bool selected) async {
    selectedCalls.add((sku: skuToken, selected: selected));
    _cart = _rebuild(
      [
        for (final l in _cart.lines)
          l.skuToken == skuToken ? _copyWithSelected(l, selected) : l,
      ],
      invalid: [
        for (final l in _cart.invalidLines)
          l.skuToken == skuToken ? _copyWithSelected(l, selected) : l,
      ],
    );
    state = AsyncData(_cart);
  }

  @override
  Future<void> setAllSelected(bool selected) async {
    selectAllCalls.add(selected);
    // 🔴 作用于**车内全部行（含失效行）**，与后端一致 —— 失效行的 selected 照实记着。
    _cart = _rebuild(
      [for (final l in _cart.lines) _copyWithSelected(l, selected)],
      invalid: [
        for (final l in _cart.invalidLines) _copyWithSelected(l, selected)
      ],
    );
    state = AsyncData(_cart);
  }

  /// 🔴 重算四个合计。subtotal / itemCount 恒按**全部有效行**算（与勾选无关），
  /// selectedSubtotal / selectedCount 才按勾选集 —— 与后端 CartService.view 一致。
  /// 失效行无论勾没勾都不进任何一个数（「把卖不了的东西放进合计」是资损形态）。
  CartView _rebuild(List<CartLine> lines, {List<CartLine>? invalid}) {
    final picked = lines.where((l) => l.selected);
    return CartView(
      lines: lines,
      invalidLines: invalid ?? _cart.invalidLines,
      subtotal: lines.fold(0, (n, l) => n + l.price * l.qty),
      itemCount: lines.fold(0, (n, l) => n + l.qty),
      selectedSubtotal: picked.fold(0, (n, l) => n + l.price * l.qty),
      selectedCount: picked.fold(0, (n, l) => n + l.qty),
    );
  }

  static CartLine _copyWithSelected(CartLine l, bool selected) => CartLine(
        skuToken: l.skuToken,
        productToken: l.productToken,
        productName: l.productName,
        specName: l.specName,
        price: l.price,
        qty: l.qty,
        mainImageUrl: l.mainImageUrl,
        availableStock: l.availableStock,
        invalidReason: l.invalidReason,
        selected: selected,
      );

  @override
  Future<void> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType}) async {
    if (addFailsOnStock) throw CartMutationError.stock;
    added.add((sku: skuToken, qty: qty, entrySource: entrySource));
  }
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

/// 取底部条上显示的合计金额。
String? _totalOf(WidgetTester tester) => tester
    .widget<ShopBottomBarWithTotal>(find.byType(ShopBottomBarWithTotal))
    .amount;
