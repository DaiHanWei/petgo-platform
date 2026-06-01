import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/nickname_page.dart';
import '../../features/auth/presentation/pet_status_page.dart';
import '../../features/content/presentation/home_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/profile/presentation/profile_onboarding_page.dart';
import '../../features/profile/presentation/profile_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../shared/widgets/app_shell.dart';

/// 根 Navigator key（供拦截器在 401 续期失败后于全局弹登录引导，Story 1.5 F3）。
final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

/// 未登录游客**不可**直接进入的受控路由前缀（FR-19 门控）。
const Set<String> _controlledLocations = {'/profile', '/triage', '/me'};

/// 应用路由（provider 化：redirect 可读登录态做受控路由门控）。
///
/// `StatefulShellRoute.indexedStack` 承载 4 个 Tab；`/login`、`/onboarding`、`/dev/*` 为
/// shell 外顶层路由。游客深链受控路由 → redirect 回 `/home`（Tab 点击门控由 [AppShell] 单一入口处理）。
final Provider<GoRouter> routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/home',
    redirect: (context, state) {
      final loggedIn = ref.read(authControllerProvider).isLoggedIn;
      final loc = state.matchedLocation;
      final controlled = _controlledLocations.any((p) => loc == p || loc.startsWith('$p/'));
      if (!loggedIn && controlled) return '/home'; // 安全规则只升不降：游客不进受控路由
      return null;
    },
    routes: <RouteBase>[
      GoRoute(path: '/login', builder: (c, s) => const LoginPage()),
      // 新用户引导流（Story 1.6）：昵称 → 宠物状态 → (A) 档案创建引导 / (B,C) 首页。
      GoRoute(path: '/onboarding', builder: (c, s) => const NicknamePage()),
      GoRoute(path: '/onboarding/pet-status', builder: (c, s) => const PetStatusPage()),
      GoRoute(path: '/onboarding/profile', builder: (c, s) => const ProfileOnboardingPage()),
      // @dev 自测入口（Story 1.4 F3）：不从 UI 链接，仅供手动深链触发登录引导。
      GoRoute(path: '/dev/login-guide', builder: (c, s) => const DevLoginGuidePage()),
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) => AppShell(navigationShell: navigationShell),
        branches: <StatefulShellBranch>[
          StatefulShellBranch(routes: [
            GoRoute(path: '/home', builder: (c, s) => const HomePage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/profile', builder: (c, s) => const ProfilePage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/triage', builder: (c, s) => const TriagePage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/me', builder: (c, s) => const MePage()),
          ]),
        ],
      ),
    ],
  );
});
