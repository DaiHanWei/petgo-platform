import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_countdown.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 电商订单详情 v3（v1.4.2 shop Story 9.1；由 `shop_order_detail_page_v2_test.dart` 迁来，
/// 仍成立的断言保留、按 9.1 新规则改写的用例逐条注明）。
///
/// 原 V1.4.0 第 2 批说明：
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
          child: const ShopOrderDetailPageV3(orderToken: 'ord1'),
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
    String displayNo = 'TOKO-20260916-7M4KQ2',
  }) =>
      ShopOrderDetail(
        orderToken: 'ord1',
        displayNo: displayNo,
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

    // v1.4.2 Story 9.1 按设计改写：PawCoin 分段不再挂在合计行（`+ PawCoin` 小字删除，FR-131.3），
    // 改由「支付方式」卡单列 —— 退款拆分的用户侧依据仍在，只是换了位置。
    testWidgets('🔴 已支付订单在支付方式卡保留 PawCoin 段 —— 退款拆分的用户侧依据', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCoinRowV2')), findsOneWidget,
          reason: '不显示币段，用户就无从知道退款会怎么拆');
      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsNothing,
          reason: '9.1：合计行不再带 + PawCoin 小字');
    });
  });

  /// v1.4.2 Story 9.1 按设计改写（原「币段只能出现一次」组）：**合计行恒为应付 `totalAmount`**，
  /// 币 / 现金只在支付方式卡出现，金额明细不出现 PawCoin（FR-131.3、P-1）。
  /// 「去支付」按钮金额仍是现金段（D-4 的那条不变式保留：按钮与支付方式卡里的 QRIS 段同数）。
  group('🔴 9.1：合计恒为应付，币 / 现金只在支付方式卡', () {
    testWidgets('已支付 → 合计行给订单总额 204.000，金额区无 PawCoin 小字', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, 'Rp 204.000');
      expect(find.byKey(const ValueKey('shopOrderPaidCoinSplitV2')), findsNothing);
    });

    testWidgets('待支付 → 合计行仍是应付总额；按钮是现金段，与 QRIS 行同数', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2')));
      expect(total.data, 'Rp 204.000');
      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2')));
      expect(btn.label, contains('154.000'),
          reason: '币段下单时已冻结；按钮写总额会让用户以为还要再付一次全款');
      expect(btn.label, isNot(contains('204.000')));
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('shopOrderCashRow')), matching: find.text('Rp 154.000')),
          findsOneWidget,
          reason: '按钮金额必须能在支付方式卡里找到出处');
    });

    testWidgets('🔴 D-4 极端形态：全额抵扣 → 按钮 Rp 0，合计仍是应付，币段在支付方式卡', (tester) async {
      await tester.pumpWidget(host(order(
        coinAmount: 204000,
        cashAmount: 0,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();

      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2')));
      expect(btn.label, contains('Rp 0'));
      expect(find.byKey(const ValueKey('shopOrderCoinRowV2')), findsOneWidget,
          reason: '那 204.000 去哪了必须有交代 —— 支付方式卡的 PawCoin 行');
      expect(find.byKey(const ValueKey('shopOrderCashRow')), findsNothing);
    });

    testWidgets('纯币单（cashAmount 为 null）→ 不显示「Rp 0」的 QRIS 行', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        coinAmount: 204000,
        cashAmount: null,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderCashRow')), findsNothing);
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
  // v1.4.2 Story 9.1：标题由履约区移到状态头（F7「Sedang dikirim」/ F9「Paket sudah diterima」），
  // D-6 的不变式（标题跟着订单状态走）保留。
  group('🔴 D-6：状态头标题必须跟着订单状态走', () {
    testWidgets('已发货 → Sedang dikirim', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      final title = tester.widget<Text>(find.byKey(const ValueKey('shopOrderHeaderTitle')));
      expect(title.data, 'Sedang dikirim');
    });

    testWidgets('已送达 → Paket sudah diterima', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.delivered,
        packages: [pkg(
          shippedAt: DateTime.now().subtract(const Duration(days: 2)),
          deliveredAt: DateTime.now().subtract(const Duration(hours: 3)),
        )],
      )));
      await tester.pumpAndSettle();

      final title = tester.widget<Text>(find.byKey(const ValueKey('shopOrderHeaderTitle')));
      expect(title.data, 'Paket sudah diterima');
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

  // v1.4.2 Story 9.1 按设计改写：接入 Biteship 后不再人工更新 → 人工时间线与免责行删除（F7 说明）；
  // BITESHIP 无异常画 5 段，MANUAL / 存量不画。
  group('物流卡（9.1：人工时间线与免责行删除）', () {
    testWidgets('存量单（只有 packages）→ 不画 5 段、不出现免责行，快递与运单号仍在', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderManualTrackingNoticeV2')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderLogisticsTimeline')), findsNothing);
      expect(find.text('JP1234567890'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderTrackSiteV2')), findsOneWidget);
    });

    testWidgets('运单无任何时刻也照样给出快递信息（不再整块隐藏）', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg()],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderLogisticsCard')), findsOneWidget);
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

    // v1.4.2 Story 9.1 按设计改写：底栏只剩「Konfirmasi terima」，「Lacak」在物流卡（F7 说明）。
    testWidgets('已发货 → 底栏只有 Konfirmasi terima（Lacak 移到物流卡）', (tester) async {
      await tester.pumpWidget(host(order(
        status: ShopOrderStatus.shipped,
        packages: [pkg(shippedAt: DateTime.now().subtract(const Duration(days: 1)))],
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('shopOrderConfirmReceiptV2')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderTrackV2')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderTrackSiteV2')), findsOneWidget);
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
            child: ShopOrderDetailPageV3(orderToken: 'ord1'),
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

      // 服务端状态一个字没变 —— 用户只是点了面板上的关闭。
      // v1.4.2 Story 7.2 按设计打破：电商面板不再有红色 qrPayCancel，只剩标题行 ×（qrPayCloseX，语义同原取消）。
      await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
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

  group('🔴 Story 4-3 · 一单一号（SHOP-FR-29）', () {
    testWidgets('详情页展示 displayNo，不再展示 22 位 orderToken', (tester) async {
      // 这正是本 story 要修的毛病：同一张单，订单中心显示 TOKO-…、
      // 详情页显示 22 位内部 token，用户报给客服的号后台还搜不到。
      await tester.pumpWidget(host(order()));
      await tester.pumpAndSettle();

      expect(find.text('TOKO-20260916-7M4KQ2'), findsOneWidget);
      expect(find.text('ord1'), findsNothing,
          reason: 'orderToken 是查询键，不是给人看的号');
    });

    testWidgets('🔴 展示的号与订单中心列表卡是同一个字符串', (tester) async {
      // 订单中心列表卡读的是 OrderSummary.displayNo，详情页读的是
      // ShopOrderDetail.displayNo —— 后端两处都取 shop_orders.display_no，
      // 所以这里断言的是「前端没有在某一侧擅自加工」。
      const fromOrderCenter = 'TOKO-20260916-7M4KQ2';
      await tester.pumpWidget(host(order(displayNo: fromOrderCenter)));
      await tester.pumpAndSettle();

      expect(find.text(fromOrderCenter), findsOneWidget);
    });

    testWidgets('号用等宽样式 —— 用户要逐位报给客服', (tester) async {
      await tester.pumpWidget(host(order()));
      await tester.pumpAndSettle();

      // 等宽在本仓是靠 ShopText.serialNo 的 fontFamily（mono）实现的，不是 fontFeatures。
      final t = tester.widget<Text>(find.text('TOKO-20260916-7M4KQ2'));
      expect(t.style, ShopText.serialNo,
          reason: '换成普通字体后逐位核对就容易串行 —— 用户要把这个号念给客服');
    });

    testWidgets('🔴 灰度期老后端不下发 displayNo → 回落显示 orderToken，不是空白',
        (tester) async {
      await tester.pumpWidget(host(order(displayNo: '')));
      await tester.pumpAndSettle();

      expect(find.text('ord1'), findsOneWidget,
          reason: '显示一个旧格式的号，好过在订单号那一行显示一片空白');
    });
  });

  // ================================================================
  // v1.4.2 shop Story 7.1：结算页提交需扫码单 → `?autopay=1` 首帧自动拉起现有支付面板
  // ================================================================
  group('v1.4.2 Story 7.1 · autoPay', () {
    Widget autoHost(ShopOrderDetail o, _FakeShopOrderRepo repo, {bool autoPay = true}) =>
        ProviderScope(
          overrides: [
            shopOrderRepositoryProvider.overrideWithValue(repo),
            shopOrderDetailProvider.overrideWith((ref, token) async => o),
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
            home: MediaQuery(
              data: const MediaQueryData(size: Size(411, 891)),
              child: ShopOrderDetailPageV3(orderToken: 'ord1', autoPay: autoPay),
            ),
          ),
        );

    testWidgets('autoPay: true → 首帧后自动调用一次 /pay 并出面板', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(autoHost(
          order(expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30))), repo));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(repo.payCalls, 1);
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
      // 再 pump 不会重复触发
      await tester.pump(const Duration(milliseconds: 500));
      expect(repo.payCalls, 1);
      // 关掉面板 = 留在待付款详情
      Navigator.of(tester.element(find.byKey(const ValueKey('qrPayImage')))).pop(false);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget);
    });

    testWidgets('autoPay 但订单已付款 → 不触发', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(autoHost(order(status: ShopOrderStatus.pendingShipment), repo));
      await tester.pumpAndSettle();
      expect(repo.payCalls, 0);
    });

    testWidgets('默认 autoPay=false → 不触发（既有调用点行为不变）', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(autoHost(
          order(expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30))), repo,
          autoPay: false));
      await tester.pumpAndSettle();
      expect(repo.payCalls, 0);
    });
  });

  // ================================================================
  // v1.4.2 shop Story 7.2：支付启动器 + E6 / E7 + canPay 门控
  // ================================================================
  group('v1.4.2 Story 7.2 · 支付面板与支付结果', () {
    late ShopOrderDetail current;

    Widget routerHost(ShopOrderDetail initial, _FakeShopOrderRepo repo) {
      current = initial;
      final router = GoRouter(
        initialLocation: '/shop/orders/ord1',
        routes: [
          GoRoute(
              path: '/shop/orders/:token',
              builder: (c, s) => MediaQuery(
                    data: const MediaQueryData(size: Size(411, 891)),
                    child: ShopOrderDetailPageV3(orderToken: s.pathParameters['token']!),
                  )),
          GoRoute(
              path: '/shop/orders/:token/paid',
              builder: (c, s) => Scaffold(body: Text('PAID ${s.pathParameters['token']}'))),
        ],
      );
      return ProviderScope(
        overrides: [
          shopOrderRepositoryProvider.overrideWithValue(repo),
          shopOrderDetailProvider.overrideWith((ref, token) async => current),
          returnEligibilityProvider.overrideWith((ref, token) async =>
              const ReturnEligibility(orderToken: 'ord1', eligible: false, lines: [])),
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
      );
    }

    ShopOrderDetail payable({bool? canPay}) => ShopOrderDetail.fromJson({
          ..._json(),
          'canPay': ?canPay,
        });

    Future<void> openSheet(WidgetTester tester) async {
      await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
    }

    testWidgets('AC6 · QRIS 轮询到账 → 跳 E6（/shop/orders/ord1/paid），不再弹 shopOrderPaid toast',
        (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await openSheet(tester);
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);

      current = ShopOrderDetail.fromJson({..._json(), 'status': 'PENDING_SHIPMENT'});
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.text('PAID ord1'), findsOneWidget);
      expect(find.text(l10n.shopOrderPaid), findsNothing, reason: '按设计改写：成功 toast 换成 E6 页');
    });

    testWidgets('AC4/AC6 · 「Saya sudah bayar」查到已付 → 跳 E6', (tester) async {
      final repo = _FakeShopOrderRepo()..checkPaid = true;
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await openSheet(tester);

      await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
      await tester.pumpAndSettle();
      expect(repo.checkCalls, 1);
      expect(find.text('PAID ord1'), findsOneWidget);
    });

    testWidgets('纯币当场结清 → 也进 E6（UI E6「纯币单也到此」）', (tester) async {
      final repo = _FakeShopOrderRepo()..payload = null;
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
      await tester.pumpAndSettle();
      expect(find.text('PAID ord1'), findsOneWidget);
    });

    testWidgets('AC7 · /pay 5xx → E7 面板（不再只弹 shopOrderPayFailed）；Coba lagi 成功切回二维码',
        (tester) async {
      final repo = _FakeShopOrderRepo()..payErrors.add(_httpError(502));
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await openSheet(tester);

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
      expect(find.text(l10n.shopOrderPayFailed), findsNothing, reason: '按设计改写');
      expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsNothing);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('qrPaySave'))).onPressed, isNull);
      expect(find.byKey(const ValueKey('qrPayCountdown')), findsOneWidget, reason: '倒计时照走');

      await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
      await tester.pump();
      await tester.pump();
      expect(repo.payCalls, 2);
      expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
      expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
      await tester.pumpAndSettle();
    });

    testWidgets('AC7.3 · /pay 409 shop-order-not-payable（超 10 分钟不再出码）→ E7 且无「Coba lagi」',
        (tester) async {
      final repo = _FakeShopOrderRepo()..payErrors.add(_httpError(409, type: 'shop-order-not-payable'));
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await openSheet(tester);

      expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
      expect(find.byKey(const ValueKey('qrPayRetryCode')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
    });

    testWidgets('其他 /pay 错误（如 409 非 not-payable）→ 仍是原 shopOrderPayFailed toast，不开面板',
        (tester) async {
      final repo = _FakeShopOrderRepo()..payErrors.add(_httpError(409, type: 'conflict'));
      await tester.pumpWidget(routerHost(payable(), repo));
      await tester.pumpAndSettle();
      await openSheet(tester);

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
      expect(find.text(l10n.shopOrderPayFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('_pay 兜底 catch：启动器外的意外异常 → 仍弹 shopOrderPayFailed、按钮恢复', (tester) async {
      current = payable();
      await tester.pumpWidget(ProviderScope(
        overrides: [
          // 取 repo 即抛：发生在启动器内部 try 之外，只有页面的兜底能接住。
          shopOrderRepositoryProvider.overrideWith((ref) => throw StateError('boom')),
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
            child: ShopOrderDetailPageV3(orderToken: 'ord1'),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
      await tester.pump();
      await tester.pump();
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.text(l10n.shopOrderPayFailed), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget);
    });

    testWidgets('AC8.3 · canPay: false → 无「Bayar Sekarang」', (tester) async {
      await tester.pumpWidget(routerHost(payable(canPay: false), _FakeShopOrderRepo()));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsNothing);
    });

    testWidgets('AC8.3 · canPay 缺键（旧后端）→ 行为同改动前：未过期待付款仍给支付按钮', (tester) async {
      await tester.pumpWidget(routerHost(payable(), _FakeShopOrderRepo()));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsOneWidget);
    });

    test('AC8.4 · canPay / displayStatus 解析；缺键为 null', () {
      final o = ShopOrderDetail.fromJson(
          {..._json(), 'canPay': false, 'displayStatus': 'CANCELLED'});
      expect(o.canPay, isFalse);
      expect(o.displayStatus, 'CANCELLED');
      expect(o.status, ShopOrderStatus.pendingPayment, reason: '原始 status 不被 displayStatus 覆盖');
      final legacy = ShopOrderDetail.fromJson(_json());
      expect(legacy.canPay, isNull);
      expect(legacy.displayStatus, isNull);
    });

    test('PaymentCheckResult 解析：缺键 / 非布尔按未到账', () {
      expect(PaymentCheckResult.fromJson({'paid': true, 'status': 'PENDING_SHIPMENT'}).paid, isTrue);
      expect(PaymentCheckResult.fromJson({'status': 'PENDING_PAYMENT'}).paid, isFalse);
      expect(PaymentCheckResult.fromJson({'paid': 'true'}).paid, isFalse);
    });
  });

  // ================================================================
  // v1.4.2 shop Story 9.1：状态头 / 金额明细 / 支付方式 / 物流 / 部分取消 / 重新下单
  // ================================================================
  group('v1.4.2 Story 9.1 · 详情 v3', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, props) => events.add((e, props));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    String? headerTitle(WidgetTester t) =>
        t.widget<Text>(find.byKey(const ValueKey('shopOrderHeaderTitle'))).data;
    String? headerHint(WidgetTester t) {
      final w = t.widget(find.byKey(const ValueKey('shopOrderHeaderHint')));
      return w is Text ? (w.data ?? w.textSpan?.toPlainText()) : null;
    }

    /// UI 稿示例数：349.000 / −20.000 / 10.000 / −10.000 / 329.000；币 12.000 / QRIS 317.000。
    Map<String, dynamic> v3({
      String status = 'SHIPPED',
      String? displayStatus,
      Map<String, dynamic>? logistics,
      Map<String, dynamic>? paymentMethods,
      Map<String, Object?> extra = const {},
    }) =>
        {
          ..._json(),
          'status': status,
          'displayStatus': displayStatus ?? status,
          'canPay': status == 'PENDING_PAYMENT' && (displayStatus ?? status) == 'PENDING_PAYMENT',
          'lines': const [
            {'productName': 'Royal Canin', 'specName': '3 kg', 'unitPrice': 289000, 'qty': 1, 'lineTotal': 289000},
            {'productName': 'Snack', 'specName': '100 g', 'unitPrice': 60000, 'qty': 1, 'lineTotal': 60000},
          ],
          'amounts': const {
            'goodsAmount': 349000,
            'discountCouponAmount': 20000,
            'shippingFee': 10000,
            'shippingDiscount': 10000,
            'shippingDiscountSource': 'COUPON',
            'insuranceFee': 0,
            'payable': 329000,
            'courierName': 'JNE',
            'courierServiceName': 'REG',
          },
          'paymentMethods': paymentMethods ??
              const {'coinAmount': 12000, 'coinStatus': 'PAID', 'cashAmount': 317000, 'cashStatus': 'PAID'},
          'logistics': ?logistics,
          ...extra,
        };

    const biteship = {
      'provider': 'BITESHIP',
      'courierName': 'JNE',
      'courierServiceName': 'REG',
      'waybillId': 'WB123',
      'trackingUrl': 'https://track.test/WB123',
      'statusKey': 'in_transit',
    };

    Widget routed(ShopOrderDetail o, {_FakeShopOrderRepo? repo}) {
      final router = GoRouter(
        initialLocation: '/shop/orders/ord1',
        routes: [
          GoRoute(
            path: '/shop/orders/:token',
            builder: (c, s) => s.pathParameters['token'] == 'ord1'
                ? const MediaQuery(
                    data: MediaQueryData(size: Size(411, 891)),
                    child: ShopOrderDetailPageV3(orderToken: 'ord1'))
                : Scaffold(body: Text('ORDER ${s.pathParameters['token']}')),
          ),
        ],
      );
      return ProviderScope(
        overrides: [
          shopOrderRepositoryProvider.overrideWithValue(repo ?? _FakeShopOrderRepo()),
          shopOrderDetailProvider.overrideWith((ref, token) async => o),
        ],
        child: MaterialApp.router(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          routerConfig: router,
        ),
      );
    }

    Finder inItems(String text) =>
        find.descendant(of: find.byKey(const ValueKey('shopOrderItemsCard')), matching: find.text(text));

    testWidgets('F4 金额明细逐行（服务端值）且不出现 PawCoin；合计 = 应付；按钮 = QRIS 段', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'PENDING_PAYMENT',
        paymentMethods: const {
          'coinAmount': 12000, 'coinStatus': 'HELD', 'cashAmount': 317000, 'cashStatus': 'AWAITING'
        },
      ))));
      await tester.pumpAndSettle();

      expect(inItems('Rp 349.000'), findsOneWidget);
      expect(inItems('Diskon belanja'), findsOneWidget);
      expect(inItems('−Rp 20.000'), findsOneWidget);
      expect(inItems('Ongkos kirim (JNE)'), findsOneWidget);
      expect(inItems('Rp 10.000'), findsOneWidget);
      expect(inItems('Kupon gratis ongkir'), findsOneWidget);
      expect(inItems('−Rp 10.000'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderAmountInsurance')), findsNothing, reason: '保价 0 不显示');
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2'))).data, 'Rp 329.000');
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalLabelV2'))).data, 'Total bayar');
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('shopOrderItemsCard')), matching: find.textContaining('PawCoin')),
          findsNothing,
          reason: 'FR-131.3：金额明细不出现 PawCoin');
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderCoinStatusV2'))).data,
          'Sudah ditahan · dikembalikan jika batal');
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderCashStatus'))).data, 'Menunggu');
      expect(tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderPayV2'))).label,
          'Bayar Sekarang Rp 317.000');
      expect(find.byKey(const ValueKey('shopOrderLogisticsCard')), findsNothing, reason: '待付款不显示物流卡');
    });

    testWidgets('F6 待发货：状态头 + 副句；无物流卡、无底栏', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(status: 'PENDING_SHIPMENT'))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Menunggu dikirim');
      expect(headerHint(tester), 'Pesanan sedang disiapkan. Kami kabari saat sudah dikirim.');
      expect(find.byKey(const ValueKey('shopOrderLogisticsCard')), findsNothing);
      expect(find.byType(ShopBottomBarActions), findsNothing);
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalLabelV2'))).data, 'Total dibayar');
    });

    testWidgets('F7 BITESHIP：5 段时间轴，in_transit = 第 3 段亮', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(logistics: biteship))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Sedang dikirim');
      expect(find.byKey(const ValueKey('shopOrderLogisticsTimeline')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderLogisticsStep_0_done')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderLogisticsStep_2_on')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderLogisticsStep_4_todo')), findsOneWidget);
      expect(find.text('JNE · REG'), findsOneWidget);
      expect(find.text('WB123'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderManualTrackingNoticeV2')), findsNothing);
    });

    testWidgets('MANUAL 运单 / 未知 statusKey → 不画 5 段，快递与运单号仍在', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(logistics: const {
        'provider': 'MANUAL', 'courierName': 'SiCepat', 'waybillId': 'SC1', 'trackingUrl': 'https://x'
      }))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderLogisticsTimeline')), findsNothing);
      expect(find.text('SC1'), findsOneWidget);

      await tester.pumpWidget(host(ShopOrderDetail.fromJson(
          v3(logistics: {...biteship, 'statusKey': 'weird_new_key'}))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderLogisticsTimeline')), findsNothing);
    });

    testWidgets('F9 已送达：状态头；5 段全亮', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(
          v3(status: 'DELIVERED', logistics: {...biteship, 'statusKey': 'delivered'}))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Paket sudah diterima');
      expect(headerHint(tester),
          'Konfirmasi terima kalau semua barang sudah sesuai. Otomatis selesai dalam 7 hari.');
      expect(find.byKey(const ValueKey('shopOrderLogisticsStep_4_on')), findsOneWidget);
    });

    testWidgets('F10 配送异常：状态头 + Hubungi CS；不画 5 段；埋点 _shown 只报一次；确认收货降为次按钮',
        (tester) async {
      final o = ShopOrderDetail.fromJson(v3(
          logistics: {...biteship, 'exception': 'DELIVERY_ISSUE', 'exceptionType': 'LOST'}));
      await tester.pumpWidget(host(o));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Pengiriman bermasalah');
      expect(find.byKey(const ValueKey('shopOrderHeaderContactCs')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderLogisticsTimeline')), findsNothing);
      expect(find.text('WB123'), findsOneWidget);
      final confirm = tester.widget<ShopButton>(find.byKey(const ValueKey('shopOrderConfirmReceiptV2')));
      expect(confirm.variant, ShopButtonVariant.outlineMuted);

      // 刷新（同一页面实例重建）不重复报
      final c = ProviderScope.containerOf(tester.element(find.byType(ShopOrderDetailPageV3)));
      c.invalidate(shopOrderDetailProvider('ord1'));
      await tester.pumpAndSettle();
      final shown = events.where((e) => e.$1 == 'toko_delivery_exception_shown').toList();
      expect(shown, hasLength(1));
      expect(shown.single.$2, {'exception_type': 'lost'});

      await tester.tap(find.byKey(const ValueKey('shopOrderHeaderContactCs')));
      await tester.pump();
      final cs = events.where((e) => e.$1 == 'toko_delivery_exception_cs_tapped').toList();
      expect(cs.single.$2, {'exception_type': 'lost'});
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('F11 已完成：状态头感谢语；物流卡仍显示（改线上）', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(
          v3(status: 'COMPLETED', logistics: {...biteship, 'statusKey': 'delivered'}))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Selesai');
      expect(headerHint(tester), 'Terima kasih sudah belanja di TailTopia.');
      expect(find.byKey(const ValueKey('shopOrderLogisticsCard')), findsOneWidget);
    });

    testWidgets('F12 超时取消：超时副句 + 券与币退回说明条；合计标签 Total pesanan', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'CANCELLED',
        paymentMethods: const {
          'coinAmount': 12000, 'coinStatus': 'RETURNED', 'cashAmount': 317000, 'cashStatus': 'UNPAID'
        },
        extra: const {'cancelReason': 'PAYMENT_TIMEOUT', 'couponReturn': 'RETURNED'},
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Dibatalkan');
      expect(headerHint(tester), 'Waktu pembayaran habis, pesanan dibatalkan otomatis.');
      expect(find.text('PawCoin Rp 12.000 dan kupon sudah dikembalikan ke akunmu.'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderCashStatus'))).data, 'Tidak dibayar');
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalLabelV2'))).data, 'Total pesanan');
      expect(find.byType(ShopBottomBarActions), findsNothing);
    });

    testWidgets('F12 券已过期 / 其余原因：无说明条、只给标题', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'CANCELLED',
        paymentMethods: const {'coinAmount': 12000, 'coinStatus': 'RETURNED'},
        extra: const {'cancelReason': 'USER_UNPAID_CANCEL', 'couponReturn': 'EXPIRED'},
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Dibatalkan');
      expect(find.byKey(const ValueKey('shopOrderHeaderHint')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderCoinCouponReturned')), findsNothing);
    });

    testWidgets('S-7 宽限期：原始待支付 · 展示已取消 → 已取消 + 超时副句，无倒计时、无底栏', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'PENDING_PAYMENT',
        displayStatus: 'CANCELLED',
        paymentMethods: const {'coinAmount': 12000, 'coinStatus': 'HELD', 'cashAmount': 317000, 'cashStatus': 'UNPAID'},
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Dibatalkan');
      expect(headerHint(tester), 'Waktu pembayaran habis, pesanan dibatalkan otomatis.');
      expect(find.byType(ShopCountdown), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderPayV2')), findsNothing);
      expect(find.byType(ShopBottomBarActions), findsNothing);
    });

    testWidgets('F17 部分取消：行标 + 降权；合计下两行取服务端值（60.333 = 2.200 + 58.133）', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'PENDING_SHIPMENT',
        extra: const {
          'cancelledLines': [
            {'lineIndex': 1, 'productName': 'Snack', 'specName': '100 g', 'cancelledQty': 1, 'refundAmount': 60333}
          ],
          'refunds': [
            {'refundToken': 'r1', 'scope': 'LINE', 'reason': 'OUT_OF_STOCK', 'totalRefund': 60333,
              'coinRefund': 2200, 'cashRefund': 58133}
          ],
        },
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Menunggu dikirim', reason: '原状态标题不变');
      expect(headerHint(tester), '1 produk dibatalkan karena stok habis. Produk lain tetap dikirim.');
      expect(find.byKey(const ValueKey('shopOrderLineCancelledTag_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderLineCancelledTag_0')), findsNothing);
      expect(find.text('Dibatalkan · stok habis'), findsOneWidget);
      expect(
          find.ancestor(
              of: find.byKey(const ValueKey('shopOrderLine_1')), matching: find.byType(Opacity)),
          findsOneWidget);
      expect(find.text('Dikembalikan (1 produk dibatalkan)'), findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('shopOrderPartialRefundTotal')), matching: find.text('Rp 60.333')),
          findsOneWidget);
      expect(find.text('PawCoin Rp 2.200 + QRIS Rp 58.133'), findsOneWidget);
    });

    testWidgets('复审：整单取消已付单（cancelledLines 含全部行 + WHOLE 退款）→ 不画 F17 两行、不画行标 / 降权',
        (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'CANCELLED',
        paymentMethods: const {'coinAmount': 12000, 'coinStatus': 'PAID', 'cashAmount': 317000, 'cashStatus': 'PAID'},
        extra: const {
          'cancelReason': 'OUT_OF_STOCK',
          'cancelledLines': [
            {'lineIndex': 0, 'cancelledQty': 1, 'refundAmount': 0},
            {'lineIndex': 1, 'cancelledQty': 1, 'refundAmount': 0},
          ],
          'refunds': [
            {'refundToken': 'w1', 'scope': 'WHOLE', 'reason': 'OUT_OF_STOCK', 'totalRefund': 329000,
              'coinRefund': 12000, 'cashRefund': 317000}
          ],
        },
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Dibatalkan · stok habis');
      expect(find.byKey(const ValueKey('shopOrderPartialRefundTotal')), findsNothing,
          reason: '否则会画出「Dikembalikan (2 produk dibatalkan) Rp 0」');
      expect(find.byKey(const ValueKey('shopOrderPartialRefundSplit')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderLineCancelledTag_0')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderLineCancelledTag_1')), findsNothing);
      expect(
          find.ancestor(of: find.byKey(const ValueKey('shopOrderLine_0')), matching: find.byType(Opacity)),
          findsNothing);
    });

    testWidgets('复审：有 logistics 时 Lacak 不回落 packages（logistics 无 trackingUrl → 不出 Lacak）', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        logistics: const {'provider': 'BITESHIP', 'courierName': 'JNE', 'waybillId': 'WB9', 'statusKey': 'picked'},
        extra: const {
          'packages': [
            {'carrier': 'JNE', 'trackingNo': 'OLD1', 'trackingUrl': 'https://old.test', 'superseded': true}
          ],
        },
      ))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderTrackSiteV2')), findsNothing);
      expect(find.text('WB9'), findsOneWidget);
      expect(find.text('OLD1'), findsNothing);
    });

    testWidgets('复审：存量单回落 packages → 取未被取代的最后一条', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson({
        ..._json(),
        'status': 'SHIPPED',
        'packages': const [
          {'carrier': 'JNE', 'carrierName': 'JNE', 'trackingNo': 'OLD1', 'trackingUrl': 'https://old', 'superseded': true},
          {'carrier': 'SICEPAT', 'carrierName': 'SiCepat', 'trackingNo': 'NEW2', 'trackingUrl': 'https://new', 'superseded': false},
          {'carrier': 'JNE', 'carrierName': 'JNE', 'trackingNo': 'OLD3', 'trackingUrl': 'https://old3', 'superseded': true},
        ],
      })));
      await tester.pumpAndSettle();
      expect(find.text('NEW2'), findsOneWidget);
      expect(find.text('SiCepat'), findsOneWidget);
      expect(find.text('OLD1'), findsNothing);
      expect(find.text('OLD3'), findsNothing);
    });

    testWidgets('F20 原单已重下：标题 + 副句 +「Lihat pesanan baru」跳新单；不出 F12 说明条', (tester) async {
      await tester.pumpWidget(routed(ShopOrderDetail.fromJson(v3(
        status: 'CANCELLED',
        paymentMethods: const {'coinAmount': 12000, 'coinStatus': 'RETURNED'},
        extra: const {
          'cancelReason': 'LATE_PAYMENT', 'couponReturn': 'RETURNED', 'reorderedToOrderToken': 'newOrd'
        },
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Dibatalkan · sudah dipesan ulang');
      expect(headerHint(tester),
          'Pembayaranmu masuk setelah waktu habis. Kami sudah membuat pesanan baru untukmu.');
      expect(find.byKey(const ValueKey('shopOrderCoinCouponReturned')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('shopOrderViewNewOrder')));
      await tester.pumpAndSettle();
      expect(find.text('ORDER newOrd'), findsOneWidget);
    });

    testWidgets('F21 新订单：副句带原单号，点单号回原单（C-9）', (tester) async {
      await tester.pumpWidget(routed(ShopOrderDetail.fromJson(v3(
        status: 'PENDING_SHIPMENT',
        extra: const {'reorderedFromOrderNo': 'TOKO-20261105-OLD111', 'reorderedFromOrderToken': 'oldOrd'},
      ))));
      await tester.pumpAndSettle();
      expect(headerTitle(tester), 'Menunggu dikirim');
      expect(headerHint(tester), 'Pesanan ini dibuat ulang dari pesanan TOKO-20261105-OLD111.');
      await tester.tap(find.byKey(const ValueKey('shopOrderReorderedFromLink')));
      await tester.pumpAndSettle();
      expect(find.text('ORDER oldOrd'), findsOneWidget);
    });

    testWidgets('F21 无原单 token → 单号只展示不可点', (tester) async {
      await tester.pumpWidget(host(ShopOrderDetail.fromJson(v3(
        status: 'PENDING_SHIPMENT',
        extra: const {'reorderedFromOrderNo': 'TOKO-1'},
      ))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderReorderedFromLink')), findsNothing);
      expect(headerHint(tester), contains('TOKO-1'));
    });

    testWidgets('确认收货：点即调接口、**无二次确认弹窗**；toast + 埋点', (tester) async {
      final repo = _FakeShopOrderRepo();
      await tester.pumpWidget(routed(ShopOrderDetail.fromJson(v3(logistics: biteship)), repo: repo));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderConfirmReceiptV2')));
      await tester.pump();
      expect(find.byKey(const ValueKey('shopOrderConfirmReceiptDialogV2')), findsNothing);
      await tester.pumpAndSettle();
      expect(repo.confirmCalls, 1);
      expect(find.text('Terima kasih, pesanan selesai'), findsOneWidget);
      expect(events.map((e) => e.$1),
          containsAllInOrder(['toko_order_receipt_confirm_tapped', 'toko_order_receipt_confirm_succeeded']));
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('F5 取消确认：有币段 → 正文追加「PawCoin dan kupon akan dikembalikan.」', (tester) async {
      await tester.pumpWidget(host(order(
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderCancelV2')));
      await tester.pumpAndSettle();
      expect(
          find.text('Stok akan dilepas dan pesanan tidak bisa dilanjutkan. '
              'PawCoin dan kupon akan dikembalikan.'),
          findsOneWidget);
      await tester.tap(find.text('Kembali'));
      await tester.pumpAndSettle();
    });

    testWidgets('F5 无币段、未用券 → 只用原正文', (tester) async {
      await tester.pumpWidget(host(order(
        coinAmount: 0,
        cashAmount: 204000,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
      )));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderCancelV2')));
      await tester.pumpAndSettle();
      expect(find.text('Stok akan dilepas dan pesanan tidak bisa dilanjutkan.'), findsOneWidget);
      await tester.tap(find.text('Kembali'));
      await tester.pumpAndSettle();
    });

    testWidgets('订单号可复制：写入 displayNo，toast「Tersalin」', (tester) async {
      String? copied;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
        return null;
      });
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));
      await tester.pumpWidget(host(order(status: ShopOrderStatus.completed)));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('shopOrderCopyOrderNo')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shopOrderCopyOrderNo')));
      await tester.pumpAndSettle();
      expect(copied, 'TOKO-20260916-7M4KQ2');
      expect(find.text('Tersalin'), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 2700));
    });

    testWidgets('存量单（新字段全 null）降级：只显示 subtotal / 运费 / 合计', (tester) async {
      await tester.pumpWidget(host(order(status: ShopOrderStatus.completed)));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderAmountSubtotal')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderAmountShipping')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderAmountCoupon')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderAmountShipDiscount')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderAmountInsurance')), findsNothing);
      expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderTotalV2'))).data, 'Rp 204.000');
      expect(tester.takeException(), isNull);
    });
  });
}

/// 详情 JSON（后端 `ShopOrderDetailView` 形态，最小集）。
Map<String, dynamic> _json() => {
      'orderToken': 'ord1',
      'displayNo': 'TOKO-20260916-7M4KQ2',
      'status': 'PENDING_PAYMENT',
      'goodsSubtotal': 189000,
      'shippingFee': 15000,
      'shippingDiscount': 0,
      'totalAmount': 204000,
      'payChannel': 'MIXED',
      'coinAmount': 50000,
      'cashAmount': 154000,
      'expiresAt': DateTime.now().toUtc().add(const Duration(minutes: 30)).toIso8601String(),
      'lines': const [],
      'shipTo': const {'receiverName': 'Budi'},
      'attributionSource': 'TOKO_ALL_FEATURED',
    };

/// 只桩 pay / cancel / paymentCheck，其余继承真实现（本组用例不会走到）。
class _FakeShopOrderRepo extends ShopOrderRepository {
  _FakeShopOrderRepo() : super(dio: Dio());

  int payCalls = 0;
  int cancelCalls = 0;
  int checkCalls = 0;

  /// v1.4.2 Story 7.2：依次消费的 `/pay` 错误（null = 正常出码）。
  final List<Object?> payErrors = [];

  /// `/pay` 正常时的 payload（null = 纯币当场结清）。
  String? payload = 'QR-DATA';

  bool checkPaid = false;

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    payCalls++;
    if (payErrors.isNotEmpty) {
      final e = payErrors.removeAt(0);
      if (e != null) throw e;
    }
    return ShopPayResult(
        orderStatus: payload == null ? 'PENDING_SHIPMENT' : 'PENDING_PAYMENT',
        paymentIntentToken: 'pi-1',
        payload: payload);
  }

  @override
  Future<PaymentCheckResult> paymentCheck(String orderToken) async {
    checkCalls++;
    return PaymentCheckResult(paid: checkPaid, status: checkPaid ? 'PENDING_SHIPMENT' : 'PENDING_PAYMENT');
  }

  @override
  Future<ShopOrderDetail> cancel(String orderToken) async {
    cancelCalls++;
    throw UnimplementedError('本组用例不该调到取消接口');
  }

  int confirmCalls = 0;

  @override
  Future<ShopOrderDetail> confirmReceipt(String orderToken) async {
    confirmCalls++;
    return ShopOrderDetail.fromJson({..._json(), 'status': 'COMPLETED'});
  }
}

DioException _httpError(int status, {String? type}) {
  final req = RequestOptions(path: '/api/v1/me/shop-orders/ord1/pay');
  return DioException(
    requestOptions: req,
    type: DioExceptionType.badResponse,
    response: Response<Object?>(
      requestOptions: req,
      statusCode: status,
      data: type == null ? null : {'type': 'https://petgo/errors/$type', 'status': status},
    ),
  );
}
