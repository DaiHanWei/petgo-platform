import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/domain/health_form_dirty.dart';
import 'package:tailtopia/features/profile/domain/health_list_item.dart';
import 'package:tailtopia/features/profile/domain/health_record_icons.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/health_record_form.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 Story 3.2：健康记录表单（假仓储；预填接口按 [responder] 回）。
class _Repo implements HealthRecordRepository {
  _Repo(this.responder);

  HealthRecordPreview Function(HealthRecordPreviewRequest req) responder;
  final previews = <HealthRecordPreviewRequest>[];
  HealthRecordDraft? created;
  HealthRecordDraft? updated;
  int deletes = 0;

  @override
  Future<List<HealthListItem>> list({int? petId}) async => const [];

  @override
  Future<int?> create(HealthRecordDraft draft, {int? petId}) async {
    created = draft;
    return 77;
  }

  @override
  Future<void> update(int id, HealthRecordDraft draft, {int? petId}) async => updated = draft;

  @override
  Future<void> delete(int id, {int? petId}) async => deletes++;

  @override
  Future<HealthRecordPreview> preview(HealthRecordPreviewRequest request, {int? petId}) async {
    previews.add(request);
    return responder(request);
  }
}

class _Harness {
  _Harness(this.repo, this.container);
  final _Repo repo;
  final ProviderContainer container;
  HealthFormOutcome? outcome;
  bool closed = false;
}

final _year = DateTime.now().year;

Future<_Harness> _open(
  WidgetTester tester, {
  String? petType = 'CAT',
  HealthListItem? existing,
  String? presetType,
  HealthRecordPrefill prefill = const HealthRecordPrefill(),
  HealthRecordPreview Function(HealthRecordPreviewRequest)? responder,
}) async {
  await tester.binding.setSurfaceSize(const Size(500, 2000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final repo = _Repo(responder ?? (_) => const HealthRecordPreview());
  final container = ProviderContainer(overrides: [
    effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
    healthRecordRepositoryProvider.overrideWithValue(repo),
  ]);
  addTearDown(container.dispose);
  final h = _Harness(repo, container);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () async {
                h.outcome = await showModalBottomSheet<HealthFormOutcome>(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => HealthRecordForm(
                    existing: existing,
                    presetType: presetType,
                    prefill: prefill,
                    petType: petType,
                  ),
                );
                h.closed = true;
              },
              child: const Text('OPEN'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('OPEN'));
  await tester.pumpAndSettle();
  return h;
}

Future<void> _choose(WidgetTester tester, String dropdownKey, String option) async {
  await tester.tap(find.byKey(ValueKey(dropdownKey)));
  await tester.pumpAndSettle();
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

/// 走完 300ms 防抖与预填回包。
Future<void> _settlePreview(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 350));
  await tester.pumpAndSettle();
}

List<String> _dropdownOptions(WidgetTester tester, String key) {
  final dd = tester.widget<DropdownButton<String>>(
      find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(DropdownButton<String>)));
  return [for (final i in dd.items!) (i.child as Text).data!];
}

String? _nextText(WidgetTester tester) {
  final f = find.byKey(const ValueKey('healthNextDateText'));
  if (f.evaluate().isEmpty) return null;
  return tester.widget<Text>(f).data;
}

void main() {
  group('AC1 字段与分支', () {
    testWidgets('类型下拉：Kontrol dokter 在 Lainnya 之前；默认 Vaksin', (tester) async {
      await _open(tester);
      expect(_dropdownOptions(tester, 'healthTypeDropdown'),
          ['Vaccine', 'Deworming', 'Heat', 'Spay/Neuter', 'Vet visit', 'Custom']);
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthTypeDropdown'))).initialValue,
          'VACCINE');
    });

    testWidgets('猫 / 狗 / 其他物种的疫苗预设', (tester) async {
      await _open(tester, petType: 'CAT');
      expect(_dropdownOptions(tester, 'healthVaccinePresetDropdown'),
          ['F3 (Tricat)', 'F4 (Tetracat)', 'Rabies', 'FeLV', 'Other']);
    });

    testWidgets('狗的疫苗预设', (tester) async {
      await _open(tester, petType: 'DOG');
      expect(_dropdownOptions(tester, 'healthVaccinePresetDropdown'), [
        'Puppy DP', 'Combo DHPPi+L', 'Rabies', 'Leptospirosis', 'Kennel Cough', 'Canine influenza', 'Other',
      ]);
    });

    testWidgets('其他物种（含未来新增物种）：只有单个 Nama vaksin 文本框', (tester) async {
      await _open(tester, petType: 'RABBIT');
      expect(find.byKey(const ValueKey('healthVaccinePresetDropdown')), findsNothing);
      expect(find.byKey(const ValueKey('healthVaccineName')), findsOneWidget);
      expect(find.text('Vaccine name'), findsOneWidget);
    });

    testWidgets('剂次 chip 只在系列预设出现；Lainnya 出自由填写框', (tester) async {
      await _open(tester);
      expect(find.byKey(const ValueKey('healthDose_1')), findsNothing);
      await _choose(tester, 'healthVaccinePresetDropdown', 'F3 (Tricat)');
      for (var n = 1; n <= 5; n++) {
        expect(find.byKey(ValueKey('healthDose_$n')), findsOneWidget);
      }
      expect(find.text('Dose (optional)'), findsOneWidget);
      await _choose(tester, 'healthVaccinePresetDropdown', 'Rabies');
      expect(find.byKey(const ValueKey('healthDose_1')), findsNothing, reason: '单剂预设无剂次');
      expect(find.byKey(const ValueKey('healthVaccineName')), findsNothing);
      await _choose(tester, 'healthVaccinePresetDropdown', 'Other');
      expect(find.byKey(const ValueKey('healthVaccineName')), findsOneWidget);
      expect(find.text('Enter vaccine name'), findsOneWidget);
    });

    testWidgets('驱虫：Heartworm 只狗；Eksternal 出时长 chip（默认 1 个月）', (tester) async {
      await _open(tester, petType: 'CAT', presetType: 'DEWORM');
      expect(_dropdownOptions(tester, 'healthDewormDropdown'),
          ['Internal (worms)', 'External (fleas, ticks, mites)', 'Flea + worm']);
      expect(find.byKey(const ValueKey('healthDuration_1')), findsNothing);
      await _choose(tester, 'healthDewormDropdown', 'External (fleas, ticks, mites)');
      expect(find.text('Duration'), findsOneWidget);
      expect(find.text('1 month'), findsOneWidget);
      expect(find.text('3 months'), findsOneWidget);
      expect(find.text('8 months'), findsOneWidget);
      expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('healthDuration_1'))).selected, isTrue);
    });

    testWidgets('狗的驱虫有 Heartworm', (tester) async {
      await _open(tester, petType: 'DOG', presetType: 'DEWORM');
      expect(_dropdownOptions(tester, 'healthDewormDropdown'), contains('Heartworm'));
    });

    testWidgets('Kontrol dokter：只有日期、下次日期、备注；Sterilisasi 无下次日期', (tester) async {
      await _open(tester, presetType: 'VET_VISIT');
      expect(find.byKey(const ValueKey('healthVaccinePresetDropdown')), findsNothing);
      expect(find.byKey(const ValueKey('healthDewormDropdown')), findsNothing);
      expect(find.byKey(const ValueKey('healthCustomName')), findsNothing);
      expect(find.byKey(const ValueKey('healthDateField')), findsOneWidget);
      expect(find.byKey(const ValueKey('healthNextDateField')), findsOneWidget);
      await _choose(tester, 'healthTypeDropdown', 'Spay/Neuter');
      await _settlePreview(tester);
      expect(find.byKey(const ValueKey('healthNextDateField')), findsNothing);
    });

    testWidgets('字段顺序：类型 → 子类型 → 剂次 → 日期 → 下次日期 → 备注', (tester) async {
      await _open(tester, prefill: const HealthRecordPrefill(vaccinePreset: 'F4'));
      double y(Finder f) => tester.getTopLeft(f).dy;
      final ys = [
        y(find.byKey(const ValueKey('healthTypeDropdown'))),
        y(find.byKey(const ValueKey('healthVaccinePresetDropdown'))),
        y(find.byKey(const ValueKey('healthDose_1'))),
        y(find.byKey(const ValueKey('healthDateField'))),
        y(find.byKey(const ValueKey('healthNextDateField'))),
        y(find.widgetWithText(TextField, 'Note')),
      ];
      expect(ys, orderedEquals([...ys]..sort()));
    });
  });

  group('AC2 下次日期与预填', () {
    testWidgets('预填覆盖：没手动改过 → 用建议；手动清空后 → 不再覆盖，保存带 clearNextDate', (tester) async {
      var suggestion = DateTime(_year, 3, 10);
      final h = await _open(tester,
          responder: (_) => HealthRecordPreview(suggestedNextDate: suggestion));
      expect(_nextText(tester), '10 Mar $_year');
      expect(find.text('Use the date your vet gave you.'), findsOneWidget);

      suggestion = DateTime(_year, 4, 20);
      await _choose(tester, 'healthVaccinePresetDropdown', 'F3 (Tricat)');
      await _settlePreview(tester);
      expect(_nextText(tester), '20 Apr $_year', reason: '依赖字段变化 → 重新预填');
      expect(h.repo.previews.last.vaccinePreset, 'F3');

      await tester.tap(find.byKey(const ValueKey('healthNextDateClear')));
      await tester.pumpAndSettle();
      expect(_nextText(tester), '');
      suggestion = DateTime(_year, 5, 1);
      await tester.tap(find.byKey(const ValueKey('healthDose_2')));
      await _settlePreview(tester);
      expect(_nextText(tester), '', reason: '手动改过 → 不覆盖');
      expect(h.repo.previews.last.dose, 2);

      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      final d = h.repo.created!;
      expect(d.vaccinePreset, 'F3');
      expect(d.vaccineName, 'F3 (Tricat)', reason: 'AC1.6 写预设标签');
      expect(d.dose, 2);
      expect(d.nextDate, isNull);
      expect(d.clearNextDate, isTrue);
      expect(d.toJson()['clearNextDate'], isTrue);
    });

    testWidgets('预填失败：不动字段、不报错', (tester) async {
      await _open(tester, responder: (_) => throw StateError('boom'));
      expect(_nextText(tester), '');
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('nextDateAllowed=false → 不显示下次日期', (tester) async {
      await _open(tester,
          presetType: 'MENSTRUATION', responder: (_) => const HealthRecordPreview(nextDateAllowed: false));
      expect(find.byKey(const ValueKey('healthNextDateField')), findsNothing);
      expect(find.byKey(const ValueKey('healthNextDateLocked')), findsNothing);
    });

    testWidgets('发情后续记录：只读 + 锁图标 + 说明，保存不带 nextDate', (tester) async {
      final h = await _open(tester,
          presetType: 'MENSTRUATION',
          responder: (_) => HealthRecordPreview(
              nextDateLocked: true, lockedNextDate: DateTime(_year, 7, 1), suggestedNextDate: DateTime(_year, 7, 1)));
      expect(find.byKey(const ValueKey('healthNextDateLocked')), findsOneWidget);
      expect(find.byKey(const ValueKey('healthNextDateField')), findsNothing);
      expect(find.byIcon(Icons.lock_outline), findsOneWidget);
      expect(find.text('Follows the first day of this heat'), findsOneWidget);
      expect(find.text('1 Jul $_year'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(h.repo.created!.nextDate, isNull);
      expect(h.repo.created!.clearNextDate, isFalse);
    });
  });

  group('AC3 提示', () {
    testWidgets('提示条按 hints 渲染；PERMETHRIN 是警示框且在 Jenis obat 正下方；未知 code 忽略', (tester) async {
      await _open(tester,
          petType: 'DOG',
          presetType: 'DEWORM',
          responder: (_) => const HealthRecordPreview(hints: ['DEWORM_PREGNANT', 'PERMETHRIN', 'SOMETHING_NEW']));
      expect(find.text('Ask your vet before giving.'), findsOneWidget);
      final perm = find.byKey(const ValueKey('healthHint_PERMETHRIN'));
      expect(perm, findsOneWidget);
      final dd = find.byKey(const ValueKey('healthDewormDropdown'));
      expect(tester.getTopLeft(perm).dy, greaterThan(tester.getBottomLeft(dd).dy - 1));
      expect(tester.getTopLeft(perm).dy, lessThan(tester.getTopLeft(find.byKey(const ValueKey('healthDateField'))).dy));
      expect(find.byKey(const ValueKey('healthHint_SOMETHING_NEW')), findsNothing);
    });

    testWidgets('七个提示 code 的 EN 文案', (tester) async {
      await _open(tester,
          responder: (_) => const HealthRecordPreview(hints: [
                'VACCINE_PREGNANT', 'RABIES_YEARLY', 'DEWORM_UNDER_8_WEEKS', 'HEARTWORM_TEST', 'HEAT_NEUTERED',
              ]));
      for (final t in [
        'Ask your vet before vaccinating.',
        'Rabies is yearly. Keep your vaccine book.',
        'Not recommended under 8 weeks yet.',
        'Ask your vet for a heartworm test first.',
        'Heat after neutering? Ask your vet.',
      ]) {
        expect(find.text(t), findsOneWidget, reason: t);
      }
    });
  });

  group('AC4 字段级报错', () {
    testWidgets('驱虫没选类型 → 字段报错、不提交；选了即消失', (tester) async {
      final h = await _open(tester, presetType: 'DEWORM');
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(find.text('Choose a treatment type first'), findsOneWidget);
      expect(h.repo.created, isNull);
      await _choose(tester, 'healthDewormDropdown', 'Internal (worms)');
      expect(find.text('Choose a treatment type first'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(h.repo.created!.dewormType, 'INTERNAL');
      expect(h.repo.created!.productDurationMonths, isNull);
    });

    testWidgets('dateEmpty → 占位「Pick a date」；保存报「Choose a date first」', (tester) async {
      final h = await _open(tester, prefill: const HealthRecordPrefill(dateEmpty: true));
      expect(find.text('Pick a date'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(find.text('Choose a date first'), findsOneWidget);
      expect(h.repo.created, isNull);
    });

    testWidgets('自定义名称为空 → 字段报错（不再是 SnackBar）', (tester) async {
      final h = await _open(tester, presetType: 'CUSTOM');
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(find.byType(SnackBar), findsNothing);
      final field = tester.widget<TextField>(find.byKey(const ValueKey('healthCustomName')));
      expect(field.decoration!.errorText, isNotNull);
      expect(h.repo.created, isNull);
    });

    testWidgets('编辑存量无类型驱虫：类型字段空，只改备注也必须先选', (tester) async {
      final h = await _open(tester,
          existing: HealthListItem(
              kind: 'RECORD', id: 5, editable: true, type: 'DEWORM', eventDate: DateTime(2024, 1, 1), note: 'a'));
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthDewormDropdown'))).initialValue,
          isNull);
      await tester.enterText(find.widgetWithText(TextField, 'Note'), 'b');
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(find.text('Choose a treatment type first'), findsOneWidget);
      expect(h.repo.updated, isNull);
    });
  });

  group('AC5 预填打开与编辑回填', () {
    testWidgets('prefill：类型 / 预设 / 剂次 → 初值，日期 = 今天，立刻调一次预填', (tester) async {
      final h = await _open(tester,
          presetType: 'VACCINE', prefill: const HealthRecordPrefill(vaccinePreset: 'F4', dose: 2));
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthVaccinePresetDropdown'))).initialValue,
          'F4');
      expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('healthDose_2'))).selected, isTrue);
      expect(h.repo.previews, isNotEmpty);
      expect(h.repo.previews.first.vaccinePreset, 'F4');
      expect(h.repo.previews.first.dose, 2);
      final n = DateTime.now();
      expect(h.repo.previews.first.eventDate, DateTime(n.year, n.month, n.day));
    });

    testWidgets('编辑：匹配的预设选中；OTHER → Lainnya + 原文；已存下次日期不被预填覆盖', (tester) async {
      await _open(tester,
          existing: HealthListItem(
              kind: 'RECORD', id: 8, editable: true, type: 'VACCINE', vaccineName: 'Vaksin X',
              vaccinePreset: 'OTHER', eventDate: DateTime(2024, 1, 1), nextDate: DateTime(2025, 1, 1)),
          responder: (_) => HealthRecordPreview(suggestedNextDate: DateTime(2030, 1, 1)));
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthVaccinePresetDropdown'))).initialValue,
          'OTHER');
      expect(find.widgetWithText(TextField, 'Vaksin X'), findsOneWidget);
      expect(_nextText(tester), '1 Jan 2025');
    });

    testWidgets('编辑：F4 预设记录直接选中 F4', (tester) async {
      await _open(tester,
          existing: HealthListItem(
              kind: 'RECORD', id: 9, editable: true, type: 'VACCINE', vaccineName: 'F4 (Tetracat)',
              vaccinePreset: 'F4', dose: 3, eventDate: DateTime(2024, 1, 1)));
      expect(tester.widget<DropdownButtonFormField<String>>(find.byKey(const ValueKey('healthVaccinePresetDropdown'))).initialValue,
          'F4');
      expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('healthDose_3'))).selected, isTrue);
    });
  });

  group('AC6 未保存标记', () {
    testWidgets('改了字段 → true；关闭抽屉 → false', (tester) async {
      final h = await _open(tester);
      expect(h.container.read(healthFormDirtyProvider), isFalse);
      await tester.enterText(find.widgetWithText(TextField, 'Note'), 'abc');
      await tester.pump();
      expect(h.container.read(healthFormDirtyProvider), isTrue);
      await tester.enterText(find.widgetWithText(TextField, 'Note'), '');
      await tester.pump();
      expect(h.container.read(healthFormDirtyProvider), isFalse, reason: '改回初值 = 不脏');
      await tester.enterText(find.widgetWithText(TextField, 'Note'), 'abc');
      await tester.pump();
      Navigator.of(tester.element(find.byKey(const ValueKey('healthTypeDropdown')))).pop();
      await tester.pumpAndSettle();
      expect(h.closed, isTrue);
      expect(h.container.read(healthFormDirtyProvider), isFalse);
    });

    testWidgets('保存成功 → false', (tester) async {
      final h = await _open(tester, presetType: 'VET_VISIT');
      await tester.enterText(find.widgetWithText(TextField, 'Note'), 'kontrol');
      await tester.pump();
      expect(h.container.read(healthFormDirtyProvider), isTrue);
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(h.outcome?.savedType, 'VET_VISIT');
      expect(h.container.read(healthFormDirtyProvider), isFalse);
    });
  });

  group('AC7 图标与名称', () {
    test('VET_VISIT 图标：听诊器 SVG + coral；回退图标不撞通用标记', () {
      final i = healthRecordIconFor('VET_VISIT');
      expect(i.svgAsset, 'assets/icons/stethoscope.svg');
      expect(i.icon, isNot(kHealthRecordGenericIcon.icon));
      expect(healthRecordIconFor('VET_VISIT'), isNot(same(healthRecordIconFor('CUSTOM'))));
    });

    testWidgets('类型名 Vet visit（列表与表单共用一份映射）', (tester) async {
      await _open(tester, presetType: 'VET_VISIT');
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(healthTypeLabel(l10n, 'VET_VISIT'), 'Kontrol dokter');
      expect(healthRecordLabel(l10n, 'VET_VISIT'), 'Kontrol dokter');
    });
  });

  group('AC8 埋点', () {
    testWidgets('health_record_saved：属性齐全、枚举小写、不带名字 / 备注 / 疫苗原文', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      final h = await _open(tester,
          petType: 'DOG',
          presetType: 'DEWORM',
          responder: (_) => HealthRecordPreview(suggestedNextDate: DateTime(_year + 1, 1, 9)));
      await _choose(tester, 'healthDewormDropdown', 'External (fleas, ticks, mites)');
      await tester.tap(find.byKey(const ValueKey('healthDuration_3')));
      await _settlePreview(tester);
      await tester.enterText(find.widgetWithText(TextField, 'Note'), 'secret note');
      await tester.tap(find.byKey(const ValueKey('healthFormSave')));
      await tester.pumpAndSettle();
      expect(h.repo.created!.productDurationMonths, 3);
      final saved = events.where((e) => e.$1 == 'health_record_saved').single.$2!;
      expect(saved, {
        'record_id': 77,
        'species': 'dog',
        'type': 'deworm',
        'deworm_type': 'external',
        'has_next_date': true,
        'next_date_prefilled': true,
        'next_date_edited': false,
        'is_edit': false,
        'entry': 'health_page',
        'source': 'manual',
        ...{if (saved.containsKey('pet_id')) 'pet_id': saved['pet_id']!},
      });
      expect(saved.values.whereType<String>().any((v) => v.contains('secret')), isFalse);
    });

    testWidgets('health_record_deleted：record_id / type / source=manual', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      final h = await _open(tester,
          existing: HealthListItem(
              kind: 'RECORD', id: 12, editable: true, type: 'VET_VISIT', eventDate: DateTime(2024, 1, 1)));
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      expect(h.repo.deletes, 1);
      expect(h.outcome?.savedType, isNull);
      final del = events.where((e) => e.$1 == 'health_record_deleted').single.$2!;
      expect(del['record_id'], 12);
      expect(del['type'], 'vet_visit');
      expect(del['source'], 'manual');
    });
  });
}
