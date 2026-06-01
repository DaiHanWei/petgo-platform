import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/onboarding_placeholder_page.dart';
import '../../features/content/presentation/home_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/profile/presentation/profile_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../shared/widgets/app_shell.dart';

/// 应用路由表（Story 1.2 Tab 外壳 + Story 1.3 登录/引导分流路由）。
///
/// 用 [StatefulShellRoute.indexedStack] 承载 4 个 Tab（首页/成长档案/问诊/我的）；
/// 中间「＋」为 [AppShell] 内的凸起按钮，非导航分支。`/login` 自测入口、
/// `/onboarding` 新用户引导占位（Story 1.6 本体）为 shell 外顶层路由。业务路由由各 Epic 追加。
final GoRouter appRouter = GoRouter(
  initialLocation: '/home',
  routes: <RouteBase>[
    GoRoute(path: '/login', builder: (c, s) => const LoginPage()),
    GoRoute(path: '/onboarding', builder: (c, s) => const OnboardingPlaceholderPage()),
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
