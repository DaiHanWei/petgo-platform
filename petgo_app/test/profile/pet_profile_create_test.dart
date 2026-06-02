import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/data/profile_repository.dart';
import 'package:petgo/features/profile/domain/pet_profile.dart';
import 'package:petgo/features/profile/presentation/pet_profile_create_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeRepo implements ProfileRepository {
  PetProfile? existing;
  bool createCalled = false;

  @override
  Future<PetProfile> create({
    required String name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? intro,
    String? idempotencyKey,
  }) async {
    createCalled = true;
    return PetProfile(id: 1, name: name, cardToken: 'T');
  }

  @override
  Future<PetProfile?> getMyProfile() async => existing;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? intro,
  }) async =>
      PetProfile(id: 1, name: name ?? 'x', cardToken: 'T');
}

Widget _wrap(ProfileRepository repo) {
  return ProviderScope(
    overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: PetProfileCreatePage(),
    ),
  );
}

void main() {
  testWidgets('无既有档案 → 渲染表单；空名提交禁用，填名后启用（AC1 校验体验）', (tester) async {
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('petProfileNameField')), findsOneWidget);
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileSubmit')));
    expect(submit.onPressed, isNull); // 空名禁用

    await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Momo');
    await tester.pump();
    final submit2 = tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileSubmit')));
    expect(submit2.onPressed, isNotNull); // 填名启用
  });

  testWidgets('名字 maxLength=20（实时计数约束）', (tester) async {
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(find.byKey(const ValueKey('petProfileNameField')));
    expect(field.maxLength, 20);
    final intro = tester.widget<TextField>(find.byKey(const ValueKey('petProfileIntroField')));
    expect(intro.maxLength, 30);
  });
}
