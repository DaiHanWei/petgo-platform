import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/refund_method_page.dart';
import 'package:tailtopia/features/shop/presentation/return_request_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 5.7 退货申请页 · 5.8 退款方式页 · L0。
///
/// 🔴 本组用例守的是**四条告知**，每一条都对应一类真实客诉：
/// ① 开封不退的行置灰但可见（三处明示的第 3 处）；
/// ② 每个原因右侧标出回程运费归属；
/// ③ 去程运费提示随勾选实时切换（UX-DR2，堵凑单套利的唯一告知点）；
/// ④ 已有进行中申请时入口置灰（UX-DR3）。
/// 外加 5.8 的那一条：**PawCoin 段不展示「退回真钱」入口**（不是展示后再拒绝）。
void main() {
  late _FakeReturnRepo repo;

  setUp(() => repo = _FakeReturnRepo());

  Widget host(Widget page) => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          shopReturnRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: page,
        ),
      );

  Future<void> open(WidgetTester t, Widget page) async {
    // 🔴 ListView 懒加载：默认 800x600 会让下半页根本没 build
    t.view.physicalSize = const Size(1200, 3600);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host(page));
    await t.pump();
    await t.pump();
  }

  group('🔴 Story 5.7 退货申请页', () {
    testWidgets('①「开封不退」的行保留可见但置灰，并直接标注原因', (t) async {
      repo.eligibilityData = _eligibility(lines: [
        _line(1, 'Royal Canin', policy: 'RETURNABLE', selectable: true),
        _line(2, 'Drontal Plus',
            policy: 'NO_RETURN_AFTER_OPEN',
            selectable: false,
            blocked: 'Sudah dibuka · tidak bisa diretur'),
      ]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));
      // 默认选中的是「质量问题」，那一档开封也能退 —— 先切到非质量问题
      await t.tap(find.byKey(const ValueKey('returnReason_NON_QUALITY_ISSUE')));
      await t.pumpAndSettle();

      // 不是「隐藏」——两行都在
      expect(find.byKey(const ValueKey('returnLine_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('returnLine_2')), findsOneWidget);
      // 置灰行不可勾选，且带原因
      final blocked = t.widget<CheckboxListTile>(find.byKey(const ValueKey('returnLine_2')));
      expect(blocked.onChanged, isNull, reason: '开封不退的行必须不可勾选');
      expect(find.byKey(const ValueKey('returnLineBlocked_2')), findsOneWidget);
    });

    testWidgets('🔴 「开封不退」在【质量问题】下仍可勾选 —— 破损与是否开封无关', (t) async {
      // 默认选中的就是质量问题
      repo.eligibilityData = _eligibility(lines: [
        _line(2, 'Drontal Plus',
            policy: 'NO_RETURN_AFTER_OPEN', selectable: false, blocked: 'x'),
      ]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      final tile = t.widget<CheckboxListTile>(find.byKey(const ValueKey('returnLine_2')));
      expect(tile.onChanged, isNotNull,
          reason: '把破损品也挡掉等于让收到破损品的用户无路可走');

      // 切到非质量问题 → 变成不可勾选
      await t.tap(find.byKey(const ValueKey('returnReason_NON_QUALITY_ISSUE')));
      await t.pumpAndSettle();
      final after = t.widget<CheckboxListTile>(find.byKey(const ValueKey('returnLine_2')));
      expect(after.onChanged, isNull);
    });

    testWidgets('② 每个原因选项右侧标出回程运费由谁承担', (t) async {
      repo.eligibilityData = _eligibility(lines: [_line(1, 'A')]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      expect(find.byKey(const ValueKey('returnReasonFee_QUALITY_ISSUE')), findsOneWidget);
      expect(find.byKey(const ValueKey('returnReasonFee_NON_QUALITY_ISSUE')),
          findsOneWidget);
      final quality = t.widget<Text>(
          find.byKey(const ValueKey('returnReasonFee_QUALITY_ISSUE')));
      final nonQuality = t.widget<Text>(
          find.byKey(const ValueKey('returnReasonFee_NON_QUALITY_ISSUE')));
      // 两档的文案必须不同 —— 相同就等于没告知
      expect(quality.data, isNot(equals(nonQuality.data)));
    });

    testWidgets('🔴 ③ UX-DR2：去程运费提示随勾选实时切换（全选↔部分选）', (t) async {
      repo.eligibilityData = _eligibility(lines: [
        _line(1, 'A', qty: 1),
        _line(2, 'B', qty: 1),
      ]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      String notice() => t
          .widget<Text>(find.byKey(const ValueKey('returnOutboundFeeNotice')))
          .data!;

      final nothingSelected = notice();
      await t.tap(find.byKey(const ValueKey('returnLine_1')));
      await t.pumpAndSettle();
      final partial = notice();
      await t.tap(find.byKey(const ValueKey('returnLine_2')));
      await t.pumpAndSettle();
      final full = notice();

      expect(partial, equals(nothingSelected), reason: '没选满都算部分退');
      expect(full, isNot(equals(partial)),
          reason: '🔴 全选与部分选必须给出不同的去程运费结论 —— 这是堵凑单套利的唯一告知点');
    });

    testWidgets('🔴 ④ UX-DR3：已有进行中申请 → 页面直说「处理中」，不让用户提交后吃 409', (t) async {
      repo.eligibilityData = _eligibility(
          eligible: false, activeRequestToken: 'ret-1', lines: [_line(1, 'A')]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      expect(find.byKey(const ValueKey('returnIneligible')), findsOneWidget);
      expect(find.byKey(const ValueKey('returnSubmit')), findsNothing);
    });

    testWidgets('「开封不退」整页级明示与「不做上门取件」说明都在（第 3 处明示 + S-7）', (t) async {
      repo.eligibilityData = _eligibility(lines: [_line(1, 'A')]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      expect(find.byKey(const ValueKey('returnNoReturnAfterOpenNotice')), findsOneWidget);
      expect(find.byKey(const ValueKey('returnNoPickupNotice')), findsOneWidget);
    });

    testWidgets('一件都没勾就提交 → 拦下，不打接口', (t) async {
      repo.eligibilityData = _eligibility(lines: [_line(1, 'A')]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      await t.tap(find.byKey(const ValueKey('returnSubmit')));
      await t.pump();
      expect(repo.calls.contains('submit'), isFalse);
      await t.pump(const Duration(seconds: 4));
    });

    testWidgets('🔴 质量问题没传凭证 → 拦下（它是平台掏运费 + 发溢价的那一类）', (t) async {
      repo.eligibilityData = _eligibility(lines: [_line(1, 'A')]);
      await open(t, const ReturnRequestPage(orderToken: 'ord-1'));

      await t.tap(find.byKey(const ValueKey('returnLine_1')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('returnSubmit')));
      await t.pump();
      expect(repo.calls.contains('submit'), isFalse);
      await t.pump(const Duration(seconds: 4));
    });
  });

  group('🔴 Story 5.8 退款方式页', () {
    testWidgets('🔴 混合支付：两段分列 + 比例条；PawCoin 段【没有】任何可选项', (t) async {
      repo.progressData = _progress(coin: 60000, cash: 240000);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      expect(find.byKey(const ValueKey('refundSplitBar')), findsOneWidget);
      expect(find.byKey(const ValueKey('refundPawcoinSegment')), findsOneWidget);
      expect(find.byKey(const ValueKey('refundCashSegment')), findsOneWidget);
      // PawCoin 段标「自动 / 没有别的选项」
      expect(find.byKey(const ValueKey('refundPawcoinAutomatic')), findsOneWidget);
      // 🔴 现金段有两个单选；PawCoin 段一个都没有 —— 不给用户产生预期再打破
      expect(find.byType(RadioListTile<CashDestination>), findsNWidgets(2));
    });

    testWidgets('🔴 纯 QRIS 单：不出现两段拆分 UI（与既有虚拟商品退款一致，无新增）', (t) async {
      repo.progressData = _progress(coin: 0, cash: 285000);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      expect(find.byKey(const ValueKey('refundSplitBar')), findsNothing);
      expect(find.byKey(const ValueKey('refundPawcoinSegment')), findsNothing);
      expect(find.byKey(const ValueKey('refundCashSegment')), findsOneWidget);
    });

    testWidgets('🔴 补偿溢价的金额来自服务端，且附「为什么不能提现」的说明', (t) async {
      repo.progressData = _progress(coin: 60000, cash: 240000, compensation: 1500);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      expect(find.byKey(const ValueKey('refundCompensationPremium')), findsOneWidget);
      expect(find.byKey(const ValueKey('refundCompensationWhy')), findsOneWidget);
      // 金额照抄服务端值，前端不算比例
      final text = t
          .widget<Text>(find.byKey(const ValueKey('refundCompensationPremium')))
          .data!;
      expect(text.contains('1'), isTrue);
    });

    testWidgets('没有补偿溢价时不渲染那一块（不是渲染成 0）', (t) async {
      repo.progressData = _progress(coin: 60000, cash: 240000, compensation: 0);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      expect(find.byKey(const ValueKey('refundCompensationPremium')), findsNothing);
    });

    testWidgets('选「退回银行」但没填账号 → 拦下，不打接口', (t) async {
      repo.progressData = _progress(coin: 60000, cash: 240000);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      await t.tap(find.byKey(const ValueKey('refundMethodConfirm')));
      await t.pump();
      expect(repo.calls.contains('chooseCashDestination'), isFalse);
      await t.pump(const Duration(seconds: 4));
    });

    testWidgets('选「转 PawCoin」→ 不需要账号，直接可提交', (t) async {
      repo.progressData = _progress(coin: 60000, cash: 240000, incentive: 12000);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      await t.tap(find.byKey(const ValueKey('refundToPawcoin')));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('refundAccount')), findsNothing);
      expect(find.byKey(const ValueKey('refundIncentivePremium')), findsOneWidget);

      await t.tap(find.byKey(const ValueKey('refundMethodConfirm')));
      await t.pumpAndSettle();
      expect(repo.calls.contains('chooseCashDestination'), isTrue);
      await t.pump(const Duration(seconds: 4));
    });

    testWidgets('底栏展示「总退回（含补偿）」', (t) async {
      repo.progressData =
          _progress(coin: 60000, cash: 240000, compensation: 1500, grandTotal: 301500);
      await open(t, const RefundMethodPage(returnToken: 'ret-1'));

      expect(find.byKey(const ValueKey('refundGrandTotal')), findsOneWidget);
    });
  });

  group('🔒 数据模型层的能力缺席', () {
    test('🔴 CashDestination 只描述现金段；不存在 CoinDestination', () {
      expect(CashDestination.values.map((e) => e.api),
          containsAll(<String>['TO_BANK', 'TO_PAWCOIN']));
      // 若将来有人加了一个「PawCoin 段去哪」的枚举，这条会提醒他先回去读 FR-100A 规则 1
      expect(CashDestination.values.length, 2);
    });

    test('渠道费与后端 PayoutChannel / FR-105 费率表逐字一致', () {
      expect(PayoutChannel.bca.fee, 0);
      expect(PayoutChannel.ovo.fee, 2500);
      expect(PayoutChannel.gopay.fee, 2500);
    });

    test('🔴 未知退货规则降级到最保守档（宁可少承诺）', () {
      final line = ReturnableLine.fromJson(const {'orderLineId': 9, 'returnableQty': 1});
      expect(line.returnPolicy, 'NON_RETURNABLE');
      expect(line.selectableFor(ReturnType.qualityIssue), isFalse);
    });
  });
}

// ---------- fixtures ----------

ReturnableLine _line(int id, String name,
        {String policy = 'RETURNABLE',
        bool selectable = true,
        String? blocked,
        int qty = 1}) =>
    ReturnableLine(
      orderLineId: id,
      productName: name,
      specName: '3 kg',
      unitPrice: 100000,
      qty: qty,
      refundedQty: 0,
      returnableQty: qty,
      returnPolicy: policy,
      selectable: selectable,
      blockedReason: blocked,
    );

ReturnEligibility _eligibility({
  bool eligible = true,
  String? activeRequestToken,
  required List<ReturnableLine> lines,
}) =>
    ReturnEligibility(
      orderToken: 'ord-1',
      eligible: eligible,
      activeRequestToken: activeRequestToken,
      ineligibleReason: eligible ? null : 'blocked',
      lines: lines,
      returnAddress: const ReturnAddress(
        receiverName: 'Gudang TailTopia',
        receiverPhone: '+628111111111',
        addressText: 'Jl. Gudang No. 1, Jakarta Selatan',
      ),
    );

ReturnProgress _progress({
  required int coin,
  required int cash,
  int compensation = 0,
  int incentive = 0,
  int grandTotal = 300000,
}) =>
    ReturnProgress(
      returnToken: 'ret-1',
      orderToken: 'ord-1',
      status: ReturnStatus.pendingReview,
      returnType: ReturnType.qualityIssue,
      fullReturn: true,
      outboundFeeRefundable: true,
      coinRefund: coin,
      cashRefund: cash,
      compensationPremium: compensation,
      incentivePremium: incentive,
      shipbackReimbursement: 0,
      grandTotal: grandTotal,
      lines: const [],
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeReturnRepo implements ShopReturnRepository {
  ReturnEligibility? eligibilityData;
  ReturnProgress? progressData;
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<ReturnEligibility> eligibility(String orderToken) async {
    calls.add('eligibility');
    return eligibilityData!;
  }

  @override
  Future<ReturnProgress> submit({
    required String orderToken,
    required ReturnType returnType,
    required Map<int, int> selections,
    String? reasonNote,
    List<String>? evidenceKeys,
  }) async {
    calls.add('submit');
    return progressData ?? _progress(coin: 0, cash: 100000);
  }

  @override
  Future<ReturnProgress> progress(String returnToken) async {
    calls.add('progress');
    return progressData!;
  }

  @override
  Future<ReturnProgress> chooseCashDestination({
    required String returnToken,
    required CashDestination destination,
    PayoutChannel? channel,
    String? account,
    String? accountHolderName,
  }) async {
    calls.add('chooseCashDestination');
    return progressData!;
  }

  @override
  Future<ReturnProgress> registerShipback({
    required String returnToken,
    required String carrier,
    required String trackingNo,
    int? fee,
  }) async {
    calls.add('registerShipback');
    return progressData!;
  }

  @override
  Future<ReturnProgress> withdraw(String returnToken) async {
    calls.add('withdraw');
    return progressData!;
  }
}
