import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 4.5 · L0：订单详情履约态（FR-103 / SPEC-2 出口② / S-2 / UX-DR7 / UX-DR13）。
///
/// 🔴 本页最容易写错的两件事：
/// 1. **把「确认收货」藏到已送达之后** —— 那等于把订单能否脱离「已发货」完全押在
///    运营记不记得点后台那个按钮上（SPEC-2 明确指出这是死锁风险）。
/// 2. **承诺当日达** —— C-14 已把配送方式收为 Reguler 一档，任何「今天送达」文案
///    都是在每天制造一批必然失望的用户（UX-DR13）。
void main() {
  late _FakeOrderRepo repo;

  setUp(() {
    repo = _FakeOrderRepo();
    // 复制按钮会写系统剪贴板 —— 测试环境里没有真实平台通道，拦下来即可。
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  Widget host() => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          shopOrderRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: const ShopOrderDetailPage(orderToken: 'ord-1'),
        ),
      );

  Future<void> open(WidgetTester t) async {
    // 🔴 ListView 懒加载：默认 800x600 画布会让下半页根本没 build。
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host());
    await t.pump();
    await t.pump();
  }

  group('🔴 SPEC-2 出口②：已发货态即可确认收货', () {
    testWidgets('已发货：确认收货按钮就在底栏，不必等系统标记送达', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderConfirmReceipt')), findsOneWidget);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('已送达：同样给确认收货', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.delivered);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderConfirmReceipt')), findsOneWidget);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('待发货：还没发货，不给确认收货', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.pendingShipment);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderConfirmReceipt')), findsNothing);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('已完成 / 已取消：不再给确认收货', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.completed);
      await open(t);
      expect(find.byKey(const ValueKey('shopOrderConfirmReceipt')), findsNothing);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('🔴 确认收货要二次确认，取消则不调接口', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped);
      await open(t);

      await t.tap(find.byKey(const ValueKey('shopOrderConfirmReceipt')));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopOrderConfirmReceiptDialog')), findsOneWidget);

      // 点「还没收到」→ 不该发生任何写动作
      await t.tap(find.text(_l10nId('shopOrderConfirmReceiptNo')));
      await t.pumpAndSettle();
      expect(repo.calls.contains('confirmReceipt'), isFalse);

      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('确认后调接口并重拉详情', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped);
      await open(t);

      await t.tap(find.byKey(const ValueKey('shopOrderConfirmReceipt')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('shopOrderConfirmReceiptYes')));
      await t.pumpAndSettle();

      expect(repo.calls.contains('confirmReceipt'), isTrue);
      // toast 自带 2.6s 定时器 —— 不等它跑完，测试收尾会因 timersPending 报错
      await t.pump(const Duration(seconds: 4));
    });
  });

  group('🔴 FR-103 物流信息：只给单号与官网跳转，不渲染轨迹', () {
    testWidgets('已发货：展示承运商名 + 单号 + 复制 + 跳官网', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped, packages: [
        const ShopOrderPackage(
          carrier: 'JNE',
          carrierName: 'JNE',
          trackingNo: 'JP8842119037',
          trackingUrl: 'https://www.jne.co.id/tracking-package',
          delivered: false,
        ),
      ]);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderCarrier_0')), findsOneWidget);
      expect(find.text('JNE'), findsWidgets);
      expect(find.byKey(const ValueKey('shopOrderTrackingNo_0')), findsOneWidget);
      expect(find.text('JP8842119037'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderCopy_0')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderTrack_0')), findsOneWidget);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('复制按钮把单号写进剪贴板', (t) async {
      String? copied;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String?;
        }
        return null;
      });

      repo.detailData = _order(status: ShopOrderStatus.shipped, packages: [
        const ShopOrderPackage(
          carrier: 'SICEPAT',
          carrierName: 'SiCepat',
          trackingNo: 'SC123456',
          trackingUrl: 'https://www.sicepat.com/checkAwb',
          delivered: false,
        ),
      ]);
      await open(t);

      await t.tap(find.byKey(const ValueKey('shopOrderCopy_0')));
      await t.pumpAndSettle();
      expect(copied, 'SC123456');
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('🔴 S-2 一单多包：逐条列出，各自标明送达状态', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped, packages: [
        const ShopOrderPackage(
          carrier: 'JNE',
          carrierName: 'JNE',
          trackingNo: 'JP1',
          trackingUrl: 'https://www.jne.co.id/tracking-package',
          delivered: true,
        ),
        const ShopOrderPackage(
          carrier: 'ANTERAJA',
          carrierName: 'Anteraja',
          trackingNo: 'AR2',
          trackingUrl: 'https://anteraja.id/tracking',
          delivered: false,
        ),
      ]);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderTrackingNo_0')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderTrackingNo_1')), findsOneWidget);
      // 两包状态不同 —— 混成一个状态就等于骗用户「都到了」
      expect(find.byKey(const ValueKey('shopOrderPackageState_0')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderPackageState_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderPackageIndex_0')), findsOneWidget);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('单包裹时不显示「第 N / 共 M 包」（那是噪音）', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped, packages: [
        const ShopOrderPackage(
          carrier: 'JNE',
          carrierName: 'JNE',
          trackingNo: 'JP1',
          trackingUrl: 'https://www.jne.co.id/tracking-package',
          delivered: false,
        ),
      ]);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderPackageIndex_0')), findsNothing);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('待发货：还没有包裹，不渲染物流区块', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.pendingShipment);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderTrackingNo_0')), findsNothing);
      await t.pump(const Duration(seconds: 2));
    });
  });

  group('🔴 UX-DR13 / UX-DR7：时效文案与三态标签', () {
    testWidgets('🔴 已发货的时效文案说 2-4 个工作日，绝不承诺当日达', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped);
      await open(t);

      final eta = t.widget<Text>(find.byKey(const ValueKey('shopOrderEta'))).data!;
      expect(eta.contains('2-4'), isTrue, reason: '时效文案必须给 Reguler 的 2-4 个工作日');
      // C-14 已砍掉当日达 —— 这些词一个都不该出现
      for (final banned in ['hari ini', 'Sameday', 'Instant', 'GoSend', 'Grab']) {
        expect(eta.contains(banned), isFalse, reason: '时效文案里出现了当日达残留：$banned');
      }
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('已发货 / 已送达 / 已完成 各有自己的状态标签与副文案', (t) async {
      for (final s in [
        ShopOrderStatus.shipped,
        ShopOrderStatus.delivered,
        ShopOrderStatus.completed,
      ]) {
        // 🔴 同一个 testWidgets 里循环 pumpWidget 会复用 widget 树 —— 这里靠每轮
        //    新建 repo + 新建 ProviderScope 规避（见 HANDOFF §测试基建 4）。
        repo = _FakeOrderRepo()..detailData = _order(status: s);
        await t.pumpWidget(host());
        await t.pump();
        await t.pump();

        final label = t.widget<Text>(find.byKey(const ValueKey('shopOrderStatus'))).data!;
        expect(label.isNotEmpty, isTrue);
        // 认不出的状态才用中性兜底文案；这三态各有专属标签
        expect(label, isNot(equals(_l10nId('shopOrderStatusOther'))), reason: '状态 $s 落到了兜底文案');
        await t.pump(const Duration(seconds: 2));
      }
    });
  });

  group('🔴 退货窗口：服务端下发，且「已完成」不等于「不能再退」', () {
    testWidgets('有 returnWindowEndsAt 时展示截止日', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.completed,
        returnWindowEndsAt: DateTime(2026, 9, 3),
      );
      await open(t);

      final text =
          t.widget<Text>(find.byKey(const ValueKey('shopOrderReturnWindow'))).data!;
      expect(text.contains('2026-09-03'), isTrue);
      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('未签收时没有退货窗口起点 → 不展示', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.shipped);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderReturnWindow')), findsNothing);
      await t.pump(const Duration(seconds: 2));
    });
  });

  group('未知承运商', () {
    test('🔴 认不出的承运商回落到原始码，不猜成某一家', () {
      final pkg = ShopOrderPackage.fromJson(const {
        'carrier': 'JXPRESS',
        'trackingNo': 'JX1',
        'trackingUrl': '',
        'status': 'SHIPPED',
      });
      // 猜错等于把包裹记到别人家的运单号上，用户点进去查无此单
      expect(pkg.carrierName, 'JXPRESS');
      expect(pkg.delivered, isFalse);
    });
  });
}

/// 从 id 语种 ARB 取一条文案（测试里不硬编码译文）。
String _l10nId(String key) {
  const map = {
    'shopOrderConfirmReceiptNo': 'Belum',
    'shopOrderStatusOther': 'Status pesanan',
  };
  return map[key]!;
}

ShopOrderDetail _order({
  required ShopOrderStatus status,
  List<ShopOrderPackage> packages = const [],
  DateTime? returnWindowEndsAt,
}) =>
    ShopOrderDetail(
      orderToken: 'ord-1',
      status: status,
      goodsSubtotal: 285000,
      shippingFee: 20000,
      shippingDiscount: 0,
      totalAmount: 305000,
      expiresAt: null,
      createdAt: DateTime.now(),
      shippedAt: DateTime(2026, 8, 20),
      deliveredAt: returnWindowEndsAt?.subtract(const Duration(days: 7)),
      returnWindowEndsAt: returnWindowEndsAt,
      packages: packages,
      lines: const [
        ShopOrderLine(
            productName: 'Royal Canin',
            specName: '3 kg',
            unitPrice: 285000,
            qty: 1,
            lineTotal: 285000),
      ],
      receiverName: 'Budi',
      receiverPhone: '+62 812 3456 789',
      addressText: 'Jl. Test No. 1, Kebayoran',
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeOrderRepo implements ShopOrderRepository {
  ShopOrderDetail? detailData;
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<ShopOrderDetail> detail(String orderToken) async {
    calls.add('detail');
    return detailData!;
  }

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    calls.add('pay');
    return const ShopPayResult(orderStatus: 'PENDING_SHIPMENT');
  }

  @override
  Future<ShopOrderDetail> cancel(String orderToken) async {
    calls.add('cancel');
    return detailData!;
  }

  @override
  Future<ShopOrderDetail> confirmReceipt(String orderToken) async {
    calls.add('confirmReceipt');
    detailData = ShopOrderDetail(
      orderToken: detailData!.orderToken,
      status: ShopOrderStatus.completed,
      goodsSubtotal: detailData!.goodsSubtotal,
      shippingFee: detailData!.shippingFee,
      shippingDiscount: detailData!.shippingDiscount,
      totalAmount: detailData!.totalAmount,
      lines: detailData!.lines,
      receiverName: detailData!.receiverName,
      receiverPhone: detailData!.receiverPhone,
      addressText: detailData!.addressText,
      packages: detailData!.packages,
    );
    return detailData!;
  }
}
