import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/presentation/cart_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 购物车 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// v1 版式的用例在 `cart_page_test.dart`，两套互不影响。
///
/// 本类看的是**会造成资损或误导**的几件事：失效行不得计入合计、库存不明时不得加、
/// 单店模型不得长出店铺分组，以及「设计稿要的勾选框刻意没实现」这个决定不被悄悄推翻。
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
  }) =>
      CartLine(
        skuToken: token,
        productName: name,
        specName: spec,
        price: price,
        qty: qty,
        availableStock: stock,
        invalidReason: invalid,
      );

  CartView cartOf({
    List<CartLine> valid = const [],
    List<CartLine> invalid = const [],
    int? subtotal,
    int? itemCount,
  }) =>
      CartView(
        lines: valid,
        invalidLines: invalid,
        subtotal: subtotal ?? valid.fold(0, (a, l) => a + l.lineTotal),
        itemCount: itemCount ?? valid.fold(0, (a, l) => a + l.qty),
      );

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
      expect(price.style?.color, isNot(ShopColors.rose));
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

  group('🔴 勾选框刻意不实现（下单接口不支持行选择）', () {
    testWidgets('不渲染任何勾选框', (tester) async {
      // 画一个不影响下单的勾选框 = 用户勾掉一行仍然会被买走，这是能造成资损的谎。
      // 补齐需要后端支持行选择，不是版式取舍 —— 见 cart_page_v2.dart 文件头。
      await tester.pumpWidget(host(cartOf(valid: [line('a'), line('b')])));
      await tester.pumpAndSettle();

      expect(find.byType(ShopCheckbox), findsNothing);
      expect(find.byType(Checkbox), findsNothing);
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
}

/// 固定返回给定购物车的 controller。
///
/// 🔴 覆写整个 controller 而不是底层 repository：本类测的是渲染，
/// 走真 controller 会连带触发登录态判断与网络层，把「渲染对不对」和
/// 「拉取对不对」两件事搅在一起，红了分不清是哪边。
class _FakeCartController extends CartController {
  _FakeCartController(this._cart);

  final CartView _cart;

  @override
  Future<CartView> build() async => _cart;
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
