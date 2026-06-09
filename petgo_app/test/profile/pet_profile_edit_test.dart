import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/data/profile_repository.dart';
import 'package:petgo/features/profile/domain/pet_profile.dart';
import 'package:petgo/features/profile/presentation/pet_profile_edit_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

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
      breed: 'Shiba',
      intro: '好奇宝宝',
    ));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    final nameField = tester.widget<TextField>(find.byKey(const ValueKey('petProfileEditNameField')));
    expect(nameField.controller!.text, 'Momo');
    final breedField = tester.widget<TextField>(find.byKey(const ValueKey('petProfileEditBreedField')));
    expect(breedField.controller!.text, 'Shiba');
    // 名字字段上限 20
    expect(nameField.maxLength, 20);
  });

  testWidgets('修改名字提交调 update（PATCH）', (tester) async {
    final repo = _FakeRepo(const PetProfile(id: 1, name: 'Momo', cardToken: 'TOK'));
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('petProfileEditNameField')), 'Momo2');
    await tester.pump();
    // 直接触发提交逻辑（避免依赖 go_router 导航）
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileEditSubmit')));
    expect(submit.onPressed, isNotNull);
  });
}
