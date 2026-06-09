import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/nickname_page.dart';
import '../../features/auth/presentation/pet_status_page.dart';
import '../../features/content/domain/content_type.dart';
import '../../features/content/presentation/content_detail_page.dart';
import '../../features/content/presentation/home_page.dart';
import '../../features/content/presentation/publish_landing_page.dart';
import '../../features/me/presentation/language_settings_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/me/presentation/settings_page.dart';
import '../../features/profile/presentation/growth_archive_page.dart';
import '../../features/profile/presentation/milestone_list_page.dart';
import '../../features/profile/domain/pet_profile.dart';
import '../../features/notify/data/push_permission_providers.dart';
import '../../features/profile/presentation/pet_profile_create_page.dart';
import '../../features/profile/presentation/day_detail_page.dart';
import '../../features/profile/presentation/pet_profile_edit_page.dart';
import '../../features/profile/presentation/profile_created_celebration_page.dart';
import '../../features/profile/presentation/profile_onboarding_page.dart';
import '../../features/consult/presentation/consult_conversation_page.dart';
import '../../features/consult/presentation/consult_entry_page.dart';
import '../../features/consult/presentation/consult_waiting_page.dart';
import '../../features/notify/presentation/notification_center_page.dart';
import '../../features/onboarding/presentation/mint_onboarding_page.dart';
import '../../features/gath/presentation/gath_page.dart';
import '../../features/profile/presentation/pet_card_page.dart';
import '../../features/vet/presentation/vet_conversation_page.dart';
import '../../features/triage/presentation/dev_triage_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../features/triage/presentation/triage_upload_page.dart';
import '../../features/vet/presentation/vet_login_page.dart';
import '../../features/vet/presentation/vet_workbench_shell.dart';
import '../../shared/widgets/app_shell.dart';

/// 根 Navigator key（供拦截器在 401 续期失败后于全局弹登录引导，Story 1.5 F3）。
final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

/// 未登录游客**不可**直接进入的受控路由前缀（FR-19 门控）。
const Set<String> _controlledLocations = {'/profile', '/triage', '/me', '/consult', '/notifications', '/publish'};

/// 应用路由（provider 化：redirect 可读登录态做受控路由门控）。
///
/// `StatefulShellRoute.indexedStack` 承载 4 个 Tab；`/login`、`/onboarding`、`/dev/*` 为
/// shell 外顶层路由。游客深链受控路由 → redirect 回 `/home`（Tab 点击门控由 [AppShell] 单一入口处理）。
final Provider<GoRouter> routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/home',
    redirect: (context, state) {
      final auth = ref.read(authControllerProvider);
      final loc = state.matchedLocation;
      final isVetRoute = loc == '/vet' || loc.startsWith('/vet/');
      // 兽医登录态：禁止进入用户侧任何路由（反之亦然），命中越权重定向回各自首页（Story 5.1 F2 守卫）。
      if (auth.isVet) {
        // /vet/login 是登录入口，登录后不应停留 → 一律收口到工作台。
        if (!isVetRoute || loc == '/vet/login') return '/vet/workbench';
        return null;
      }
      // 非兽医（用户/游客）不可进兽医工作台等 vet 专属路由（/vet/login 允许，供登录）。
      if (isVetRoute && loc != '/vet/login') return '/home';
      final controlled = _controlledLocations.any((p) => loc == p || loc.startsWith('$p/'));
      if (!auth.isLoggedIn && controlled) return '/home'; // 安全规则只升不降：游客不进受控路由
      return null;
    },
    routes: <RouteBase>[
      GoRoute(path: '/login', builder: (c, s) => const LoginPage()),
      // 兽医账密登录 + 工作台壳（Story 5.1）。与用户侧 5-Tab 隔离：shell 外顶层路由。
      GoRoute(path: '/vet/login', builder: (c, s) => const VetLoginPage()),
      GoRoute(path: '/vet/workbench', builder: (c, s) => const VetWorkbenchShell()),
      // 新用户引导流（PetGo Prototype 全面换肤 · FR-11）：欢迎(Momo) → 创建宠物 → 完成。
      GoRoute(path: '/onboarding', builder: (c, s) => const MintOnboardingPage()),
      // 旧分步引导（昵称 → 宠物状态 → 档案）保留可路由，供后端联动流程复用。
      GoRoute(path: '/onboarding/nickname', builder: (c, s) => const NicknamePage()),
      GoRoute(path: '/onboarding/pet-status', builder: (c, s) => const PetStatusPage()),
      GoRoute(path: '/onboarding/profile', builder: (c, s) => const ProfileOnboardingPage()),
      // 宠物档案创建表单（Story 2.2）。受控路由（/profile/ 前缀，游客被门控）。
      GoRoute(path: '/profile/create', builder: (c, s) => const PetProfileCreatePage()),
      // 建档「创建成功」庆祝页（Story 1.7 R2 · FR-0G · F15）。受控（/profile/ 前缀）。
      // 经 extra 接收刚创建的 PetProfile；主 CTA 串接推送时机（庆祝页后、进首页前，Story 6.4）后进首页。
      GoRoute(
        path: '/profile/created',
        builder: (c, s) {
          final created = s.extra is PetProfile ? s.extra as PetProfile : null;
          if (created == null) {
            // 防御：无数据直达（如刷新/深链）→ 回首页，不崩。
            WidgetsBinding.instance
                .addPostFrameCallback((_) => c.canPop() ? c.pop() : c.go('/home'));
            return const SizedBox.shrink();
          }
          return ProfileCreatedCelebrationPage(
            petName: created.name,
            cardToken: created.cardToken,
            avatarUrl: created.avatarUrl,
            onStartExplore: () async {
              // FR-22D 建档时机（庆祝页后、进首页前）：触发推送权限闸门（Story 6.4）。
              // neverConsulted 传 true 安全——已问诊者其 `alreadyAsked` 守卫已使闸门跳过（取最早、仅一次）。
              final gate = await ref.read(pushPermissionGateProvider.future);
              await gate.maybeRequestAfterProfileCreated(neverConsulted: true);
              if (c.mounted) c.go('/home');
            },
          );
        },
      ),
      // 宠物档案编辑（Story 2.8）。两入口（档案 Tab 信息卡 /「我的」Tab）复用同一页。
      GoRoute(path: '/profile/edit', builder: (c, s) => const PetProfileEditPage()),
      // 成长档案当天详情（Story 2.4 AC6 · F9）。?date=yyyy-MM-dd；受控（/profile/ 前缀）。
      GoRoute(
        path: '/profile/day',
        builder: (c, s) {
          final raw = s.uri.queryParameters['date'];
          final date = raw != null ? DateTime.tryParse(raw) : null;
          if (date == null) {
            WidgetsBinding.instance
                .addPostFrameCallback((_) => c.canPop() ? c.pop() : c.go('/profile'));
            return const SizedBox.shrink();
          }
          return DayDetailPage(date: date);
        },
      ),
      // @dev 自测入口（Story 1.4 F3）：不从 UI 链接，仅供手动深链触发登录引导。
      GoRoute(path: '/dev/login-guide', builder: (c, s) => const DevLoginGuidePage()),
      // @dev 自测入口（Story 4.1 F2）：仅供手动深链驱动分诊「提交 → 短轮询」契约（联调）。
      GoRoute(path: '/dev/triage', builder: (c, s) => const DevTriagePage()),
      // AI 分诊上传页（Story 4.3）。受控路由（/triage/ 前缀，游客被门控）；shell 外 push 隐藏 Tab Bar。
      GoRoute(path: '/triage/upload', builder: (c, s) => const TriageUploadPage()),
      // 兽医咨询入口 + 等待界面（Story 5.3）。受控路由（/consult 前缀，游客被门控）。
      GoRoute(path: '/consult', builder: (c, s) => const ConsultEntryPage()),
      GoRoute(
        path: '/consult/waiting/:id',
        builder: (c, s) => ConsultWaitingPage(sessionId: int.parse(s.pathParameters['id']!)),
      ),
      // 进行中会话界面（Story 5.5）。用户侧 /consult/conversation、兽医侧 /vet/conversation（各自 role 守卫）。
      GoRoute(
        path: '/consult/conversation/:id',
        builder: (c, s) => ConsultConversationPage(sessionId: int.parse(s.pathParameters['id']!)),
      ),
      GoRoute(
        path: '/vet/conversation/:id',
        builder: (c, s) => VetConversationPage(sessionId: int.parse(s.pathParameters['id']!)),
      ),
      // 通知中心（Story 6.6）+ 6.1 深链兜底落点。受控路由（需登录）。
      GoRoute(path: '/notifications', builder: (c, s) => const NotificationCenterPage()),
      // 二级设置页（Story 7.1 · F8）：语言/退出/注销。受控（/me 前缀，需登录）。
      GoRoute(path: '/me/settings', builder: (c, s) => const SettingsPage()),
      // 语言设置（Story 7.2）。
      GoRoute(path: '/me/language', builder: (c, s) => const LanguageSettingsPage()),
      // 宠物聚会 Gath（PetGo Prototype 占位页）。shell 外 push。
      GoRoute(path: '/gath', builder: (c, s) => const GathPage()),
      // 宠物名片 H5 预览（FR-14）。shell 外 push（全屏浏览器外壳观感）。
      GoRoute(path: '/card/preview', builder: (c, s) => const PetCardPage()),
      // 内容详情（Story 3.3）。shell 外顶层 push（隐藏 Tab Bar）；返回保持 Feed 滚动位置。游客只读可进。
      GoRoute(
        path: '/content/:id',
        builder: (c, s) => ContentDetailPage(postId: int.parse(s.pathParameters['id']!)),
      ),
      // 发布深链着陆（Story 6.1 · FR-40）：PET_BIRTHDAY 深链 → 打开统一发布 sheet，可预选成长日历。受控（需登录）。
      GoRoute(
        path: '/publish',
        builder: (c, s) {
          final dateRaw = s.uri.queryParameters['date'];
          return PublishLandingPage(
            preset: s.uri.queryParameters['preset'] == 'growth-calendar'
                ? ContentType.growthMoment
                : null,
            // F9：日历无记录格「+」跳发布预填该事件日期（AC6）。
            presetEventDate: dateRaw != null ? DateTime.tryParse(dateRaw) : null,
          );
        },
      ),
      // 里程碑列表页（壳）（Story 6.1 · FR-42）：MILESTONE_NODE 深链承接；本体属里程碑 mini-epic。受控（/profile/ 前缀）。
      GoRoute(path: '/profile/milestones', builder: (c, s) => const MilestoneListPage()),
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
