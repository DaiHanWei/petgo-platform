import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/profile_prompt_controller.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeRepo implements ProfileRepository {
  PetProfile? existing;
  bool createCalled = false;
  bool failExists = false; // true → 模拟并发 409（档案已存在）
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
    if (failExists) {
      final req = RequestOptions(path: '/pet-profiles');
      throw DioException(
        requestOptions: req,
        response: Response(requestOptions: req, statusCode: 409),
      );
    }
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

/// 带路由的容器化挂载：submit 成功后页面会 `context.go(...)`，故需真实 GoRouter；
/// 用 ProviderContainer 便于 submit 后断言 auth / 提示条 provider 的状态翻转。
GoRouter _router() => GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (_, __) => const PetProfileCreatePage()),
        GoRoute(
            path: '/profile/created',
            builder: (_, __) => const Scaffold(body: Text('created'))),
        GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('profile'))),
      ],
    );

Future<void> _pumpRouted(WidgetTester tester, ProviderContainer container) async {
  await tester.binding.setSurfaceSize(const Size(440, 1600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: _router(),
    ),
  ));
  await tester.pumpAndSettle();
}

/// 填齐必填三项（类型 CAT / 名字 / 生日）并提交。
Future<void> _fillAndSubmit(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('petProfileSpeciesField')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('speciesOption_CAT')));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Momo');
  await tester.pump();
  await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('OK'));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('petProfileSubmit')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC3: 必填三项（类型/名字/生日）齐全才可提交；缺一即禁用', (tester) async {
    // 表单较长（虚线头像+分段label+多行bio），用高视口确保 ListView 全量构建（提交钮不出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();

    // JENIS HEWAN 下拉选择器存在
    expect(find.byKey(const ValueKey('petProfileSpeciesField')), findsOneWidget);

    // 空表单 → 禁用
    expect(_submitBtn(tester).onPressed, isNull);

    // 仅选类型（下拉弹层选 Kucing）→ 仍禁用（缺名字/生日）
    await tester.tap(find.byKey(const ValueKey('petProfileSpeciesField')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('speciesOption_CAT')));
    await tester.pumpAndSettle();
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

  testWidgets('建档成功 → 回填 auth.hasPetProfile=true 且提示条标记 completed（首页提示条立即消失，无需重启）',
      (tester) async {
    final repo = _FakeRepo();
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()), // 无既有档案 → 渲染表单
    ]);
    addTearDown(container.dispose);
    // 起始：HAS_PET 用户尚无档案（提示条会显示的前置态）。
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(petStatus: 'HAS_PET', hasPetProfile: false));
    expect(container.read(authControllerProvider).profile?.hasPetProfile, isFalse);
    expect(container.read(profilePromptProvider).petProfileCompleted, isFalse);

    await _pumpRouted(tester, container);
    await _fillAndSubmit(tester);

    expect(repo.createCalled, isTrue);
    // 关键断言：本次 session 内状态已翻转（修复前要等下次冷启动重拉 /me 才更新）。
    expect(container.read(authControllerProvider).profile?.hasPetProfile, isTrue);
    expect(container.read(profilePromptProvider).petProfileCompleted, isTrue);
    // 正常建档（默认 origin=onboarding）→ 跳庆祝页。
    expect(find.text('created'), findsOneWidget);
  });

  testWidgets('并发 409（档案已存在）→ 同样翻转状态并直达档案（提示条不残留）', (tester) async {
    final repo = _FakeRepo()..failExists = true;
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
    ]);
    addTearDown(container.dispose);
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(petStatus: 'HAS_PET', hasPetProfile: false));

    await _pumpRouted(tester, container);
    await _fillAndSubmit(tester);

    expect(container.read(authControllerProvider).profile?.hasPetProfile, isTrue);
    expect(container.read(profilePromptProvider).petProfileCompleted, isTrue);
    expect(find.text('profile'), findsOneWidget); // 提示并直达档案 Tab
    await tester.pump(const Duration(seconds: 3)); // 走完 petProfileExists toast 定时器
  });
}
