import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/features/auth/data/me_repository.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/auth/domain/onboarding_branch.dart';
import 'package:petgo/features/auth/presentation/nickname_page.dart';
import 'package:petgo/features/auth/presentation/pet_status_page.dart';
import 'package:petgo/features/profile/presentation/profile_onboarding_page.dart';
import 'package:petgo/features/content/data/feed_repository.dart';
import 'package:petgo/features/content/presentation/feed_tab_row.dart';
import 'package:petgo/features/content/presentation/home_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/pet_status_selector.dart';
import 'package:petgo/shared/widgets/profile_prompt_bar.dart';

import '../support/fake_feed_repository.dart';

class _FakeMeRepository implements MeRepository {
  @override
  Future<UserProfile> getMe() async => const UserProfile();
  @override
  Future<UserProfile> updateNickname(String nickname) async =>
      UserProfile(nickname: nickname, displayName: nickname, onboardingCompleted: false);
  @override
  Future<UserProfile> updatePetStatus(String petStatus) async =>
      UserProfile(petStatus: petStatus, onboardingCompleted: true);
}

LoginResponse _newUser(String displayName) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: true,
      onboardingCompleted: false,
      profile: UserProfile(displayName: displayName),
    );

GoRouter _flowRouter() => GoRouter(
      initialLocation: '/onboarding/pet-status',
      routes: [
        GoRoute(path: '/onboarding/pet-status', builder: (c, s) => const PetStatusPage()),
        GoRoute(path: '/onboarding/profile', builder: (c, s) => const ProfileOnboardingPage()),
        GoRoute(path: '/home', builder: (c, s) => const HomePage()),
      ],
    );

void main() {
  group('AC2 分叉（纯函数）', () {
    test('A → 档案创建引导；B/C → 首页', () {
      expect(petStatusBranchLocation('A'), '/onboarding/profile');
      expect(petStatusBranchLocation('B'), '/home');
      expect(petStatusBranchLocation('C'), '/home');
    });
  });

  testWidgets('AC1: 昵称页默认填充 displayName，≤20 可继续、>20 禁继续', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_newUser('Alice G'));

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: NicknamePage(),
      ),
    ));
    await tester.pumpAndSettle();

    // 默认填充
    expect(find.widgetWithText(TextField, 'Alice G'), findsOneWidget);
    // 有效 → 继续可用
    FilledButton btn() => tester.widget<FilledButton>(find.byKey(const ValueKey('nicknameContinue')));
    expect(btn().onPressed, isNotNull);

    // >20 字 → 禁用
    await tester.enterText(find.byKey(const ValueKey('nicknameField')), 'x' * 21);
    await tester.pump();
    expect(btn().onPressed, isNull);

    // 清空 → 禁用（禁止空昵称）
    await tester.enterText(find.byKey(const ValueKey('nicknameField')), '   ');
    await tester.pump();
    expect(btn().onPressed, isNull);
  });

  testWidgets('AC2: 状态页三选一必选——未选禁完成，选后可完成', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [meRepositoryProvider.overrideWithValue(_FakeMeRepository())],
      child: MaterialApp.router(
        routerConfig: _flowRouter(),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(PetStatusSelector), findsOneWidget);
    FilledButton btn() => tester.widget<FilledButton>(find.byKey(const ValueKey('petStatusComplete')));
    expect(btn().onPressed, isNull); // 未选禁用

    await tester.tap(find.byKey(const ValueKey('petStatus_A')));
    await tester.pump();
    expect(btn().onPressed, isNotNull); // 选后可用
  });

  testWidgets('AC2: 选 A 完成 → 进档案创建引导', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [meRepositoryProvider.overrideWithValue(_FakeMeRepository())],
      child: MaterialApp.router(
        routerConfig: _flowRouter(),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('petStatus_A')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('petStatusComplete')));
    await tester.pumpAndSettle();

    expect(find.text("Create your pet's profile"), findsOneWidget);
  });

  testWidgets('AC2: 选 B 完成 → 进首页（无提示条）', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        meRepositoryProvider.overrideWithValue(_FakeMeRepository()),
        feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
      ],
      child: MaterialApp.router(
        routerConfig: _flowRouter(),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('petStatus_B')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('petStatusComplete')));
    await tester.pumpAndSettle();

    // 进首页（Story 3.2 Feed 已就位）+ B 状态不显示档案提示条。
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(find.byType(ProfilePromptBar), findsNothing);
  });
}
