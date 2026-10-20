import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/gift/presentation/gift_send_animation.dart';
import 'package:tailtopia/features/gift/presentation/gift_send_flow.dart';
import 'package:tailtopia/features/growth/domain/growth_popup_trigger.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'gift_fakes.dart';

/// V1.4.0 gift Story 1.3 · AC3.4 ~ 3.6 / AC4 / AC5（L0，假仓储 + 假路由）：送礼全流程状态机。
void main() {
  late FakeGiftRepository repo;
  late List<int> pawBuilds;
  late int balance;
  late List<(String, Map<String, Object>?)> events;
  late int delivered;
  late List<String> pushed;
  late List<GrowthPullReason> growthPulls;

  void growthSink(GrowthPullReason r) => growthPulls.add(r);

  setUp(() {
    growthPulls = [];
    growthPopupTrigger.bind(growthSink);
    repo = FakeGiftRepository();
    pawBuilds = [];
    balance = 100000;
    events = [];
    delivered = 0;
    pushed = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    growthPopupTrigger.resetForTest();
  });

  Future<void> start(WidgetTester tester,
      {GiftRecipientRef recipient = const GiftRecipientRef.pet(42),
      String source = giftSourceDiaryTab,
      int? sourcePostId,
      bool isMultiPetPost = false,
      bool disableAnimations = false}) async {
    // 测试字体每个字形是 fontSize 见方的方块，360 宽时「Isi saldo dulu →」会把 PawCoin 行挤到逐字换行；
    // 用宽一点的逻辑画布（800×1300）避免测试环境专属的溢出。
    tester.view.physicalSize = const Size(1600, 2600);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => Scaffold(
          body: Consumer(
            builder: (context, ref, _) => Center(
              child: ElevatedButton(
                key: const ValueKey('go'),
                onPressed: () => runGiftSendFlow(
                  context: context,
                  ref: ref,
                  recipient: recipient,
                  source: source,
                  sourcePostId: sourcePostId,
                  isMultiPetPost: isMultiPetPost,
                  onDelivered: () => delivered++,
                ),
                child: const Text('go'),
              ),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/me/growth',
        builder: (_, _) {
          pushed.add('growth');
          return const Scaffold(body: Text('level page'));
        },
      ),
      GoRoute(
        path: '/me/pawcoin/recharge',
        builder: (_, _) {
          pushed.add('recharge');
          return const Scaffold(body: Text('recharge page'));
        },
      ),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        giftRepositoryProvider.overrideWithValue(repo),
        pawCoinProvider.overrideWith(() => FakePawCoin(() => balance, pawBuilds)),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(disableAnimations: disableAnimations),
          child: child!,
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
  }

  /// 余额被 invalidate 后不会自己重建（没有监听者）；读一次才知道有没有被失效。
  Future<void> rereadBalance(WidgetTester tester) async {
    final c = ProviderScope.containerOf(tester.element(find.byKey(const ValueKey('go'))));
    await tester.runAsync(() => c.read(pawCoinProvider.future));
  }

  Finder panel() => find.byKey(const ValueKey('giftPanelLoaded'));
  Finder picker() => find.byKey(const ValueKey('hdPayConfirm'));

  Future<void> kirim(WidgetTester tester, {int? chip}) async {
    if (chip != null) {
      await tester.tap(find.byKey(ValueKey('giftQtyChip-$chip')));
      await tester.pump();
    }
    await tester.tap(find.byKey(const ValueKey('giftSendButton')));
    await tester.pumpAndSettle();
  }

  Future<void> confirm(WidgetTester tester) async {
    await tester.tap(picker());
    await tester.pumpAndSettle();
  }

  /// 点抽屉上方遮罩 = 下滑 / 点遮罩关闭。
  Future<void> tapBarrier(WidgetTester tester) async {
    await tester.tapAt(const Offset(400, 20));
    await tester.pumpAndSettle();
  }

  bool cellSelected(String token) => find
      .descendant(
          of: find.byKey(ValueKey('giftCell-$token')),
          matching: find.byKey(const ValueKey('giftCellCheck')))
      .evaluate()
      .isNotEmpty;

  testWidgets('依次弹不叠层：面板收起后才弹选渠道；摘要卡 + 文案', (tester) async {
    await start(tester);
    expect(panel(), findsOneWidget);
    expect(events.where((e) => e.$1 == 'gift_panel_opened').single.$2,
        {'source': 'diary_tab', 'pet_id': 42, 'is_multi_pet_post': false});
    await kirim(tester, chip: 5);
    expect(panel(), findsNothing, reason: '面板已收起（S1 不叠层）');
    expect(picker(), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPaySummary')), findsOneWidget);
    expect(find.text('Tulang × 5'), findsOneWidget);
    expect(find.text('untuk Mochi'), findsOneWidget);
    expect(find.text('Kirim hadiah'), findsOneWidget);
    expect(find.text('Hadiah langsung terkirim setelah pembayaran berhasil.'), findsOneWidget);
    expect(find.text('Bayar Rp5.000'), findsOneWidget);
    // 总价 Rp5.000 < 门槛 Rp10.000（C2）：QRIS 置灰 + 提示 + 上报一次。
    expect(find.text('Minimal Rp10.000'), findsOneWidget);
    expect(find.text('Isi PawCoin biar kirim hadiah lebih praktis.'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'gift_qris_low_amount_shown').single.$2, {'total_amount': 5000});
  });

  testWidgets('PawCoin 成功 → 抽屉关 → 动效 → Toast「Tulang ×5 terkirim ke Mochi」→ 余额 invalidate + 埋点',
      (tester) async {
    await start(tester);
    await kirim(tester, chip: 5);
    final buildsBefore = pawBuilds.length;
    await tester.tap(picker());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(picker(), findsNothing, reason: '送达 → 抽屉关');
    expect(find.byType(GiftSendAnimation), findsOneWidget, reason: '播放送出动效');
    await tester.pump(giftSendAnimationDuration);
    await tester.pump();
    expect(find.byType(GiftSendAnimation), findsNothing);
    expect(find.text('Tulang ×5 terkirim ke Mochi'), findsOneWidget);
    await rereadBalance(tester);
    expect(pawBuilds.length, greaterThan(buildsBefore), reason: '送达后失效余额（重读时重建）');
    expect(delivered, 1);
    expect(growthPulls, [GrowthPullReason.afterAction], reason: 'L2 r2 P1-2：送达即触发升级庆祝拉取');

    final s = repo.sends.single;
    expect([s.petId, s.giftToken, s.quantity, s.channel, s.source, s.postId],
        [42, 'g-tulang', 5, 'PAWCOIN', 'diary_tab', null]);
    expect(s.requestId, matches(RegExp(r'^[0-9a-f]{32}$')));
    expect(events.where((e) => e.$1 == 'gift_payment_succeeded').single.$2, {
      'gift_id': 'g-tulang',
      'quantity': 5,
      'total_amount': 5000,
      'channel': 'PAWCOIN',
    });
    expect(events.where((e) => e.$1 == 'af_purchase'), isEmpty, reason: 'PawCoin 消费不计现金付款');
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('详情页入口（gift 4.1 · AC4.3）：按所选 petId 取目录、发起带 source=content_detail 与来源 postId；'
      '多宠帖埋点 is_multi_pet_post=true；数量 1 省略「×1」', (tester) async {
    await start(tester,
        source: giftSourceContentDetail, sourcePostId: 77, isMultiPetPost: true);
    expect(repo.catalogArgs.single, (petId: 42, postId: null));
    expect(events.where((e) => e.$1 == 'gift_panel_opened').single.$2,
        {'source': 'content_detail', 'pet_id': 42, 'is_multi_pet_post': true});
    await kirim(tester);
    await confirm(tester);
    await tester.pump(const Duration(seconds: 2));
    final s = repo.sends.single;
    expect([s.petId, s.source, s.postId], [42, 'content_detail', 77]);
    expect(find.text('Tulang terkirim ke Mochi'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('锁区块（gift 4.1 · AC7.1）→ 面板关 → 进 ① 等级页 /me/growth，流程结束不重开面板', (tester) async {
    repo.catalog = sampleCatalog(lockBlock: const GiftLockBlock(requiredLevel: 5));
    await start(tester);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('giftLockBlock')), 200,
        scrollable: find.descendant(
            of: find.byKey(const ValueKey('giftPanelGrid')), matching: find.byType(Scrollable)));
    await tester.tap(find.byKey(const ValueKey('giftLockBlock')));
    await tester.pumpAndSettle();
    expect(panel(), findsNothing);
    expect(pushed, ['growth']);
    expect(find.text('level page'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'gift_locked_block_tapped').single.$2, {'required_level': 5});
    expect(repo.sends, isEmpty);
  });

  testWidgets('pawcoin-insufficient → 抽屉保留、按钮恢复、刷新余额', (tester) async {
    repo.sendResults = [problem('pawcoin-insufficient')];
    await start(tester);
    await kirim(tester);
    final buildsBefore = pawBuilds.length;
    await confirm(tester);
    expect(picker(), findsOneWidget, reason: '抽屉保留可重试');
    expect(find.text('Bayar Rp1.000'), findsOneWidget, reason: '按钮恢复');
    expect(find.text(lookupAppLocalizations(const Locale('id')).idCardHdInsufficientBalance),
        findsOneWidget);
    await rereadBalance(tester);
    expect(pawBuilds.length, greaterThan(buildsBefore), reason: '余额不足 → 失效余额');
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('pawcoin-insufficient → 抽屉里的余额跟着刷新：PawCoin 行置灰 + 先充值，不再默认 PawCoin', (tester) async {
    repo.sendResults = [problem('pawcoin-insufficient')];
    await start(tester);
    await kirim(tester);
    expect(find.text('Saldo 100.000 · Potong 1.000 koin'), findsOneWidget);
    balance = 300; // 别处花掉了：打开时读到的余额已过期
    await confirm(tester);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(picker(), findsOneWidget, reason: '抽屉保留');
    expect(find.text('Saldo 300 · Potong 1.000 koin'), findsOneWidget, reason: '抽屉内余额已刷新');
    expect(find.text('Isi saldo dulu →'), findsOneWidget, reason: '余额不足 → 先充值');
    // 总价 Rp1.000 < QRIS 门槛 → QRIS 置灰；余额也不足 → 无选中、确认置灰（不会再拿 PawCoin 撞 409）。
    final btn = tester.widget<FilledButton>(picker());
    expect(btn.onPressed, isNull);
    expect(repo.sends, hasLength(1));
  });

  testWidgets('gift-blocked → 关抽屉、结束流程 + giftCannotSend（不回面板）', (tester) async {
    repo.sendResults = [problem('gift-blocked', status: 403)];
    await start(tester);
    await kirim(tester);
    await confirm(tester);
    expect(picker(), findsNothing);
    expect(panel(), findsNothing);
    expect(find.text('Hadiah tidak bisa dikirim. Hewan ini sudah tidak ada.'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('gift-locked → 关抽屉、结束；gift-unavailable → Toast 后重开面板并重拉目录', (tester) async {
    repo.sendResults = [problem('gift-unavailable')];
    await start(tester);
    await kirim(tester);
    expect(repo.catalogCalls, 1);
    await confirm(tester);
    expect(find.text('Hadiah ini sudah tidak tersedia.'), findsOneWidget);
    expect(panel(), findsOneWidget, reason: '重开面板');
    expect(repo.catalogCalls, 2, reason: '重拉目录');
    await tester.pump(const Duration(seconds: 3));

    repo.sendResults = [problem('gift-locked', status: 403)];
    repo.sends.clear();
    await kirim(tester);
    await confirm(tester);
    expect(panel(), findsNothing);
    expect(picker(), findsNothing);
    expect(find.text('Hadiah ini belum terbuka untuk levelmu.'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('关闭选渠道 → 回面板，礼物与数量保留', (tester) async {
    await start(tester);
    await tester.tap(find.byKey(const ValueKey('giftCell-g-ikan')));
    await kirim(tester, chip: 5);
    expect(picker(), findsOneWidget);
    await tapBarrier(tester);
    expect(picker(), findsNothing);
    expect(panel(), findsOneWidget);
    expect(cellSelected('g-ikan'), isTrue);
    expect(find.text('Kirim · Rp5.000'), findsOneWidget);
    expect(repo.catalogCalls, 1, reason: '目录在流程内保活，不重拉');
    expect(events.where((e) => e.$1 == 'gift_panel_opened'), hasLength(1), reason: '只报一次');
  });

  testWidgets('先充值 → await 充值页 → 刷新余额 → 自动重开选渠道（礼物、数量保留）', (tester) async {
    balance = 100;
    await start(tester);
    await kirim(tester, chip: 5);
    await tester.tap(find.text('Isi saldo dulu →'));
    await tester.pumpAndSettle();
    expect(find.text('recharge page'), findsOneWidget);
    expect(pushed, ['recharge']);

    balance = 50000;
    final buildsBefore = pawBuilds.length;
    final ctx = tester.element(find.text('recharge page'));
    GoRouter.of(ctx).pop();
    await tester.pumpAndSettle();
    expect(picker(), findsOneWidget, reason: '自动重开选渠道');
    expect(find.text('Tulang × 5'), findsOneWidget);
    expect(pawBuilds.length, greaterThan(buildsBefore));
    expect(find.text('Saldo 50.000 · Potong 5.000 koin'), findsOneWidget);
  });

  testWidgets('requestId：同渠道重试复用；换渠道新生成', (tester) async {
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    repo.sendResults = [problem('server-error', status: 500)];
    await start(tester);
    await kirim(tester);
    await confirm(tester); // PawCoin 失败 → 抽屉保留
    await tester.pump(const Duration(seconds: 3));
    await confirm(tester); // 同渠道重试
    await tester.pump(const Duration(seconds: 3));
    await tester.tap(find.text('QRIS'));
    await tester.pumpAndSettle();
    await confirm(tester); // 换渠道
    await tester.pump(const Duration(seconds: 3));
    final ids = repo.sends.map((s) => s.requestId).toList();
    final channels = repo.sends.map((s) => s.channel).toList();
    expect(channels, ['PAWCOIN', 'PAWCOIN', 'QRIS']);
    expect(ids[0], ids[1]);
    expect(ids[2], isNot(ids[0]));

    // 关掉抽屉 → 重新点「Kirim」→ 新生成。
    await tapBarrier(tester);
    await kirim(tester);
    await tester.tap(find.text('QRIS'));
    await tester.pumpAndSettle();
    await confirm(tester);
    await tester.pump(const Duration(seconds: 3));
    expect(repo.sends.last.requestId, isNot(ids[2]));
  });

  for (final closed in [GiftOrderStatus.failed, GiftOrderStatus.expired]) {
    testWidgets('OCR #4：QRIS 回 ${closed.wire} 无码 → 同抽屉重试换新 requestId，第二次出码', (tester) async {
      repo.catalog = sampleCatalog(qrisMinAmount: 1000);
      repo.sendResults = [
        GiftSendResult(delivered: false, orderToken: 'ord-dead', status: closed),
        const GiftSendResult(
          delivered: false,
          paymentToken: 'pi-2',
          paymentDisplayNo: 'PAY-2',
          payload: '00020101021226',
          orderToken: 'ord-2',
          status: GiftOrderStatus.pending,
        ),
      ];
      await start(tester);
      await kirim(tester);
      await tester.tap(find.text('QRIS'));
      await tester.pumpAndSettle();
      await confirm(tester); // 回已关单、无码 → 抽屉保留
      expect(picker(), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      await confirm(tester); // 同渠道重试
      expect(repo.sends, hasLength(2));
      expect(repo.sends[1].requestId, isNot(repo.sends[0].requestId), reason: '已关的单不能同键重放');
      expect(picker(), findsNothing);
      expect(find.textContaining('PAY-2'), findsWidgets, reason: '第二次出码成功');
      await tester.pump(const Duration(seconds: 3));
      await tester.pump(const Duration(seconds: 3));
    });
  }

  testWidgets('网关出码失败首次即回 200 FAILED（后端原样 JSON）→ 提示支付失败，第 2 次点击就换新 requestId 出码',
      (tester) async {
    // 后端 GiftSendService.chargeOrFailed 的响应形状（与重放旧 FAILED 单一致）：不再 500。
    // 以前 500 被视为结果未知、保留 requestId → 第 2 次重放旧单才拿到 FAILED，第 3 次才真正重下。
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    repo.sendResults = [
      GiftSendResult.fromJson({
        'delivered': false,
        'payment': null,
        'payload': null,
        'orderToken': 'ord-gw-fail',
        'status': 'FAILED',
      }),
      const GiftSendResult(
        delivered: false,
        paymentToken: 'pi-3',
        paymentDisplayNo: 'PAY-3',
        payload: '00020101021226',
        orderToken: 'ord-3',
        status: GiftOrderStatus.pending,
      ),
    ];
    await start(tester);
    await kirim(tester);
    await tester.tap(find.text('QRIS'));
    await tester.pumpAndSettle();
    await confirm(tester);
    expect(find.text('Pembayaran gagal. Coba lagi.'), findsOneWidget);
    expect(picker(), findsOneWidget, reason: '抽屉保留可重试');
    await tester.pump(const Duration(seconds: 3));
    await confirm(tester); // 第 2 次点击
    expect(repo.sends, hasLength(2));
    expect(repo.sends[1].requestId, isNot(repo.sends[0].requestId));
    expect(find.textContaining('PAY-3'), findsWidgets, reason: '第 2 次点击即出码');
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('OCR #4：仍待付但无码（非已关单）→ 同渠道重试仍复用 requestId', (tester) async {
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    repo.sendResults = [const GiftSendResult(delivered: false, orderToken: 'ord-p', status: GiftOrderStatus.pending)];
    await start(tester);
    await kirim(tester);
    await tester.tap(find.text('QRIS'));
    await tester.pumpAndSettle();
    await confirm(tester);
    await tester.pump(const Duration(seconds: 3));
    await confirm(tester);
    await tester.pump(const Duration(seconds: 3));
    expect(repo.sends, hasLength(2));
    expect(repo.sends[1].requestId, repo.sends[0].requestId);
  });

  group('QRIS', () {
    const pending = GiftSendResult(
      delivered: false,
      paymentToken: 'pi',
      paymentDisplayNo: 'PAY-1',
      payload: '00020101021226',
      orderToken: 'ord-q',
      status: GiftOrderStatus.pending,
    );

    for (final c in [
      (GiftOrderStatus.expired, 'Pembayaran kedaluwarsa. Coba lagi.'),
      (GiftOrderStatus.failed, 'Pembayaran gagal. Coba lagi.'),
      (GiftOrderStatus.undeliveredRefundPending,
          'Hadiah tidak terkirim. PawCoin akan dikembalikan ke saldomu.'),
    ]) {
      testWidgets('pollPaid ${c.$1.wire} → 关二维码 + 「${c.$2}」', (tester) async {
        repo.catalog = sampleCatalog(qrisMinAmount: 1000);
        repo.sendResults = [pending];
        repo.statuses = [GiftOrderStatus.pending, c.$1];
        await start(tester);
        await kirim(tester);
        await tester.tap(find.text('QRIS'));
        await tester.pumpAndSettle();
        await confirm(tester);
        expect(picker(), findsNothing);
        expect(find.textContaining('PAY-1'), findsWidgets, reason: '二维码面板显示支付号');
        await tester.pump(const Duration(seconds: 3)); // PENDING
        await tester.pump(const Duration(seconds: 3)); // 终态
        await tester.pumpAndSettle();
        expect(find.text(c.$2), findsOneWidget);
        expect(events.where((e) => e.$1 == 'gift_payment_succeeded'), isEmpty);
        await tester.pump(const Duration(seconds: 3));
      });
    }

    testWidgets('pollPaid DELIVERED → 动效 + Toast + capturePurchase + gift_payment_succeeded(QRIS)',
        (tester) async {
      repo.catalog = sampleCatalog(qrisMinAmount: 1000);
      repo.sendResults = [pending];
      repo.statuses = [GiftOrderStatus.delivered];
      await start(tester, disableAnimations: true);
      await kirim(tester, chip: 10);
      await tester.tap(find.text('QRIS'));
      await tester.pumpAndSettle();
      await confirm(tester);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.text('Tulang ×10 terkirim ke Mochi'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'gift_payment_succeeded').single.$2?['channel'], 'QRIS');
      final purchase = events.where((e) => e.$1 == 'af_purchase').single.$2!;
      expect(purchase['af_revenue'], 10000);
      expect(purchase['af_content_id'], 'GIFT');
      expect(purchase['af_order_id'], 'PAY-1');
      expect(growthPulls, [GrowthPullReason.afterAction], reason: 'QRIS 到账同样触发升级庆祝拉取');
      await tester.pump(const Duration(seconds: 3));
    });
  });

  testWidgets('减少动态效果：不飞行、不出粒子，直接 Toast', (tester) async {
    await start(tester, disableAnimations: true);
    await kirim(tester);
    await tester.tap(picker());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(GiftSendAnimation), findsNothing);
    expect(find.text('Tulang terkirim ke Mochi'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });
}
