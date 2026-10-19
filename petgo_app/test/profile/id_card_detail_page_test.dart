import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/hd_paywall_sheet.dart';
import 'package:tailtopia/features/profile/presentation/id_card_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 ktp Story 1.8：详情页「Buka HD」走共享付费函数（entry = detail）、按钮文案、自动导出、分享埋点补卡种。
class _Repo implements IdCardRepository {
  _Repo(this.card);
  IdCard card;
  int purchases = 0;

  @override
  Future<IdCard> getCard(int cardId) async => card;
  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async {
    purchases++;
    card = IdCard(
        id: card.id, serialId: card.serialId, name: card.name, cardType: card.cardType,
        cardVariant: card.cardVariant, hdUnlocked: true);
    return const HdPurchaseResult(unlocked: true);
  }

  @override
  Future<int> hdPrice() async => 5000;
  @override
  Future<List<IdCard>> listCards() async => [card];
  @override
  Future<int> reportShareForReward(int cardId, {int? petId}) async => 0;
  @override
  Future<int> advertisableShareReward({int? petId}) async => 0;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

Future<_Repo> _pump(WidgetTester tester, IdCard card, {Locale locale = const Locale('en'), bool autoExport = false}) async {
  tester.view.physicalSize = const Size(420, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final repo = _Repo(card);
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      idCardRepositoryProvider.overrideWithValue(repo),
      pawCoinProvider.overrideWith(_Paw.new),
    ],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: IdCardDetailPage(cardId: card.id, autoExport: autoExport),
    ),
  ));
  if (autoExport) {
    await _settle(tester); // 自动导出期间按钮转圈持续动画，不能 pumpAndSettle
  } else {
    await tester.pumpAndSettle();
  }
  return repo;
}

/// 付费 / 导出期间按钮转圈持续动画、截图（toImage）是真异步：交替跑真实时钟与帧，代替 pumpAndSettle。
Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 8; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 60)));
    await tester.pump(const Duration(milliseconds: 100));
  }
}

String _buttonText(WidgetTester tester) => tester
    .widget<Text>(find.descendant(of: find.byKey(const ValueKey('idCardHdButton')), matching: find.byType(Text)))
    .data!;

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  final locked = IdCard(id: 3, serialId: 8, name: 'Oyen', cardType: 'SIM');

  testWidgets('未解锁按钮文案：en「Unlock HD」/ id「Buka HD」', (tester) async {
    await _pump(tester, locked);
    expect(_buttonText(tester), 'Unlock HD');
    await _pump(tester, locked, locale: const Locale('id'));
    expect(_buttonText(tester), 'Buka HD');
  });

  testWidgets('点「Buka HD」→ 共享付费抽屉；关闭 → 留在详情、按钮不变；埋点 entry = detail', (tester) async {
    final repo = await _pump(tester, locked);
    await tester.tap(find.byKey(const ValueKey('idCardHdButton')));
    await _settle(tester);
    expect(find.byType(HdPaywallSheet), findsOneWidget);
    await tester.tapAt(const Offset(10, 10));
    await _settle(tester);
    expect(find.byType(IdCardDetailPage), findsOneWidget);
    expect(_buttonText(tester), 'Unlock HD');
    expect(repo.purchases, 0);
    expect(events.map((e) => e.$1),
        ['id_card_buka_hd_tapped', 'ktp_unlock_paywall_shown', 'id_card_paywall_dismissed']);
    expect(events.first.$2, {'card_type': 'SIM', 'entry': 'detail'});
    expect(events.last.$2, {'card_type': 'SIM', 'entry': 'detail'});
  });

  testWidgets('付费成功 → 留在详情、按钮变「Download HD」、出现 ✓ HD 行 + toast + 自动弹导出选单', (tester) async {
    await _pump(tester, locked);
    await tester.tap(find.byKey(const ValueKey('idCardHdButton')));
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await _settle(tester);
    expect(find.byType(IdCardDetailPage), findsOneWidget);
    expect(find.text('HD download unlocked!'), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardSaveToGallery')), findsOneWidget, reason: '成功后自动弹保存 / 分享选单');
    final started = events.firstWhere((e) => e.$1 == 'ktp_unlock_started').$2!;
    expect(started['entry'], 'detail');
    expect(started['card_type'], 'SIM');
  });

  testWidgets('D-16：autoExport + 已解锁 → 进页自动弹一次保存 / 分享选单', (tester) async {
    await _pump(tester, IdCard(id: 3, serialId: 8, name: 'Oyen', cardType: 'KTP', hdUnlocked: true), autoExport: true);
    await _settle(tester);
    await _settle(tester);
    expect(find.byKey(const ValueKey('idCardSaveToGallery')), findsOneWidget);
  });

  testWidgets('不带 autoExport 的已解锁卡不自动弹', (tester) async {
    await _pump(tester, IdCard(id: 3, serialId: 8, name: 'Oyen', cardType: 'KTP', hdUnlocked: true));
    await _settle(tester);
    expect(find.byKey(const ValueKey('idCardSaveToGallery')), findsNothing);
  });

  test('locationFor：autoExport 时带 ?export=1', () {
    expect(IdCardDetailPage.locationFor(7), '/profile/id-cards/7');
    expect(IdCardDetailPage.locationFor(7, autoExport: true), '/profile/id-cards/7?export=1');
  });

  testWidgets('分享埋点：8 款卡的 card_style / card_type / card_variant（D-7）', (tester) async {
    const expectedStyle = {
      IdCardStyle.ktp: 0,
      IdCardStyle.passport: 1,
      IdCardStyle.studentBintang: 2,
      IdCardStyle.studentMalam: 2,
      IdCardStyle.studentCeria: 2,
      IdCardStyle.studentKlasik: 2,
      IdCardStyle.sim: 3,
      IdCardStyle.kitas: 4,
    };
    for (final style in IdCardStyle.values) {
      events.clear();
      await _pump(tester,
          IdCard(id: 5, serialId: 8, name: 'Oyen', cardType: style.cardType, cardVariant: style.cardVariant));
      await tester.tap(find.byKey(const ValueKey('idCardFreeShare')));
      await _settle(tester);
      final props = events.firstWhere((e) => e.$1 == 'id_card_share_tapped').$2!;
      expect(props['card_style'], expectedStyle[style], reason: '$style');
      expect(props['card_type'], style.cardType, reason: '$style');
      if (style.cardVariant == null) {
        expect(props.containsKey('card_variant'), isFalse, reason: '$style');
      } else {
        expect(props['card_variant'], style.cardVariant, reason: '$style');
      }
    }
  });
}
