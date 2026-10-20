import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_item.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_switcher.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 1.5 · AC3.1 / AC3.5（UI 稿 B1 / B5）：Diary Tab 宠物切换器。
/// 结构断言（不写 golden）：头像个数、「+」、当前紫环、只读锁标、点击切换与埋点。
void main() {
  const two = [
    PetListItem(id: 1, name: 'Orenji', isDefault: true),
    PetListItem(id: 2, name: 'Kopi'),
  ];

  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<ProviderContainer> pump(WidgetTester tester, List<PetListItem> pets, int? currentId) async {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: PetSwitcher(pets: pets, currentId: currentId)),
      ),
    ));
    await tester.pumpAndSettle();
    return c;
  }

  PetAvatarItem item(WidgetTester tester, int id) =>
      tester.widget<PetAvatarItem>(find.byKey(ValueKey('petSwitcherItem-$id')));

  /// 外环颜色（PetAvatarItem 最外层圆形 Container）。
  Color ringOf(WidgetTester tester, int id) {
    final ring = find
        .descendant(of: find.byKey(ValueKey('petSwitcherItem-$id')), matching: find.byType(Container))
        .first;
    return (tester.widget<Container>(ring).decoration! as BoxDecoration).color!;
  }

  testWidgets('B1：两只 → 两个头像（40）+「+」，当前宠物主色环', (tester) async {
    await pump(tester, two, 1);

    expect(find.byKey(const ValueKey('petSwitcherItem-1')), findsOneWidget);
    expect(find.byKey(const ValueKey('petSwitcherItem-2')), findsOneWidget);
    expect(find.byKey(const ValueKey('petSwitcherAdd')), findsOneWidget);
    expect(item(tester, 1).size, 40);
    expect(item(tester, 1).selected, isTrue);
    expect(item(tester, 2).selected, isFalse);
    expect(ringOf(tester, 1), AppColors.mint);
    expect(ringOf(tester, 2), isNot(AppColors.mint));
    expect(find.text('Orenji'), findsOneWidget);
    expect(find.text('Kopi'), findsOneWidget);
  });

  testWidgets('点另一只 → select + pet_switched {from_pet_id, to_pet_id, source: diary_tab}', (tester) async {
    final c = await pump(tester, two, 1);

    await tester.tap(find.byKey(const ValueKey('petSwitcherItem-2')));
    await tester.pump();

    expect(c.read(currentPetIdProvider), 2);
    expect(events.where((e) => e.$1 == 'pet_switched').map((e) => e.$2), [
      {'from_pet_id': 1, 'to_pet_id': 2, 'source': 'diary_tab'},
    ]);
  });

  testWidgets('点当前那只 → 不切换、不上报', (tester) async {
    final c = await pump(tester, two, 1);

    await tester.tap(find.byKey(const ValueKey('petSwitcherItem-1')));
    await tester.pump();

    expect(c.read(currentPetIdProvider), isNull);
    expect(events.where((e) => e.$1 == 'pet_switched'), isEmpty);
  });

  testWidgets('只读宠物 → 锁标 + 灰化', (tester) async {
    await pump(tester, const [
      PetListItem(id: 1, name: 'Orenji', isDefault: true),
      PetListItem(id: 2, name: 'Kopi', status: 'READ_ONLY'),
    ], 1);

    expect(find.byKey(const ValueKey('petSwitcherLock-2')), findsOneWidget);
    expect(find.byKey(const ValueKey('petSwitcherLock-1')), findsNothing);
    expect(item(tester, 2).readOnly, isTrue);
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('petSwitcherItem-2')), matching: find.byType(ColorFiltered)),
        findsOneWidget);
  });

  testWidgets('B5：只有 1 只 → 1 个头像 +「+」', (tester) async {
    await pump(tester, const [PetListItem(id: 1, name: 'Orenji', isDefault: true)], 1);

    expect(find.byType(PetAvatarItem), findsOneWidget);
    expect(find.byKey(const ValueKey('petSwitcherAdd')), findsOneWidget);
  });

  testWidgets('超出宽度横滑：外层是横向 SingleChildScrollView', (tester) async {
    await pump(tester, [for (var i = 1; i <= 5; i++) PetListItem(id: i, name: 'P$i')], 1);

    final scroll = tester.widget<SingleChildScrollView>(find.byKey(const ValueKey('petSwitcher')));
    expect(scroll.scrollDirection, Axis.horizontal);
    expect(find.byType(PetAvatarItem), findsNWidgets(5));
  });
}
