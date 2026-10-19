import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_pick_for_consult.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（V1.4.0 multi-pet Story 4.1 · AC7.1 · UI 稿 H1 / H3 / H4）：问诊 / 咨询前单选宠物。
/// 结构断言（无 golden）：0 / 1 只不弹；≥2 只弹、默认选中当前宠物；只读行带锁可选；取消 → null；按用途切标题。
const _momo = PetListItem(id: 7, name: 'Momo', petType: 'CAT', isDefault: true);
const _kopi = PetListItem(id: 8, name: 'Kopi', petType: 'DOG');
const _boba = PetListItem(id: 9, name: 'Boba', petType: 'CAT', status: PetListItem.statusReadOnly);

class _Harness {
  final List<PetPick?> results = [];
}

Future<_Harness> _pump(
  WidgetTester tester, {
  required List<PetListItem> pets,
  PetPickPurpose purpose = PetPickPurpose.triage,
  int? current,
  bool listFails = false,
}) async {
  SharedPreferences.setMockInitialValues({});
  tester.platformDispatcher.localesTestValue = const [Locale('id')];
  addTearDown(tester.platformDispatcher.clearLocalesTestValue);
  final h = _Harness();
  final container = ProviderContainer(overrides: [
    petListProvider.overrideWith((ref) async {
      if (listFails) {
        // 4xx 不走默认重试，失败立即落定。
        final opts = RequestOptions(path: '/api/v1/pet-profiles/me/list');
        throw DioException(requestOptions: opts, response: Response(requestOptions: opts, statusCode: 403));
      }
      return pets;
    }),
  ]);
  addTearDown(container.dispose);
  if (current != null) await container.read(currentPetIdProvider.notifier).select(current);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Consumer(
          builder: (context, ref, _) => Center(
            child: ElevatedButton(
              key: const ValueKey('go'),
              onPressed: () async => h.results.add(await pickPetForConsult(context, ref, purpose: purpose)),
              child: const Text('go'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
  return h;
}

void main() {
  testWidgets('0 只 → 不弹，返回 PetPick(null)（不带 petId）', (tester) async {
    final h = await _pump(tester, pets: const []);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(h.results, hasLength(1));
    expect(h.results.single, isNotNull);
    expect(h.results.single!.petId, isNull);
  });

  testWidgets('1 只 → 不弹（H3），直接返回那只（P-8：单宠物也带上下文）', (tester) async {
    final h = await _pump(tester, pets: const [_momo]);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(h.results.single!.petId, 7);
  });

  testWidgets('≥2 只 → 弹 H1：标题 / 说明、默认选中当前宠物、Lanjut 返回所选', (tester) async {
    final h = await _pump(tester, pets: const [_momo, _kopi, _boba], current: 8);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();

    expect(find.text('Cek gejala untuk siapa?'), findsOneWidget);
    expect(find.text('AI akan memakai jenis, umur, dan berat hewan ini.'), findsOneWidget);
    expect(find.text('Lanjut'), findsOneWidget);
    // 默认选中 Diary 当前宠物（Kopi）。
    final kopi = tester.widget<Icon>(find.byKey(const ValueKey('petPickerRadio-8')));
    expect(kopi.icon, Icons.check_circle);
    final momo = tester.widget<Icon>(find.byKey(const ValueKey('petPickerRadio-7')));
    expect(momo.icon, Icons.radio_button_unchecked);

    await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
    await tester.pumpAndSettle();
    expect(h.results.single!.petId, 8);
  });

  testWidgets('只读宠物行带锁 + H-3 副标题，仍可选', (tester) async {
    final h = await _pump(tester, pets: const [_momo, _kopi, _boba]);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();

    final bobaRow = find.byKey(const ValueKey('petPickerRow-9'));
    expect(find.descendant(of: bobaRow, matching: find.byKey(const ValueKey('petCardRowLock'))), findsOneWidget);
    expect(find.descendant(of: bobaRow, matching: find.text('Hanya-baca · hasil tidak bisa disimpan')),
        findsOneWidget);
    // 正常行无锁。
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('petPickerRow-7')), matching: find.byKey(const ValueKey('petCardRowLock'))),
        findsNothing);

    await tester.tap(bobaRow);
    await tester.pump();
    expect(tester.widget<Icon>(find.byKey(const ValueKey('petPickerRadio-9'))).icon, Icons.check_circle);
    await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
    await tester.pumpAndSettle();
    expect(h.results.single!.petId, 9);
    expect(h.results.single!.pet!.isReadOnly, isTrue);
  });

  testWidgets('下滑 / 点遮罩取消 → 返回 null（调用方不进入后续页面）', (tester) async {
    final h = await _pump(tester, pets: const [_momo, _kopi]);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsOneWidget);

    await tester.tapAt(const Offset(10, 10)); // 遮罩
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(h.results, [null]);
  });

  testWidgets('purpose = vetConsult → H-6 / H-7 文案', (tester) async {
    await _pump(tester, pets: const [_momo, _kopi], purpose: PetPickPurpose.vetConsult);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.text('Konsultasi untuk siapa?'), findsOneWidget);
    expect(find.text('Dokter akan melihat profil hewan ini.'), findsOneWidget);
    expect(find.text('Cek gejala untuk siapa?'), findsNothing);
  });

  // code review 4-1 A3：列表取不到不悄悄放行（多宠用户会被落到默认宠物）。
  testWidgets('宠物列表取不到 → 提示加载失败、返回 null（不进入后续页面）', (tester) async {
    final h = await _pump(tester, pets: const [_momo, _kopi], listFails: true);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(find.text('Gagal memuat, silakan coba lagi'), findsOneWidget);
    expect(h.results, [null]);
    await tester.pump(const Duration(seconds: 5)); // toast 计时器
  });
}
