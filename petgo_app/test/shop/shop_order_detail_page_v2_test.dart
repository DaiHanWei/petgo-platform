import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
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
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_countdown.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
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
    String? paymentStatus,
    String? paymentFailureCategory,
  }) =>
      ShopOrderDetail(
        orderToken: 'ord1',
        paymentStatus: paymentStatus,
        paymentFailureCategory: paymentFailureCategory,
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
            mainImageUrl: 'https://cdn.test/shop/main.jpg',
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

  /// 🔴 下单前有图、下单后无图（2026-09-03 stag 回归 P2）：本页的商品缩略图写死
  /// `ShopImage(url: null)`，于是**永远**是占位斜纹。订单详情是用户回头找「我买的是哪件」
  /// 的地方，只有商品名 + 规格名时，同名不同规格的两件根本分不出来。
  group('🔴 订单行必须显示商品图', () {
    testWidgets('缩略图拿到 URL，不是写死的 null', (tester) async {
      await tester.pumpWidget(host(order()));
      await tester.pumpAndSettle();

      final withUrl = tester.widgetList<ShopImage>(find.byType(ShopImage))
          .where((i) => i.url != null);
      expect(withUrl, isNotEmpty, reason: '缩略图恒为占位斜纹 —— 用户认不出买的是哪件');
      expect(withUrl.first.url, 'https://cdn.test/shop/main.jpg');
    });
  });

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

    /// 🔴 D-4 **最极端形态**（2026-09-02 复测，指定为回归验收用例）：
    /// PawCoin 余额够覆盖全单 ⇒ 现金段为 0。实测同一屏：
    /// 明细区「Total due Rp 70.000」，正下方按钮「**Pay now Rp 0**」——
    /// 按钮自己写着付 0，上方却称应付 70.000。差的不再是 999 而是**全额**，
    /// 用户会以为还要再付 70.000 而放弃下单。
    testWidgets('🔴 D-4 极端形态：全额抵扣 → Total due 为 0，与 Pay now 同数',
        (tester) async {
      await tester.pumpWidget(host(order(
        coinAmount: 204000, // 币段覆盖全单
        cashAmount: 0,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, contains('0'));
      expect(total.data, isNot(contains('204.000')),
          reason: '一分现金都不欠，却写着应付 204.000 —— 用户会以为还要再付一次全款');

      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2')));
      expect(btn.label, contains('Rp 0'), reason: '按钮与合计必须是同一个数');

      // 那 204.000 去哪了必须有交代，否则合计从总额掉到 0 无从解释
      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsOneWidget);
    });

    testWidgets('🔴 已支付的纯币单仍显总额 —— 「Dibayar Rp 0」读起来像没付钱',
        (tester) async {
      // 两态判据不同是刻意的：待支付问「还欠多少」，已支付说「付了多少」。
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        coinAmount: 204000,
        cashAmount: 0,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, contains('204.000'));
      expect(total.data, isNot(contains('Rp 0')));
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

  /// 🔴 D-19（2026-09-02 stag，P2）：订单取消后 Payment 区显示「Paid」。
  ///
  /// 实测**资金是对的**：取消后 PawCoin 余额完整恢复（冻结的 70.000 已解冻退回，
  /// App 顶栏也同步成 120rb）。错的只是这一行文案 —— 而用户看到「Paid」会以为被扣了款。
  /// 根因是个**二元判断** `pending ? Held : Paid`，取消态落到了 else。
  group('🔴 D-19：取消态不能说「已支付」', () {
    testWidgets('🔴 取消后 PawCoin 行说「已退回余额」，不是「Paid」', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(order(status: ShopOrderStatus.cancelled)));
      await tester.pumpAndSettle();

      final t = tester.widget<Text>(find.byKey(const ValueKey('shopOrderCoinStatusV2')));
      expect(t.data, l10n.shopOrderCoinReturned);
      expect(t.data, isNot(l10n.shopOrderPaidLabel),
          reason: '钱从来没被拿走，说「已支付」用户会以为被扣了');
    });

    testWidgets('待支付仍说「已冻结、取消会退回」（这一支本来就是对的）', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final t = tester.widget<Text>(find.byKey(const ValueKey('shopOrderCoinStatusV2')));
      expect(t.data, l10n.shopOrderCoinHeld);
    });

    testWidgets('已发货仍说「Paid」', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final t = tester.widget<Text>(find.byKey(const ValueKey('shopOrderCoinStatusV2')));
      expect(t.data, l10n.shopOrderPaidLabel);
    });

    testWidgets('🔴 取消后合计行不再写「应付」—— 这单不存在应付', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(order(status: ShopOrderStatus.cancelled)));
      await tester.pumpAndSettle();

      final label = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalLabelV2')));
      expect(label.data, l10n.shopOrderCancelledTotalLabel);
      expect(label.data, isNot(l10n.checkoutPayable));

      // 也不该拆成「现金段 + 币段」—— 那读起来像一笔真发生过的付款
      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsNothing);
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

    /// 🔴 V1.3.0 · SD-5：本条原先断言「已有进行中的退货申请 → 入口置灰而不是隐藏」。
    /// 退货整块在 App 侧隐藏后，那条不变式不再成立 —— **改断言是对的，不是迁就实现**：
    /// 「置灰而不隐藏」守的是「别让用户以为没提交成功」，而现在他根本没有提交的入口。
    /// 后端退货接口与后台退货页面都还在、都可用；下一版恢复时这条要一并改回去。
    testWidgets('🔴 SD-5：任何状态都不出现退货入口（含有进行中申请的已完成单）', (tester) async {
      for (final s in ShopOrderStatus.values) {
        await tester.pumpWidget(host(
          order(
            status: s,
            expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
          ),
          eligibility: const ReturnEligibility(
            orderToken: 'ord1',
            eligible: true,
            activeRequestToken: 'ret1',
            lines: [],
          ),
        ));
        await tester.pumpAndSettle();

        expect(find.byKey(const ValueKey('shopOrderReturnV2')), findsNothing,
            reason: '$s 态出现了退货入口 —— 点了走不通，用户会白填一遍表单和凭证照片');
      }
    });

    testWidgets('🔴 已完成态底部条整条消失（不留空白 bar，也不塞别的按钮）', (tester) async {
      await tester.pumpWidget(host(order(status: ShopOrderStatus.completed)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderReturnV2')), findsNothing);
      expect(find.byType(ShopBottomBarActions), findsNothing);
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

  // ================================================================
  // Story 1-3：支付三态处置（AC2~AC5）
  //
  // 🔴 四种结局里，「用户取消订单」与「仅关闭面板」都表现为「面板关闭 + 返回 false」，
  //    唯一可靠的判据是 pollPaid 有没有抛出中止信号。两者各有一条用例，**不合并断言**。
  // ================================================================
  group('🔴 Story 1-3 · 支付三态处置', () {
    /// 让 pollPaid 每次 refresh 都读到「当前」订单：测试中途改这个引用即可模拟服务端推进。
    late ShopOrderDetail current;

    Widget payHost(ShopOrderDetail initial, _FakeShopOrderRepo repo) {
      current = initial;
      return ProviderScope(
        overrides: [
          shopOrderRepositoryProvider.overrideWithValue(repo),
          shopOrderDetailProvider.overrideWith((ref, token) async => current),
          returnEligibilityProvider.overrideWith((ref, token) async =>
              const ReturnEligibility(orderToken: 'ord1', eligible: false, lines: [])),
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
            child: ShopOrderDetailPageV2(orderToken: 'ord1'),
          ),
        ),
      );
    }

    ShopOrderDetail payable() =>
        order(expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)));

    /// Toast 自己挂着一个 2.6s 的消失定时器；不放它跑完，测试结束时会报
    /// 「A Timer is still pending even after the widget tree was disposed」。
    Future<void> flushToast(WidgetTester tester) async {
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    }

    /// 点支付 → 出码 → 把服务端状态换成 [next] → 等一个轮询周期（3s）。
    Future<void> payThenServerMovesTo(WidgetTester tester, ShopOrderDetail next) async {
      await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500)); // sheet 滑入
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget,
          reason: '二维码没出来，后面的断言都没有意义');

      current = next;
      await tester.pump(const Duration(seconds: 3)); // 轮询 tick
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500)); // 关闭动画
      await tester.pump();
    }

    testWidgets('AC2 · 网关拒付 → 关码 + 说明原因 + **保留**支付按钮', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(payHost(payable(), repo));
      await tester.pumpAndSettle();

      // 🔴 拒付只改 payment_intents：订单状态**仍是 PENDING_PAYMENT**。
      //    只看 status 的旧实现在这里会一直轮询到 60 分钟窗口耗尽。
      await payThenServerMovesTo(
          tester,
          order(
            expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
            paymentStatus: 'FAILED',
            paymentFailureCategory: 'GATEWAY_DECLINED',
          ));

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayImage')), findsNothing, reason: '二维码必须关掉');
      expect(find.text(l10n.shopPaymentDeclinedNotice), findsOneWidget,
          reason: '不说原因用户会以为是自己手机坏了');
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget,
          reason: '订单没取消也没过期 —— 重试入口必须留着');
      expect(repo.cancelCalls, 0, reason: '拒付不该顺手取消订单');
      await flushToast(tester);
    });

    testWidgets('AC3 · 超时未付 → 关码 + 告知已取消 + **不给**任何支付入口', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(payHost(payable(), repo));
      await tester.pumpAndSettle();

      await payThenServerMovesTo(
          tester,
          order(
            status: ShopOrderStatus.cancelled,
            paymentStatus: 'EXPIRED',
            paymentFailureCategory: 'EXPIRED',
          ));

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsNothing,
          reason: '一个点下去必然失败的按钮比没有更糟');
      expect(find.text(l10n.shopOrderExpiredNotice), findsOneWidget);
      // 🔴 不弹 shopOrderPayFailed —— 那是「再试一次」的口吻，而这一单已经没了。
      expect(find.text(l10n.shopOrderPayFailed), findsNothing);
      await flushToast(tester);
    });

    testWidgets('AC4 · 用户取消订单 → 关码，**不弹任何错误**', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(payHost(payable(), repo));
      await tester.pumpAndSettle();

      await payThenServerMovesTo(
          tester,
          order(
            status: ShopOrderStatus.cancelled,
            paymentStatus: 'FAILED',
            paymentFailureCategory: 'USER_CANCELLED',
          ));

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
      expect(find.text(l10n.shopOrderPayFailed), findsNothing);
      expect(find.text(l10n.shopPaymentDeclinedNotice), findsNothing);
      expect(find.text(l10n.shopOrderExpiredNotice), findsNothing,
          reason: '是他自己取消的，他知道发生了什么');
    });

    testWidgets('AC5 · 仅关闭面板 → 不调任何接口、不弹文案、支付按钮仍在', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(payHost(payable(), repo));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);

      // 服务端状态一个字没变 —— 用户只是点了面板上的取消。
      await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pump();

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
      expect(repo.cancelCalls, 0, reason: '关个面板不等于作废订单（pending intent 可复用）');
      expect(find.text(l10n.shopOrderPayFailed), findsNothing);
      expect(find.text(l10n.shopPaymentDeclinedNotice), findsNothing);
      expect(find.text(l10n.shopOrderExpiredNotice), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget,
          reason: '还能接着付');
    });

    testWidgets('AC1 兜底 · 两字段为 null（后端未升级）时行为与改动前一致', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(payHost(payable(), repo));
      await tester.pumpAndSettle();

      // 老后端：只有订单转 CANCELLED，没有 paymentFailureCategory。
      await payThenServerMovesTo(tester, order(status: ShopOrderStatus.cancelled));

      expect(find.byKey(const ValueKey('qrPayImage')), findsNothing, reason: '仍按中止关闭');
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsNothing);
    });
  });
}

/// 只桩 pay / cancel 两个方法，其余继承真实现（本组用例不会走到）。
class _FakeShopOrderRepo extends ShopOrderRepository {
  _FakeShopOrderRepo() : super(dio: Dio());

  int payCalls = 0;
  int cancelCalls = 0;

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    payCalls++;
    return const ShopPayResult(
        orderStatus: 'PENDING_PAYMENT', paymentIntentToken: 'pi-1', payload: 'QR-DATA');
  }

  @override
  Future<ShopOrderDetail> cancel(String orderToken) async {
    cancelCalls++;
    throw UnimplementedError('本组用例不该调到取消接口');
  }
}
