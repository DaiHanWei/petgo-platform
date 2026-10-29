import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/pet_slot/domain/pet_slot_purchase.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'pet_slot_fakes.dart';

/// V1.4.0 multi-pet Story 2.4 · AC1 / AC2 / AC3 / AC4 / AC7（UI 稿 D1 ~ D15）：名额管理页四态、加载失败、
/// 租用 / 续租 / 改选流程与埋点。结构断言，不写 golden。
void main() {
  late AppLocalizations l10n;
  late List<(String, Map<String, Object>?)> events;

  setUpAll(() async {
    await initializeDateFormatting('id');
    l10n = await AppLocalizations.delegate.load(const Locale('id'));
  });
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  final oct31 = DateTime.utc(2026, 10, 31, 5);
  final oct16 = DateTime.utc(2026, 10, 16, 5);
  final twoPets = [pet(1, 'Orenji'), pet(2, 'Kopi', petType: 'DOG')];

  FilledButton rentButton(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const ValueKey('petSlotRentButton')));

  group('页面四态（D1 ~ D4）', () {
    testWidgets('D1 无月租：已用 / 总数、Bawaan / Level + 3 格 Bisa disewa、无 Slot sewa / Perpanjang / Ubah pilihan',
        (tester) async {
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets);
      expect(find.text(l10n.petSlotPageTitle), findsOneWidget);
      expect(find.byKey(const ValueKey('petSlotUsed')), findsOneWidget);
      expect(find.text(l10n.petSlotUsedSuffix(2)), findsOneWidget);
      expect(find.text(l10n.petSlotCapNote), findsOneWidget);
      expect(find.text(l10n.petSlotSourceDefault), findsOneWidget);
      expect(find.text(l10n.petSlotSourceLevel), findsOneWidget);
      expect(find.text(l10n.petSlotCellAvailable), findsNWidgets(3));
      expect(find.text(l10n.petSlotRentalsSection.toUpperCase()), findsNothing);
      expect(find.text(l10n.petSlotStatusActive), findsNWidgets(2));
      expect(find.text(l10n.petSlotNoAutoRenewNote), findsOneWidget);
      expect(find.byKey(const ValueKey('petSlotRenewButton')), findsNothing);
      expect(find.byKey(const ValueKey('petSlotChangeSelection')), findsNothing);
      // 价格 / 天数取后端（无本地兜底）。
      expect(find.text(l10n.petSlotRentCta('Rp5.000', 30)), findsOneWidget);
      expect(rentButton(tester).onPressed, isNotNull);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('bug 592：空名额格（Kosong）可点 → 走添加宠物入口进建档页', (tester) async {
      await pumpSlotPage(tester, overviews: [overview(levelSlots: 2, petCount: 1)], pets: [pet(1, 'Ys')]);
      expect(find.text(l10n.petSlotCellEmpty), findsNWidgets(2));
      await tester.tap(find.byKey(const ValueKey('petSlotEmptyCellTap')).first);
      await tester.pumpAndSettle();
      expect(find.text('create'), findsOneWidget);
    });

    testWidgets('bug 593：无头像宠物按物种出 emoji 占位（名额格 + Hewanku 行），不再是通用爪印', (tester) async {
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets);
      expect(find.text('🐱'), findsNWidgets(2));
      expect(find.text('🐶'), findsNWidgets(2));
      expect(find.byIcon(Icons.pets), findsNothing);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('价格不写死：单价 7000 / 31 天 → CTA 跟随', (tester) async {
      await pumpSlotPage(tester, overviews: [overview(petCount: 2, unitPrice: 7000, days: 31)], pets: twoPets);
      expect(find.text(l10n.petSlotRentCta('Rp7.000', 31)), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('D2 有月租：Slot sewa 小节 + 到期日 + Perpanjang 次按钮', (tester) async {
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, rentals: [rental(1, oct31)])],
          pets: [...twoPets, pet(3, 'Boba')]);
      // 稿 `.sec2`：小节标题大写（L2 还原度）。
      expect(find.text(l10n.petSlotRentalsSection.toUpperCase()), findsOneWidget);
      expect(find.text(l10n.petSlotRentalName(1)), findsOneWidget);
      expect(find.text(l10n.petSlotRentalEnds('31 Okt 2026')), findsOneWidget);
      expect(find.text(l10n.petSlotSourceRent), findsOneWidget);
      expect(find.byKey(const ValueKey('petSlotRenewButton')), findsOneWidget);
      expect(find.text(l10n.petSlotRenew), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('D3 有只读：锁格「Hanya-baca」、状态胶囊、Ubah pilihan ›、缺名额说明', (tester) async {
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, readOnlyCount: 1)],
          pets: [...twoPets, pet(3, 'Boba', readOnly: true)]);
      expect(find.text(l10n.petReadonlyBadge), findsNWidgets(2), reason: '名额格一处 + Hewanku 状态一处');
      expect(find.byKey(const ValueKey('petSlotPetLock-3')), findsOneWidget);
      expect(find.byKey(const ValueKey('petSlotChangeSelection')), findsOneWidget);
      expect(find.text(l10n.petReadonlyShortNote(1)), findsOneWidget);
      expect(find.text(l10n.petSlotUsedSuffix(2)), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('D4 已到 5：主按钮置灰「Sudah maksimal 5 slot」，续租照常；≤ 3 天提醒色文案', (tester) async {
      await pumpSlotPage(tester,
          overviews: [
            overview(levelSlots: 2, petCount: 4, rentals: [rental(1, oct31), rental(2, oct16, daysLeft: 3)]),
          ],
          pets: [...twoPets, pet(3, 'Boba'), pet(4, 'Luna')]);
      expect(find.text(l10n.petSlotMaxReached), findsOneWidget);
      expect(rentButton(tester).onPressed, isNull);
      expect(find.byKey(const ValueKey('petSlotRenewButton')), findsOneWidget);
      expect(tester.widget<OutlinedButton>(find.byKey(const ValueKey('petSlotRenewButton'))).onPressed,
          isNotNull);
      expect(find.text(l10n.petSlotRentalEndsSoon('16 Okt 2026', 3)), findsOneWidget);
      // 空名额格：「Kosong」+ 来源小胶囊「Sewa」（与已占用的月租格同款，L2 还原度）。
      expect(find.text(l10n.petSlotCellEmpty), findsOneWidget);
      expect(find.text(l10n.petSlotSourceRent), findsNWidgets(2));
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('D15 加载失败：cloud_off + Gagal memuat slot hewan + Coba lagi → 重取恢复', (tester) async {
      final o = overview(petCount: 2);
      await pumpSlotPage(tester,
          overviews: [Exception('a'), Exception('b'), o], pets: twoPets);
      expect(find.text(l10n.petSlotLoadError), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off_rounded), findsOneWidget);
      expect(find.text(l10n.commonRetry), findsOneWidget);
      await tester.tap(find.text(l10n.commonRetry));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotLoadError), findsNothing);
      expect(find.text(l10n.petSlotUsedSuffix(2)), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });
  });

  group('租用（D5 ~ D10b）', () {
    testWidgets('D5 PawCoin：步进器 + 总价行 → 扣币成交 → D9 SnackBar + 埋点 started / succeeded', (tester) async {
      final repo = FakePetSlotRepo();
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotRentSheetTitle), findsOneWidget);
      expect(find.text(l10n.petSlotItemName), findsOneWidget);
      expect(find.text(l10n.petSlotUnitPrice('Rp5.000', 30)), findsOneWidget);
      expect(find.text(l10n.petSlotRentSheetNote(30)), findsOneWidget);
      expect(find.text(l10n.petSlotQuantityLabel), findsOneWidget);
      expect(find.text(l10n.petSlotQuantityTotal(1, 'Rp5.000')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
      await tester.pump();
      expect(find.text(l10n.petSlotQuantityTotal(2, 'Rp5.000')), findsOneWidget);
      expect(find.text('Rp10.000'), findsWidgets, reason: '总价行 + QRIS 行按总价');
      expect(find.text(l10n.keepsakePayConfirm('10.000')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
      await tester.pumpAndSettle();

      expect(repo.rents, hasLength(1));
      expect(repo.rents.single.quantity, 2);
      expect(repo.rents.single.channel, 'PAWCOIN');
      expect(repo.rents.single.key, startsWith('petslot-'));
      expect(find.text(l10n.petSlotRentSuccess(2, '12 Nov 2026')), findsOneWidget);
      final started = events.where((e) => e.$1 == 'pet_slot_rent_started').map((e) => e.$2).toList();
      final succeeded = events.where((e) => e.$1 == 'pet_slot_rent_paid').map((e) => e.$2).toList();
      expect(started, [
        {'count': 2, 'is_renewal': false, 'channel': 'pawcoin'},
      ]);
      expect(succeeded, [
        {'count': 2, 'is_renewal': false, 'channel': 'pawcoin'},
      ]);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('步进器上限 = rentable、下限 1', (tester) async {
      // 名额 3（rentable 2）。
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, rentals: [rental(1, oct31)])],
          pets: [...twoPets, pet(3, 'Boba')]);
      await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('payQtyMinus')));
      await tester.pump();
      expect(tester.widget<Text>(find.byKey(const ValueKey('payQtyValue'))).data, '1');
      for (var i = 0; i < 4; i++) {
        await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
        await tester.pump();
      }
      expect(tester.widget<Text>(find.byKey(const ValueKey('payQtyValue'))).data, '2');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    Future<FakePetSlotRepo> qrisFlow(WidgetTester tester, List<PetSlotPurchaseStatus> statuses) async {
      final repo = FakePetSlotRepo()
        ..rentResult = const KeepsakePurchaseResult(
            unlocked: false, payload: '000201QR', purchaseToken: 'p9', paymentDisplayNo: 'PS-1')
        ..statuses = statuses;
      // 余额 0 → 默认选中 QRIS（D6 现状）。
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo, balance: 0);
      await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.triageUnlockTopupFirst), findsOneWidget, reason: 'D6：PawCoin 行置灰 + 去充值');
      await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
      // V1.4.1 QRIS 支付页：二维码抽屉里有常驻的「等待中」转圈，pumpAndSettle 会一路推进假时钟
      // 直到轮询把抽屉关掉、Toast 也已消失；这里只推进到抽屉弹出，再走一个轮询周期。
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      return repo;
    }

    testWidgets('D10b QRIS 过期 → 「Pembayaran kedaluwarsa. Coba lagi.」', (tester) async {
      final repo = await qrisFlow(tester, const [PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.expired)]);
      expect(repo.statusQueries, ['p9']);
      expect(find.text(l10n.paymentExpiredRetry), findsOneWidget);
      expect(events.where((e) => e.$1 == 'pet_slot_rent_paid'), isEmpty);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('D10 QRIS 失败 → 「Pembayaran gagal. Coba lagi.」', (tester) async {
      await qrisFlow(tester, const [PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.failed)]);
      expect(find.text(l10n.idCardHdPurchaseError), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('L2 第四轮：发起失败（网络超时）→ 顶部 Toast 落在 AppBar 标题下方，不出底部 SnackBar', (tester) async {
      final repo = FakePetSlotRepo()
        ..rentError = DioException(requestOptions: RequestOptions(path: '/x'), type: DioExceptionType.receiveTimeout);
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
      await tester.pumpAndSettle();

      expect(find.text(l10n.idCardHdPurchaseError), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing, reason: '底部 SnackBar 会盖住吸底「Perpanjang」');
      final appBarBottom = tester.getBottomLeft(find.byType(AppBar)).dy;
      expect(tester.getTopLeft(find.text(l10n.idCardHdPurchaseError)).dy, greaterThanOrEqualTo(appBarBottom),
          reason: 'Toast 不能压住页面标题「Kelola Hewan」');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('ORPHAN_PAID / OVERPAID → 「付款已收到、处理中 / 联系客服」，不说支付失败', (tester) async {
      await qrisFlow(tester, const [PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.overpaid)]);
      expect(find.text(l10n.petSlotPaymentProcessing), findsOneWidget);
      expect(find.text(l10n.idCardHdPurchaseError), findsNothing);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('QRIS 到账 → D9（日期取购买查询的 expiresAt）+ succeeded{channel: qris}', (tester) async {
      await qrisFlow(tester, [
        PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.paid, expiresAt: DateTime.utc(2026, 11, 20, 5)),
      ]);
      expect(find.text(l10n.petSlotRentSuccess(1, '20 Nov 2026')), findsOneWidget);
      expect(events.where((e) => e.$1 == 'pet_slot_rent_paid').single.$2,
          {'count': 1, 'is_renewal': false, 'channel': 'qris'});
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('409 pet-slot-rent-unavailable（rentable 0：待付单占位）→ 具体提示（非「支付失败」）+ 页面重取',
        (tester) async {
      final repo = FakePetSlotRepo()..rentError = problem('pet-slot-rent-unavailable', extra: {'rentable': 0});
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 2), overview(petCount: 2), overview(levelSlots: 4, petCount: 2)],
          pets: twoPets,
          repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotRentPendingPayment), findsOneWidget);
      expect(find.text(l10n.idCardHdPurchaseError), findsNothing);
      // 重取到「名额已 5」→ 主按钮置灰。
      expect(find.text(l10n.petSlotMaxReached), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('409 pet-slot-rent-unavailable（rentable 1）→「只能租 1 个」；业务拒绝后下次换新键', (tester) async {
      final repo = FakePetSlotRepo()..rentError = problem('pet-slot-rent-unavailable', extra: {'rentable': 1});
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo);
      Future<void> attempt() async {
        await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
        await tester.pump();
        await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
        await tester.pumpAndSettle();
      }

      await attempt();
      expect(find.text(l10n.petSlotRentOnlyN(1)), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      await attempt();
      expect(repo.rents, hasLength(2));
      expect(repo.rents[1].key, isNot(repo.rents[0].key), reason: '4xx 业务拒绝 = 意图结束');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    group('幂等键跨重试（code review #1）', () {
      DioException timeout() => DioException(
          requestOptions: RequestOptions(path: '/x'), type: DioExceptionType.receiveTimeout);

      Future<void> attempt(WidgetTester tester, {int plus = 0}) async {
        await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
        await tester.pumpAndSettle();
        for (var i = 0; i < plus; i++) {
          await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
          await tester.pump();
        }
        await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
        await tester.pumpAndSettle();
        await tester.pump(const Duration(seconds: 5));
        await tester.pumpAndSettle();
      }

      testWidgets('网络超时后重试同一意图 → 沿用同一个 Idempotency-Key（后端重放、不二次扣款）', (tester) async {
        final repo = FakePetSlotRepo()..rentError = timeout();
        await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo);
        await attempt(tester);
        repo.rentError = null;
        await attempt(tester);
        expect(repo.rents, hasLength(2));
        expect(repo.rents[1].key, repo.rents[0].key);
        // 成交后清键：再买一次 = 新键。
        await attempt(tester);
        expect(repo.rents[2].key, isNot(repo.rents[1].key));
        await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
      });

      testWidgets('换了数量 → 新键', (tester) async {
        final repo = FakePetSlotRepo()..rentError = timeout();
        await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo);
        await attempt(tester);
        await attempt(tester, plus: 1);
        expect(repo.rents.map((r) => r.quantity), [1, 2]);
        expect(repo.rents[1].key, isNot(repo.rents[0].key));
        await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
      });

      testWidgets('QRIS 网关出码失败（200 无载荷）→「Pembayaran gagal. Coba lagi.」，第 2 次点击即换新键出码', (tester) async {
        final repo = FakePetSlotRepo()
          ..rentResult = const KeepsakePurchaseResult(unlocked: false, purchaseToken: 'p-gw-fail')
          ..statuses = const [PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.pending)];
        await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo, balance: 0);
        await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
        await tester.pumpAndSettle();
        expect(find.text('Pembayaran gagal. Coba lagi.'), findsOneWidget);
        await tester.pump(const Duration(seconds: 5));
        await tester.pumpAndSettle();

        repo.rentResult = const KeepsakePurchaseResult(unlocked: false, payload: '000201QR', purchaseToken: 'p-2');
        await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
        // 二维码抽屉的「等待中」转圈常驻，pumpAndSettle 等不到静止 —— 只推进到抽屉弹出。
        for (var i = 0; i < 10; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(repo.rents, hasLength(2));
        expect(repo.rents[1].key, isNot(repo.rents[0].key), reason: '已失败的意图不再沿用旧键');
        expect(find.text(l10n.vetPayCancel), findsOneWidget, reason: '第 2 次点击即出码');
        await tester.tap(find.text(l10n.vetPayCancel));
        await tester.pumpAndSettle();
        await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
      });

      testWidgets('QRIS 关掉面板未付 → 再付沿用同键（复用原意图，不出第二张码）', (tester) async {
        final repo = FakePetSlotRepo()
          ..rentResult = const KeepsakePurchaseResult(unlocked: false, payload: '000201QR', purchaseToken: 'p9')
          ..statuses = const [PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.pending)];
        await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, repo: repo, balance: 0);
        for (var i = 0; i < 2; i++) {
          await tester.tap(find.byKey(const ValueKey('petSlotRentButton')));
          await tester.pumpAndSettle();
          await tester.tap(find.byKey(const ValueKey('petSlotRentConfirm')));
          for (var j = 0; j < 10; j++) {
            await tester.pump(const Duration(milliseconds: 100));
          }
          await tester.tap(find.text(l10n.vetPayCancel));
          await tester.pumpAndSettle();
        }
        expect(repo.rents, hasLength(2));
        expect(repo.rents[1].key, repo.rents[0].key);
        await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
      });
    });
  });

  group('续租（D11 / D12）', () {
    testWidgets('默认勾选全部（≤ renewableMax）、行显示「原到期 → 新到期」；一次付款；D12 SnackBar', (tester) async {
      final repo = FakePetSlotRepo()
        ..statuses = [
          PetSlotPurchaseStatus(status: PetSlotPurchaseStatus.paid, expiresAt: DateTime.utc(2026, 11, 30, 5)),
        ];
      await pumpSlotPage(tester,
          overviews: [
            overview(levelSlots: 1, petCount: 4, rentals: [rental(1, oct31), rental(2, oct16, daysLeft: 3)]),
          ],
          pets: [...twoPets, pet(3, 'Boba'), pet(4, 'Luna')],
          repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewButton')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotRenewSheetTitle), findsOneWidget);
      expect(find.text(l10n.petSlotRenewSheetNote(30)), findsOneWidget);
      expect(find.text(l10n.petSlotRenewRowRange('31 Okt', '30 Nov 2026')), findsOneWidget);
      expect(find.text(l10n.petSlotRenewRowRange('16 Okt', '15 Nov 2026')), findsOneWidget);
      expect(find.text(l10n.petSlotQuantityTotal(2, 'Rp5.000')), findsOneWidget);
      expect(find.text(l10n.keepsakePayConfirm('10.000')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewConfirm')));
      await tester.pumpAndSettle();
      expect(repo.renews.single.tokens, ['r1', 'r2']);
      expect(repo.renews.single.channel, 'PAWCOIN');
      expect(find.text(l10n.petSlotRenewSuccess('30 Nov 2026')), findsOneWidget);
      expect(events.where((e) => e.$1 == 'pet_slot_rent_started').single.$2,
          {'count': 2, 'is_renewal': true, 'channel': 'pawcoin'});
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('超过 renewableMax 的行不可勾（默认勾最早到期的）；一个都不勾 → 主按钮禁用', (tester) async {
      await pumpSlotPage(tester,
          overviews: [
            overview(levelSlots: 1, petCount: 4, renewableMax: 1, rentals: [rental(1, oct31), rental(2, oct16)]),
          ],
          pets: [...twoPets, pet(3, 'Boba'), pet(4, 'Luna')]);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewButton')));
      await tester.pumpAndSettle();
      Icon check(int i) => tester.widget<Icon>(find.byKey(ValueKey('petSlotRenewCheck-$i')));
      expect(check(2).icon, Icons.check_box, reason: '4.4 AC3：从最早到期（16 Okt）的开始勾');
      expect(check(1).icon, Icons.check_box_outline_blank);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewRow-1')));
      await tester.pump();
      expect(check(1).icon, Icons.check_box_outline_blank, reason: '已勾满上限');
      await tester.tap(find.byKey(const ValueKey('petSlotRenewRow-2')));
      await tester.pump();
      expect(check(2).icon, Icons.check_box_outline_blank);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('petSlotRenewConfirm'))).onPressed, isNull);
      // L2 第二轮：一个都不勾 → 金额按 Rp0 显示（不出「…」占位）。
      expect(find.text('…'), findsNothing);
      expect(tester.widget<Text>(find.byKey(const ValueKey('payTotalAmount'))).data, 'Rp0');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('4.4 AC3：升级拿到永久名额后默认少勾一个；正常宠物 ≤ 永久名额 → 默认不勾、主按钮禁用、抽屉不关', (tester) async {
      await pumpSlotPage(tester,
          overviews: [
            overview(levelSlots: 2, petCount: 3, rentals: [rental(1, oct31)]),
          ],
          pets: [...twoPets, pet(3, 'Boba')]);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewButton')));
      await tester.pumpAndSettle();
      expect(tester.widget<Icon>(find.byKey(const ValueKey('petSlotRenewCheck-1'))).icon,
          Icons.check_box_outline_blank);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('petSlotRenewConfirm'))).onPressed, isNull);
      expect(find.byKey(const ValueKey('petSlotRenewSheet')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewRow-1')));
      await tester.pump();
      expect(tester.widget<Icon>(find.byKey(const ValueKey('petSlotRenewCheck-1'))).icon, Icons.check_box,
          reason: '用户可手动再勾');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('409 pet-slot-renew-invalid → D10 文案', (tester) async {
      final repo = FakePetSlotRepo()..rentError = problem('pet-slot-renew-invalid');
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, rentals: [rental(1, oct31)])],
          pets: [...twoPets, pet(3, 'Boba')],
          repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotRenewButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petSlotRenewConfirm')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.idCardHdPurchaseError), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });
  });

  group('?action= 自动弹抽屉（A4 / B4 / E1 → P-5）', () {
    testWidgets('action=rent → D5', (tester) async {
      await pumpSlotPage(tester, overviews: [overview(petCount: 2)], pets: twoPets, action: 'rent');
      expect(find.text(l10n.petSlotRentSheetTitle), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const ValueKey('payQtyValue'))).data, '1');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('action=renew + 有生效中月租 → D11', (tester) async {
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, rentals: [rental(1, oct31)])],
          pets: [...twoPets, pet(3, 'Boba')],
          action: 'renew');
      expect(find.text(l10n.petSlotRenewSheetTitle), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });

    testWidgets('action=renew + 无生效中月租（已全过期）→ D5 新租、数量默认 = shortBy', (tester) async {
      await pumpSlotPage(tester,
          overviews: [overview(levelSlots: 0, petCount: 3, readOnlyCount: 2)],
          pets: [pet(1, 'A'), pet(2, 'B', readOnly: true), pet(3, 'C', readOnly: true)],
          action: 'renew');
      expect(find.text(l10n.petSlotRentSheetTitle), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const ValueKey('payQtyValue'))).data, '2');
      await tester.pump(const Duration(seconds: 3)); // 收掉提示 Toast 的计时器
    });
  });

  group('改选（D13 / D14）', () {
    testWidgets('默认勾 ACTIVE、勾满后其余不可勾、未选满 Simpan 禁用；保存 → PUT + Toast', (tester) async {
      final repo = FakePetSlotRepo();
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, readOnlyCount: 1)],
          pets: [...twoPets, pet(3, 'Boba', readOnly: true)],
          repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotChangeSelection')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotSelectTitle), findsOneWidget);
      expect(find.text(l10n.petSlotSelectNote(2)), findsOneWidget);
      expect(find.text(l10n.petSlotSelectCount(2, 2)), findsOneWidget);
      Icon check(int id) => tester.widget<Icon>(find.byKey(ValueKey('petSlotSelectCheck-$id')));
      expect(check(1).icon, Icons.check_box);
      expect(check(2).icon, Icons.check_box);
      expect(check(3).icon, Icons.check_box_outline_blank);
      // 勾满后点第 3 只无效。
      await tester.tap(find.byKey(const ValueKey('petSlotSelectRow-3')));
      await tester.pump();
      expect(check(3).icon, Icons.check_box_outline_blank);
      // 取消 Kopi → 未选满，Simpan 禁用。
      await tester.tap(find.byKey(const ValueKey('petSlotSelectRow-2')));
      await tester.pump();
      expect(find.text(l10n.petSlotSelectCount(1, 2)), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('petSlotSelectSave'))).onPressed, isNull);
      await tester.tap(find.byKey(const ValueKey('petSlotSelectRow-3')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petSlotSelectSave')));
      await tester.pumpAndSettle();
      expect(repo.selections, [
        [1, 3],
      ]);
      expect(find.text(l10n.petSlotSelectTitle), findsNothing);
      expect(find.text(l10n.petSlotSelectSaved), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('保存失败 → 抽屉保持、提示可重试', (tester) async {
      final repo = FakePetSlotRepo()..selectionError = Exception('x');
      await pumpSlotPage(tester,
          overviews: [overview(petCount: 3, readOnlyCount: 1)],
          pets: [...twoPets, pet(3, 'Boba', readOnly: true)],
          repo: repo);
      await tester.tap(find.byKey(const ValueKey('petSlotChangeSelection')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petSlotSelectSave')));
      await tester.pumpAndSettle();
      expect(find.text(l10n.petSlotSelectTitle), findsOneWidget);
      expect(find.text(l10n.petProfileSaveFailed), findsOneWidget);
      expect(find.text(l10n.commonRetry), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });
  });
}
