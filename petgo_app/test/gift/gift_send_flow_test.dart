import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/gift/presentation/gift_send_flow.dart';
import 'package:tailtopia/features/growth/domain/growth_popup_trigger.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'gift_fakes.dart';

/// V1.4.0 gift Story 1.3 / V1.5.0 gift-pack Story 2-2（L0，假仓储 + 假路由）：送礼全流程状态机
/// （选礼物页 → 包装页 → 送出 / QRIS / 终止）。
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
    // V1.5.0 gift-pack Story 2-3：送出动画替身（不渲染 Rive）
    giftSendRiveBuilderOverride = (_, _, _, _) => const SizedBox.expand(key: ValueKey('fakeRive'));
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
    giftSendRiveBuilderOverride = null;
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
    final c = ProviderScope.containerOf(tester.element(find.byKey(const ValueKey('go'), skipOffstage: false)));
    await tester.runAsync(() => c.read(pawCoinProvider.future));
  }

  Finder wrap() => find.byKey(const ValueKey('giftWrapTitle'));
  Finder catalogPage() => find.byKey(const ValueKey('giftCatalogLoaded'));
  Finder send() => find.byKey(const ValueKey('giftWrapSend'));

  Future<void> pick(WidgetTester tester, [String token = 'g-tulang']) async {
    await tester.tap(find.byKey(ValueKey('giftCell-$token')));
    await tester.pumpAndSettle();
  }

  Future<void> kirim(WidgetTester tester, {int? chip, bool settle = true}) async {
    if (chip != null) {
      await tester.tap(find.byKey(ValueKey('giftWrapQty-$chip')));
      await tester.pump();
    }
    await tester.tap(send());
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }
  }

  Future<void> chooseDirect(WidgetTester tester) async {
    if (find.byKey(const ValueKey('giftWrapPay-collapsed')).evaluate().isNotEmpty) {
      await tester.tap(find.byKey(const ValueKey('giftWrapPayToggle')));
      await tester.pumpAndSettle();
    }
    await tester.tap(find.byKey(const ValueKey('payMethodCard-direct')));
    await tester.pumpAndSettle();
  }

  String sendLabel(WidgetTester tester) => tester
      .widget<Text>(find.descendant(of: send(), matching: find.byType(Text)))
      .data!;

  testWidgets('选礼物页 → 包装页：标题 / EXP / 祝福语计数；返回 → 回选礼物页', (tester) async {
    await start(tester);
    expect(catalogPage(), findsOneWidget);
    await pick(tester);
    expect(find.text('Bungkus hadiah untuk Mochi'), findsOneWidget);
    expect(find.text('0/500'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), 'Halo 😀');
    await tester.pump();
    expect(find.text('6/500'), findsOneWidget, reason: '按 runes 计数');
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(catalogPage(), findsOneWidget);
    expect(repo.sends, isEmpty);
  });

  testWidgets('PawCoin 成功 → 动效 → Toast → 余额 invalidate + 埋点；祝福语 trim 后带上', (tester) async {
    await start(tester);
    await pick(tester);
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), '  Selamat!  ');
    await tester.pump();
    expect(sendLabel(tester), 'Kirim · 1.000 PawCoin');
    final buildsBefore = pawBuilds.length;
    repo.sendResults = [const GiftSendResult(delivered: true, orderToken: 'ord-1', senderExp: 25)];
    await kirim(tester, chip: 5, settle: false);
    expect(find.byKey(const ValueKey('fakeRive')), findsOneWidget, reason: 'E1 送出动画');
    await tester.pump(const Duration(milliseconds: 5600));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget, reason: '5.5 秒兜底 → E3');
    expect(find.text('Kamu dapat 25 EXP'), findsOneWidget);
    expect(find.text('Tulang ×5 terkirim ke Mochi'), findsNothing, reason: '旧 Toast 不再出现');
    await rereadBalance(tester);
    expect(pawBuilds.length, greaterThan(buildsBefore));
    expect(delivered, 1);
    expect(growthPulls, isEmpty, reason: '升级庆祝在离开送达页之后');
    await tester.tap(find.byKey(const ValueKey('giftDeliveredContinue')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('go')), findsOneWidget, reason: 'Lanjut → 回入口页');
    expect(growthPulls, [GrowthPullReason.afterAction]);
    final s = repo.sends.single;
    expect([s.petId, s.giftToken, s.quantity, s.channel, s.source, s.postId, s.message],
        [42, 'g-tulang', 5, 'PAWCOIN', 'diary_tab', null, 'Selamat!']);
    expect(s.requestId, matches(RegExp(r'^[0-9a-f]{32}$')));
    expect(events.where((e) => e.$1 == 'gift_payment_succeeded').single.$2, {
      'gift_id': 'g-tulang',
      'quantity': 5,
      'total_amount': 5000,
      'channel': 'PAWCOIN',
    });
    expect(events.where((e) => e.$1 == 'gift_panel_opened').length, 1);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('gift-message-blocked → 不离开、框变红 + 提示；改字后红态消失；再送换新 requestId', (tester) async {
    repo.sendResults = [problem('gift-message-blocked', status: 422), const GiftSendResult(delivered: true, orderToken: 'o')];
    await start(tester, disableAnimations: true);
    await pick(tester);
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), 'kata jelek');
    await kirim(tester);
    expect(wrap(), findsOneWidget);
    expect(find.text('Ucapan berisi kata yang tidak diperbolehkan. Ubah dulu, ya.'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), 'kata baik');
    await tester.pump();
    expect(find.byKey(const ValueKey('giftWrapMessageBlocked')), findsNothing);
    await kirim(tester);
    expect(repo.sends.length, 2);
    expect(repo.sends[0].requestId, isNot(repo.sends[1].requestId));
    expect(repo.sends[1].message, 'kata baik');
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('pawcoin-insufficient → 停留包装页 + 刷新余额', (tester) async {
    repo.sendResults = [problem('pawcoin-insufficient')];
    await start(tester);
    await pick(tester);
    final before = pawBuilds.length;
    await kirim(tester);
    expect(wrap(), findsOneWidget);
    expect(find.text('PawCoin tidak cukup. Silakan isi ulang dulu.'), findsOneWidget);
    await rereadBalance(tester);
    expect(pawBuilds.length, greaterThan(before));
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('gift-blocked → 结束流程 + giftCannotSend', (tester) async {
    repo.sendResults = [problem('gift-blocked', status: 403)];
    await start(tester);
    await pick(tester);
    await kirim(tester);
    expect(wrap(), findsNothing);
    expect(catalogPage(), findsNothing);
    expect(find.text('Hadiah tidak bisa dikirim. Hewan ini sudah tidak ada.'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('gift-unavailable → Toast 后回选礼物页并重拉目录', (tester) async {
    repo.sendResults = [problem('gift-unavailable')];
    await start(tester);
    final calls = repo.catalogCalls;
    await pick(tester);
    await kirim(tester);
    expect(catalogPage(), findsOneWidget);
    expect(repo.catalogCalls, greaterThan(calls));
    expect(find.text('Hadiah ini sudah tidak tersedia.'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('锁区块 → 进 /me/growth，流程结束', (tester) async {
    repo.catalog = sampleCatalog(lockBlock: const GiftLockBlock(requiredLevel: 5));
    await start(tester);
    await tester.tap(find.byKey(const ValueKey('giftLockBlock')));
    await tester.pumpAndSettle();
    expect(pushed, ['growth']);
  });

  testWidgets('余额不足 + 低于门槛：自动展开、按钮置灰；点 PawCoin 卡去充值，回来余额够 → 选回 PawCoin、内容保留',
      (tester) async {
    balance = 500;
    await start(tester);
    await pick(tester);
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), 'tetap');
    await tester.pump();
    expect(find.byKey(const ValueKey('giftWrapPay-expanded')), findsOneWidget);
    expect(sendLabel(tester), 'PawCoin tidak cukup · 1.000 PawCoin');
    expect(tester.widget<FilledButton>(send()).onPressed, isNull);
    expect(find.text('Minimal Rp10.000'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-pawcoin')));
    await tester.pumpAndSettle();
    expect(pushed, ['recharge']);
    balance = 100000;
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(wrap(), findsOneWidget);
    expect(sendLabel(tester), 'Kirim · 1.000 PawCoin');
    expect(find.text('tetap'), findsOneWidget);
  });

  testWidgets('余额不足但够门槛：默认直接付款「Kirim · Rp…」', (tester) async {
    balance = 500;
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    await start(tester);
    await pick(tester);
    expect(sendLabel(tester), 'Kirim · Rp1.000');
  });

  testWidgets('requestId：同渠道重试复用；换渠道新生成', (tester) async {
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    repo.sendResults = [problem('internal', status: 500)];
    await start(tester);
    await pick(tester);
    await kirim(tester);
    await kirim(tester);
    expect(repo.sends[0].requestId, repo.sends[1].requestId);
    await chooseDirect(tester);
    await kirim(tester);
    expect(repo.sends[2].channel, 'QRIS');
    expect(repo.sends[2].requestId, isNot(repo.sends[1].requestId));
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('requestId：改数量或祝福语后重试换新键（防重放旧单）', (tester) async {
    repo.sendResults = [problem('internal', status: 500)];
    await start(tester);
    await pick(tester);
    await kirim(tester);
    await kirim(tester, chip: 5);
    expect(repo.sends[1].requestId, isNot(repo.sends[0].requestId));
    await tester.enterText(find.byKey(const ValueKey('giftWrapMessage')), 'baru');
    await kirim(tester);
    expect(repo.sends[2].requestId, isNot(repo.sends[1].requestId));
    await kirim(tester);
    expect(repo.sends[3].requestId, repo.sends[2].requestId);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('返回时选礼物页仍在下面（不重新加载）', (tester) async {
    await start(tester);
    final calls = repo.catalogCalls;
    await pick(tester);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(catalogPage(), findsOneWidget);
    expect(repo.catalogCalls, calls);
  });

  testWidgets('QRIS 出码 → 二维码；pollPaid DELIVERED → Toast + capturePurchase', (tester) async {
    const pending = GiftSendResult(
      delivered: false,
      paymentToken: 'pi-1',
      paymentDisplayNo: 'PAY-1',
      payload: '00020101021226',
      orderToken: 'ord-q',
      status: GiftOrderStatus.pending,
    );
    repo.catalog = sampleCatalog(qrisMinAmount: 1000);
    repo.sendResults = [pending];
    repo.statuses = [GiftOrderStatus.delivered];
    await start(tester, disableAnimations: true);
    await pick(tester);
    await chooseDirect(tester);
    await kirim(tester, chip: 10, settle: false);
    expect(wrap(), findsNothing);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget, reason: '减弱动态效果 → 直接 E3');
    expect(find.byKey(const ValueKey('giftDeliveredExp')), findsNothing, reason: '目录 senderExp = 0');
    expect(events.where((e) => e.$1 == 'gift_payment_succeeded').single.$2?['channel'], 'QRIS');
    expect(events.where((e) => e.$1 == 'af_purchase').single.$2!['af_order_id'], 'PAY-1');
    await tester.pump(const Duration(seconds: 3));
  });
}
