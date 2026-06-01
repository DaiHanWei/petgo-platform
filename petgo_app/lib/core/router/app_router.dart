import 'package:go_router/go_router.dart';

import '../../features/content/presentation/home_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/profile/presentation/profile_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../shared/widgets/app_shell.dart';

/// 应用路由表（Story 1.2：5 位 Tab 外壳 + 4 个可导航分支）。
///
/// 用 [StatefulShellRoute.indexedStack] 承载 4 个 Tab（首页/成长档案/问诊/我的）；
/// 中间「＋」为 [AppShell] 内的凸起按钮，非导航分支。业务路由由各 Epic 追加。
final GoRouter appRouter = GoRouter(
  initialLocation: '/home',
  routes: <RouteBase>[
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
