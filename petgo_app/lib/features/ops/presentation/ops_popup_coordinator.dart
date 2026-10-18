import '../../growth/presentation/growth_popup_coordinator.dart';
import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/app_router.dart';
import '../../../core/startup/startup_popup_ledger.dart';
import '../../../core/storage/prefs.dart';
import '../../../shared/widgets/app_image.dart';
import '../../auth/domain/auth_state.dart';
import '../data/ops_popup_repository.dart';
import '../domain/ops_popup.dart';
import '../domain/ops_popup_frequency.dart';
import '../domain/ops_target.dart';
import '../domain/ops_target_action.dart';
import 'open_ops_target.dart';
import 'ops_popup_dialog.dart';

/// 冷启动运营弹窗判定协调器（V1.3.4 Story 5.4 · AC1 · AD-13）。
///
/// 监听路由：当前路径是落地页（Diary `/profile` / Social `/home`，D-8）且本次运行尚未定局
/// → 发起一次判定。推送 / 外链冷启动落在别处时**当时不判定**，之后第一次回到落地页再判（D-11）。
///
/// 🔴 判定第一步就 `await StartupPopupLedger.instance.startupChainDone`：widget 测试里
/// `main.dart` 首帧回调不跑 ⇒ 它永不完成 ⇒ 不发请求、不起 Timer（整 App 测试零副作用）。
///
/// 内部状态只有 `_attached` / `_inFlight` / `_settled`（= 定局，只在内存，下次冷启动重来）。
class OpsPopupCoordinator {
  OpsPopupCoordinator({
    required this._fetchCandidates,
    required this._reportImpression,
    required this._prefs,
    required this._auth,
    this._authReady,
    StartupPopupLedger? ledger,
    DateTime Function()? now,
    Future<bool> Function(BuildContext ctx, ImageProvider image)? precache,
    Future<OpsPopupOutcome?> Function(BuildContext ctx, OpsPopupDialog dialog)? show,
    this._open,
    BuildContext? Function()? rootContext,
    bool Function()? rootCanPop,
    bool Function()? isResumed,
    this._beforeLedgerCheck,
  })  : _ledger = ledger ?? StartupPopupLedger.instance,
        _now = now ?? DateTime.now,
        _precache = precache ?? defaultPrecache,
        _show = show ?? showOpsPopupDialog,
        _rootContext = rootContext ?? (() => rootNavigatorKey.currentContext),
        _rootCanPop = rootCanPop ?? (() => rootNavigatorKey.currentState?.canPop() ?? false),
        _isResumed = isResumed ??
            (() => WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed);

  final Future<List<OpsPopupCandidate>> Function() _fetchCandidates;
  final Future<void> Function(String token) _reportImpression;
  final Future<AppPrefs> Function() _prefs;
  final AuthState Function() _auth;

  /// 冷启动登录态恢复结束（成功 / 失败都完成）；null = 不等。判 VET / 是否登录、取候选（带 token）前等它。
  final Future<void> Function()? _authReady;
  final StartupPopupLedger _ledger;
  final DateTime Function() _now;
  final Future<bool> Function(BuildContext ctx, ImageProvider image) _precache;
  final Future<OpsPopupOutcome?> Function(BuildContext ctx, OpsPopupDialog dialog) _show;
  final Future<void> Function(BuildContext ctx, OpsTarget target)? _open;
  final BuildContext? Function() _rootContext;
  final bool Function() _rootCanPop;
  final bool Function() _isResumed;

  /// V1.4.0 ① Story 2.3：首次读 ledger 之前等它（≤ [beforeLedgerCheckTimeout]，超时 / 异常放行）；null = 不等。
  /// 成长弹窗依赖一次网络拉取，可能晚于这里的 ledger 检查返回 → 两窗叠加；由它等冷启动那次拉取有结论。
  final Future<void> Function()? _beforeLedgerCheck;

  static const Duration beforeLedgerCheckTimeout = Duration(seconds: 3);

  /// 首帧后延 500ms（D-22）。
  static const Duration showDelay = Duration(milliseconds: 500);

  /// 等登录态恢复的上限：超时按当时已知态继续（与 splash 的 5s 兜底同量级，不另起更长等待）。
  static const Duration authReadyTimeout = Duration(seconds: 5);

  /// 图片预加载上限（D-21）。
  static const Duration imageTimeout = Duration(seconds: 5);

  /// 落地页 = Diary / Social 的 Tab 根（D-8）。
  static const Set<String> landingPaths = {'/profile', '/home'};

  GoRouter? _router;
  WidgetRef? _widgetRef;
  bool _attached = false;
  bool _inFlight = false;
  bool _settled = false;
  bool _disposed = false;

  @visibleForTesting
  bool get settled => _settled;

  @visibleForTesting
  bool get inFlight => _inFlight;

  /// 幂等：首次 addListener + 立即按当前路径评估一次。
  ///
  /// [ref]：`openOpsTarget` 需要一个**调用当下仍挂载**的 `WidgetRef`（Story 5.2 调用契约）；
  /// 由宿主（AppShell）传入，重复 attach 时刷新为最新宿主的 ref。
  void attach(GoRouter router, {WidgetRef? ref}) {
    if (ref != null) _widgetRef = ref;
    if (_attached || _disposed) return;
    _attached = true;
    _router = router;
    router.routerDelegate.addListener(_onRouteChanged);
    _onRouteChanged();
  }

  void dispose() {
    _disposed = true;
    _router?.routerDelegate.removeListener(_onRouteChanged);
    _router = null;
    _widgetRef = null;
  }

  /// 当前是否停在落地页。
  ///
  /// 判据是 `currentConfiguration.uri.path`（与迟到纠正同一判据），**另加** `router.state.uri.path`：
  /// 前者不反映 imperative `push`（落地页上 push 了通知中心，它仍是 `/home`），后者是栈顶真实落点。
  /// 两者都是落地页才算 —— 只会更保守。
  bool _onLandingPage() {
    final router = _router;
    if (router == null) return false;
    try {
      final base = router.routerDelegate.currentConfiguration.uri.path;
      if (!landingPaths.contains(base)) return false;
      return landingPaths.contains(router.state.uri.path);
    } catch (_) {
      return false;
    }
  }

  void _onRouteChanged() {
    if (_disposed || _settled || _inFlight) return;
    if (!_onLandingPage()) return;
    _inFlight = true;
    unawaited(_evaluate().whenComplete(() => _inFlight = false));
  }

  /// 空闲检查（不通过 → 本次不弹、**不定局**）。
  bool _idle() {
    if (!_onLandingPage()) return false;
    if (_rootCanPop()) return false;
    return _isResumed();
  }

  void _settle() => _settled = true;

  Future<void> _evaluate() async {
    try {
      await _evaluateUnsafe();
    } catch (e) {
      // NFR5：任何异常都不抛到框架、不提示；本次运行不再尝试。
      // 只打类型：异常文本可能带 URL / token，不进日志。
      debugPrint('[OpsPopup] evaluate failed: ${e.runtimeType}');
      _settle();
    }
  }

  Future<void> _evaluateUnsafe() async {
    // 1) 启动链结束。
    await _ledger.startupChainDone;
    if (_disposed || _settled) return;

    // 1b) 登录态恢复落定（否则恢复中的兽医 / 用户会被当游客判定、取到游客候选）。
    final authReady = _authReady;
    if (authReady != null) {
      try {
        await authReady().timeout(authReadyTimeout);
      } catch (_) {
        // 超时 / 失败：按当时已知态继续。
      }
      if (_disposed || _settled) return;
    }

    // 2) 首次可见后延一帧 + 500ms（D-22）。
    await WidgetsBinding.instance.endOfFrame;
    await Future<void>.delayed(showDelay);
    if (_disposed || _settled) return;

    // 3) 空闲检查：不通过不定局。
    if (!_idle()) return;

    // 4) 兽医不弹。
    if (_auth().isVet) return _settle();

    // 4b) 等升级庆祝 / 存量汇总冷启动拉取有结论（V1.4.0 ①，可选注入）。
    final before = _beforeLedgerCheck;
    if (before != null) {
      try {
        await before().timeout(beforeLedgerCheckTimeout);
      } catch (_) {
        // 超时 / 异常：照常继续。
      }
      if (_disposed || _settled) return;
    }

    // 5) 本次冷启动出现过功能弹窗 → 顺延到下次冷启动。
    if (_ledger.hasAny) return _settle();

    // 6) 候选 → 剔除本地频次已满 → 取第一个。
    List<OpsPopupCandidate> candidates;
    try {
      candidates = await _fetchCandidates();
    } catch (_) {
      candidates = const [];
    }
    if (_disposed) return;
    final prefs = await _prefs();
    final picked = pickOpsPopup(candidates, prefs.opsPopupShown(), wibDateOf(_now()));
    if (picked == null) return _settle();

    // 7) 按当前语言取图，预加载；失败 / 5s 超时 → 定局、不计频次，不退而取第二个（D-21）。
    final ctx = _rootContext();
    if (ctx == null || !ctx.mounted) return;
    final isEn = Localizations.maybeLocaleOf(ctx)?.languageCode == 'en';
    final image = NetworkImage(AppImage.ossResized(picked.imageUrlFor(isEn), width: 1080));
    bool loaded;
    try {
      loaded = await _precache(ctx, image);
    } catch (_) {
      loaded = false;
    }
    if (_disposed) return;
    if (!loaded) return _settle();

    // 8) show 前再查一次（D-22）。
    if (_ledger.hasAny) return _settle();
    if (!_idle()) return;
    final showCtx = _rootContext();
    if (showCtx == null || !showCtx.mounted) return;

    // 9) 弹出，定局。
    _settle();
    final target = picked.target;
    final targetValid = resolveOpsTarget(target) is! OpsNoAction;
    var shownReported = false;
    final outcome = await _show(
      showCtx,
      OpsPopupDialog(
        image: image,
        title: picked.titleFor(isEn),
        body: picked.bodyFor(isEn),
        buttonText: picked.buttonTextFor(isEn),
        imageTappable: targetValid,
        onShown: () {
          if (shownReported) return;
          shownReported = true;
          _onShown(picked, prefs);
        },
      ),
    );

    // 结果处理在弹窗 pop 之后（AC5.4）。
    if (outcome == OpsPopupOutcome.tap) {
      unawaited(Analytics.capture('ops_popup_tapped', {'popup_id': picked.token}));
      if (!targetValid) return;
      final navCtx = _rootContext();
      if (navCtx == null || !navCtx.mounted) return;
      await _openTarget(navCtx, target);
    } else {
      unawaited(Analytics.capture('ops_popup_closed', {'popup_id': picked.token}));
    }
  }

  /// 真正显示后：写本地频次（游客也写）+ 登录用户曝光上报 + `ops_popup_shown`（AC3.4 / AC3.5）。
  void _onShown(OpsPopupCandidate c, AppPrefs prefs) {
    unawaited(Analytics.capture('ops_popup_shown', {'popup_id': c.token}));
    unawaited(prefs
        .markOpsPopupShown(c.token, opsPopupShownValue(c.frequency, _now()))
        .catchError((Object _) {}));
    AuthState auth;
    try {
      auth = _auth();
    } catch (_) {
      return;
    }
    if (auth.isLoggedIn && !auth.isVet) {
      unawaited(_reportImpression(c.token).catchError((Object _) {}));
    }
  }

  Future<void> _openTarget(BuildContext ctx, OpsTarget target) async {
    final open = _open;
    if (open != null) return open(ctx, target);
    final ref = _widgetRef;
    // Story 5.2 调用契约：ref 必须在调用当下仍挂载。
    if (ref == null || !ref.context.mounted) return;
    return openOpsTarget(ctx, ref, target);
  }

  /// 默认预加载：`precacheImage` + 5s 超时；加载失败 / 超时 / 任何异常 → false。
  static Future<bool> defaultPrecache(BuildContext ctx, ImageProvider image) async {
    var failed = false;
    try {
      await precacheImage(image, ctx, onError: (_, _) => failed = true).timeout(imageTimeout);
      return !failed;
    } catch (_) {
      return false;
    }
  }
}

final Provider<OpsPopupCoordinator> opsPopupCoordinatorProvider =
    Provider<OpsPopupCoordinator>((ref) {
  final c = OpsPopupCoordinator(
    fetchCandidates: () => ref.read(opsPopupRepositoryProvider).fetchCandidates(),
    reportImpression: (token) => ref.read(opsPopupRepositoryProvider).reportImpression(token),
    prefs: AppPrefs.create,
    auth: () => ref.read(authControllerProvider),
    authReady: () => ref.read(authControllerProvider.notifier).ensureRestored(),
    // V1.4.0 ① Story 2.3：升级庆祝 > 存量汇总 > 运营开屏弹窗。
    beforeLedgerCheck: () => ref.read(growthPopupCoordinatorProvider).coldStartSettled,
  );
  ref.onDispose(c.dispose);
  return c;
});
