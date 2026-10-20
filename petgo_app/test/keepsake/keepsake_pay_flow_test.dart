import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/keepsake/presentation/keepsake_pay_flow.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pay_channel_picker.dart';
import 'package:tailtopia/shared/widgets/qr_payment_sheet.dart';

import '../pet_slot/pet_slot_fakes.dart';

/// `runKeepsakePurchase` 的 V1.4.0 multi-pet Story 2.4 增量：
/// - AC2.5：可选 `onQrAborted` 原样透传给 `showQrPaymentSheet(onAborted:)`；不传时行为不变；
/// - AC5.6：409 `pet-read-only` → 弹 E1 拦截抽屉，不报「支付失败」。
void main() {
  late AppLocalizations l10n;
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));

  Future<List<KeepsakeFlowOutcome>> pump(
    WidgetTester tester, {
    required Future<KeepsakePurchaseResult> Function(HdPayChannel) start,
    required Future<bool> Function() pollPaid,
    void Function(QrPaymentAborted)? onQrAborted,
    bool passOnQrAborted = true,
    List<PetListItem> pets = const [],
  }) async {
    final outcomes = <KeepsakeFlowOutcome>[];
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        pawCoinProvider.overrideWith(() => FakePaw(0)),
        petListProvider.overrideWith((ref) async => pets),
        effectivePetIdProvider.overrideWithValue(AsyncData(pets.isEmpty ? null : pets.first.id)),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Consumer(
          builder: (context, ref, _) {
            ref.watch(petListProvider);
            return Scaffold(
              body: Center(
                child: TextButton(
                  key: const ValueKey('buy'),
                  onPressed: () async {
                    outcomes.add(await runKeepsakePurchase(
                      context: context,
                      ref: ref,
                      sheet: (balance) => PayChannelPicker(
                        header: const SizedBox.shrink(),
                        title: 'T',
                        body: 'B',
                        price: const AsyncData(5000),
                        onRetryPrice: () {},
                        balance: balance,
                        confirmLabel: (p) => 'pay',
                      ),
                      start: start,
                      pollPaid: pollPaid,
                      purchasePurpose: 'PET_SLOT_RENT',
                      cashPriceIdr: () => 5000,
                      onQrAborted: passOnQrAborted ? onQrAborted : null,
                    ));
                  },
                  child: const Text('buy'),
                ),
              ),
            );
          },
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('buy')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    return outcomes;
  }

  const qr = KeepsakePurchaseResult(unlocked: false, payload: '000201QR', purchaseToken: 'p');

  testWidgets('onQrAborted 被透传：pollPaid 抛 QrPaymentAborted → 回调带 category，结局 notCompleted', (tester) async {
    final aborted = <String?>[];
    final outcomes = await pump(tester,
        start: (_) async => qr,
        pollPaid: () async => throw const QrPaymentAborted('EXPIRED'),
        onQrAborted: (a) => aborted.add(a.category));
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(aborted, ['EXPIRED']);
    expect(outcomes, [KeepsakeFlowOutcome.notCompleted]);
    expect(find.text(l10n.idCardHdPurchaseError), findsNothing, reason: '中止本身不报失败（由调用方决定提示）');
  });

  testWidgets('不传 onQrAborted：同样静默关闭面板、notCompleted（行为不变）', (tester) async {
    final outcomes = await pump(tester,
        start: (_) async => qr,
        pollPaid: () async => throw const QrPaymentAborted('EXPIRED'),
        passOnQrAborted: false);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(outcomes, [KeepsakeFlowOutcome.notCompleted]);
    expect(find.text(l10n.idCardHdPurchaseError), findsNothing);
  });

  testWidgets('409 pet-read-only → 弹 E1（宠物名取 petId 对应项），不报支付失败', (tester) async {
    final outcomes = await pump(tester,
        start: (_) async => throw problem('pet-read-only', extra: {'petId': 2}),
        pollPaid: () async => false,
        pets: [pet(1, 'Orenji'), pet(2, 'Boba', readOnly: true)]);
    expect(outcomes, [KeepsakeFlowOutcome.notCompleted]);
    expect(find.text(l10n.petReadonlyBlockTitle('Boba')), findsOneWidget);
    expect(find.text(l10n.petReadonlyBlockBody('Boba')), findsOneWidget);
    expect(find.text(l10n.idCardHdPurchaseError), findsNothing);
  });

  testWidgets('其它错误照旧：pawcoin-insufficient → 余额不足文案', (tester) async {
    await pump(tester,
        start: (_) async => throw problem('pawcoin-insufficient'), pollPaid: () async => false);
    expect(find.text(l10n.idCardHdInsufficientBalance), findsOneWidget);
  });
}
