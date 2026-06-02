import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/nickname_page.dart';
import '../../features/auth/presentation/pet_status_page.dart';
import '../../features/content/presentation/content_detail_page.dart';
import '../../features/content/presentation/home_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/profile/presentation/growth_archive_page.dart';
import '../../features/profile/presentation/pet_profile_create_page.dart';
import '../../features/profile/presentation/pet_profile_edit_page.dart';
import '../../features/profile/presentation/profile_onboarding_page.dart';
import '../../features/triage/presentation/dev_triage_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../features/triage/presentation/triage_upload_page.dart';
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
      // 宠物档案创建表单（Story 2.2）。受控路由（/profile/ 前缀，游客被门控）。
      GoRoute(path: '/profile/create', builder: (c, s) => const PetProfileCreatePage()),
      // 宠物档案编辑（Story 2.8）。两入口（档案 Tab 信息卡 /「我的」Tab）复用同一页。
      GoRoute(path: '/profile/edit', builder: (c, s) => const PetProfileEditPage()),
      // @dev 自测入口（Story 1.4 F3）：不从 UI 链接，仅供手动深链触发登录引导。
      GoRoute(path: '/dev/login-guide', builder: (c, s) => const DevLoginGuidePage()),
      // @dev 自测入口（Story 4.1 F2）：仅供手动深链驱动分诊「提交 → 短轮询」契约（联调）。
      GoRoute(path: '/dev/triage', builder: (c, s) => const DevTriagePage()),
      // AI 分诊上传页（Story 4.3）。受控路由（/triage/ 前缀，游客被门控）；shell 外 push 隐藏 Tab Bar。
      GoRoute(path: '/triage/upload', builder: (c, s) => const TriageUploadPage()),
      // 内容详情（Story 3.3）。shell 外顶层 push（隐藏 Tab Bar）；返回保持 Feed 滚动位置。游客只读可进。
      GoRoute(
        path: '/content/:id',
        builder: (c, s) => ContentDetailPage(postId: int.parse(s.pathParameters['id']!)),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) => AppShell(navigationShell: navigationShell),
        branches: <StatefulShellBranch>[
          StatefulShellBranch(routes: [
            GoRoute(path: '/home', builder: (c, s) => const HomePage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/profile', builder: (c, s) => const GrowthArchivePage()),
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
