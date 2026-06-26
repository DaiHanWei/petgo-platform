import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../theme/app_theme.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/nickname_page.dart';
import '../../features/auth/presentation/pet_status_page.dart';
import '../../features/content/domain/content_type.dart';
import '../../features/content/presentation/content_detail_page.dart';
import '../../features/content/presentation/home_page.dart';
import '../../features/content/presentation/publish_landing_page.dart';
import '../../features/content/presentation/publish_result_page.dart';
import '../../features/me/presentation/delete_account_page.dart';
import '../../features/me/presentation/language_settings_page.dart';
import '../../features/me/presentation/me_page.dart';
import '../../features/me/presentation/settings_page.dart';
import '../../features/profile/presentation/growth_archive_page.dart';
import '../../features/profile/presentation/milestone_list_page.dart';
import '../../features/profile/domain/pet_profile.dart';
import '../../features/notify/data/push_permission_providers.dart';
import '../../features/onboarding/presentation/splash_page.dart';
import '../../features/profile/presentation/pet_profile_create_page.dart';
import '../../features/profile/presentation/day_detail_page.dart';
import '../../features/profile/presentation/pet_profile_edit_page.dart';
import '../../features/profile/presentation/profile_created_celebration_page.dart';
import '../../features/profile/presentation/profile_onboarding_page.dart';
import '../../features/consult/presentation/consult_case_form_page.dart';
import '../../features/consult/presentation/consult_conversation_page.dart';
import '../../features/consult/presentation/consult_entry_page.dart';
import '../../features/consult/presentation/consult_waiting_page.dart';
import '../../features/notify/presentation/notification_center_page.dart';
import '../../features/gath/presentation/gath_page.dart';
import '../../features/profile/presentation/pet_card_page.dart';
import '../../features/vet/presentation/vet_conversation_page.dart';
import '../../features/triage/presentation/dev_triage_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../features/triage/presentation/triage_upload_page.dart';
import '../../features/vet/domain/vet_inbox_item.dart';
import '../../features/vet/presentation/vet_login_page.dart';
import '../../features/vet/presentation/vet_request_detail_page.dart';
import '../../features/vet/presentation/vet_workbench_shell.dart';
import '../../shared/widgets/app_shell.dart';

/// 根 Navigator key（供拦截器在 401 续期失败后于全局弹登录引导，Story 1.5 F3）。
final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

/// 冷启动深链落点暂存（成长档案分享页 `tailtopia://card/...` → `/profile`）。
/// 冷启动时 app_links 拿到的初始链接存这里，由 [SplashPage] 完成时消费（替代默认 `/home`），
/// 避免与 splash 的 `go('/home')` 抢路由。热启动（app 已活）直接 `router.go`，不经此。
class PendingDeepLinkNotifier extends Notifier<String?> {
  @override
  String? build() => null;

  void set(String? location) => state = location;
}

final NotifierProvider<PendingDeepLinkNotifier, String?> pendingDeepLinkProvider =
    NotifierProvider<PendingDeepLinkNotifier, String?>(PendingDeepLinkNotifier.new);

/// 兽医端主题作用域：给 `/vet/*` 子树注入薄荷主题（spec-vet-mint-theme.md），
/// 与用户侧紫主题物理隔离。
Widget _vetScoped(Widget child) => Theme(data: AppTheme.vet, child: child);

/// 未登录游客**不可**直接进入的受控路由前缀（FR-19 门控）。
const Set<String> _controlledLocations = {'/profile', '/triage', '/me', '/consult', '/notifications', '/publish'};

/// 应用路由（provider 化：redirect 可读登录态做受控路由门控）。
///
/// `StatefulShellRoute.indexedStack` 承载 4 个 Tab；`/login`、`/onboarding`、`/dev/*` 为
/// shell 外顶层路由。游客深链受控路由 → redirect 回 `/home`（Tab 点击门控由 [AppShell] 单一入口处理）。
/// Debug-only 开发直达路由：`--dart-define=DEV_ROUTE=/vet/workbench` 启动即落该屏，
/// 供本地逐屏视觉验收（配合 `DEV_VET=true` 种子兽医态）。release 恒走 /splash。
const String _devRoute = String.fromEnvironment('DEV_ROUTE');

final Provider<GoRouter> routerProvider = Provider<GoRouter>((ref) {
  // 登录态变化(尤其冷启动异步恢复完成)后让 redirect 重跑——否则兽医会话恢复晚于
  // splash→home 跳转时不会被分流到工作台。
  final authRefresh = ValueNotifier<int>(0);
  ref.listen(authControllerProvider, (_, _) => authRefresh.value++);
  ref.onDispose(authRefresh.dispose);
  return GoRouter(
    navigatorKey: rootNavigatorKey,
    refreshListenable: authRefresh,
    initialLocation: kDebugMode && _devRoute.isNotEmpty ? _devRoute : '/splash',
    redirect: (context, state) {
      final auth = ref.read(authControllerProvider);
      final loc = state.matchedLocation;
      // 启动屏（P-01）：先显示品牌过场，由 SplashPage 完成时按角色直达（vet→工作台 / 其余→home）。
      if (loc == '/splash') return null;
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
      // 启动屏（P-01）：initialLocation；动画后转 /home。
      // 启动屏：动效结束时**等会话恢复完成再按角色直达**——兽医直进工作台，其余进 home。
      // 避免「先渲染用户首页再跳兽医工作台」的闪屏（恢复慢则 3s 兜底后跳，余下交 redirect 收口）。
      GoRoute(
        path: '/splash',
        builder: (c, s) => SplashPage(
          onComplete: () async {
            await ref
                .read(authControllerProvider.notifier)
                .ensureRestored()
                .timeout(const Duration(seconds: 3), onTimeout: () {});
            final ctx = rootNavigatorKey.currentContext;
            if (ctx == null || !ctx.mounted) return;
            // 冷启动若由分享页深链唤起 → 落该目标（成长档案）；否则按角色直达。
            // 受控路由门控仍由 redirect 收口（游客 /profile → /home）。
            final pending = ref.read(pendingDeepLinkProvider);
            if (pending != null) {
              ref.read(pendingDeepLinkProvider.notifier).set(null);
              ctx.go(pending);
              return;
            }
            ctx.go(ref.read(authControllerProvider).isVet ? '/vet/workbench' : '/home');
          },
        ),
      ),
      GoRoute(path: '/login', builder: (c, s) => const LoginPage()),
      // 兽医账密登录 + 工作台壳（Story 5.1）。与用户侧 5-Tab 隔离：shell 外顶层路由。
      GoRoute(path: '/vet/login', builder: (c, s) => _vetScoped(const VetLoginPage())),
      GoRoute(path: '/vet/workbench', builder: (c, s) => _vetScoped(const VetWorkbenchShell())),
      // 新用户引导流（TailTopia Prototype 全面换肤 · FR-11）：欢迎(Momo) → 创建宠物 → 完成。
      // [临时·勿提交] B 方案：入口暂指向 Story 1.6 规格流（昵称→状态→分叉），看完改回 MintOnboardingPage。
      GoRoute(path: '/onboarding', redirect: (c, s) => '/onboarding/nickname'),
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
          var created = s.extra is PetProfile ? s.extra as PetProfile : null;
          // Debug 截图钩子（仅 debug + flag）：深链无 extra 时合成代表性档案，让 pet-success 庆祝页可直达。
          if (created == null && kDebugMode && const bool.fromEnvironment('DEV_CELEBRATE')) {
            created = const PetProfile(id: 7001, name: 'Mochi', cardToken: 'dev-token', petType: 'CAT');
          }
          if (created == null) {
            // 防御：无数据直达（如刷新/深链）→ 回首页，不崩。
            WidgetsBinding.instance
                .addPostFrameCallback((_) => c.canPop() ? c.pop() : c.go('/home'));
            return const SizedBox.shrink();
          }
          return ProfileCreatedCelebrationPage(
            petName: created.name,
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
      // 直连问诊病例填写页（Story F）：症状 + 照片，提交才发起 DIRECT 会话。
      GoRoute(path: '/consult/case', builder: (c, s) => const ConsultCaseFormPage()),
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
        builder: (c, s) => _vetScoped(VetConversationPage(sessionId: int.parse(s.pathParameters['id']!))),
      ),
      // 抢单请求详情/预览页（Story 5.2 AC5 · F11）：3 分钟预览计时 + 三态返回。
      GoRoute(
        path: '/vet/request/:id',
        // 正常流程从列表带入 extra(VetInboxItem)；extra 为空时仅出现在 dev 深链（DEV_ROUTE，
        // 生产流程恒带 extra）→ 合成一份代表性富样本（含宠物身份），供逐屏视觉验收完整渲染。
        builder: (c, s) => _vetScoped(VetRequestDetailPage(
          item: s.extra as VetInboxItem? ??
              VetInboxItem(
                sessionId: int.parse(s.pathParameters['id']!),
                source: 'AI_UPGRADE',
                aiDangerLevel: 'YELLOW',
                symptomPreview:
                    'Muntah busa putih 2x semalam, jadi lebih lemas & kurang nafsu makan',
                imageCount: 2,
                waitingElapsedSeconds: 45,
                petName: 'Mochi',
                petSpecies: 'CAT',
                petAgeMonths: 12,
                ownerHandle: 'aditya.kurniawan',
              ),
        )),
      ),
      // 通知中心（Story 6.6）+ 6.1 深链兜底落点。受控路由（需登录）。
      GoRoute(path: '/notifications', builder: (c, s) => const NotificationCenterPage()),
      // 二级设置页（Story 7.1 · F8）：语言/退出/注销。受控（/me 前缀，需登录）。
      GoRoute(path: '/me/settings', builder: (c, s) => const SettingsPage()),
      // 语言设置（Story 7.2）。
      GoRoute(path: '/me/language', builder: (c, s) => const LanguageSettingsPage()),
      // 账号注销整页（P-43 · Story 7.3）。受控（/me 前缀，需登录）。
      GoRoute(path: '/me/delete-account', builder: (c, s) => const DeleteAccountPage()),
      // 宠物聚会 Gath（TailTopia Prototype 占位页）。shell 外 push。
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
            // 里程碑「去发布」回填（Story 8.4）：携里程碑 code，发布成功后自动打卡。
            milestoneCode: s.uri.queryParameters['milestoneCode'],
          );
        },
      ),
      // 发布结果三屏（P-39 成功 / P-39b 审核中 / P-39c 被拒）。受控（/publish 前缀，需登录）；
      // 正常流程由发布 sheet 提交后 push（带 extra=PublishResultArgs）；extra 为空仅 DEV 深链 → 用样例渲染。
      GoRoute(
        path: '/publish/reviewing',
        builder: (c, s) => PublishReviewingPage(
            args: s.extra is PublishResultArgs ? s.extra as PublishResultArgs : PublishResultArgs.sample),
      ),
      GoRoute(
        path: '/publish/done',
        builder: (c, s) => PublishDonePage(
            args: s.extra is PublishResultArgs ? s.extra as PublishResultArgs : PublishResultArgs.sample),
      ),
      GoRoute(
        path: '/publish/rejected',
        builder: (c, s) => PublishRejectedPage(
            args: s.extra is PublishResultArgs
                ? s.extra as PublishResultArgs
                : PublishResultArgs.sampleRejected),
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
