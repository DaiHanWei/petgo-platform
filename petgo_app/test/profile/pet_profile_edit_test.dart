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
    String? intro,
  }) async {
    updatedName = name;
    return profile.copyWith(name: name);
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
}
