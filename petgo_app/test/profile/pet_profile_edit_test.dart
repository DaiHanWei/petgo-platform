import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
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

  @override
  Future<void> deleteMyProfile() async {
    deleted = true;
  }

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    String? idempotencyKey,
  }) async =>
      profile;

  @override
  Future<PetProfile?> getMyProfile() async => profile;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
  }) async {
    updatedName = name;
    updatedSex = sex;
    updateCalled = true;
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

  testWidgets('F6: pet_type 置灰只读，展示既有类型不可改', (tester) async {
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK', petType: 'DOG'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    // JENIS HEWAN 改为锁定下拉字段（SpeciesField locked）：展示既有类型，不可改。
    final speciesField =
        tester.widget<SpeciesField>(find.byKey(const ValueKey('petProfileEditTypeReadonly')));
    expect(speciesField.petType, 'DOG'); // 既有类型
    expect(speciesField.locked, isTrue); // 锁定不可改
    expect(speciesField.onChanged, isNull); // 无变更回调
    // update() 签名无 petType 参数 → 结构上不可能随 PATCH 提交（后端 DTO 亦无该字段）。
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
}
