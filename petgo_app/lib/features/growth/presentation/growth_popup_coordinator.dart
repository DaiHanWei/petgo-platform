import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/app_router.dart';
import '../../../core/startup/startup_popup_ledger.dart';
import '../../auth/domain/auth_state.dart';
import '../data/growth_repository.dart';
import '../domain/growth_models.dart';
import '../domain/growth_popup_trigger.dart';
import '../domain/growth_popups.dart';
import 'backfill_popup.dart';
import 'level_page.dart';
import 'level_up_celebration.dart';

/// 升级庆祝 / 存量汇总弹窗协调器（V1.4.0 ① Story 2.3 · AD-11）。
///
/// - **拉取时机**全部经 [requestPull]：冷启动（停到落地页后一次）、回前台、自己完成可能得 EXP 的动作后（延迟 1.5s，
///   防抖合并）、点击升级推送。游客 / 兽医不拉；进行中再来的请求合并进当前这次。
/// - **一次只弹一个**：庆祝优先，汇总顺延到下一个触发点。
/// - **不弹（不 ack，等下一个触发点）**：非前台 / 在 `/splash` / 根 Navigator 顶层已是弹出层（对话框、底抽屉、
///   强制更新、另一个庆祝）。普通页面照弹（PRD「立即在当前页面上方弹出」）。
/// - 弹出前登记 `StartupPopupLedger`；运营开屏弹窗经 [coldStartSettled] 等冷启动这次拉取有结论后再判（有则让路）。
/// - 本进程内弹过的不重弹（ack 失败兜底）；下次冷启动以服务端为准。
class GrowthPopupCoordinator {
  GrowthPopupCoordinator({
    required this._fetch,
    required this._ackCelebration,
    required this._ackBackfill,
    required this._auth,
    this._authReady,
    StartupPopupLedger? ledger,
    BuildContext? Function()? rootContext,
    bool Function()? topIsPopup,
    bool Function()? isResumed,
    this._currentPath,
    this._levels,
    Future<GrowthPopupOutcome?> Function(
            BuildContext ctx, GrowthCelebration c, AuthState auth, List<GrowthLevelDef> levels)?
        showCelebration,
    Future<GrowthPopupOutcome?> Function(BuildContext ctx, GrowthBackfill b, AuthState auth)? showBackfill,
    void Function(BuildContext ctx)? openLevelPage,
    Duration? actionDelay,
  })  : _ledger = ledger ?? StartupPopupLedger.instance,
        _rootContext = rootContext ?? (() => rootNavigatorKey.currentContext),
        _topIsPopup = topIsPopup ?? _rootTopIsPopup,
        _isResumed = isResumed ??
            (() => WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed),
        _showCelebration = showCelebration ?? _defaultShowCelebration,
        _showBackfill = showBackfill ?? _defaultShowBackfill,
        _openLevelPage = openLevelPage ?? ((ctx) => GoRouter.of(ctx).push(LevelPage.location)),
        _actionDelay = actionDelay ?? afterActionDelay;

  final Future<GrowthPopups?> Function() _fetch;
  final Future<void> Function(int toLevel) _ackCelebration;
  final Future<void> Function() _ackBackfill;
  final AuthState Function() _auth;
  final Future<void> Function()? _authReady;
  final StartupPopupLedger _ledger;
  final BuildContext? Function() _rootContext;
  final bool Function() _topIsPopup;
  final bool Function() _isResumed;
  final String? Function()? _currentPath;

  /// 等级表（满级判定 + 逐级色）；null / 失败 / 超时 → 空表（组件按段位色兜底、不显示满级）。
  final Future<List<GrowthLevelDef>> Function()? _levels;
  final Future<GrowthPopupOutcome?> Function(
      BuildContext ctx, GrowthCelebration c, AuthState auth, List<GrowthLevelDef> levels) _showCelebration;
  final Future<GrowthPopupOutcome?> Function(BuildContext ctx, GrowthBackfill b, AuthState auth) _showBackfill;
  final void Function(BuildContext ctx) _openLevelPage;
  final Duration _actionDelay;

  /// 动作后延迟拉取（入账是后端异步的，约 1.5s，AD-11）。
  static const Duration afterActionDelay = Duration(milliseconds: 1500);
  static const Duration authReadyTimeout = Duration(seconds: 5);
  static const Duration levelsTimeout = Duration(milliseconds: 1500);
  static const Set<String> landingPaths = {'/profile', '/home'};

  final Completer<void> _coldStartSettled = Completer<void>();
  GoRouter? _router;
  bool _attached = false;
  bool _disposed = false;
  bool _coldStartPending = false;
  bool _inFlight = false;

  /// 启动链 + 登录态恢复已落定。attach 之后、落定之前的非冷启动请求一律丢弃（iOS 关系统权限框会触发 resumed，
  /// 此时启动链 / 强制更新还没走完）；唯独升级推送点击记下，落定后当作冷启动那次拉取。
  bool _ready = false;
  bool _deferredPushClick = false;

  /// 拉取进行中又来请求、且这次没弹出任何东西 → 结束后再拉一次（合并而非吞掉）。
  bool _rerun = false;
  Timer? _actionTimer;
  final Set<String> _shownCelebrations = <String>{};
  bool _shownBackfill = false;

  /// 冷启动这次拉取有结论（无弹窗 / 已登记 ledger 并弹出 / 不该拉）后完成；运营弹窗协调器据此让路。
  Future<void> get coldStartSettled => _coldStartSettled.future;

  @visibleForTesting
  bool get inFlight => _inFlight;

  /// 幂等：绑定全局触发器 + 安排冷启动那次拉取。
  void attach(GoRouter router) {
    if (_attached || _disposed) return;
    _attached = true;
    _router = router;
    growthPopupTrigger.bind(requestPull);
    unawaited(_coldStart());
  }

  void dispose() {
    _disposed = true;
    _actionTimer?.cancel();
    growthPopupTrigger.unbind(requestPull);
    _router?.routerDelegate.removeListener(_onRouteChanged);
    _router = null;
    _settleColdStart();
  }

  void _settleColdStart() {
    if (!_coldStartSettled.isCompleted) _coldStartSettled.complete();
  }

  /// 唯一拉取入口。
  void requestPull(GrowthPullReason reason) {
    if (_disposed) return;
    if (_attached && !_ready) {
      if (reason == GrowthPullReason.pushClicked) _deferredPushClick = true;
      return;
    }
    if (reason == GrowthPullReason.afterAction) {
      // 1.5s 内多次动作只拉一次（首个动作起算）。
      if (_actionTimer?.isActive ?? false) return;
      _actionTimer = Timer(_actionDelay, () => unawaited(_pull()));
      return;
    }
    unawaited(_pull());
  }

  Future<void> _coldStart() async {
    try {
      await _ledger.startupChainDone;
      if (_disposed) return;
      final ready = _authReady;
      if (ready != null) {
        try {
          await ready().timeout(authReadyTimeout);
        } catch (_) {}
      }
      if (_disposed) return;
      _ready = true;
      if (!_eligible()) return _settleColdStart();
      if (_onLandingPage() || _deferredPushClick) {
        _deferredPushClick = false;
        await _pull();
        _settleColdStart();
        return;
      }
      // 推送 / 外链冷启动落在别处：第一次回到落地页再拉。
      _coldStartPending = true;
      _router?.routerDelegate.addListener(_onRouteChanged);
    } catch (e) {
      debugPrint('[GrowthPopup] cold start failed: ${e.runtimeType}');
      _settleColdStart();
    }
  }

  void _onRouteChanged() {
    if (!_coldStartPending || _disposed || !_onLandingPage()) return;
    _coldStartPending = false;
    _router?.routerDelegate.removeListener(_onRouteChanged);
    unawaited(_pull().whenComplete(_settleColdStart));
  }

  bool _onLandingPage() {
    final path = _path();
    return path != null && landingPaths.contains(path);
  }

  String? _path() {
    final injected = _currentPath;
    if (injected != null) return injected();
    try {
      return _router?.state.uri.path;
    } catch (_) {
      return null;
    }
  }

  bool _eligible() {
    try {
      final auth = _auth();
      return auth.isLoggedIn && !auth.isVet;
    } catch (_) {
      return false;
    }
  }

  bool _idle() {
    if (!_isResumed()) return false;
    if (_path() == '/splash') return false;
    return !_topIsPopup();
  }

  Future<void> _pull() async {
    if (_disposed || !_eligible()) return;
    if (_inFlight) {
      _rerun = true;
      return;
    }
    _inFlight = true;
    var shown = false;
    try {
      final popups = await _fetch();
      if (_disposed || popups == null || popups.isEmpty) return;
      if (!_idle()) return;
      final ctx = _rootContext();
      if (ctx == null || !ctx.mounted) return;
      final c = popups.celebration;
      if (c != null && !_shownCelebrations.contains('${c.fromLevel}-${c.toLevel}')) {
        shown = true;
        await _celebrate(ctx, c);
        return; // 一次只弹一个：汇总顺延
      }
      final b = popups.backfill;
      if (b != null && !_shownBackfill) {
        shown = true;
        await _backfill(ctx, b);
      }
    } catch (e) {
      debugPrint('[GrowthPopup] pull failed: ${e.runtimeType}');
    } finally {
      _inFlight = false;
      final again = _rerun && !shown;
      _rerun = false;
      if (again) unawaited(_pull());
    }
  }

  Future<List<GrowthLevelDef>> _loadLevels() async {
    final load = _levels;
    if (load == null) return const [];
    try {
      return await load().timeout(levelsTimeout);
    } catch (_) {
      return const [];
    }
  }

  Future<void> _celebrate(BuildContext ctx, GrowthCelebration c) async {
    _shownCelebrations.add('${c.fromLevel}-${c.toLevel}');
    final levels = await _loadLevels();
    if (_disposed || !ctx.mounted || !_idle()) {
      _shownCelebrations.remove('${c.fromLevel}-${c.toLevel}');
      return;
    }
    // V-3：拿到升级、弹出之前上报。
    Analytics.capture('growth_level_up', {
      'from_level': c.fromLevel,
      'to_level': c.toLevel,
      'is_tier_up': c.tierUp,
    });
    StartupPopupLedger.instance.record(StartupPopupKind.growthCelebration);
    _settleColdStart();
    final outcome = await _showCelebration(ctx, c, _auth(), levels);
    unawaited(_ackCelebration(c.toLevel).catchError((Object _) {}));
    if (outcome == GrowthPopupOutcome.viewLevel) _goToLevelPage();
  }

  Future<void> _backfill(BuildContext ctx, GrowthBackfill b) async {
    _shownBackfill = true;
    StartupPopupLedger.instance.record(StartupPopupKind.growthBackfill);
    _settleColdStart();
    final outcome = await _showBackfill(ctx, b, _auth());
    unawaited(_ackBackfill().catchError((Object _) {}));
    // E1「Lihat Level Saya」没有独立 source，归 celebration（看板据此判读）。
    if (outcome == GrowthPopupOutcome.viewLevel) _goToLevelPage();
  }

  void _goToLevelPage() {
    final ctx = _rootContext();
    if (ctx == null || !ctx.mounted) return;
    // 已在等级页（点升级推送直达后补弹的庆祝）：只关弹窗，不再叠一层、不重复计进页。
    if (_path() == LevelPage.location) return;
    Analytics.capture('growth_level_page_viewed', {'source': 'celebration'});
    _openLevelPage(ctx);
  }

  /// 根 Navigator 顶层是否为弹出层（对话框 / 底抽屉 / 另一个庆祝）。`popUntil` 首个路由即返回 true，不弹任何东西。
  static bool _rootTopIsPopup() {
    final nav = rootNavigatorKey.currentState;
    if (nav == null) return false;
    Route<dynamic>? top;
    nav.popUntil((route) {
      top = route;
      return true;
    });
    return top is PopupRoute;
  }

  static Future<GrowthPopupOutcome?> _defaultShowCelebration(
      BuildContext ctx, GrowthCelebration c, AuthState auth, List<GrowthLevelDef> levels) {
    final p = auth.profile;
    return showLevelUpCelebration(ctx,
        celebration: c, avatarUrl: p?.avatarUrl, nickname: p?.nickname ?? p?.displayName ?? '', levels: levels);
  }

  static Future<GrowthPopupOutcome?> _defaultShowBackfill(BuildContext ctx, GrowthBackfill b, AuthState auth) {
    final p = auth.profile;
    return showBackfillPopup(
        ctx, BackfillPopup(backfill: b, avatarUrl: p?.avatarUrl, nickname: p?.nickname ?? p?.displayName ?? ''));
  }
}

final Provider<GrowthPopupCoordinator> growthPopupCoordinatorProvider = Provider<GrowthPopupCoordinator>((ref) {
  final repo = ref.read(growthRepositoryProvider);
  final c = GrowthPopupCoordinator(
    fetch: repo.fetchPopups,
    ackCelebration: repo.ackCelebration,
    ackBackfill: repo.ackBackfill,
    auth: () => ref.read(authControllerProvider),
    authReady: () => ref.read(authControllerProvider.notifier).ensureRestored(),
    levels: () async => (await repo.fetchPage()).levels,
  );
  ref.onDispose(c.dispose);
  return c;
});
