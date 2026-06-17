import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/data/me_repository.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/onboarding_branch.dart';
import 'package:tailtopia/features/auth/presentation/nickname_page.dart';
import 'package:tailtopia/features/auth/presentation/pet_status_page.dart';
import 'package:tailtopia/features/profile/presentation/profile_onboarding_page.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/profile_prompt_bar.dart';

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

/// 记录写端点调用，用于断言「返回键不建账号」（AC4）。
class _RecordingMeRepository implements MeRepository {
  final List<String> writeCalls = [];
  @override
  Future<UserProfile> getMe() async => const UserProfile();
  @override
  Future<UserProfile> updateNickname(String nickname) async {
    writeCalls.add('nickname');
    return UserProfile(nickname: nickname);
  }

  @override
  Future<UserProfile> updatePetStatus(String petStatus) async {
    writeCalls.add('petStatus');
    return UserProfile(petStatus: petStatus, onboardingCompleted: true);
  }
}

/// 引导返回键测试用路由（昵称 ↔ 状态 ↔ 未登录首页占位）。
GoRouter _backRouter(String initial) => GoRouter(
      initialLocation: initial,
      routes: [
        GoRoute(path: '/onboarding/nickname', builder: (c, s) => const NicknamePage()),
        GoRoute(path: '/onboarding/pet-status', builder: (c, s) => const PetStatusPage()),
        GoRoute(
            path: '/home',
            builder: (c, s) => const Scaffold(body: Center(child: Text('GUEST HOME')))),
      ],
    );

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
      expect(petStatusBranchLocation('HAS_PET'), '/onboarding/profile');
      expect(petStatusBranchLocation('PLANNING'), '/home');
      expect(petStatusBranchLocation('ENTHUSIAST'), '/home');
    });
  });

  group('AC5 状态切换分支（纯函数 · FR-21）', () {
    test('B/C→A 未建档 → 触发建档引导', () {
      expect(
        petStatusChangeAction(oldStatus: 'PLANNING', newStatus: 'HAS_PET', hasPetProfile: false),
        PetStatusChangeAction.toProfileOnboarding,
      );
      expect(
        petStatusChangeAction(oldStatus: 'ENTHUSIAST', newStatus: 'HAS_PET', hasPetProfile: false),
        PetStatusChangeAction.toProfileOnboarding,
      );
    });
    test('B/C→A 已建档 → 恢复可见，不重复引导', () {
      expect(
        petStatusChangeAction(oldStatus: 'PLANNING', newStatus: 'HAS_PET', hasPetProfile: true),
        PetStatusChangeAction.restoreExistingProfile,
      );
    });
    test('A→B/C → 档案保留不删 + 非 A 态', () {
      expect(
        petStatusChangeAction(oldStatus: 'HAS_PET', newStatus: 'PLANNING', hasPetProfile: true),
        PetStatusChangeAction.switchAwayFromPet,
      );
      expect(
        petStatusChangeAction(oldStatus: 'HAS_PET', newStatus: 'ENTHUSIAST', hasPetProfile: true),
        PetStatusChangeAction.switchAwayFromPet,
      );
    });
    test('非 A 维度变化（B↔C / A→A）→ 仅刷新', () {
      expect(
        petStatusChangeAction(oldStatus: 'PLANNING', newStatus: 'ENTHUSIAST', hasPetProfile: false),
        PetStatusChangeAction.refreshOnly,
      );
      expect(
        petStatusChangeAction(oldStatus: 'HAS_PET', newStatus: 'HAS_PET', hasPetProfile: true),
        PetStatusChangeAction.refreshOnly,
      );
    });
  });

  testWidgets('AC4: 昵称页返回 → 退出登录流程回未登录首页，账号不创建', (tester) async {
    final fake = _RecordingMeRepository();
    final container = ProviderContainer(
        overrides: [meRepositoryProvider.overrideWithValue(fake)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_newUser('Alice G'));
    expect(container.read(authControllerProvider).status,
        AuthStatus.newUserPendingOnboarding);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: _backRouter('/onboarding/nickname'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(NicknamePage), findsOneWidget);

    await tester.binding.handlePopRoute(); // 模拟设备返回键
    await tester.pumpAndSettle();

    // 回未登录首页 + 落游客态 + 未调任何写账号端点
    expect(find.text('GUEST HOME'), findsOneWidget);
    expect(container.read(authControllerProvider).status, AuthStatus.guest);
    expect(fake.writeCalls, isEmpty);
  });

  testWidgets('AC4: 状态页返回 → 回昵称页且已填昵称保留，不退出流程', (tester) async {
    final container = ProviderContainer(
        overrides: [meRepositoryProvider.overrideWithValue(_FakeMeRepository())]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_newUser('Alice G'));
    // 模拟昵称已填为 Bob（从昵称页继续时写入 profile）
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(displayName: 'Alice G', nickname: 'Bob'));

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: _backRouter('/onboarding/pet-status'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(PetStatusPage), findsOneWidget);

    await tester.binding.handlePopRoute(); // 模拟设备返回键
    await tester.pumpAndSettle();

    // 回昵称页 + 已填昵称保留 + 仍在登录流程（未退出）
    expect(find.byType(NicknamePage), findsOneWidget);
    expect(find.widgetWithText(TextField, 'Bob'), findsOneWidget);
    expect(container.read(authControllerProvider).status,
        AuthStatus.newUserPendingOnboarding);
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

    expect(find.byKey(const ValueKey('petStatus_HAS_PET')), findsOneWidget);
    FilledButton btn() => tester.widget<FilledButton>(find.byKey(const ValueKey('petStatusComplete')));
    expect(btn().onPressed, isNull); // 未选禁用

    await tester.tap(find.byKey(const ValueKey('petStatus_HAS_PET')));
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

    await tester.tap(find.byKey(const ValueKey('petStatus_HAS_PET')));
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

    await tester.tap(find.byKey(const ValueKey('petStatus_PLANNING')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('petStatusComplete')));
    await tester.pumpAndSettle();

    // 进首页（Story 3.2 Feed 已就位）+ B 状态不显示档案提示条。
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(find.byType(ProfilePromptBar), findsNothing);
  });
}
