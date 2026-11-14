import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/domain/pet_species.dart';
import 'package:tailtopia/features/profile/presentation/widgets/species_picker_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 pet-species Story 3.2 · AC7.1 —— 类型 / 品种抽屉的结构与返回值（结构断言，不写 golden）。
void main() {
  final events = <(String, Map<String, Object>?)>[];

  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> host(WidgetTester tester, Future<Object?> Function(BuildContext) open,
      void Function(Object?) onResult) async {
    tester.view.physicalSize = const Size(1080, 7200);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Builder(
        builder: (ctx) => Scaffold(
          body: Center(
            child: TextButton(
              key: const ValueKey('open'),
              onPressed: () async => onResult(await open(ctx)),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
  }

  Finder cells() => find.byWidgetPredicate(
      (w) => w.key is ValueKey<String> && (w.key as ValueKey<String>).value.startsWith('speciesCell_'));

  Finder breedRows() => find.byWidgetPredicate(
      (w) => w.key is ValueKey<String> && (w.key as ValueKey<String>).value.startsWith('breedOption_'));

  testWidgets('类型宫格 15 格、顺序固定、Lainnya 最后；点一格返回并埋点', (tester) async {
    Object? result;
    await host(tester, (c) => showPetSpeciesPicker(c, scene: 'create'), (r) => result = r);
    expect(find.byKey(const ValueKey('speciesPickerGrid')), findsOneWidget);
    expect(cells(), findsNWidgets(15));
    final keys = tester.widgetList(cells()).map((w) => (w.key as ValueKey<String>).value).toList();
    expect(keys.first, 'speciesCell_CAT');
    expect(keys.last, 'speciesCell_OTHER');
    expect(find.text('Kelinci'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('speciesCell_RABBIT')));
    await tester.pumpAndSettle();
    expect((result as PetSpeciesPick).code, PetSpeciesCode.rabbit);
    expect(events.single.$1, 'pet_type_selected');
    expect(events.single.$2, {'species': 'RABBIT', 'scene': 'create', 'via_search': false});
  });

  testWidgets('当前是「其他」组物种 → Lainnya 格打勾', (tester) async {
    await host(tester, (c) => showPetSpeciesPicker(c, current: PetSpeciesCode.civet, scene: 'edit'), (_) {});
    expect(find.descendant(of: find.byKey(const ValueKey('speciesCell_OTHER')), matching: find.byIcon(Icons.check_circle)),
        findsOneWidget);
  });

  testWidgets('输入 ka → 列表 Ikan / Kadal / Katak', (tester) async {
    await host(tester, (c) => showPetSpeciesPicker(c, scene: 'create'), (_) {});
    await tester.enterText(find.byKey(const ValueKey('speciesPickerSearch')), 'ka');
    await tester.pump();
    expect(find.byKey(const ValueKey('speciesPickerList')), findsOneWidget);
    expect(find.byKey(const ValueKey('speciesOption_FISH')), findsOneWidget);
    expect(find.byKey(const ValueKey('speciesOption_LIZARD')), findsOneWidget);
    expect(find.byKey(const ValueKey('speciesOption_FROG')), findsOneWidget);
    expect(cells(), findsNothing);
  });

  testWidgets('输入 musang → 类型空态，点 Lainnya 返回 OTHER', (tester) async {
    Object? result;
    await host(tester, (c) => showPetSpeciesPicker(c, scene: 'create'), (r) => result = r);
    await tester.enterText(find.byKey(const ValueKey('speciesPickerSearch')), 'musang');
    await tester.pump();
    expect(find.byKey(const ValueKey('speciesPickerEmpty')), findsOneWidget);
    expect(find.text('Jenis "musang" belum ada'), findsOneWidget);
    expect(find.text('Pilih Lainnya, lalu cari di daftar ras.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('speciesOption_OTHER')));
    await tester.pumpAndSettle();
    expect((result as PetSpeciesPick).code, PetSpeciesCode.other);
    expect((result as PetSpeciesPick).viaSearch, isTrue);
  });

  testWidgets('品种：兔 19 项 + 底部「Tulis sendiri」', (tester) async {
    await host(tester, (c) => showPetBreedPicker(c, type: PetSpeciesCode.rabbit, scene: 'create'), (_) {});
    expect(breedRows(), findsNWidgets(19));
    expect(find.text('Tulis sendiri'), findsOneWidget);
  });

  testWidgets('品种：狗搜 poo → 3 项（命中段加粗）', (tester) async {
    await host(tester, (c) => showPetBreedPicker(c, type: PetSpeciesCode.dog, scene: 'create'), (_) {});
    await tester.enterText(find.byKey(const ValueKey('speciesPickerSearch')), 'poo');
    await tester.pump();
    expect(breedRows(), findsNWidgets(3));
    expect(find.byKey(const ValueKey('breedOption_Pudel / Poodle')), findsOneWidget);
    expect(find.byKey(const ValueKey('breedOption_Maltipoo')), findsOneWidget);
    expect(find.byKey(const ValueKey('breedOption_Shih-poo')), findsOneWidget);
  });

  testWidgets('品种：清单外词 → 只剩「Pakai」，点了返回该词且 isCustom', (tester) async {
    Object? result;
    await host(tester, (c) => showPetBreedPicker(c, type: PetSpeciesCode.dog, scene: 'edit'), (r) => result = r);
    await tester.enterText(find.byKey(const ValueKey('speciesPickerSearch')), 'Weimaraner');
    await tester.pump();
    expect(breedRows(), findsNothing);
    expect(find.text('Belum ada di daftar'), findsOneWidget);
    expect(find.text('Pakai "Weimaraner"'), findsOneWidget);
    await tester.pump(kBreedNoResultDebounce + const Duration(milliseconds: 50));
    expect(events.where((e) => e.$1 == 'pet_breed_search_no_result'), hasLength(1));
    await tester.tap(find.byKey(const ValueKey('breedPickerCustom')));
    await tester.pumpAndSettle();
    final pick = result as BreedPick;
    expect(pick.label, 'Weimaraner');
    expect(pick.isCustom, isTrue);
    expect(pick.species, PetSpeciesCode.dog);
    expect(events.last.$2, {'species': 'DOG', 'scene': 'edit', 'is_custom': true, 'via_search': true});
  });

  testWidgets('「其他」分组 12 组，每组末项「… lainnya」；搜 musang 命中整组；选 Musang Pandan → CIVET', (tester) async {
    Object? result;
    await host(tester, (c) => showPetBreedPicker(c, type: PetSpeciesCode.other, scene: 'create'), (r) => result = r);
    for (final g in kOtherSpeciesGroups) {
      await tester.scrollUntilVisible(find.byKey(ValueKey('breedOption_lainnya_${g.code.wire}')), 200,
          scrollable: find.descendant(of: find.byKey(const ValueKey('breedPickerList')), matching: find.byType(Scrollable)));
    }
    expect(find.text('Tikus & Dormouse lainnya'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('speciesPickerSearch')), 'musang');
    await tester.pump();
    expect(find.byKey(const ValueKey('breedGroup_CIVET')), findsOneWidget);
    expect(find.byKey(const ValueKey('breedOption_Musang Akar')), findsOneWidget);
    expect(find.byKey(const ValueKey('breedOption_lainnya_CIVET')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('breedOption_Musang Pandan')));
    await tester.pumpAndSettle();
    expect((result as BreedPick).species, PetSpeciesCode.civet);
    expect((result as BreedPick).label, 'Musang Pandan');
  });

  testWidgets('已选手填值：搜索框预填、未聚焦、Pakai 行打勾', (tester) async {
    await host(tester,
        (c) => showPetBreedPicker(c, type: PetSpeciesCode.other, current: 'Kelinci lokal', scene: 'edit'), (_) {});
    final field = tester.widget<TextField>(find.byKey(const ValueKey('speciesPickerSearch')));
    expect(field.controller!.text, 'Kelinci lokal');
    expect(field.focusNode!.hasFocus, isFalse);
    expect(find.text('Pakai "Kelinci lokal"'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('breedPickerCustom')), matching: find.byIcon(Icons.check_rounded)),
        findsOneWidget);
  });
}
