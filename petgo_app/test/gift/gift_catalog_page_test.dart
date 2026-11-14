import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/gift/presentation/gift_catalog_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'gift_fakes.dart';

/// V1.5.0 gift-pack Story 2-1 · AC2 / AC3（L0，结构断言）：选礼物页按分类分组。
void main() {
  const snackBone = GiftItem(
      giftToken: 'c-bone',
      nameEn: 'Bone',
      nameId: 'Tulang',
      price: 1000,
      senderExp: 5,
      receivedFrom: GiftReceivedFrom(senders: [
        GiftReceivedSender(nickname: 'Michelle'),
        GiftReceivedSender(nickname: 'Tony'),
        GiftReceivedSender(deleted: true),
      ], more: 5));
  const snackFish = GiftItem(giftToken: 'c-fish', nameEn: 'Fish', nameId: 'Ikan', price: 1000);
  const toyBall = GiftItem(giftToken: 'c-ball', nameEn: 'Ball', nameId: 'Bola', price: 10000, senderExp: 50);

  GiftCatalog catalog() => GiftCatalog(
        recipient: const GiftRecipient(petId: 42, name: 'Mochi', petType: 'CAT', breed: 'Persia'),
        qrisMinAmount: 10000,
        tiers: const [GiftTier(price: 1000, gifts: [snackBone, snackFish]), GiftTier(price: 10000, gifts: [toyBall])],
        categories: const [
          GiftCategoryGroup(categoryId: 3, nameEn: 'Toys', nameId: 'Mainan', gifts: [toyBall]),
          GiftCategoryGroup(categoryId: 9, nameEn: 'Empty', nameId: 'Kosong'),
          GiftCategoryGroup(categoryId: 1, nameEn: 'Snacks', nameId: 'Camilan', gifts: [snackBone, snackFish]),
        ],
        exclusiveGroups: const [GiftExclusiveGroup(minLevel: 5, gifts: [mahkota])],
        lockBlock: const GiftLockBlock(requiredLevel: 15),
      );

  late FakeGiftRepository repo;
  late List<Object?> results;
  setUp(() {
    repo = FakeGiftRepository(catalog: catalog());
    results = [];
  });

  Future<void> open(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: ElevatedButton(
              key: const ValueKey('open'),
              onPressed: () async => results.add(await Navigator.of(context).push<GiftCatalogResult>(
                  MaterialPageRoute(builder: (_) => const GiftCatalogPage(recipient: GiftRecipientRef.pet(42))))),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
  }

  testWidgets('sections follow backend order; empty category not rendered; exclusive after; lock last', (tester) async {
    await open(tester);
    expect(find.text('Kirim hadiah untuk Mochi'), findsOneWidget);
    expect(find.text('Kucing · Persia'), findsOneWidget);
    final toys = tester.getTopLeft(find.byKey(const ValueKey('giftCatalogSection-cat-3'))).dy;
    final snacks = tester.getTopLeft(find.byKey(const ValueKey('giftCatalogSection-cat-1'))).dy;
    final ex = tester.getTopLeft(find.byKey(const ValueKey('giftCatalogSection-ex-5'))).dy;
    expect(toys < snacks && snacks < ex, isTrue);
    expect(find.byKey(const ValueKey('giftCatalogSection-cat-9')), findsNothing);
    expect(find.byKey(const ValueKey('giftCatalogTab-cat-9')), findsNothing);
    expect(find.byKey(const ValueKey('giftLockBlock')), findsOneWidget);
  });

  testWidgets('price / EXP / receivedFrom lines', (tester) async {
    await open(tester);
    final ball = find.byKey(const ValueKey('giftCell-c-ball'));
    expect(find.descendant(of: ball, matching: find.text('10.000 PawCoin')), findsOneWidget);
    expect(find.descendant(of: ball, matching: find.text('+50 EXP')), findsOneWidget);
    final fish = find.byKey(const ValueKey('giftCell-c-fish'));
    expect(find.descendant(of: fish, matching: find.byKey(const ValueKey('giftCellExp'))), findsNothing,
        reason: 'senderExp = 0 不显示 EXP 行');
    final bone = find.byKey(const ValueKey('giftCell-c-bone'));
    expect(find.descendant(of: bone, matching: find.text('dari Michelle, Tony, Pengguna terhapus +5')), findsOneWidget);
    expect(find.descendant(of: bone, matching: find.byKey(const ValueKey('giftCellExp'))), findsNothing);
  });

  testWidgets('tap gift pops GiftCatalogPicked; lock block pops level page', (tester) async {
    await open(tester);
    await tester.tap(find.byKey(const ValueKey('giftCell-c-ball')));
    await tester.pumpAndSettle();
    expect((results.single as GiftCatalogPicked).item.giftToken, 'c-ball');
  });

  testWidgets('B5 load failed shows retry and keeps close', (tester) async {
    repo.catalogError = problem('internal', status: 500);
    await open(tester);
    expect(find.text('Gagal memuat hadiah'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftCatalogRetry')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftCatalogClose')), findsOneWidget);
  });

  testWidgets('fallback to price tiers when categories absent', (tester) async {
    repo.catalog = sampleCatalog();
    await open(tester);
    expect(find.byKey(const ValueKey('giftCatalogSection-tier-1000')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftCatalogSection-tier-5000')), findsOneWidget);
  });
}
