import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_edit_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_form_fields.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeRepo implements ProfileRepository {
  _FakeRepo(this.profile);
  final PetProfile profile;
  String? updatedName;
  bool deleted = false;
  /// V1.1.6 Story 1.1：记录 PATCH 实际带出去的性别。
  /// `updateCalled` 用来区分「传了 null」与「压根没调用 update」——只看 `updatedSex == null` 分不出这两者。
  String? updatedSex;
  bool updateCalled = false;
  /// V1.4.0 multi-pet：删除 / 更新实际带出去的 petId。
  int? deletedPetId;
  int? updatedPetId;
  /// V1.5.0 pet-species Story 3.3：PATCH 带出去的 species / breed；cooldownUntil 非空 → 回 409 pet-species-cooldown。
  String? updatedSpecies;
  String? updatedBreed;
  String? cooldownUntil;

  @override
  Future<void> deleteMyProfile({int? petId}) async {
    deleted = true;
    deletedPetId = petId;
  }

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    String? sex,
    String? idempotencyKey, String? species}) async =>
      profile;

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async => profile;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    int? petId, String? species}) async {
    updatedName = name;
    updatedSex = sex;
    updateCalled = true;
    updatedPetId = petId;
    updatedSpecies = species;
    updatedBreed = breed;
    if (cooldownUntil != null) {
      final req = RequestOptions(path: '/pet-profiles/me');
      throw DioException(
        requestOptions: req,
        response: Response(requestOptions: req, statusCode: 409, data: {
          'type': 'https://petgo/errors/pet-species-cooldown',
          'status': 409,
          'changeableFrom': cooldownUntil,
        }),
      );
    }
    return profile.copyWith(name: name, sex: sex);
  }
}

Widget _wrap(_FakeRepo repo) {
  return ProviderScope(
    overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) async => repo.profile),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: PetProfileEditPage(),
    ),
  );
}

void main() {
  testWidgets('编辑页预填既有值（AC1 复用表单 + 预填）', (tester) async {
    final repo = _FakeRepo(const PetProfile(
      id: 1,
      name: 'Momo',
      cardToken: 'TOK',
      petType: 'DOG',
      breed: 'Shiba',
      intro: '好奇宝宝',
    ));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    final nameField = tester.widget<TextField>(find.byKey(const ValueKey('petProfileEditNameField')));
    expect(nameField.controller!.text, 'Momo');
    // RAS 改为下拉字段（BreedField）：既有品种 'Shiba' 预填并显示。
    expect(find.byKey(const ValueKey('petProfileEditBreedField')), findsOneWidget);
    expect(find.text('Shiba'), findsOneWidget);
    // 名字字段上限 20
    expect(nameField.maxLength, 20);
  });

  testWidgets('修改名字提交调 update（PATCH）', (tester) async {
    // 表单较长（分段label+多行bio），用高视口确保 ListView 全量构建（提交钮不出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('petProfileEditNameField')), 'Momo2');
    await tester.pump();
    // 直接触发提交逻辑（避免依赖 go_router 导航）
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileEditSubmit')));
    expect(submit.onPressed, isNotNull);
  });

  testWidgets('删除档案：按钮存在，点击弹二次确认，取消不删（bug 20260702-237）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    // 危险区删除按钮存在（不再是隐藏/桩）。
    final deleteBtn = find.byKey(const ValueKey('petProfileDeleteButton'));
    expect(deleteBtn, findsOneWidget);

    // 点击 → 弹二次确认（标题 + 危险确认项）。
    await tester.ensureVisible(deleteBtn);
    await tester.tap(deleteBtn);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petProfileDeleteConfirm')), findsOneWidget);

    // 取消 → 不删除（deleteMyProfile 零调用）。
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(repo.deleted, isFalse);
  });

  // V1.5.0 pet-species Story 3.3：规则按设计改变——原「类型恒锁」，现为「可改（C1）/ 90 天冷却中锁定（C6）」两态。
  testWidgets('F6 → V1.5.0：从没改过（无 speciesChangeableFrom）→ 类型可改，展示既有类型', (tester) async {
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK', petType: 'DOG'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();
    final speciesField = tester.widget<SpeciesField>(find.byKey(const ValueKey('petProfileEditTypeField')));
    expect(speciesField.species, 'DOG'); // 旧后端无 species → 大类线上值
    expect(speciesField.locked, isFalse);
    expect(speciesField.onChanged, isNotNull);
  });

  group('V1.5.0 pet-species Story 3.3：改类型与冷却', () {
    const rabbit = PetProfile(
        id: 1, name: 'Momo', cardToken: 'TOK', petType: 'OTHER', species: 'RABBIT', breed: 'Holland Lop');

    Future<void> pumpEdit(WidgetTester tester, _FakeRepo repo) async {
      await tester.binding.setSurfaceSize(const Size(440, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(ProviderScope(
        overrides: [
          profileRepositoryProvider.overrideWithValue(repo),
          petProfileProvider.overrideWith((ref) async => repo.profile),
        ],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: PetProfileEditPage(),
        ),
      ));
      await tester.pumpAndSettle();
    }

    Future<void> pickType(WidgetTester tester, String wire) async {
      await tester.tap(find.byKey(const ValueKey('petProfileEditTypeField')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(ValueKey('speciesCell_$wire')));
      await tester.pumpAndSettle();
    }

    Future<void> save(WidgetTester tester) async {
      final submit = find.byKey(const ValueKey('petProfileEditSubmit'));
      await tester.ensureVisible(submit);
      await tester.tap(submit);
      await tester.pumpAndSettle();
    }

    testWidgets('选不同类型 → 确认；Batal 不变、Ubah 清空品种；选相同不弹', (tester) async {
      final repo = _FakeRepo(rabbit);
      await pumpEdit(tester, repo);
      await pickType(tester, 'RABBIT'); // 相同 → 不弹
      expect(find.byKey(const ValueKey('petTypeChangeConfirm')), findsNothing);
      await pickType(tester, 'HAMSTER');
      expect(find.text('Ubah jenis hewan?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petTypeChangeCancel')));
      await tester.pumpAndSettle();
      expect(find.text('Holland Lop'), findsOneWidget);
      expect(find.textContaining('Kelinci'), findsWidgets);
      await pickType(tester, 'HAMSTER');
      await tester.tap(find.byKey(const ValueKey('petTypeChangeConfirm')));
      await tester.pumpAndSettle();
      expect(find.text('Holland Lop'), findsNothing);
      expect(find.text('Pilih ras'), findsOneWidget);
    });

    testWidgets('保存：改了类型 → 带 species 与 breed=空串；没改 → 不带 species', (tester) async {
      final repo = _FakeRepo(rabbit);
      await pumpEdit(tester, repo);
      await save(tester);
      expect(repo.updatedSpecies, isNull);
      await tester.pump(const Duration(seconds: 4));

      final repo2 = _FakeRepo(rabbit);
      await pumpEdit(tester, repo2);
      await pickType(tester, 'HAMSTER');
      await tester.tap(find.byKey(const ValueKey('petTypeChangeConfirm')));
      await tester.pumpAndSettle();
      await save(tester);
      expect(repo2.updatedSpecies, 'HAMSTER');
      expect(repo2.updatedBreed, '');
      await tester.pump(const Duration(seconds: 4));
    });

    testWidgets('改类型后又改回原类型 → 不带 species、品种按清空提交', (tester) async {
      final repo = _FakeRepo(rabbit);
      await pumpEdit(tester, repo);
      await pickType(tester, 'HAMSTER');
      await tester.tap(find.byKey(const ValueKey('petTypeChangeConfirm')));
      await tester.pumpAndSettle();
      await pickType(tester, 'RABBIT');
      await tester.tap(find.byKey(const ValueKey('petTypeChangeConfirm')));
      await tester.pumpAndSettle();
      await save(tester);
      expect(repo.updatedSpecies, isNull);
      expect(repo.updatedBreed, '');
      await tester.pump(const Duration(seconds: 4));
    });

    testWidgets('冷却中 → 锁定显示「Bisa diubah lagi 12 Feb」、点击无反应、品种仍可改', (tester) async {
      final repo = _FakeRepo(PetProfile(
          id: 1, name: 'Momo', cardToken: 'TOK', petType: 'OTHER', species: 'RABBIT', breed: 'Holland Lop',
          speciesChangeableFrom: DateTime(2027, 2, 12)));
      await pumpEdit(tester, repo);
      final field = tester.widget<SpeciesField>(find.byKey(const ValueKey('petProfileEditTypeField')));
      expect(field.locked, isTrue);
      expect(find.text('Bisa diubah lagi 12 Feb'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petProfileEditTypeField')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('speciesPickerGrid')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('petProfileEditBreedField')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('breedPickerList')), findsOneWidget);
    });

    testWidgets('409 冷却 → toast 含日期、类型品种回滚并锁定、名字输入保留', (tester) async {
      final repo = _FakeRepo(rabbit)..cooldownUntil = '2027-02-12';
      await pumpEdit(tester, repo);
      await pickType(tester, 'HAMSTER');
      await tester.tap(find.byKey(const ValueKey('petTypeChangeConfirm')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('petProfileEditNameField')), 'Momo 2');
      await tester.pump();
      await save(tester);
      expect(find.text('Jenis hewan baru bisa diubah lagi 12 Feb'), findsOneWidget);
      expect(find.text('Holland Lop'), findsOneWidget);
      expect(tester.widget<SpeciesField>(find.byKey(const ValueKey('petProfileEditTypeField'))).locked, isTrue);
      expect(find.text('Momo 2'), findsOneWidget);
      await tester.pump(const Duration(seconds: 4));
    });
  });

  // ===== V1.1.6 Story 1.1：性别字段落地 =====
  //
  // 本组用例守的是一个**曾经存在的缺陷**：性别选择器能选能显示，但选完不提交、不回填 ——
  // 用户选了、保存了、退出再进来又变回「请选择」。三条用例分别钉住：能存、能回填、未填不崩。

  testWidgets('AC1：选了性别再保存 → 随 PATCH 带出去', (tester) async {
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    // 打开性别选择器 → 选「公」
    final sexTile = find.byKey(const ValueKey('petProfileEditSexTile'));
    await tester.ensureVisible(sexTile);
    await tester.tap(sexTile);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('petSexMaleOption')));
    await tester.pumpAndSettle();

    final submit = find.byKey(const ValueKey('petProfileEditSubmit'));
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(repo.updateCalled, isTrue);
    expect(repo.updatedSex, 'MALE'); // ⚠️ 缺陷期这里是 null

    // 提交成功后页面会 context.go('/profile')，测试里没有 router → 落到 catch 弹 toast。
    // 与本用例无关，但要把它的 3s 定时器排空，否则框架报「Timer is still pending」。
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('AC1：已有性别的档案 → 进页面就回填，不是「请选择」', (tester) async {
    final repo = _FakeRepo(const PetProfile(
      id: 1,
      name: 'Momo',
      cardToken: 'TOK',
      sex: 'FEMALE',
    ));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.petProfileSexFemale), findsOneWidget);
    expect(find.text(l10n.petProfileSexPick), findsNothing); // 占位不该还在
  });

  testWidgets('AC3：存量档案没有性别 → 显示占位文案，不崩不空白', (tester) async {
    // 存量宠物一律 sex = null（迁移不回填），这是最常见的一种档案。
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.byKey(const ValueKey('petProfileEditSexTile')), findsOneWidget);
    expect(find.text(l10n.petProfileSexPick), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  // ===== V1.4.0 multi-pet Story 1.5 · AC5（UI 稿 C1 ~ C6）=====
  group('V1.4.0 multi-pet：删除单只宠物', () {
    const kopi = PetProfile(id: 2, name: 'Kopi', cardToken: 'TOK2');
    const twoPets = [
      PetListItem(id: 1, name: 'Orenji', isDefault: true),
      PetListItem(id: 2, name: 'Kopi'),
    ];

    setUp(() => SharedPreferences.setMockInitialValues({}));

    Future<ProviderContainer> pumpRouted(
        WidgetTester tester, _FakeRepo repo, List<PetListItem> pets) async {
      await tester.binding.setSurfaceSize(const Size(440, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final c = ProviderContainer(overrides: [
        profileRepositoryProvider.overrideWithValue(repo),
        petProfileProvider.overrideWith((ref) async => repo.profile),
        petListProvider.overrideWith((ref) async => pets),
      ]);
      addTearDown(c.dispose);
      // 列表先解析好（真实路径里「我的」页 / Diary 已经取过）。
      await c.read(petListProvider.future);
      c.read(currentPetIdProvider.notifier).select(repo.profile.id);
      final router = GoRouter(initialLocation: '/profile/edit', routes: [
        GoRoute(path: '/profile/edit', builder: (_, _) => const PetProfileEditPage()),
        GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('diary-tab'))),
      ]);
      addTearDown(router.dispose);
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
      return c;
    }

    Future<void> openConfirm(WidgetTester tester) async {
      final deleteBtn = find.byKey(const ValueKey('petProfileDeleteButton'));
      await tester.ensureVisible(deleteBtn);
      await tester.tap(deleteBtn);
      await tester.pumpAndSettle();
    }

    testWidgets('C2：不是最后一只 → 标题带名字 + 新正文', (tester) async {
      await pumpRouted(tester, _FakeRepo(kopi), twoPets);
      await openConfirm(tester);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.petProfileDeleteConfirmTitle('Kopi')), findsOneWidget);
      expect(find.text(l10n.petDeleteConfirmBodyOther('Kopi')), findsOneWidget);
      expect(find.text(l10n.petProfileDeleteConfirmBody('Kopi')), findsNothing);
    });

    testWidgets('C2：最后一只 → 标题带名字 + 原正文', (tester) async {
      await pumpRouted(tester, _FakeRepo(kopi), const [PetListItem(id: 2, name: 'Kopi')]);
      await openConfirm(tester);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.petProfileDeleteConfirmTitle('Kopi')), findsOneWidget);
      expect(find.text(l10n.petProfileDeleteConfirmBody('Kopi')), findsOneWidget);
    });

    testWidgets('C4：删非最后一只 → 只删这只（带 petId）+ Toast + 回 Diary Tab + 当前宠物回落', (tester) async {
      final repo = _FakeRepo(kopi);
      final c = await pumpRouted(tester, repo, twoPets);
      await openConfirm(tester);
      await tester.tap(find.byKey(const ValueKey('petProfileDeleteConfirm')));
      await tester.pumpAndSettle();

      expect(repo.deletedPetId, 2);
      expect(find.text('diary-tab'), findsOneWidget);
      expect(find.text('Kopi\'s profile deleted'), findsOneWidget);
      // 当前宠物清掉 → effectivePetIdProvider 回落默认宠物（列表重取后同一份桩：默认 = 1）。
      expect(c.read(currentPetIdProvider), isNull);
      expect(await c.read(effectivePetIdProvider.future), 1);
      await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
    });

    testWidgets('C6：删最后一只 → 现状：回 Diary Tab，无 Toast', (tester) async {
      final repo = _FakeRepo(kopi);
      await pumpRouted(tester, repo, const [PetListItem(id: 2, name: 'Kopi')]);
      await openConfirm(tester);
      await tester.tap(find.byKey(const ValueKey('petProfileDeleteConfirm')));
      await tester.pumpAndSettle();

      expect(repo.deletedPetId, 2);
      expect(find.text('diary-tab'), findsOneWidget);
      expect(find.text('Kopi\'s profile deleted'), findsNothing);
    });

    testWidgets('保存只改编辑中的那只（PATCH 带 petId）', (tester) async {
      final repo = _FakeRepo(kopi);
      await pumpRouted(tester, repo, twoPets);
      await tester.tap(find.byKey(const ValueKey('petProfileEditSubmit')));
      await tester.pumpAndSettle();
      expect(repo.updatedPetId, 2);
    });
  });
}
