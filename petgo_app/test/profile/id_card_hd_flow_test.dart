import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_hd_flow.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 ktp Story 1.6（AD-10 / AD-12）：共享付费函数的结局与埋点序列。
class _Repo implements IdCardRepository {
  _Repo({this.error});
  final Object? error;

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async {
    if (error != null) throw error!;
    return const HdPurchaseResult(unlocked: true);
  }

  @override
  Future<int> hdPrice() async => 5000;
  @override
  Future<List<IdCard>> listCards() async => const [];
  @override
  Future<IdCard> getCard(int cardId) async => IdCard(id: cardId, hdUnlocked: true);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  IdCardHdFlowResult? result;

  Future<void> pump(WidgetTester tester, IdCard card, IdCardPaywallEntry entry, {Object? error}) async {
    result = null;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        idCardRepositoryProvider.overrideWithValue(_Repo(error: error)),
        pawCoinProvider.overrideWith(_Paw.new),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Scaffold(
          body: Consumer(
            builder: (context, ref, _) => TextButton(
              onPressed: () async => result = await openIdCardHdPaywall(context, ref, card, entry: entry),
              child: const Text('go'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('go'));
    await tester.pumpAndSettle();
  }

  test('IdCardPaywallEntry.wire 三值', () {
    expect(IdCardPaywallEntry.values.map((e) => e.wire), ['create', 'detail', 'list_badge']);
  });

  final student = IdCard(id: 4, serialId: 9, name: 'Luna', cardType: 'STUDENT', cardVariant: 'MALAM');
  final sim = IdCard(id: 3, serialId: 8, name: 'Oyen', cardType: 'SIM');

  testWidgets('列表入口关闭抽屉：buka_hd_tapped → paywall_shown → paywall_dismissed；结局 dismissed', (tester) async {
    await pump(tester, student, IdCardPaywallEntry.listBadge);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(result, IdCardHdFlowResult.dismissed);
    expect(events.map((e) => e.$1), ['id_card_buka_hd_tapped', 'ktp_unlock_paywall_shown', 'id_card_paywall_dismissed']);
    expect(events[0].$2, {'card_type': 'STUDENT', 'entry': 'list_badge'});
    expect(events[1].$2, containsPair('entry', 'list_badge'));
    expect(events[1].$2, containsPair('card_type', 'STUDENT'));
    expect(events[1].$2, containsPair('card_variant', 'MALAM'));
    expect(events[2].$2, {'card_type': 'STUDENT', 'entry': 'list_badge'});
  });

  testWidgets('选 PawCoin：ktp_unlock_started 带 method / 卡种；非学生卡不带 card_variant；结局 unlocked', (tester) async {
    await pump(tester, sim, IdCardPaywallEntry.listBadge);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(result, IdCardHdFlowResult.unlocked);
    final started = events.firstWhere((e) => e.$1 == 'ktp_unlock_started').$2!;
    expect(started['entry'], 'list_badge');
    expect(started['method'], 'PAWCOIN');
    expect(started['card_type'], 'SIM');
    expect(started.containsKey('card_variant'), isFalse);
    expect(events.map((e) => e.$1), isNot(contains('ktp_unlock_succeeded')), reason: '成功只由服务端报');
    expect(find.text('HD download unlocked!'), findsOneWidget);
  });

  testWidgets('entry = create 不报 id_card_buka_hd_tapped', (tester) async {
    await pump(tester, sim, IdCardPaywallEntry.create);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(events.map((e) => e.$1), ['ktp_unlock_paywall_shown', 'id_card_paywall_dismissed']);
    expect(events.last.$2, {'card_type': 'SIM', 'entry': 'create'});
  });

  testWidgets('余额不足 409：ktp_unlock_failed 带卡种 + 余额不足 toast；结局 failed', (tester) async {
    final err = DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 409),
    );
    await pump(tester, student, IdCardPaywallEntry.detail, error: err);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(result, IdCardHdFlowResult.failed);
    final failed = events.firstWhere((e) => e.$1 == 'ktp_unlock_failed').$2!;
    expect(failed['failure_reason'], 'INSUFFICIENT_BALANCE');
    expect(failed['entry'], 'detail');
    expect(failed['card_variant'], 'MALAM');
    expect(events.first.$2, {'card_type': 'STUDENT', 'entry': 'detail'});
  });
}
