import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/content/presentation/home_page.dart';
import 'package:petgo/features/profile/presentation/profile_onboarding_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/profile_prompt_bar.dart';
import 'package:shared_preferences/shared_preferences.dart';

LoginResponse _userWithStatus(String status, {bool hasPetProfile = false}) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(petStatus: status, hasPetProfile: hasPetProfile, onboardingCompleted: true),
    );

Future<void> _pumpHome(WidgetTester tester, ProviderContainer container) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: HomePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('AC1: 档案引导页含「立即创建」+「跳过，稍后创建」', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp.router(
        routerConfig: GoRouter(
          initialLocation: '/onboarding/profile',
          routes: [
            GoRoute(path: '/onboarding/profile', builder: (c, s) => const ProfileOnboardingPage()),
            GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('HOME'))),
          ],
        ),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('profileOnboardingCreate')), findsOneWidget);
    expect(find.byKey(const ValueKey('profileOnboardingSkip')), findsOneWidget);
  });

  testWidgets('AC2: 状态 A 未完成档案 → 首页显示提示条', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_userWithStatus('A'));

    await _pumpHome(tester, container);
    expect(find.byType(ProfilePromptBar), findsOneWidget);
  });

  testWidgets('AC3: 状态 B/C → 首页不显示提示条', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_userWithStatus('B'));

    await _pumpHome(tester, container);
    expect(find.byType(ProfilePromptBar), findsNothing);
  });

  testWidgets('AC2: 关闭 X → 当次 session 隐藏提示条', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_userWithStatus('A'));

    await _pumpHome(tester, container);
    expect(find.byType(ProfilePromptBar), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('profilePromptClose')));
    await tester.pumpAndSettle();

    expect(find.byType(ProfilePromptBar), findsNothing);
  });

  testWidgets('AC2: 已完成档案（hasPetProfile）→ 不显示提示条', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_userWithStatus('A', hasPetProfile: true));

    await _pumpHome(tester, container);
    expect(find.byType(ProfilePromptBar), findsNothing);
  });
}
