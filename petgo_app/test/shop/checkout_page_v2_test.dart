import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/data/checkout_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart' show ReturnPolicy;
import 'package:tailtopia/features/shop/presentation/checkout_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 结算页 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// v1 版式的用例在 `checkout_page_test.dart`，两套互不影响。
///
/// 本类看的全是**会造成资损或误导**的事：运费位不得显示 Rp 0、两段金额必须同时在、
/// PawCoin 上限截断必须明示、超范围时必须列出已开通城市而不是一句「不送」。
void main() {
  Widget host(
    CheckoutPreview preview, {
    RegionTree? regions,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        addressListProvider.overrideWith((ref) async => [
              const ShippingAddress(
                token: 'addr1',
                receiverName: 'Budi',
                receiverPhone: '08123456789',
                provinsi: 'DKI Jakarta',
                kotaKabupaten: 'Jakarta Selatan',
                kecamatan: 'Kebayoran',
                addressLine: 'Jl. Test No. 1',
                kodePos: '12160',
                label: 'Rumah',
                isDefault: true,
              ),
            ]),
        checkoutPreviewProvider.overrideWith((ref, token) async => preview),
        regionTreeProvider.overrideWith((ref) async =>
            regions ??
            const RegionTree([
              RegionProvinsi('DKI Jakarta', [
                RegionKota('Jakarta Selatan', []),
                RegionKota('Jakarta Pusat', []),
              ]),
              RegionProvinsi('Jawa Barat', [RegionKota('Bandung', [])]),
            ])),
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
          child: const CheckoutPageV2(),
        ),
      ),
    );
  }

  CheckoutLine line({int price = 185000, int qty = 1}) => CheckoutLine(
        skuToken: 's1',
        productName: 'Royal Canin Adult Dog',
        specName: '3 kg',
        price: price,
        qty: qty,
        returnPolicy: ReturnPolicy.noReturnAfterOpen,
      );

  CheckoutPreview preview({
    bool serviceable = true,
    int? shippingFee = 15000,
    int? payableTotal = 200000,
    int? coinAmount = 50000,
    int? cashAmount = 150000,
    bool coinCapped = false,
    int coinBalance = 80000,
    int maxCoinPerOrder = 50000,
    int? shippingDiscount,
  }) =>
      CheckoutPreview(
        addressToken: 'addr1',
        receiverName: 'Budi',
        receiverPhone: '08123456789',
        addressText: 'Jl. Test No. 1, Kebayoran, Jakarta Selatan',
        serviceable: serviceable,
        lines: [line()],
        unavailableLines: const [],
        goodsSubtotal: 185000,
        shippingFee: serviceable ? shippingFee : null,
        shippingDiscount: shippingDiscount,
        payableTotal: serviceable ? payableTotal : null,
        coinAmount: serviceable ? coinAmount : null,
        cashAmount: serviceable ? cashAmount : null,
        coinBalance: coinBalance,
        maxCoinPerOrder: maxCoinPerOrder,
        coinCapped: coinCapped,
        strictestReturnPolicy: ReturnPolicy.noReturnAfterOpen,
      );

  group('🔴 FR-100A：两段金额必须同时展示', () {
    testWidgets('PawCoin 段与 QRIS 段都在，且金额分别显示', (tester) async {
      await tester.pumpWidget(host(preview()));
      await tester.pumpAndSettle();

      expect(find.textContaining('PawCoin'), findsWidgets);
      expect(find.text('QRIS'), findsOneWidget);
      expect(find.text('− Rp 50.000'), findsWidgets,
          reason: '只显示一个总数会让用户误解扣款构成 —— 而 PawCoin 段不能提现');
      expect(find.text('Rp 150.000'), findsOneWidget);
    });

    testWidgets('coinAmount 为 0 时不渲染「− Rp 0」这种噪音行', (tester) async {
      await tester.pumpWidget(host(preview(coinAmount: 0, cashAmount: 200000, payableTotal: 201000)));
      await tester.pumpAndSettle();

      expect(find.textContaining('− Rp 0'), findsNothing);
      expect(find.text('Rp 200.000'), findsOneWidget);
    });

    testWidgets('🔴 PawCoin 被单笔上限截断时必须多一行说明（C-16 / UX-DR14）', (tester) async {
      await tester.pumpWidget(host(preview(coinCapped: true)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutCoinCappedV2')), findsOneWidget,
          reason: '不明示则用户以为系统算错了');
    });

    testWidgets('未截断时不显示那一行', (tester) async {
      await tester.pumpWidget(host(preview(coinCapped: false)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutCoinCappedV2')), findsNothing);
    });

    testWidgets('🔴 用到 PawCoin 时，防套现提示在支付前可见（合规位点）', (tester) async {
      await tester.pumpWidget(host(preview()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutPawcoinNoteV2')), findsOneWidget);
    });

    testWidgets('本单没用 PawCoin 时不渲染该提示 —— 别警告一件没发生的事', (tester) async {
      // 真机上撞到的：余额 0 的账号结算，页面仍挂着一段「PawCoin 部分只退 PawCoin」。
      await tester.pumpWidget(host(preview(coinAmount: 0, cashAmount: 268000, payableTotal: 268000)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutPawcoinNoteV2')), findsNothing);
    });
  });

  group('🔴 FR-99 超服务范围：绝不显示 Rp 0', () {
    testWidgets('运费位显示「暂不可用」而不是 Rp 0', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();

      final fee = tester.widget<Text>(find.byKey(const ValueKey('checkoutShippingFeeV2')));
      expect(fee.data, 'Belum tersedia');
      expect(find.text('Rp 0'), findsNothing,
          reason: '0 会被读成「免运费」—— 一个买不了的订单显示免运费是最糟的误导');
    });

    testWidgets('底部总价也不显示数字，且转灰', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();

      final bar = tester.widget<ShopBottomBarWithTotal>(find.byType(ShopBottomBarWithTotal));
      expect(bar.amount, 'Belum tersedia');
      expect(bar.amountColor, ShopColors.text4);
    });

    testWidgets('提交按钮置灰不可点', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();

      final submit = tester.widget<ShopButton>(find.byKey(const ValueKey('checkoutSubmitV2')));
      expect(submit.onTap, isNull);
      expect(submit.variant, ShopButtonVariant.disabled);
    });

    testWidgets('🔴 必须列出已开通城市，不是一句「不送」了事', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('checkoutServiceArea'));
      expect(find.byKey(const ValueKey('checkoutServiceArea')), findsOneWidget,
          reason: '用户要能自己判断换哪个地址');
      // 🔴 城市取自配送区域接口（真数据），不是写死的三个名字
      expect(find.text('Jakarta Selatan'), findsWidgets);
      expect(find.text('Bandung'), findsWidgets);
    });

    testWidgets('区域数据取不到时整块不渲染 —— 不留一个列不出地方的空块', (tester) async {
      await tester.pumpWidget(host(
        preview(serviceable: false),
        regions: const RegionTree([]),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutServiceArea')), findsNothing);
    });

    testWidgets('提供「换地址」出口', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutPickAnotherAddress')), findsOneWidget);
    });

    testWidgets('服务范围内时不渲染警示与城市块', (tester) async {
      await tester.pumpWidget(host(preview()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutOutOfRangeTitle')), findsNothing);
      expect(find.byKey(const ValueKey('checkoutServiceArea')), findsNothing);
    });
  });

  group('🔴 FR-104：开封不退是三处明示的第 2 处', () {
    testWidgets('勾选框在，且文案取自与详情页同一批 key', (tester) async {
      await tester.pumpWidget(host(preview()));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('checkoutAgreeNoReturn'));
      expect(find.byKey(const ValueKey('checkoutAgreeNoReturn')), findsOneWidget);
      // 与 product_detail_page_v2_test 里断言的是同一句 —— 文案一致性契约
      expect(
          find.text('Demi keamanan pangan, kemasan makanan yang sudah dibuka tidak bisa diretur.'),
          findsOneWidget);
    });

    testWidgets('取消勾选后提交按钮禁用', (tester) async {
      await tester.pumpWidget(host(preview()));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('checkoutAgreeNoReturn'));
      await tester.tap(find.byKey(const ValueKey('checkoutAgreeNoReturn')));
      await tester.pumpAndSettle();

      final submit = tester.widget<ShopButton>(find.byKey(const ValueKey('checkoutSubmitV2')));
      expect(submit.onTap, isNull);
    });
  });

  group('金额一个都不自己算', () {
    testWidgets('小计 / 运费 / 应付全部原样取服务端值', (tester) async {
      await tester.pumpWidget(host(preview(
        shippingFee: 15000,
        payableTotal: 200000,
      )));
      await tester.pumpAndSettle();

      expect(find.text('Rp 185.000'), findsWidgets); // 小计
      expect(find.text('Rp 15.000'), findsWidgets); // 运费
      final bar = tester.widget<ShopBottomBarWithTotal>(find.byType(ShopBottomBarWithTotal));
      // 🔴 200.000 ≠ 185.000 + 15.000 − 50.000。刻意给一个「算不出来」的组合：
      //    页面若自己算过一遍，这条就会红。
      expect(bar.amount, 'Rp 200.000');
    });

    testWidgets('免运抵扣是一条负数行，不是把运费改成 0', (tester) async {
      await tester.pumpWidget(host(preview(shippingFee: 15000, shippingDiscount: -15000)));
      await tester.pumpAndSettle();

      expect(find.text('Rp 15.000'), findsWidgets);
      expect(find.text('Rp -15.000'), findsOneWidget,
          reason: '用户要看得见「本来多少、减了多少」');
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 正常态', (tester) async {
      await tester.pumpWidget(host(preview(coinCapped: true)));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('411dp · 超范围态', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false)));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号（NFR-13 上限）', (tester) async {
      await tester.pumpWidget(host(preview(serviceable: false), textScale: 1.3));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}

/// 滚到指定 key 可见。
///
/// ⚠️ 必要而不是啰嗦：`ListView` 只构建视口内（加 cacheExtent）的子项，
/// 视口外的块**根本不在 widget 树里**，`find.byKey` 找不到。
/// 不滚就断言会得到「功能没做」的假象 —— 这三条一开始就是这么红的。
Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(find.byKey(key), 200, maxScrolls: 20);
  await tester.pumpAndSettle();
}
