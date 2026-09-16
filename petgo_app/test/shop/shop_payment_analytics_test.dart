import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/button_ids.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 1-4：电商支付环节埋点。
///
/// 🔴 **埋点与功能同版本发布**：1-3 建了四条分支（被拒 / 超时 / 用户取消 / 仅关闭面板），
/// 本 story 在这四支上各挂事件。少一个，SHOP-FR-02 的漏斗就缺一格，而 SD-1 拍板的
/// 「不查库定性、在 PostHog 持续看」就落空了。
///
/// 🔒 **埋点禁带 PII 与订单号**（SHOP-NFR-01）：本类逐事件逐键核查，不靠「写的时候注意点」。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<String> names() => events.map((e) => e.$1).toList();
  Map<String, Object>? propsOf(String name) =>
      events.firstWhere((e) => e.$1 == name, orElse: () => (name, null)).$2;

  /// 让 pollPaid 每次 refresh 都读到「当前」订单：中途改这个引用即可模拟服务端推进。
  late ShopOrderDetail current;

  ShopOrderDetail order({
    ShopOrderStatus status = ShopOrderStatus.pendingPayment,
    DateTime? expiresAt,
    String? paymentFailureCategory,
    int? coinAmount = 50000,
  }) =>
      ShopOrderDetail(
        orderToken: 'ord-secret-token-1',
        status: status,
        paymentFailureCategory: paymentFailureCategory,
        paymentStatus: paymentFailureCategory == null ? null : 'FAILED',
        goodsSubtotal: 189000,
        shippingFee: 15000,
        shippingDiscount: 0,
        totalAmount: 204000,
        payChannel: 'MIXED',
        coinAmount: coinAmount,
        cashAmount: 154000,
        expiresAt: expiresAt ?? DateTime.now().toUtc().add(const Duration(minutes: 30)),
        lines: const [
          ShopOrderLine(
            productName: 'Royal Canin Adult Dog',
            specName: '3 kg',
            unitPrice: 189000,
            qty: 1,
            lineTotal: 189000,
          ),
        ],
        receiverName: 'Budi Santoso',
        receiverPhone: '08123456789',
        addressText: 'Jl. Test No. 1, Cilandak',
        attributionSource: 'TOKO_ALL_FEATURED',
      );

  Widget host(ShopOrderDetail initial, _FakeShopOrderRepo repo) {
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

  Future<void> tapPayAndOpenSheet(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
  }

  /// 服务端推进到 [next]，等一个轮询周期让面板中止关闭。
  Future<void> serverMovesTo(WidgetTester tester, ShopOrderDetail next) async {
    current = next;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();
  }

  /// Toast 自己挂着 2.6s 的消失定时器，不放它跑完测试结束会报 Timer pending。
  Future<void> flushToast(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  }

  // ---------- 漏斗补的五格 ----------

  testWidgets('出码 → toko_payment_qr_shown（漏斗第二格）', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    expect(names(), containsAllInOrder(['toko_order_pay_tapped', 'toko_payment_qr_shown']),
        reason: '「进入支付」与「出码成功」之间的落差正是本 story 要量的东西');
    expect(propsOf('toko_payment_qr_shown'), {
      'pay_channel': 'MIXED',
      'attribution_source': 'TOKO_ALL_FEATURED',
      'has_pawcoin': true,
    });

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  testWidgets('🔴 关闭面板（未完成）→ toko_payment_sheet_dismissed', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    // 服务端状态一个字没变 —— 用户只是点了面板上的取消。
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();

    // 这一格在服务端**没有任何对应事件**（关面板不产生服务端状态变化），
    // 「出码后自己走掉」只有客户端看得见 —— 这正是它的价值。
    expect(names(), contains('toko_payment_sheet_dismissed'));
    expect(names(), isNot(contains('toko_payment_declined_shown')));
    expect(names(), isNot(contains('toko_payment_expired_shown')));
  });

  testWidgets('被拒 → toko_payment_declined_shown 且 failure_category=GATEWAY_DECLINED',
      (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);
    await serverMovesTo(tester, order(paymentFailureCategory: 'GATEWAY_DECLINED'));

    expect(names(), contains('toko_payment_declined_shown'));
    expect(propsOf('toko_payment_declined_shown')?['failure_category'], 'GATEWAY_DECLINED');
    // 被拒不是「仅关闭面板」，两者不得同时上报。
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    await flushToast(tester);
  });

  testWidgets('超时 → toko_payment_expired_shown', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);
    await serverMovesTo(tester,
        order(status: ShopOrderStatus.cancelled, paymentFailureCategory: 'EXPIRED'));

    expect(names(), contains('toko_payment_expired_shown'));
    expect(propsOf('toko_payment_expired_shown')?['failure_category'], 'EXPIRED');
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    await flushToast(tester);
  });

  testWidgets('🔴 老后端（无失败类别）的服务端中止 **不得**记成 sheet_dismissed', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    // 1-1 未上线时的形态：订单转 CANCELLED，但没有 paymentFailureCategory。
    await serverMovesTo(tester, order(status: ShopOrderStatus.cancelled));

    // 这不是「用户自己走掉了」—— 灰度期把每一单服务端取消都记进那一格，
    // 会把「出码后放弃率」整个指标做废。
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    // 也不许猜成被拒或超时（根本不知道是哪一种）。这一格灰度期由服务端事件兜底。
    expect(names(), isNot(contains('toko_payment_declined_shown')));
    expect(names(), isNot(contains('toko_payment_expired_shown')));
  });

  // ---------- 重试（AC2） ----------

  testWidgets('🔴 被拒后再点支付 → toko_payment_retry_tapped', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);
    await serverMovesTo(tester, order(paymentFailureCategory: 'GATEWAY_DECLINED'));
    await flushToast(tester);

    events.clear();
    // 被拒后订单仍在待支付窗内，底部支付按钮还在（1-3 AC2）。
    await tapPayAndOpenSheet(tester);

    expect(names(), contains('toko_order_pay_tapped'),
        reason: '既有事件照发不误 —— 重试也是一次「进入支付」，漏斗分母要算上');
    expect(names(), contains('toko_payment_retry_tapped'));

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  testWidgets('反例：首次支付（未经历被拒）**不发** toko_payment_retry_tapped', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    expect(names(), contains('toko_order_pay_tapped'));
    expect(names(), isNot(contains('toko_payment_retry_tapped')));

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  testWidgets('🔴 跨页面/跨进程：进来时订单就停在被拒 → 首次点支付也算重试', (tester) async {
    // 用户被拒后退出详情页（或杀掉进程）再回来 —— State 里的记忆没了，但服务端
    // 下发的 paymentFailureCategory 还在。只认 State 的话这一格就永久漏报，
    // 而它**没有服务端事件兜底**（服务端只知道「又创建了一个意图」，不知道是不是重试）。
    await tester.pumpWidget(
        host(order(paymentFailureCategory: 'GATEWAY_DECLINED'), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    expect(names(), contains('toko_payment_retry_tapped'));

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  testWidgets('反例：进来时订单是超时态 → 不算重试（只有被拒才算）', (tester) async {
    await tester.pumpWidget(
        host(order(paymentFailureCategory: 'EXPIRED'), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);

    expect(names(), isNot(contains('toko_payment_retry_tapped')));

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  testWidgets('反例：只关面板（没被拒）之后再点支付，仍不算重试', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();

    events.clear();
    await tapPayAndOpenSheet(tester);

    // 判据是「上次失败类别是不是被拒」，不是计数器 —— 计数器在这里就会误报。
    expect(names(), isNot(contains('toko_payment_retry_tapped')));

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();
  });

  // ---------- 取消（AC1 末行） ----------

  testWidgets('取消成功 → toko_order_cancel_tapped + toko_order_cancel_succeeded',
      (tester) async {
    final repo = _FakeShopOrderRepo()
      ..cancelResult = order(status: ShopOrderStatus.cancelled);
    await tester.pumpWidget(host(order(), repo));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('shopOrderCancelV2')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('shopOrderCancelConfirmYesV2')));
    await tester.pumpAndSettle();

    expect(names(),
        containsAllInOrder(['toko_order_cancel_tapped', 'toko_order_cancel_succeeded']));
    expect(repo.cancelCalls, 1);
    await flushToast(tester);
  });

  // ---------- 🔒 PII（AC3） ----------

  testWidgets('🔒 逐事件逐键：属性只有四个允许键，绝无订单号与任何 PII', (tester) async {
    await tester.pumpWidget(host(order(), _FakeShopOrderRepo()));
    await tester.pumpAndSettle();
    await tapPayAndOpenSheet(tester);
    await serverMovesTo(tester, order(paymentFailureCategory: 'GATEWAY_DECLINED'));
    await flushToast(tester);
    await tapPayAndOpenSheet(tester);
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pumpAndSettle();

    const allowed = {'pay_channel', 'attribution_source', 'has_pawcoin', 'failure_category'};
    const banned = {
      'order_token', 'orderToken', 'order_no', 'display_no', 'displayNo',
      'receiver_name', 'receiverName', 'receiver_phone', 'phone',
      'address', 'address_text', 'email', 'whatsapp', 'user_id',
    };
    expect(events, isNotEmpty);
    for (final (name, props) in events) {
      if (props == null) continue;
      expect(props.keys, everyElement(isIn(allowed)), reason: '$name 带了不该带的属性键');
      for (final b in banned) {
        expect(props.containsKey(b), isFalse, reason: '$name 带了 $b');
      }
      // 订单 token 也不许作为**值**混进任何属性（例如被塞进 attribution_source）。
      expect(props.values.map((v) => v.toString()),
          isNot(contains('ord-secret-token-1')));
      expect(props.values.map((v) => v.toString()), isNot(contains('Budi Santoso')));
      expect(props.values.map((v) => v.toString()), isNot(contains('08123456789')));
    }
  });

  // ---------- AC4 / AC7 的护栏断言 ----------

  test('🔴 新增事件一个都不进 appsflyerEvents（那是收入/归因白名单）', () {
    for (final e in const [
      'toko_payment_qr_shown',
      'toko_payment_sheet_dismissed',
      'toko_payment_declined_shown',
      'toko_payment_expired_shown',
      'toko_payment_retry_tapped',
      'toko_order_cancel_succeeded',
    ]) {
      expect(Analytics.appsflyerEvents, isNot(contains(e)),
          reason: 'PawCoin 消耗与电商下单不得计为收入事件');
    }
  });

  test('AC7 · 按钮白名单仍是原来那 8 条（本 story 不走 buttonTapped 这条腿）', () {
    // buttonTapped 发的事件名固定是 button_tapped，button_id 只是属性 ——
    // 走那条通路，重试与取消就拼不进 toko_* 漏斗，而漏斗正是 SHOP-FR-02 的验收物。
    const eight = [
      ButtonId.triageStart, ButtonId.triageUpload, ButtonId.consultStart,
      ButtonId.publishSubmit, ButtonId.profileCreate, ButtonId.milestoneShare,
      ButtonId.vetAcceptQueue, ButtonId.vetAdviceTemplate,
    ];
    for (final id in eight) {
      expect(Analytics.isRegisteredButtonId(id), isTrue);
    }
    // 本 story 没往里加任何电商 id。
    for (final id in const ['toko.pay', 'toko.retry', 'shop.pay', 'toko.cancel']) {
      expect(Analytics.isRegisteredButtonId(id), isFalse);
    }
  });
}

/// 只桩 pay / cancel，其余继承真实现（本组用例不会走到）。
class _FakeShopOrderRepo extends ShopOrderRepository {
  _FakeShopOrderRepo() : super(dio: Dio());

  int payCalls = 0;
  int cancelCalls = 0;

  /// 页面拿到返回值后只是 invalidate provider 重拉，不直接用它，所以这里给什么都行。
  ShopOrderDetail? cancelResult;

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    payCalls++;
    return const ShopPayResult(
        orderStatus: 'PENDING_PAYMENT', paymentIntentToken: 'pi-1', payload: 'QR-DATA');
  }

  @override
  Future<ShopOrderDetail> cancel(String orderToken) async {
    cancelCalls++;
    return cancelResult!;
  }
}
