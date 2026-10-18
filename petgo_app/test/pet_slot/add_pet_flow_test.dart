import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/pet_slot/data/pet_slot_repository.dart';
import 'package:tailtopia/features/pet_slot/presentation/add_pet_flow.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 1.5 · AC4.1 ~ AC4.3（UI 稿 A4 / A4b）：「+」入口的名额判定与两种抽屉。
PetSlotOverview _o({required int petCount, int total = 2, int cap = 5, int unitPrice = 5000}) =>
    PetSlotOverview(
      total: total,
      cap: cap,
      defaultSlots: 1,
      levelSlots: total - 1,
      rentalSlots: 0,
      petCount: petCount,
      rentable: 3,
      canRent: true,
      nextLevelSlot: 5,
      unitPrice: unitPrice,
      days: 30,
    );

void main() {
  late List<(String, Map<String, Object>?)> events;
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  /// 挂一个「+」格；[overview] 为 null 时让摘要接口失败。返回建档页收到的 extra（未进则 null）。
  Future<List<Object?>> pump(WidgetTester tester, PetSlotOverview? overview) async {
    final createdExtras = <Object?>[];
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => const Scaffold(
            body: Center(child: AddPetTile(key: ValueKey('add'), size: 56, width: 84))),
      ),
      GoRoute(
        path: '/profile/create',
        builder: (_, s) {
          createdExtras.add(s.extra);
          // 「+」流程带 `?add=1`：建档页据此不走存量「已有档案直达」。
          expect(s.uri.queryParameters['add'], '1');
          return const Scaffold(body: Text('create-page'));
        },
      ),
      GoRoute(path: kPetSlotsRoute, builder: (_, _) => const Scaffold(body: Text('slots-page'))),
    ]);
    addTearDown(router.dispose);
    final c = ProviderContainer(overrides: [
      petSlotOverviewProvider.overrideWith((ref) async {
        if (overview == null) throw StateError('offline');
        return overview;
      }),
    ]);
    addTearDown(c.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('add')));
    await tester.pumpAndSettle();
    return createdExtras;
  }

  testWidgets('未满 → 进建档页，并把建档前的名额摘要带过去', (tester) async {
    final o = _o(petCount: 1);
    final extras = await pump(tester, o);
    expect(find.text('create-page'), findsOneWidget);
    expect(extras.single, same(o));
    expect(events.where((e) => e.$1 == 'pet_slot_full_shown'), isEmpty);
  });

  testWidgets('摘要接口失败 → 直接进建档页（后端 409 兜底）', (tester) async {
    await pump(tester, null);
    expect(find.text('create-page'), findsOneWidget);
  });

  testWidgets('A4b：petCount ≥ cap → 已达上限抽屉，唯一按钮 OK', (tester) async {
    await pump(tester, _o(petCount: 5, total: 5));
    expect(find.text(l10n.petSlotLimitTitle), findsOneWidget);
    expect(find.text(l10n.petSlotLimitBody), findsOneWidget);
    expect(find.byKey(const ValueKey('petSlotLimitOk')), findsOneWidget);
    expect(find.text('OK'), findsOneWidget);
    expect(find.byKey(const ValueKey('confirmSheetCancel')), findsNothing);
    expect(find.text(l10n.petSlotFullTitle), findsNothing);

    await tester.tap(find.byKey(const ValueKey('petSlotLimitOk')));
    await tester.pumpAndSettle();
    expect(find.text('create-page'), findsNothing);
  });

  testWidgets('A4：petCount ≥ total → 名额已满抽屉；正文数字 + 价格「Rp5.000」（取后端单价）', (tester) async {
    await pump(tester, _o(petCount: 2, total: 2));
    expect(find.text(l10n.petSlotFullTitle), findsOneWidget);
    expect(find.text(l10n.petSlotFullBody(2, 2, 'Rp5.000')), findsOneWidget);
    expect(find.textContaining('Rp5.000'), findsOneWidget);
    expect(find.text(l10n.petSlotFullRent), findsOneWidget);
    expect(find.text('create-page'), findsNothing);
  });

  testWidgets('A4 价格不写死：单价 12000 → 「Rp12.000」', (tester) async {
    await pump(tester, _o(petCount: 2, total: 2, unitPrice: 12000));
    expect(find.textContaining('Rp12.000'), findsOneWidget);
  });

  testWidgets('A4 点「Rent a slot」→ 名额管理页 + pet_slot_full_shown {action: rent}', (tester) async {
    await pump(tester, _o(petCount: 2, total: 2));
    await tester.tap(find.byKey(const ValueKey('petSlotFullRent')));
    await tester.pumpAndSettle();

    expect(find.text('slots-page'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'pet_slot_full_shown').map((e) => e.$2), [
      {'source': 'add_pet', 'action': 'rent'},
    ]);
  });

  testWidgets('A4 点关闭 → 留在原页 + pet_slot_full_shown {action: close}（每次弹出报一次）', (tester) async {
    await pump(tester, _o(petCount: 2, total: 2));
    await tester.tap(find.byKey(const ValueKey('petSlotFullClose')));
    await tester.pumpAndSettle();

    expect(find.text('slots-page'), findsNothing);
    expect(events.where((e) => e.$1 == 'pet_slot_full_shown').map((e) => e.$2), [
      {'source': 'add_pet', 'action': 'close'},
    ]);

    // 再点一次「+」→ 再弹一次、再报一次。
    await tester.tap(find.byKey(const ValueKey('add')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('petSlotFullClose')));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'pet_slot_full_shown'), hasLength(2));
  });

  test('PetSlotOverview.fromJson：字段同后端 PetSlotOverview', () {
    final o = PetSlotOverview.fromJson({
      'total': 3,
      'cap': 5,
      'defaultSlots': 1,
      'levelSlots': 2,
      'rentalSlots': 0,
      'petCount': 3,
      'rentable': 2,
      'canRent': true,
      'nextLevelSlot': null,
      'unitPrice': 5000,
      'days': 30,
    });
    expect(o.total, 3);
    expect(o.isFull, isTrue);
    expect(o.atCap, isFalse);
    expect(o.nextLevelSlot, isNull);
    expect(o.unitPrice, 5000);
  });

  test('PetSlotFullInfo.fromProblemBody：只取数字扩展成员', () {
    final info = PetSlotFullInfo.fromProblemBody({
      'type': 'https://petgo/errors/pet-slot-full',
      'detail': '宠物名额已满',
      'canRent': true,
      'nextLevelSlot': 5,
      'petCount': 2,
      'slotTotal': 2,
      'unitPrice': 5000,
    });
    expect(info.petCount, 2);
    expect(info.slotTotal, 2);
    expect(info.unitPrice, 5000);
    expect(info.canRent, isTrue);
    expect(info.nextLevelSlot, 5);
  });
}
