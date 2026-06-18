import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeRepo implements ProfileRepository {
  PetProfile? existing;
  bool createCalled = false;
  String? lastPetType;

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    String? idempotencyKey,
  }) async {
    createCalled = true;
    lastPetType = petType;
    return PetProfile(id: 1, name: name, cardToken: 'T', petType: petType, birthday: birthday);
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

FilledButton _submitBtn(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileSubmit')));

void main() {
  testWidgets('AC3: 必填三项（类型/名字/生日）齐全才可提交；缺一即禁用', (tester) async {
    // 表单较长（虚线头像+分段label+多行bio），用高视口确保 ListView 全量构建（提交钮不出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();

    // 三个类型选择器存在
    expect(find.byKey(const ValueKey('petType_CAT')), findsOneWidget);
    expect(find.byKey(const ValueKey('petType_DOG')), findsOneWidget);
    expect(find.byKey(const ValueKey('petType_OTHER')), findsOneWidget);

    // 空表单 → 禁用
    expect(_submitBtn(tester).onPressed, isNull);

    // 仅选类型 → 仍禁用（缺名字/生日）
    await tester.tap(find.byKey(const ValueKey('petType_CAT')));
    await tester.pump();
    expect(_submitBtn(tester).onPressed, isNull);

    // 类型 + 名字 → 仍禁用（缺生日，R2/AC3：生日必填）
    await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Momo');
    await tester.pump();
    expect(_submitBtn(tester).onPressed, isNull);

    // 补完整生日（date picker 产出完整年月日）→ 启用
    await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(_submitBtn(tester).onPressed, isNotNull);
  });

  testWidgets('AC3: 头像未传 → 渲染默认占位（add photo）；选填品种/介绍可空', (tester) async {
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();
    // 头像缺省占位 widget（pet-create.html：虚线圆 + 相机角标 + Upload Foto）。
    expect(find.byIcon(Icons.photo_camera_rounded), findsOneWidget);
    expect(find.byKey(const ValueKey('petProfileAvatar')), findsOneWidget);
  });

  testWidgets('名字 maxLength=20、介绍 maxLength=30（实时计数约束）', (tester) async {
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(find.byKey(const ValueKey('petProfileNameField')));
    expect(field.maxLength, 20);
    final intro = tester.widget<TextField>(find.byKey(const ValueKey('petProfileIntroField')));
    expect(intro.maxLength, 30);
  });
}
