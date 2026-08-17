import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:posthog_flutter/posthog_flutter.dart';

import '../analytics/analytics.dart';
import 'deep_link_routes.dart';
import '../theme/app_theme.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/domain/user_state.dart';
import '../../features/auth/presentation/dev_login_guide_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/shop/address/presentation/address_book_page.dart';
import '../../features/shop/address/presentation/address_form_page.dart';
import '../../features/shop/presentation/cart_page.dart';
import '../../features/shop/presentation/checkout_page.dart';
import '../../features/shop/presentation/product_detail_page.dart';
import '../../features/shop/presentation/refund_method_page.dart';
import '../../features/shop/presentation/return_request_page.dart';
import '../../features/shop/presentation/shop_order_detail_page.dart';
import '../../features/shop/presentation/toko_page.dart';
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
import '../../features/social/presentation/blocked_users_page.dart';
import '../../features/support/presentation/my_tickets_page.dart';
import '../../features/support/presentation/ticket_compose_page.dart';
import '../../features/support/presentation/ticket_detail_page.dart';
import '../../features/support/presentation/csat_page.dart';
import '../../features/pawcoin/presentation/pawcoin_page.dart';
import '../../features/pawcoin/presentation/recharge_page.dart';
import '../../features/order/presentation/order_list_page.dart';
import '../../features/order/presentation/order_detail_page.dart';
import '../../features/refund/domain/refund_request.dart';
import '../../features/refund/presentation/refund_list_page.dart';
import '../../features/refund/presentation/refund_choose_page.dart';
import '../../features/refund/presentation/refund_pawcoin_success_page.dart';
import '../../features/refund/presentation/refund_account_page.dart';
import '../../features/refund/presentation/refund_status_page.dart';
import '../../features/profile/presentation/growth_archive_page.dart';
import '../../features/profile/presentation/health_list_page.dart';
import '../../features/profile/presentation/id_card_create_page.dart';
import '../../features/profile/presentation/id_card_detail_page.dart';
import '../../features/profile/presentation/id_card_page.dart';
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
import '../../features/consult/presentation/vet_request_confirm_page.dart';
import '../../features/consult/presentation/vet_timed_pay_page.dart';
import '../../features/consult/presentation/vet_waiting_page.dart';
import '../../features/notify/presentation/notification_center_page.dart';
import '../../features/gath/presentation/gath_page.dart';
import '../../features/profile/presentation/pet_card_page.dart';
import '../../features/vet/domain/vet_workbench_lists.dart';
import '../../features/vet/presentation/vet_conversation_page.dart';
import '../../features/vet/presentation/vet_income_page.dart';
import '../../features/vet/presentation/vet_history_detail_page.dart';
import '../../features/triage/presentation/dev_triage_page.dart';
import '../../features/triage/presentation/triage_page.dart';
import '../../features/triage/presentation/triage_snapshot_page.dart';
import '../../features/triage/presentation/triage_upload_page.dart';
import '../../features/vet/domain/vet_inbox_item.dart';
import '../../features/vet/presentation/vet_login_page.dart';
import '../../features/vet/presentation/vet_request_detail_page.dart';
import '../../features/vet/presentation/vet_workbench_shell.dart';
import '../../shared/widgets/app_shell.dart';
import '../../shared/widgets/bottom_tab_bar.dart';

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

/// 冷启动落地路径 → 埋点里的**页面产品名**（Story 6.1）。
///
/// 与 `app_shell.dart` 里 Tab 切换用的 `AppTab.analyticsName` **同一套字面量** ——
/// 否则「冷启动落在 Diary」与「切到 Diary」在看板上会被算成两个不同页面。
/// 路径本身（`/profile`）不适合直接送埋点：产品看不出它是 Diary。
const Map<String, String> _landingTabNames = {
  '/profile': 'diary',
  '/home': 'social',
  '/vet/workbench': 'vet_workbench',
};

/// 未登录游客**不可**直接进入的受控路由前缀（FR-19 门控）。
///
/// ⚠️ **安全默认是「拦」**：前缀匹配（`loc == p || loc.startsWith('$p/')`）保持默认拦截，
/// 新增的任何 `/profile/*`、`/me/*` … 子页**自动受控，无需逐个登记**。
const Set<String> _controlledLocations = {
  '/profile',
  '/triage',
  '/me',
  '/consult',
  '/notifications',
  '/publish',
};

/// 受控前缀里的**精确例外**（V1.1.2 Story 2.4 · AD-7 Rule 1）：游客可直接进入的完整路径。
///
/// 目前只有 Diary 主页 `/profile` —— 它是游客的冷启动落地页（FR-78/FR-80 种草页），
/// 而**建档 / 编辑档案 / 当天详情 / 里程碑列表等子页仍受控**。
///
/// ⚠️ 三条硬约束（违反即为把安全默认反转）：
/// 1. **只接受完整路径字面量**：判定用 `contains(loc)`，**不得**出现 `startsWith` 或通配；
/// 2. **不得**为了放行某个子页而把 `/profile/xxx` 塞进来 —— 该子页应当自证不需要门控
///    （如 Story 2.2 的示例内容详情走**页内推入**，根本不落在受控前缀下）；
/// 3. 反向做法（改成「列举受控子页」）明确禁止：新增子页忘登记就会对游客敞开。
const Set<String> _controlledExactExceptions = {'/profile'};

/// 应用路由（provider 化：redirect 可读登录态做受控路由门控）。
///
/// `StatefulShellRoute.indexedStack` 承载 4 个 Tab；`/login`、`/onboarding`、`/dev/*` 为
/// shell 外顶层路由。游客深链受控路由 → redirect 回 `/home`（Tab 点击门控由 [AppShell] 单一入口处理）。
/// Debug-only 开发直达路由：`--dart-define=DEV_ROUTE=/vet/workbench` 启动即落该屏，
/// 供本地逐屏视觉验收（配合 `DEV_VET=true` 种子兽医态）。release 恒走 /splash。
const String _devRoute = String.fromEnvironment('DEV_ROUTE');

/// Tab 分支根页（与 [AppTab] 一一对应；穷尽 switch，新增 Tab 时编译期报错，不会静默漏配）。
Widget _tabRootPage(AppTab tab) => switch (tab) {
  AppTab.profile => const GrowthArchivePage(),
  AppTab.triage => const TriagePage(),
  AppTab.home => const HomePage(),
  AppTab.me => const MePage(),
};

/// 冷启动深链是否需要**先铺一层底座页再叠上去**（纯函数，L0 可测）。
///
/// 🐛 2026-08-07 iOS 实机：杀后台 → 点系统推送 → 落通知中心，**返回不了**（无返回箭头、
/// 左滑手势也无效）；而从 Feed 进同一个页面一切正常。
///
/// 根因不在通知中心页，而在这里：冷启动的深链落点当时是无条件 `go(pending)`。`go` 会**替换
/// 整个路由栈**，于是 `/notifications` 成了栈里唯一一页 —— `Navigator.canPop()` 为 false，
/// `AppBar` 便不会自动生成返回箭头，iOS 的边缘返回手势同样因为没有上一页而失效。用户被困死，
/// 只能杀进程。
///
/// 从 Feed 进去之所以正常，是因为那是 `push`（叠在 shell 之上，下面有页可回）。
/// App 已在运行时的推送点击也走 `push`（见 `push_service.dart` 的 `_navigate`）——
/// **只有冷启动这一条路径漏了**，而它恰恰是最常见的点推送场景。
///
/// 影响面不止通知中心：`/content/:id`、`/consult/conversation/:id`、`/profile/milestones`、
/// `/publish` 等**所有 shell 外的深链落点**在冷启动下都是死路。
///
/// 判定与 `_navigate` 同口径：Tab 分支根必须 `go`（`push` 会二次构建 StatefulShellRoute →
/// GlobalKey 撞车 → release 白屏，见 [DeepLinkRoutes.shellTabRoots] 的血泪注释）；
/// `/vet/*` 也走 `go`（靠角色守卫收口到工作台）。其余一律「先 go 底座，再 push 目标」。
bool deepLinkNeedsBaseRoute(String location) =>
    !DeepLinkRoutes.isShellTabRoot(location) && !location.startsWith('/vet');

/// [location] 在 [auth] 登录态下是否会被顶层 redirect 改写（纯函数，L0 可测；
/// 逻辑镜像下方 GoRouter `redirect`，改守卫时**必须同步维护**）。
///
/// PR#34 finding #8：`push` 同样走 redirect 管线，且**改写后的目标以 push 方式入栈**。
/// 游客态点推送（如会话过期 → 401 → toGuest 之后）深链 `/notifications` 会被改写成
/// `/home`——shell 分支根被 push 即二次构建 StatefulShellRoute → GlobalKey 撞车 →
/// release 白屏 + Tab 永久失效（bug 20260729 同机制）。
/// 因此「无法证明落点不被改写就不许 push」：本判定为 true 时调用方必须用 `go`（或放弃叠加）。
bool redirectWouldRewrite(AuthState auth, String location) {
  final path = Uri.parse(location).path; // redirect 比对的是 matchedLocation（无 query）
  final isVetRoute = path == '/vet' || path.startsWith('/vet/');
  if (auth.isVet) return !isVetRoute || path == '/vet/login';
  if (isVetRoute && path != '/vet/login') return true;
  final controlled =
      _controlledLocations.any((p) => path == p || path.startsWith('$p/')) &&
          !_controlledExactExceptions.contains(path);
  return !auth.isLoggedIn && controlled;
}

/// 冷启动落深链：需要底座的先 `go` 到落地矩阵目标，再把深链 `push` 上去（这样才可返回）。
///
/// 底座取**落地矩阵目标**而不是写死 `/home`：游客/已建档用户落 Diary、其余落 Social，
/// 返回后看到的是他本该看到的那一屏，与不点推送直接冷启动完全一致。
void _goDeepLink(BuildContext ctx, Ref ref, String location) {
  if (!deepLinkNeedsBaseRoute(location)) {
    ctx.go(location);
    return;
  }
  ctx.go(appUserStateOf(ref.read(authControllerProvider)).landingLocation);
  // ⚠️ **必须等下一帧再 push，不能和 go 同帧发出**（2026-08-07 实测）：go_router 的 `go`
  // 走的是 RouteInformationParser 的**异步**解析，调用返回时 `currentConfiguration` 还是旧值。
  // 同帧接着 push，等于把目标叠在**旧栈**（/splash）上，随后 go 的解析落地又把整个栈替换掉 ——
  // 净效果是深链被吃掉，用户只落到底座页。必须让 go 先落定。
  WidgetsBinding.instance.addPostFrameCallback((_) {
    final c = rootNavigatorKey.currentContext;
    if (c == null || !c.mounted) return;
    // PR#34 finding #8（孪生位）：游客/角色不符时受控深链会被 redirect 改写到 shell 根，
    // push 它 = GlobalKey 撞车白屏。底座已是该状态的正确落地页，直接放弃叠加。
    if (redirectWouldRewrite(ref.read(authControllerProvider), location)) return;
    c.push(location);
  });
}

/// FR-91 迟到纠正的**判定结果**（纯数据，便于单测逐条锁 7 条约束）。
enum LateCorrectionOutcome {
  /// 未武装或额度已用完（约束①④）。
  notArmed,

  /// 🔴 用户已自行导航离开兜底落地页 —— **一律不纠正**（AC4 / NFR-15，优先级高于纠正）。
  userMovedOn,

  /// 重判结果与兜底目标相同（约束⑥：恢复失败=真游客 → 不打扰）。
  targetUnchanged,

  /// 应当纠正，跳向重判出的目标。
  correct,
}

/// 迟到纠正的**纯判定**（无副作用，可单测）。
///
/// - [armed]：兜底送去的目标路径；null = 未武装 / 额度已用完
/// - [currentPath]：**当前路由路径**。🔴 必须用路径比对，**不得**用路由栈深度等间接信号 ——
///   用户在同页内开弹层 / 进详情再返回，栈深会变但人没走，会误判成"已离开"
/// - [recomputedTarget]：按恢复完成后的真实状态重算出的落地目标
LateCorrectionOutcome resolveLateCorrection({
  required String? armed,
  required String currentPath,
  required String recomputedTarget,
}) {
  if (armed == null) return LateCorrectionOutcome.notArmed;
  if (currentPath != armed) return LateCorrectionOutcome.userMovedOn;
  if (recomputedTarget == armed) return LateCorrectionOutcome.targetUnchanged;
  return LateCorrectionOutcome.correct;
}

/// FR-91「超时兜底的迟到纠正」状态（V1.1.2 Story 7.4）。**每次冷启动最多纠正一次。**
///
/// 公开而非私有，仅为让「只纠正一次」的额度机制可被单测锁定（见 late_landing_correction_test）。
///
/// 为什么需要它：`ensureRestored()` 的 `.timeout()` **只停止等待、不取消底层请求** ——
/// 恢复请求继续跑，完成后写回 `AuthState`。所以超时兜底送去的落地页**是会变化的中间态，
/// 不是终态**。兽医能自愈（redirect 里的角色隔离守卫会收口），但**已登录的 A·未建档与
/// B/C 不能** —— 门控条件是 `!auth.isLoggedIn && controlled`，已登录不命中；redirect 里
/// 没有任何分支会把已登录用户从 Diary 移到 Social。本机制补的就是这一环。
class LateLandingCorrection {
  /// 非 null = 本次冷启动的落地**由超时兜底产生**，且尚未纠正。值为兜底送去的目标路径。
  String? _armed;
  bool _used = false;

  /// 记录「这次落地是兜底产物」。仅超时路径调用。
  void arm(String fallbackTarget) {
    if (_used) return;
    _armed = fallbackTarget;
  }

  /// 本次冷启动内待纠正的目标；null = 未武装或已用掉（约束④：只纠正一次）。
  String? get armed => _used ? null : _armed;

  /// 放弃纠正（用户已离开 / 重判结果不变），但不消耗"只纠正一次"的额度。
  void disarm() => _armed = null;

  /// 消耗额度：此后本次冷启动内不再干预（约束④）。
  void consume() {
    _used = true;
    _armed = null;
  }
}

final Provider<GoRouter> routerProvider = Provider<GoRouter>((ref) {
  // 登录态变化(尤其冷启动异步恢复完成)后让 redirect 重跑——否则兽医会话恢复晚于
  // splash→home 跳转时不会被分流到工作台。
  final authRefresh = ValueNotifier<int>(0);
  ref.listen(authControllerProvider, (_, _) => authRefresh.value++);
  ref.onDispose(authRefresh.dispose);

  // FR-91（Story 7.4）：超时兜底的迟到纠正。
  final lateCorrection = LateLandingCorrection();
  late final GoRouter router;

  /// 🔴 AC4 补强（code-review 2026-08-04）：**只要用户离开过兜底落地页，就永久放弃纠正。**
  ///
  /// 改前只在恢复完成的那一刻比对一次当前路径，于是分不清「一直没走」和「走了又回来」：
  /// 兜底落 `/profile` → 用户点 Social → 再点回 Diary（`goBranch` 会把 path 真的改回 `/profile`）
  /// → 恢复完成 → 判定「他还在原地」→ 把他从**自己刚刚主动选定的 Diary** 拽到 Social。
  ///
  /// 现在改为监听路由变化：一旦当前路径偏离过 armed 目标就 `disarm()` —— 走过就不回头。
  /// 兜底那次 `ctx.go(target)` 自身落点**等于** armed，所以不会自我误伤。
  void onNavigationAwayFromFallback() {
    final armed = lateCorrection.armed;
    if (armed == null) return;
    if (router.routerDelegate.currentConfiguration.uri.path != armed) {
      lateCorrection.disarm();
    }
  }

  /// 恢复 future 真正完成后（成功或失败都算完成 —— 约束②，不是固定延时）重判一次落地目标。
  void lateCorrect() {
    final armed = lateCorrection.armed;
    final ctx = rootNavigatorKey.currentContext;
    if (ctx == null || !ctx.mounted) return;

    // 🔴 AC4 补强（code-review 2026-08-04）：**用户正在做别的事时一律放手。**
    //
    // 根 Navigator 还能 pop ⇒ 上面压着一层用户自己打开的东西（`showDialog` 默认
    // `useRootNavigator: true`，强登录弹窗、软登录浮层都在这一层）。此时纠正会**无声关掉**
    // 它 —— 比落错页严重得多，正是 AC4 要防的伤害。
    //
    // ⚠️ 这不是 AC4 禁止的「用路由栈深度判断用户是否离开」：身份判定仍然是**路径比对**
    // （见 [resolveLateCorrection]）。这里只是额外加一个「人是否正忙」的免打扰条件 ——
    // 方向恰好相反：栈上有东西 ⇒ 更不该动他。
    if (rootNavigatorKey.currentState?.canPop() ?? false) {
      lateCorrection.disarm();
      return;
    }

    final auth = ref.read(authControllerProvider);
    final state = appUserStateOf(auth);
    final corrected = state.landingLocation;
    // 🔴 安全攸关（AC4 / NFR-15）：判定用**当前路由路径**比对，不得用路由栈深度等间接信号。
    final outcome = resolveLateCorrection(
      armed: armed,
      currentPath: router.routerDelegate.currentConfiguration.uri.path,
      recomputedTarget: corrected,
    );
    switch (outcome) {
      case LateCorrectionOutcome.notArmed:
        return;
      case LateCorrectionOutcome.userMovedOn:
      case LateCorrectionOutcome.targetUnchanged:
        lateCorrection.disarm();
        return;
      case LateCorrectionOutcome.correct:
        break;
    }
    // 走到这里 outcome 必为 correct ⇒ armed 非空（notArmed 已在上面 return）。
    final from = armed!;

    lateCorrection.consume();
    // AC6：纠正后的落地**也报一次 T-1**，用 `corrected_from` 与兜底那次区分。
    // 事件名沿用既有 `app_launch_landed_on_tab`，**不新造** —— 否则 Story 6.1 已交付的
    // 看板口径会断。
    final tabName = _landingTabNames[corrected] ?? 'other';
    Analytics.capture('app_launch_landed_on_tab', {
      'tab': tabName,
      'user_state': state.wire,
      // ⚠️ 送**产品名**而不是路由路径原文（code-review 2026-08-04）：同一次上报里的 `tab`
      // 已经经 [_landingTabNames] 转过（产品看不出 `/profile` 是 Diary），`corrected_from`
      // 若回退成 `/profile`，看板上两个属性根本对不起来；7-4 的强制护栏也写明
      // 「不得把路径原文塞进属性」。
      'corrected_from': _landingTabNames[from] ?? 'other',
    });
    Analytics.screen('${tabName}_page');
    ctx.go(corrected);
  }

  router = GoRouter(
    navigatorKey: rootNavigatorKey,
    // PostHog 自动页面追踪：路由跳转 → $screen 事件（页面名取自路由 path）。
    observers: [PosthogObserver()],
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
      // 默认受控 + 精确例外（AD-7 Rule 1）：先按前缀判定受控，再看是否命中精确例外。
      final controlled =
          _controlledLocations.any((p) => loc == p || loc.startsWith('$p/')) &&
          !_controlledExactExceptions.contains(loc);
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
          // 会话恢复（`/me`）**由 splash 在首帧即触发，与动效并行**（Story 7.3 / 决策 C-4）。
          // 改前是在下面的 onComplete 里才发起 —— 动效播完才开始取数，白等一个动效时长。
          // `ensureRestored()` 返回共享 future，故这里只是提早触发，不会打两次 `/me`。
          prepareSession: () => ref.read(authControllerProvider.notifier).ensureRestored(),
          onComplete: () async {
            // 🔴 **这里不再二次等待**（code-review 2026-08-04）。
            //
            // 改前是 `await restore.timeout(const Duration(seconds: 5))`，而 splash 自己已经
            // 有一个 5s 兜底（`readyDeadline`）—— 两段**串联**成最坏 **10s**：用户在紫屏上停
            // 10 秒、慢网提示可读 7.7s，而决策 D-2 的口径是「从冷启动起算 5s」、可读 2.7s。
            // 更隐蔽的连带：恢复若在 5~10s 之间完成，`timedOut` 会保持 false ⇒ 既不打
            // `restore_timeout` 标记（AC6 的兜底发生率不可观测），也不武装 FR-91 迟到纠正
            // ⇒ **7-4 的核心机制在最常见的慢网区间形同死代码**。
            //
            // 现在等待预算**只由 splash 持有**（单一时间源）：本回调被调到时，要么 splash 已
            // 就绪（`isRestored == true`），要么 splash 用尽 5s 预算 force 放行
            // （`isRestored == false` ⇒ 即超时兜底态）。同步读一个标志即可，一秒都不必再等。
            // ⚠️ 不要改回「自己再算一次剩余时间」：那会引入第二个时间源（`DateTime.now()`
            //    是真实时钟，与 Timer 的时钟在测试里根本不同步，写出来的回归测试是假的）。
            //
            // ⚠️ 恢复**不会**因为不等它而被取消 —— 晚到仍会写回 AuthState，
            //    Story 7.4 的迟到纠正正建立在这一既有行为之上，不要"顺手"改成可取消。
            final notifier = ref.read(authControllerProvider.notifier);
            final restore = notifier.ensureRestored();
            final timedOut = !notifier.isRestored;
            final ctx = rootNavigatorKey.currentContext;
            if (ctx == null || !ctx.mounted) return;
            // 🔴 位置守卫（code-review 2026-08-04）：**只有还停在启动屏时才有资格决定落地。**
            //
            // 缺这一句的后果：`app.dart` 的 `uriLinkStream` 分支在 splash 期间命中时会直接
            // `router.go(深链目标)`，而本回调随后无条件 `ctx.go(落地矩阵目标)` 把它覆盖掉 ——
            // 用户点了分享名片，却被送到默认 Tab。交棒时机本轮从 4500ms 压到 1720ms 之后，
            // 这个窗口反而更容易被撞上。
            if (router.routerDelegate.currentConfiguration.uri.path != '/splash') {
              return;
            }
            // 分流顺序（AD-8）：pending 深链 > 按用户状态的落地矩阵。
            // 冷启动若由分享页深链唤起 → 落该目标（游客点名片深链也落 Diary 游客态：
            // `/profile` 已在门控精确例外集里，不再被 redirect 弹回 Social）。
            final pending = ref.read(pendingDeepLinkProvider);
            if (pending != null) {
              ref.read(pendingDeepLinkProvider.notifier).set(null);
              // 约束⑤：深链唤起的冷启动**不纠正** —— 深链优先级最高（与 AD-8 分流顺序一致）。
              // 此处不武装 lateCorrection，故恢复晚到也不会把用户从深链目标拽走。
              _goDeepLink(ctx, ref, pending);
              return;
            }
            // 落地 Tab 按**当时状态实时判定**，不持久化「上次落在哪」（宠物状态会变，记住反而错）。
            final auth = ref.read(authControllerProvider);
            final state = appUserStateOf(auth);
            final target = state.landingLocation;
            // T-1 app_launch_landed_on_tab（Story 6.1）：本版本改了落地页矩阵，
            // 需要度量各状态实际落在哪。`tab` 用产品名（diary/social/vet_workbench），
            // 不用路由路径 —— 产品看不出 `/profile` 是 Diary。
            final tabName = _landingTabNames[target] ?? 'other';
            Analytics.capture('app_launch_landed_on_tab', {
              'tab': tabName,
              'user_state': state.wire,
              // AC6：超时兜底态与真实游客态**都会上报 user_state=guest**，混在一起则
              // 「兜底发生率」无法统计（PRD OQ-23 的口径要求）。故兜底那次带此标记。
              if (timedOut) 'restore_timeout': true,
            });
            // 落地的那一屏同样要有浏览事件：屏名与 Tab 切换用**同一套**字面量。
            Analytics.screen('${tabName}_page');
            ctx.go(target);
            // FR-91：仅超时路径武装迟到纠正（约束①：正常路径的落地已是正确结果，不需重判）。
            if (timedOut) {
              lateCorrection.arm(target);
              // 约束②：以**恢复 future 完成**为时机（非固定延时）。失败也算完成 —— 走约束⑥。
              //
              // ⚠️ 用 `whenComplete` 而不是 `.then(...).catchError(...)`
              // （code-review 2026-08-04）：`_restoreSession()` 内部已 catch-all，这条 future
              // **永不 error** ⇒ 原来的 `catchError` 是死路径；更糟的是它挂在 `.then` 的结果上，
              // 万一 `lateCorrect()` 自己抛错就会**再调一次**（有 `consume()` 兜底所以当前无害，
              // 但结构是错的）。`whenComplete` 无论成功失败都只调一次，正好就是约束②要的语义。
              restore.whenComplete(lateCorrect);
            }
          },
        ),
      ),
      GoRoute(path: '/login', builder: (c, s) => const LoginPage()),

      // ===== Toko（V1.4.0 Story 1.6，FR-93 / FR-93A）=====
      // 🔒 游客可直接进入：靠【不在 _controlledLocations 白名单里】达成，零安全规则改动。
      //    这与 V1.1.2 FR-78「未登录点击非落地 Tab 触发登录引导」有意不同——商品浏览是
      //    转化漏斗最上层，用登录墙拦截会直接杀掉转化。登录引导推迟到加购（Epic 3）。
      // 🔴 Tab 位序归 DEP-1 未拍板、图标归 DEP-2 未交付 → 本版本【只挂路由，不动 AppTab 枚举】
      //    （bottom_tab_bar.dart 属并行契约 C 类）。Tab 接入待 DEP-1 闭合后单独处理。
      // `?category=` 只被 FR-110 的品类跳转注入（Story 9.1）：健康记录类型 → 品类，
      // 系统生成、无 SKU。非法值由 ShopCategory.fromApi 落回 null（= 全部精选）。
      GoRoute(
        path: '/shop',
        builder: (c, s) => TokoPage(initialCategory: s.uri.queryParameters['category']),
      ),
      // 商品详情（Story 1.7）。同样对游客开放——不在 _controlledLocations 里。
      GoRoute(
        path: '/shop/products/:token',
        // `?from=` 带入归因来源（Story 3.10）：商品从哪个入口进的，只有跳转那一刻知道。
        builder: (c, s) => ProductDetailPage(
          token: s.pathParameters['token']!,
          entrySource: s.uri.queryParameters['from'],
        ),
      ),
      // 购物车（Story 3.6）。🔒 **有意不放进 _controlledLocations**：门控在页面内部
      //    （游客渲染「登录后查看」空态 + 软性引导），而不是 redirect 弹走。
      //    redirect 会把游客直接甩回 /home，等于告诉他「这里没有购物车」——
      //    而真相是「登录后就有」，这一句差别就是 FR-0B 软性引导存在的理由。
      //    页面本身不发任何 /me 请求，游客态零数据暴露。
      GoRoute(path: '/shop/cart', builder: (c, s) => const CartPage()),
      // 结算页（Story 3.7）。🔒 与购物车同理：门控在页内（本页只在已登录态可达 ——
      //    入口是购物车页的 Checkout 按钮，而游客的购物车页根本不渲染那个按钮）。
      GoRoute(path: '/shop/checkout', builder: (c, s) => const CheckoutPage()),
      // 电商订单详情（Story 3.8）。token 寻址（不可枚举）；越权与不存在同为后端 404。
      GoRoute(
        path: '/shop/orders/:token',
        builder: (c, s) => ShopOrderDetailPage(orderToken: s.pathParameters['token']!),
      ),
      // 退货申请页（Story 5.7）。入口在订单详情；已有进行中申请时页面自己渲染置灰态
      // （UX-DR3）而不是 redirect —— 用户需要知道「已在处理中」，而不是被弹走。
      GoRoute(
        path: '/shop/orders/:token/return',
        builder: (c, s) => ReturnRequestPage(orderToken: s.pathParameters['token']!),
      ),
      // 退款方式选择页（Story 5.8）。token 寻址（退货申请的不可枚举 token）。
      GoRoute(
        path: '/shop/returns/:token/refund-method',
        builder: (c, s) => RefundMethodPage(returnToken: s.pathParameters['token']!),
      ),
      // ⏳ 退货进度页（Story 5.9）路由暂不挂载：UX-DR5 视觉稿未交付，
      //    AC 写死「实现前不得自行发挥」。后端与数据层已就绪，补稿后只差这一页。
      // 地址簿（Story 2.4）。🔒 挂在 /me 前缀下 —— 它已在 _controlledLocations 里，
      // 游客访问自动重定向。地址是 PII，与 Toko 的游客开放策略正好相反。
      GoRoute(path: '/me/addresses', builder: (c, s) => const AddressBookPage()),
      GoRoute(path: '/me/addresses/new', builder: (c, s) => const AddressFormPage()),
      GoRoute(
        path: '/me/addresses/:token',
        builder: (c, s) => AddressFormPage(token: s.pathParameters['token']),
      ),
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
            created = const PetProfile(
              id: 7001,
              name: 'Mochi',
              cardToken: 'dev-token',
              petType: 'CAT',
            );
          }
          if (created == null) {
            // 防御：无数据直达（如刷新/深链）→ 回首页，不崩。
            WidgetsBinding.instance.addPostFrameCallback(
              (_) => c.canPop() ? c.pop() : c.go('/home'),
            );
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
      // 宠物身份证详情（Story 6.2 · FR-49B）。受控（/profile/ 前缀，游客被门控）。
      GoRoute(path: '/profile/id-card', builder: (c, s) => const IdCardPage()),
      // Story 6-7 多卡：建卡器（字面量在前，避免被 :id 吞）+ 单卡详情。
      GoRoute(path: '/profile/id-cards/create', builder: (c, s) => const IdCardCreatePage()),
      GoRoute(
        path: '/profile/id-cards/:id',
        builder: (c, s) => IdCardDetailPage(cardId: int.parse(s.pathParameters['id']!)),
      ),
      // 健康记录列表（Story 7.2 · FR-45B）。受控（/profile/ 前缀，游客被门控）。
      // ?add=VACCINE|DEWORM|...：进页自动弹预选类型的添加表单（bug 20260729-406，健康类里程碑直跳）。
      // ?focus=<记录 id>：进页滚到该条并短暂高亮（Diary 时间线类④ 点击 → 定位到对应条目）。
      GoRoute(
        path: '/profile/health',
        builder: (c, s) => HealthListPage(
          presetAddType: s.uri.queryParameters['add'],
          focusRecordId: int.tryParse(s.uri.queryParameters['focus'] ?? ''),
        ),
      ),
      // 成长档案当天详情（Story 2.4 AC6 · F9）。?date=yyyy-MM-dd；受控（/profile/ 前缀）。
      GoRoute(
        path: '/profile/day',
        builder: (c, s) {
          final raw = s.uri.queryParameters['date'];
          final date = raw != null ? DateTime.tryParse(raw) : null;
          if (date == null) {
            WidgetsBinding.instance.addPostFrameCallback(
              (_) => c.canPop() ? c.pop() : c.go('/profile'),
            );
            return const SizedBox.shrink();
          }
          return DayDetailPage(date: date);
        },
      ),
      // @dev 自测入口（Story 1.4 F3）：不从 UI 链接，仅供手动深链触发登录引导。
      GoRoute(path: '/dev/login-guide', builder: (c, s) => const DevLoginGuidePage()),
      // @dev 自测入口（Story 4.1 F2）：仅供手动深链驱动分诊「提交 → 短轮询」契约（联调）。
      GoRoute(path: '/dev/triage', builder: (c, s) => const DevTriagePage()),
      // ⚠️ 曾有 `/dev/diary-guest` 调试直达（Story 2.2 时门控未解、游客进不去 `/profile`，靠它验收）。
      // **Story 2.4 解开门控后已删除**：它在 shell 外 → 没有底部 Tab；且已登录时点主 CTA 会直接进建档
      // 表单 —— 两者都与真实游客路径不符，极易被误判成缺陷。现在验游客态请走真路径：
      // 退出登录 → 冷启动即落 Diary 游客引导态（底栏在、点 CTA 弹登录窗）。
      // AI 分诊上传页（Story 4.3）。受控路由（/triage/ 前缀，游客被门控）；shell 外 push 隐藏 Tab Bar。
      GoRoute(path: '/triage/upload', builder: (c, s) => const TriageUploadPage()),
      // AI 分诊历史结果快照（bug 20260702-238/228）：按 triageId 只读回看，extra 带历史症状摘要。
      GoRoute(
        path: '/triage/result/:id',
        builder: (c, s) => TriageSnapshotPage(
          triageId: int.parse(s.pathParameters['id']!),
          symptomSummary: s.extra as String?,
        ),
      ),
      // 兽医咨询入口 + 等待界面（Story 5.3）。受控路由（/consult 前缀，游客被门控）。
      // triageTaskId：从 AI 分诊结果页升级而来时带入，走 AI_UPGRADE 发起（bug 20260702-235）。
      GoRoute(
        path: '/consult',
        builder: (c, s) => ConsultEntryPage(
          triageTaskId: int.tryParse(s.uri.queryParameters['triageTaskId'] ?? ''),
        ),
      ),
      // 直连问诊病例填写页（Story F）：症状 + 照片，提交才发起 DIRECT 会话。
      GoRoute(path: '/consult/case', builder: (c, s) => const ConsultCaseFormPage()),
      // 客服工单（Story 4.2，/me 受控前缀）。/new 必须在 /:token 之前注册，否则 token 段会吞 'new'。
      GoRoute(path: '/me/support-tickets', builder: (c, s) => const MyTicketsPage()),
      GoRoute(path: '/me/support-tickets/new', builder: (c, s) => const TicketComposePage()),
      GoRoute(
        path: '/me/support-tickets/:token',
        builder: (c, s) => TicketDetailPage(token: s.pathParameters['token']!),
      ),
      // CSAT 满意度问卷（Story 4.7）。受控（/me 前缀）；从工单详情「评价服务」进。
      GoRoute(
        path: '/me/support-tickets/:token/csat',
        builder: (c, s) => CsatPage(token: s.pathParameters['token']!),
      ),
      GoRoute(
        path: '/consult/waiting/:id',
        builder: (c, s) => ConsultWaitingPage(sessionId: int.parse(s.pathParameters['id']!)),
      ),
      // 计费流下单三屏（Story 3.5）：确认 → 等待接单(1min) → 限时支付(1.5min)。token 走 path param。
      GoRoute(path: '/consult/vet-request', builder: (c, s) => const VetRequestConfirmPage()),
      GoRoute(
        path: '/consult/vet-request/waiting/:token',
        builder: (c, s) => VetWaitingPage(requestToken: s.pathParameters['token']!),
      ),
      GoRoute(
        path: '/consult/vet-request/pay/:token',
        builder: (c, s) => VetTimedPayPage(requestToken: s.pathParameters['token']!),
      ),
      // 进行中会话界面（Story 5.5）。用户侧 /consult/conversation、兽医侧 /vet/conversation（各自 role 守卫）。
      GoRoute(
        path: '/consult/conversation/:id',
        builder: (c, s) => ConsultConversationPage(
          sessionId: int.parse(s.pathParameters['id']!),
          from: s.uri.queryParameters['from'],
        ),
      ),
      GoRoute(
        path: '/vet/conversation/:id',
        builder: (c, s) =>
            _vetScoped(VetConversationPage(sessionId: int.parse(s.pathParameters['id']!))),
      ),
      // 兽医收入页（Story 3.7）：当月待结算 + 历史月结倒序（从「我的」进）。
      GoRoute(path: '/vet/income', builder: (c, s) => _vetScoped(const VetIncomePage())),
      // 兽医「历史」卡 View → 只读问诊结果页（Bug 20260701-196）。列表带入 VetHistoryEntry(extra)
      // 供顶栏宠物名；深链无 extra 时降级为 null，仅按 sessionId 拉诊断。
      GoRoute(
        path: '/vet/history/:id',
        builder: (c, s) => _vetScoped(
          VetHistoryDetailPage(
            sessionId: int.parse(s.pathParameters['id']!),
            entry: s.extra as VetHistoryEntry?,
          ),
        ),
      ),
      // 抢单请求详情/预览页（Story 5.2 AC5 · F11）：3 分钟预览计时 + 三态返回。
      GoRoute(
        path: '/vet/request/:id',
        // 正常流程从列表带入 extra(VetInboxItem)；extra 为空时仅出现在 dev 深链（DEV_ROUTE，
        // 生产流程恒带 extra）→ 合成一份代表性富样本（含宠物身份），供逐屏视觉验收完整渲染。
        builder: (c, s) => _vetScoped(
          VetRequestDetailPage(
            item:
                s.extra as VetInboxItem? ??
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
          ),
        ),
      ),
      // 通知中心（Story 6.6）+ 6.1 深链兜底落点。受控路由（需登录）。
      GoRoute(path: '/notifications', builder: (c, s) => const NotificationCenterPage()),
      // 二级设置页（Story 7.1 · F8）：语言/退出/注销。受控（/me 前缀，需登录）。
      GoRoute(path: '/me/settings', builder: (c, s) => const SettingsPage()),
      // PawCoin 余额与流水页（Story 1.4）。受控（/me 前缀已被 _controlledLocations 守卫）；shell 外顶层隐 Tab Bar。
      GoRoute(path: '/me/pawcoin', builder: (c, s) => const PawCoinPage()),
      // PawCoin 充值页（Story 1.5）。余额页「Isi Saldo」入口；同样 /me 门控 + shell 外隐 Tab。
      GoRoute(path: '/me/pawcoin/recharge', builder: (c, s) => const RechargePage()),
      // 订单中心列表（Story 5.2）。受控（/me 前缀，需登录）；shell 外顶层隐 Tab。
      GoRoute(path: '/me/orders', builder: (c, s) => const OrderListPage()),
      // 订单详情（Story 5.3）：各态 + 退款进度 + 宠物已删失效占位。受控（/me 前缀）。
      GoRoute(
        path: '/me/orders/:token',
        builder: (c, s) => OrderDetailPage(token: s.pathParameters['token']!),
      ),
      // 用户端退款方式选择/填收款 3 屏（Story 4.5）。受控（/me 前缀，需登录）；shell 外顶层隐 Tab。
      GoRoute(path: '/me/refunds', builder: (c, s) => const RefundListPage()),
      GoRoute(
        path: '/me/refunds/choose',
        builder: (c, s) => RefundChoosePage(refund: s.extra as MyRefund),
      ),
      GoRoute(
        path: '/me/refunds/account',
        builder: (c, s) => RefundAccountPage(refund: s.extra as MyRefund),
      ),
      GoRoute(
        path: '/me/refunds/status',
        builder: (c, s) => RefundStatusPage(refund: s.extra as MyRefund),
      ),
      GoRoute(
        path: '/me/refunds/pawcoin-success',
        builder: (c, s) => RefundPawcoinSuccessPage(result: s.extra as RefundPawcoinResult),
      ),
      // 语言设置（Story 7.2）。
      GoRoute(path: '/me/language', builder: (c, s) => const LanguageSettingsPage()),
      // 账号注销整页（P-43 · Story 7.3）。受控（/me 前缀，需登录）。
      GoRoute(path: '/me/delete-account', builder: (c, s) => const DeleteAccountPage()),
      // 黑名单管理（V1.1.4 Story 1.5 · FR-94）。受控（/me 前缀已在 _controlledLocations，游客深链自动 redirect）。
      // ⚠️ 放顶层而非 shell 分支：push 时要隐藏底部 Tab Bar。
      GoRoute(path: '/me/blocked-users', builder: (c, s) => const BlockedUsersPage()),
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
          args: s.extra is PublishResultArgs
              ? s.extra as PublishResultArgs
              : PublishResultArgs.sample,
        ),
      ),
      GoRoute(
        path: '/publish/done',
        builder: (c, s) => PublishDonePage(
          args: s.extra is PublishResultArgs
              ? s.extra as PublishResultArgs
              : PublishResultArgs.sample,
        ),
      ),
      GoRoute(
        path: '/publish/rejected',
        builder: (c, s) => PublishRejectedPage(
          args: s.extra is PublishResultArgs
              ? s.extra as PublishResultArgs
              : PublishResultArgs.sampleRejected,
        ),
      ),
      // 里程碑列表页（壳）（Story 6.1 · FR-42）：MILESTONE_NODE 深链承接；本体属里程碑 mini-epic。受控（/profile/ 前缀）。
      GoRoute(path: '/profile/milestones', builder: (c, s) => const MilestoneListPage()),
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) => AppShell(navigationShell: navigationShell),
        // 分支顺序 **按 AppTab.values 循环生成**（Story 1.1 · AD-3 AC2/AC3⑤）：
        // 分支声明顺序即索引来源，手写四段会与枚举顺序脱节；循环生成后结构上不可能漂移。
        branches: <StatefulShellBranch>[
          for (final AppTab tab in AppTab.values)
            StatefulShellBranch(
              routes: [GoRoute(path: tab.location, builder: (c, s) => _tabRootPage(tab))],
            ),
        ],
      ),
    ],
  );
  // 见 [onNavigationAwayFromFallback]：用户离开过兜底落地页就永久放弃纠正（AC4）。
  router.routerDelegate.addListener(onNavigationAwayFromFallback);
  ref.onDispose(() => router.routerDelegate.removeListener(onNavigationAwayFromFallback));

  return router;
});
