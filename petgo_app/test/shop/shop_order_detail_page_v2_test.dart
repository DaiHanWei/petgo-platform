import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_countdown.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 电商订单详情 · **设计稿版式**（V1.4.0 第 2 批）。
///
/// ⚠️ 2026-08-28：v1 版式整体删除，`shop_order_detail_page_test.dart` 与
/// `shop_order_fulfillment_page_test.dart` 一并移除；其中在 v2 下仍成立的用例已迁入本文件。
///
/// 本类看四件**说不清就会来客服**的事：倒计时来源、PawCoin 冻结明示、
/// 已付订单保留 PawCoin 分段、以及物流「非自动追踪」的免责行。
void main() {
  Widget host(
    ShopOrderDetail order, {
    ReturnEligibility? eligibility,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        shopOrderDetailProvider.overrideWith((ref, token) async => order),
        returnEligibilityProvider.overrideWith((ref, token) async =>
            eligibility ??
            const ReturnEligibility(
              orderToken: 'ord1',
              eligible: false,
              lines: [],
            )),
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
          child: const ShopOrderDetailPageV2(orderToken: 'ord1'),
        ),
      ),
    );
  }

  ShopOrderDetail order({
    ShopOrderStatus status = ShopOrderStatus.pendingPayment,
    DateTime? expiresAt,
    int? coinAmount = 50000,
    int? cashAmount = 154000,
    List<ShopOrderPackage> packages = const [],
  }) =>
      ShopOrderDetail(
        orderToken: 'ord1',
        status: status,
        goodsSubtotal: 189000,
        shippingFee: 15000,
        shippingDiscount: 0,
        totalAmount: 204000,
        coinAmount: coinAmount,
        cashAmount: cashAmount,
        expiresAt: expiresAt,
        packages: packages,
        lines: const [
          ShopOrderLine(
            productName: 'Royal Canin Adult Dog',
            specName: '3 kg',
            unitPrice: 189000,
            qty: 1,
            lineTotal: 189000,
          ),
        ],
        receiverName: 'Budi',
        receiverPhone: '08123456789',
        addressText: 'Jl. Test No. 1, Cilandak',
        attributionSource: 'TOKO_ALL_FEATURED',
      );

  ShopOrderPackage pkg({DateTime? shippedAt, DateTime? deliveredAt}) => ShopOrderPackage(
        carrier: 'JNE',
        carrierName: 'JNE',
        trackingNo: 'JP1234567890',
        trackingUrl: 'https://jne.co.id/track/JP1234567890',
        delivered: deliveredAt != null,
        shippedAt: shippedAt,
        deliveredAt: deliveredAt,
      );

  group('🔴 倒计时：服务端下发到期时刻，前端只渲染', () {
    testWidgets('待支付且未过期 → 渲染倒计时组件', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 58)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCountdownV2')), findsOneWidget);
      expect(find.byType(ShopCountdown), findsOneWidget);
    });

    testWidgets('🔴 已过期 → 不保留支付入口（点下去必然失败的按钮比没有更糟）', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().subtract(const Duration(minutes: 1)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderCountdownV2')), findsNothing);
    });

    testWidgets('没有支付窗（expiresAt 为 null）时整块不渲染', (tester) async {
      await tester.pumpWidget(host(order(expiresAt: null)));
      await tester.pumpAndSettle();

      expect(find.byType(ShopCountdown), findsNothing);
    });
  });

  group('🔴 PawCoin 冻结必须明示', () {
    testWidgets('待支付 → 显示「已冻结·取消会退回」', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      expect(find.text('Sudah ditahan · dikembalikan jika batal'), findsOneWidget,
          reason: '不说的话用户以为币已经花掉了，取消订单时会来问「我的币呢」');
    });

    testWidgets('coinAmount 为 0 时不渲染 PawCoin 行', (tester) async {
      await tester.pumpWidget(host(order(
        coinAmount: 0,
        cashAmount: 204000,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCoinRowV2')), findsNothing);
    });
  });

  group('🔴 三色分工：已付的钱降为信息层', () {
    testWidgets('待支付 → 总额玫红', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.style?.color, ShopColors.accent);
    });

    testWidgets('已发货 → 总额转墨色（玫红只留给还要付钱的动作）', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.style?.color, ShopColors.ink);
      expect(total.style?.color, isNot(ShopColors.accent));
    });

    testWidgets('🔴 已支付订单保留 PawCoin 分段 —— 退款拆分的用户侧依据', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsOneWidget,
          reason: '不显示分段，用户就无从知道退款会怎么拆');
    });
  });

  group('🔴 混合支付的金额语义：币段只能出现一次', () {
    // 设计稿 02 §3 的原文是 `Dibayar Rp 154.000 + 50.000 PawCoin` —— 现金 + 币。
    // 夹具正是这组数：total 204.000 = cash 154.000 + coin 50.000。
    testWidgets('已支付 → 金额行给**现金段**，不给总额（否则和 `+ PawCoin` 行把币算两遍）',
        (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, contains('154.000'));
      expect(total.data, isNot(contains('204.000')),
          reason: '204.000 已含 50.000 币，再跟一行「+ 50.000 PawCoin」就是重复计币');
    });

    // 🔴 这条原先断言的是**反过来的**：「待支付 → 金额行仍是总额（此时标题是
    //    Total bayar，语义正确）」。那个理由只在印尼语下成立 —— 同一个 ARB key
    //    (`checkoutPayable`) 的英文是 **Total due**（现在还欠多少），而币段下单时已冻结。
    //    2026-09-02 stag 用英文 locale 实测（D-4，P0）：同屏「Total due Rp 305.000」
    //    配按钮「Pay now Rp 304.001」，那 999 的差额页面上无处可解释，
    //    且该页明细区连 PawCoin 那一行都没有。已支付态本就按现金段显示 ⇒ 同页两态口径打架。
    testWidgets('🔴 D-4：待支付 → 金额行也给**现金段**，与支付按钮同数', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, contains('154.000'));
      expect(total.data, isNot(contains('204.000')),
          reason: '204.000 含 50.000 币段；按钮写 154.000，合计写 204.000 就是同屏自相矛盾');

      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2')));
      expect(btn.label, contains('154.000'), reason: '合计与按钮必须是同一个数');
    });

    testWidgets('🔴 D-4：待支付也必须列出 PawCoin 分段 —— 否则差额无从解释', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsOneWidget,
          reason: '合计从 204.000 变成 154.000，页面必须说清那 50.000 去哪了');
    });

    testWidgets('待支付 · 纯现金单 → 金额行仍是总额（无币段可拆）', (tester) async {
      await tester.pumpWidget(host(order(
        coinAmount: 0,
        cashAmount: 204000,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, contains('204.000'));
      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsNothing);
    });

    testWidgets('🔴 支付按钮上的金额是**现在真要付的现金**，不是订单总额', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2')));
      expect(btn.label, contains('154.000'),
          reason: '币段下单时已冻结；按钮写总额会让用户以为币白冻了、还要再付一次全款');
      expect(btn.label, isNot(contains('204.000')));
    });

    testWidgets('纯币单（cashAmount 为 null）→ 不显示「Dibayar Rp 0」', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        coinAmount: 204000,
        cashAmount: null,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, isNot(contains('Rp 0')));
    });
  });

  /// 🔴 D-6（2026-09-02 stag 电商测试，P2）：顶部状态标签停在「On the way」。
  ///
  /// 复现：后台把包裹标记送达 → 订单转 DELIVERED → App 订单详情页。
  /// 顶部那行大字紫色标签仍写「On the way」，而同页下方 Delivery history 的当前态、
  /// 订单列表卡、后台订单状态**全是 Delivered**。
  /// 它是 shipped/delivered 订单的**第一个区块** —— 用户第一眼读到的就是错的那个。
  group('🔴 D-6：履约区标题必须跟着订单状态走', () {
    testWidgets('已发货 → On the way', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final title =
          tester.widget<Text>(find.byKey(const ValueKey('shopOrderFulfillmentTitleV2')));
      expect(title.data, 'Sedang dikirim');
    });

    testWidgets('已送达 → 改「Terkirim / Delivered」，与下方时间线同一个词', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.delivered,
        packages: [pkg(
          shippedAt: DateTime.now().subtract(const Duration(days: 2)),
          deliveredAt: DateTime.now().subtract(const Duration(hours: 3)),
        )],
      )));
      await tester.pumpAndSettle();

      final title =
          tester.widget<Text>(find.byKey(const ValueKey('shopOrderFulfillmentTitleV2')));
      expect(title.data, 'Terkirim',
          reason: '后台已 DELIVERED、时间线当前态也是 Terkirim，顶部标签不能还停在运输中');
      expect(title.data, isNot('Sedang dikirim'), reason: 'D-6 的原形');
    });
  });

  group('🔴 物流：非自动追踪的免责行不可省', () {
    testWidgets('已发货且有时间线 → 免责行必须在', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderManualTrackingNoticeV2')), findsOneWidget,
          reason: '不接承运商 API 却不明示，用户会按实时追踪的精度预期 —— 一滞后就是投诉');
    });

    testWidgets('无任何物流时刻 → 整块不渲染（不画一条空时间线）', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg()],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderManualTrackingNoticeV2')), findsNothing);
    });

    testWidgets('待支付态不渲染物流块', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCopyResiV2')), findsNothing);
    });

    // ⚠️ 2026-08-28 自 shop_order_fulfillment_page_test.dart 迁入（v1 版式删除）。
    //    那个文件其余用例测的是 v1 的**多包裹逐条列表**（carrier_0 / trackingNo_0/1 /
    //    packageState_0/1），v2 不再逐包渲染，随 v1 一并作废；唯独这一条在 v2 下依然成立 ——
    //    而本文件此前只断言过「某态下复制按钮不出现」，从没验过**复制真的写进了剪贴板**。
    testWidgets('🔴 复制按钮把单号写进剪贴板（不是只把按钮画出来）', (tester) async {
      String? copied;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String?;
        }
        return null;
      });
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));

      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: const [
          ShopOrderPackage(
            carrier: 'SICEPAT',
            carrierName: 'SiCepat',
            trackingNo: 'SC123456',
            trackingUrl: 'https://www.sicepat.com/checkAwb',
            delivered: false,
          ),
        ],
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('shopOrderCopyResiV2')));
      await tester.pumpAndSettle();
      expect(copied, 'SC123456', reason: '🔴 复制按钮必须真的把单号写进剪贴板');
      // ⚠️ 复制后会弹 toast（app_toast 默认 2600ms）。必须等它自己消失，
      //    否则 widget 树拆除时仍有 pending Timer，测试框架会断言失败。
      //    原 v1 用例写的是 2 秒 —— 迁过来才暴露，因为两边 toast 时长不同。
      await tester.pump(const Duration(milliseconds: 2700));
    });
  });

  group('底部条按状态切换', () {
    testWidgets('待支付 → Batalkan + Bayar 双按钮', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCancelV2')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget);
    });

    testWidgets('已发货 → Lacak + Barang Diterima', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderConfirmReceiptV2')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderTrackV2')), findsOneWidget);
    });

    testWidgets('🔴 已有进行中的退货申请 → 入口置灰而不是隐藏', (tester) async {
      await tester.pumpWidget(host(
        order(status: ShopOrderStatus.completed),
        eligibility: const ReturnEligibility(
          orderToken: 'ord1',
          eligible: true,
          activeRequestToken: 'ret1',
          lines: [],
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderReturnV2')), findsOneWidget,
          reason: '隐藏会让用户以为没提交成功，转头再提交一次');
    });
  });

  group('布局不得溢出', () {
    testWidgets('待支付 · 411dp', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 58)),
      )));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('已发货 · 1.3 倍字号', (tester) async {
      await tester.pumpWidget(host(
        order(
          status: ShopOrderStatus.shipped,
          packages: [
            pkg(
              shippedAt: DateTime.now().subtract(const Duration(days: 2)),
              deliveredAt: DateTime.now().subtract(const Duration(hours: 3)),
            )
          ],
        ),
        textScale: 1.3,
      ));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}
