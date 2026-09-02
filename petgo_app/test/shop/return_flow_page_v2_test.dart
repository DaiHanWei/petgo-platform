import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/refund_method_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/return_request_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 退货申请 + 退款方式 · **设计稿版式**（V1.4.0 第 2 批）。
///
/// v1 版式的用例在 `return_flow_page_test.dart`，两套互不影响。
///
/// 这两屏是**钱的事**，本类看的都是「说错一句就变客诉或资损」的点：
/// 回程运费归属、去程运费的凑单套利口子、PawCoin 不可提现、以及
/// 「去向不可改」那句话**只对 PawCoin 段成立**。
void main() {
  Widget wrap(Widget child) => MaterialApp(
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
          child: child,
        ),
      );

  // ================================================================ 退货申请

  Widget requestHost(ReturnEligibility e) => ProviderScope(
        overrides: [
          returnEligibilityProvider.overrideWith((ref, token) async => e),
        ],
        child: wrap(const ReturnRequestPageV2(orderToken: 'ord1')),
      );

  ReturnableLine line({
    int id = 1,
    int qty = 2,
    bool selectable = true,
    String? blockedReason,
    String policy = 'RETURNABLE',
  }) =>
      ReturnableLine(
        orderLineId: id,
        productName: 'Royal Canin Adult Dog',
        specName: '3 kg',
        unitPrice: 185000,
        qty: qty,
        refundedQty: 0,
        returnableQty: qty,
        returnPolicy: policy,
        selectable: selectable,
        blockedReason: blockedReason,
      );

  ReturnEligibility eligibility({List<ReturnableLine>? lines, bool eligible = true}) =>
      ReturnEligibility(
        orderToken: 'ord1',
        eligible: eligible,
        lines: lines ?? [line()],
      );

  /// 🔴 D-10（2026-09-02 stag，P0）：凭证照片曾是**桩实现**。
  ///
  /// 点「+」**不弹相册也不拍照**，只往列表里追加字面量 `return-evidence-1/2/…`，
  /// 计数照跳 0/5 → 1/5、缩略图是占位斜纹；随后这些假串被原样提交入库。
  /// 后端当时也不校验 key 是否指向真实对象 ⇒ 运营在退货审核页无图可看，
  /// 而本页文案还写着「拍到封口和保质期标签 —— 这是质检要看的」。
  /// 整条凭证链路端到端不可用。
  ///
  /// ⚠️ 本组只能测到**入口行为**：真正的选图/上传要打相册与网络，
  /// 归 `MediaUploadUseCase` 自己的用例管。这里钉住的是「点下去发生的是选图，
  /// 而不是凭空造一个 key」——那正是 D-10 的形态。
  group('🔴 D-10：凭证照片必须真的选图上传', () {
    testWidgets('🔴 点「+」弹来源选择，而不是凭空加一张', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      expect(find.text('0/5'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('returnEvidenceAddV2')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('returnEvidenceCameraV2')), findsOneWidget,
          reason: '不给相机/相册入口，就只能是桩');
      expect(find.byKey(const ValueKey('returnEvidenceGalleryV2')), findsOneWidget);
      expect(find.text('1/5'), findsNothing,
          reason: '🔴 计数在选图之前就涨 = 又变回那个假 key 的桩');
    });

    testWidgets('取消选择 → 计数不变，也没有凭证被加进去', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('returnEvidenceAddV2')));
      await tester.pumpAndSettle();
      // 点 sheet 外面关掉（用户改主意）
      await tester.tapAt(const Offset(200, 60));
      await tester.pumpAndSettle();

      expect(find.text('0/5'), findsOneWidget);
      expect(find.byKey(const ValueKey('returnEvidenceRemove_0')), findsNothing);
    });
  });

  group('🔴 退货申请：回程运费归属必须在提交前告知', () {
    testWidgets('选「质量问题」类原因 → 平台承担', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('returnReason_damaged')));
      await tester.pumpAndSettle();

      expect(find.text('Ongkos kirim balik ditanggung TailTopia'), findsOneWidget);
    });

    testWidgets('选「改主意」→ 买家承担', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('returnReason_changedMind')));
      await tester.pumpAndSettle();

      expect(find.text('Ongkos kirim balik ditanggung pembeli'), findsOneWidget,
          reason: '等提交后才告知运费自付，是最典型的客诉来源');
    });

    testWidgets('🔴 不预选原因 —— 预选会让人在没看清运费归属时就提交', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('returnShipBearerNoticeV2')), findsNothing);
    });
  });

  group('🔴 去程运费：堵凑单套利的唯一告知点（UX-DR2）', () {
    testWidgets('未全选 → 去程运费不退回', (tester) async {
      await tester.pumpWidget(requestHost(eligibility(lines: [line(qty: 2)])));
      await tester.pumpAndSettle();

      final notice = tester.widget<Text>(
          find.byKey(const ValueKey('returnOutboundFeeNoticeV2')));
      expect(notice.data, 'Ongkir awal tidak dikembalikan untuk retur sebagian');
    });

    testWidgets('全选 → 去程运费会退回', (tester) async {
      await tester.pumpWidget(requestHost(eligibility(lines: [line(id: 1, qty: 2)])));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('returnLine_1')));
      await tester.pumpAndSettle();

      final notice = tester.widget<Text>(
          find.byKey(const ValueKey('returnOutboundFeeNoticeV2')));
      expect(notice.data, 'Ongkir awal akan dikembalikan',
          reason: '不实时切换的话，「退掉凑单商品」这条套利路径就没有任何告知');
    });
  });

  group('🔴 不可退的行保留可见并标注原因', () {
    testWidgets('置灰但不隐藏，且展示服务端给的原因', (tester) async {
      await tester.pumpWidget(requestHost(eligibility(lines: [
        // ⚠️ 可退性由 `selectableFor()` 按 **returnPolicy** 算，
        //    `selectable` 字段本身不参与 —— 用 NON_RETURNABLE 才真的挡住。
        line(id: 7, policy: 'NON_RETURNABLE', blockedReason: 'Sudah dibuka'),
      ])));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('returnLine_7')), findsOneWidget,
          reason: '直接隐藏会让用户以为自己记错了买过什么');
      expect(find.byKey(const ValueKey('returnBlocked_7')), findsOneWidget);
      expect(find.text('Sudah dibuka'), findsOneWidget);
    });
  });

  group('开封不退是三处明示的第 3 处', () {
    testWidgets('警示块在，且文案与详情页/结算页同一批 key', (tester) async {
      await tester.pumpWidget(requestHost(eligibility()));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('returnNoReturnBlockV2'));
      expect(find.byKey(const ValueKey('returnNoReturnBlockV2')), findsOneWidget);
      expect(
          find.textContaining(
              'Demi keamanan pangan, kemasan makanan yang sudah dibuka tidak bisa diretur.'),
          findsOneWidget);
    });
  });

  // ================================================================ 退款方式

  Widget refundHost(ReturnProgress p) => ProviderScope(
        overrides: [
          returnProgressProvider.overrideWith((ref, token) async => p),
        ],
        child: wrap(const RefundMethodPageV2(returnToken: 'ret1')),
      );

  ReturnProgress progress({
    int coinRefund = 50000,
    int cashRefund = 154000,
    ReturnType? type = ReturnType.qualityIssue,
    // 🔴 默认给值，且给的是**服务端真实下发的枚举**（`PLATFORM` / `BUYER`）。
    //    这个字段原先在夹具里恒为 null，于是页面里 `p.returnShipBearer ?? 推断` 的
    //    右半边在测试里总是生效、在真机上永远不生效 —— 单测全绿、上机显示 `PLATFORM`。
    //    夹具不还原真实下发值，护栏守的就是另一个世界。
    String? shipBearer = 'PLATFORM',
    int compensationPremium = 0,
    int incentivePremium = 0,
    // D-11：「若选转币会拿到多少」的预览值。默认 0 = staging 实测的 premiumRate=0。
    int incentivePremiumIfPawcoin = 0,
  }) =>
      ReturnProgress(
        returnToken: 'ret1',
        orderToken: 'ord1',
        status: ReturnStatus.pendingReview,
        returnType: type,
        returnShipBearer: shipBearer,
        fullReturn: true,
        outboundFeeRefundable: true,
        coinRefund: coinRefund,
        cashRefund: cashRefund,
        compensationPremium: compensationPremium,
        incentivePremium: incentivePremium,
        incentivePremiumIfPawcoin: incentivePremiumIfPawcoin,
        shipbackReimbursement: 0,
        grandTotal: coinRefund + cashRefund,
        lines: const [
          ReturnProgressLine(
            productName: 'Royal Canin Adult Dog',
            specName: '3 kg',
            qty: 1,
            lineRefundAmount: 204000,
          ),
        ],
      );

  /// 🔴 D-11（2026-09-02 stag，P1）：三句承诺都是**无条件硬编码**的。
  ///
  /// 实测「Changed my mind（买家自身原因）」的退货：补偿溢价 0、激励溢价 0、
  /// 合计 = 商品原价，页面却照样写着
  /// 「Because this one is on us, we are adding extra balance.」、
  /// 「Convert to PawCoin · Lands instantly, **with a bonus**」、
  /// 「Total refunded **(incl. goodwill)**」。
  ///
  /// 🔴 转币这句尤其要紧：用户**因为这句话才选的转币**，而该选择**不可逆**
  /// （PawCoin 不能提现）。
  /// ⚠️ 「on us（算我们的）」还隐含卖家责任，买家自身原因的退货显示它本身就是错的口径。
  group('🔴 D-11：没有的补偿不许承诺', () {
    testWidgets('🔴 补偿为 0 → 不说「算我们的，额外补余额」', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(refundHost(progress(compensationPremium: 0)));
      await tester.pumpAndSettle();

      final block = tester.widget<ShopWarnBlock>(
          find.byKey(const ValueKey('refundNotCashBlockV2')));
      expect(block.body, l10n.refundNotCashBody,
          reason: '补偿是 0 却承诺补余额 —— 用户会去客服问补偿在哪');
      expect(block.body, isNot(contains('ekstra')));
    });

    testWidgets('补偿 > 0 → 那句话回来（平台责任才说「算我们的」）', (tester) async {
      await tester.pumpWidget(refundHost(progress(compensationPremium: 5000)));
      await tester.pumpAndSettle();

      final block = tester.widget<ShopWarnBlock>(
          find.byKey(const ValueKey('refundNotCashBlockV2')));
      expect(block.body, contains('ekstra'));
    });

    testWidgets('🔴 激励溢价为 0 → 转币选项不写「dapat bonus」', (tester) async {
      await tester.pumpWidget(refundHost(progress(incentivePremiumIfPawcoin: 0)));
      await tester.pumpAndSettle();

      final tile = tester.widget<ShopRadioTile>(
          find.byKey(const ValueKey('refundToPawcoinV2')));
      expect(tile.label, isNot(contains('bonus')),
          reason: '用户因为这句话才选转币，而转币不可逆');
      expect(tile.label, contains('Masuk seketika'));
    });

    testWidgets('🔴 判据是「若选转币会拿到多少」，不是当前已算出的激励额', (tester) async {
      // incentivePremium 要**已经选了**转币才非零，而这句承诺是在选择**之前**看到的。
      // 拿它判就恒为 0、永远藏掉 —— 所以后端另给了 incentivePremiumIfPawcoin。
      await tester.pumpWidget(refundHost(
          progress(incentivePremium: 0, incentivePremiumIfPawcoin: 3000)));
      await tester.pumpAndSettle();

      final tile = tester.widget<ShopRadioTile>(
          find.byKey(const ValueKey('refundToPawcoinV2')));
      expect(tile.label, contains('bonus'));
    });

    testWidgets('🔴 补偿为 0 → 合计不写「(termasuk tambahan)」', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(refundHost(progress(compensationPremium: 0)));
      await tester.pumpAndSettle();

      expect(find.text(l10n.refundMethodGrandTotalPlain), findsOneWidget);
      expect(find.text(l10n.refundMethodGrandTotal), findsNothing,
          reason: '补偿是 0 时，「含补偿」说的是一笔不存在的钱');
    });
  });

  group('🔴 后端枚举不得漏到用户眼前', () {
    testWidgets('运费归属步骤给文案，不给 `PLATFORM`', (tester) async {
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('refundProcessBlockV2'));
      expect(find.textContaining('PLATFORM'), findsNothing,
          reason: '`??` 的右半边永远不执行 —— 服务端恒有值');
      expect(find.text('Ongkos kirim balik ditanggung TailTopia'), findsOneWidget);
    });

    testWidgets('BUYER → 买家承担那句（归属以服务端下发为准）', (tester) async {
      await tester.pumpWidget(refundHost(progress(shipBearer: 'BUYER')));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('refundProcessBlockV2'));
      expect(find.textContaining('BUYER'), findsNothing);
      expect(find.text('Ongkos kirim balik ditanggung pembeli'), findsOneWidget);
    });

    testWidgets('🔴 服务端归属与退货类型不一致时，以服务端为准', (tester) async {
      // 拒收 / 发货前取消这类由服务端算归属的分支，端上按 returnType 推会推错。
      await tester.pumpWidget(refundHost(progress(
        type: ReturnType.nonQualityIssue,
        shipBearer: 'PLATFORM',
      )));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('refundProcessBlockV2'));
      expect(find.text('Ongkos kirim balik ditanggung TailTopia'), findsOneWidget);
    });

    testWidgets('页头第三行是退货原因，不是运费归属枚举', (tester) async {
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();

      expect(find.textContaining('PLATFORM'), findsNothing);
      expect(find.textContaining('Barang rusak'), findsWidgets,
          reason: '设计稿这一行是原因；渲染成 bearer 是把枚举当文案用');
    });
  });

  group('🔴 退款拆分：两段各自的归宿必须讲清', () {
    testWidgets('混合支付 → 两段都渲染', (tester) async {
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('refundCoinSegmentV2')), findsOneWidget);
      expect(find.byKey(const ValueKey('refundCashSegmentV2')), findsOneWidget);
    });

    testWidgets('🔴 纯 QRIS 单不出现 PawCoin 段与不可提现说明', (tester) async {
      await tester.pumpWidget(refundHost(progress(coinRefund: 0, cashRefund: 204000)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('refundCoinSegmentV2')), findsNothing);
      expect(find.byKey(const ValueKey('refundNotCashBlockV2')), findsNothing,
          reason: '一笔没用 PawCoin 的退款不该出现「PawCoin 不能提现」的警告');
    });

    testWidgets('🔴 PawCoin 段必须带「不可提现」说明（堵变相提现）', (tester) async {
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('refundNotCashBlockV2')), findsOneWidget);
    });

    testWidgets('🔴 「去向不可改」只贴在 PawCoin 段上', (tester) async {
      // 设计稿把这句写在整块的副标题上，但本支付栈的现金段是用户选的 ——
      // 贴到整页就是一句假话，而这是钱的事。
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('refundCoinFixedNoticeV2')), findsOneWidget);
      expect(find.byKey(const ValueKey('refundToBankV2')), findsOneWidget,
          reason: '现金段必须能选去向，否则这笔钱根本退不出去');
    });

    testWidgets('合计取服务端 grandTotal，不由前端相加', (tester) async {
      // 故意给一个「加不出来」的组合：50.000 + 154.000 ≠ 300.000。
      final p = ReturnProgress(
        returnToken: 'ret1',
        orderToken: 'ord1',
        status: ReturnStatus.pendingReview,
        returnType: ReturnType.qualityIssue,
        fullReturn: true,
        outboundFeeRefundable: true,
        coinRefund: 50000,
        cashRefund: 154000,
        compensationPremium: 0,
        incentivePremium: 0,
        shipbackReimbursement: 0,
        grandTotal: 300000,
        lines: const [],
      );
      await tester.pumpWidget(refundHost(p));
      await tester.pumpAndSettle();

      final total = tester.widget<Text>(find.byKey(const ValueKey('refundGrandTotalV2')));
      expect(total.data, 'Rp 300.000',
          reason: '前端自己相加会漏掉服务端的溢价/补偿项，且与实际到账对不上');
    });
  });

  group('流程说明第 3 步随退货原因切换', () {
    testWidgets('质量问题 → 平台承担回程运费', (tester) async {
      // 服务端未下发归属时才轮到端上按原因推 —— 这两条测的正是那条回落分支。
      await tester.pumpWidget(
          refundHost(progress(type: ReturnType.qualityIssue, shipBearer: null)));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('refundProcessBlockV2'));
      expect(find.text('Ongkos kirim balik ditanggung TailTopia'), findsOneWidget);
    });

    testWidgets('非质量问题 → 买家承担', (tester) async {
      await tester.pumpWidget(
          refundHost(progress(type: ReturnType.nonQualityIssue, shipBearer: null)));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('refundProcessBlockV2'));
      expect(find.text('Ongkos kirim balik ditanggung pembeli'), findsOneWidget);
    });
  });

  group('布局不得溢出', () {
    testWidgets('退货申请 · 411dp', (tester) async {
      await tester.pumpWidget(requestHost(eligibility(lines: [
        line(id: 1),
        line(id: 2, selectable: false, blockedReason: 'Sudah dibuka'),
      ])));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('退款方式 · 411dp', (tester) async {
      await tester.pumpWidget(refundHost(progress()));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}

/// 滚到指定 key 可见。
///
/// ⚠️ `ListView` 只构建视口内的子项，视口外的块**不在 widget 树里** ——
/// 不滚就断言会得到「功能没做」的假象。显式给 scrollable：本页的输入框
/// 与下拉都各自带 Scrollable，不指定会因「找到多个」直接抛错。
Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    200,
    scrollable: find.byType(Scrollable).first,
    maxScrolls: 25,
  );
  await tester.pumpAndSettle();
}
