import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../../../core/storage/prefs.dart';
import '../data/app_version_repository.dart';
import 'app_version_check.dart';

// 版本检查的统一控制器（V1.3.4 Story 3.2 · AD-11/AD-13）。
//
// 只管**数据与冷却**：拉接口、按本平台判定三态、算横幅是否该出现、记 72h 冷却。
// 不持有 BuildContext —— 弹窗与跳商店在 `presentation/app_update_presenter.dart`，便于纯单测。
//
// 触发方：冷启动（`AppUpdateBootstrap`）、回前台（`app.dart`）、设置页手动检查（Story 3.4）。
// 横幅（Story 3.3）读 [AppUpdateState.bannerVisible]。

/// 检查由谁发起。
enum AppUpdateTrigger { coldStart, resume, manual }

/// 设置页手动检查的结果（Story 3.4 用）。
enum ManualCheckOutcome { forced, recommended, upToDate, failed }

/// 可注入的环境（测试经 [appUpdateEnvProvider] `overrideWithValue` 注入）。
class AppUpdateEnv {
  const AppUpdateEnv({
    required this.now,
    required this.isIos,
    required this.loadCurrentVersion,
    required this.loadPrefs,
  });

  final DateTime Function() now;
  final bool isIos;

  /// 当前版本 = **真机包版本**（AC1）。stag 包带 `-stag` 后缀，`compareSemver` 会忽略。
  final Future<String> Function() loadCurrentVersion;
  final Future<AppPrefs> Function() loadPrefs;
}

final appUpdateEnvProvider = Provider<AppUpdateEnv>((ref) => AppUpdateEnv(
      now: DateTime.now,
      isIos: defaultTargetPlatform == TargetPlatform.iOS,
      loadCurrentVersion: () async => (await PackageInfo.fromPlatform()).version,
      loadPrefs: AppPrefs.create,
    ));

@immutable
class AppUpdateState {
  const AppUpdateState({
    this.info,
    this.currentVersion,
    this.decision = UpdateDecision.none,
    this.lastCheckedAt,
    this.skippedVersion,
    this.skippedAt,
    this.bannerVisible = false,
    this.bannerShownReported = false,
  });

  /// 最近一次成功拉到的接口数据。
  final AppVersionInfo? info;

  /// 真机包版本。
  final String? currentVersion;

  /// 最近一次成功判定；失败时保持上一次值（初始 none）。
  final UpdateDecision decision;

  /// 最近一次「发出请求」的时刻（成功或失败都记）。
  final DateTime? lastCheckedAt;

  /// 冷却记录（来自 AppPrefs）。
  final String? skippedVersion;
  final DateTime? skippedAt;

  /// 横幅是否该显示（Story 3.3）：recommended && 有商店链接 && 不在冷却期。
  final bool bannerVisible;

  /// 本次运行是否已报过横幅曝光（Story 3.3 用）。
  final bool bannerShownReported;

  AppUpdateState copyWith({
    AppVersionInfo? info,
    String? currentVersion,
    UpdateDecision? decision,
    DateTime? lastCheckedAt,
    String? skippedVersion,
    DateTime? skippedAt,
    bool? bannerVisible,
    bool? bannerShownReported,
  }) =>
      AppUpdateState(
        info: info ?? this.info,
        currentVersion: currentVersion ?? this.currentVersion,
        decision: decision ?? this.decision,
        lastCheckedAt: lastCheckedAt ?? this.lastCheckedAt,
        skippedVersion: skippedVersion ?? this.skippedVersion,
        skippedAt: skippedAt ?? this.skippedAt,
        bannerVisible: bannerVisible ?? this.bannerVisible,
        bannerShownReported: bannerShownReported ?? this.bannerShownReported,
      );
}

final appUpdateControllerProvider =
    NotifierProvider<AppUpdateController, AppUpdateState>(AppUpdateController.new);

class AppUpdateController extends Notifier<AppUpdateState> {
  /// 在途检查（并发去重：再调 check / onResumed / checkManually 复用同一个 Future）。
  Future<UpdateDecision?>? _inFlight;

  @override
  AppUpdateState build() => const AppUpdateState(); // 纯构造，不发请求

  AppUpdateEnv get _env => ref.read(appUpdateEnvProvider);

  /// 发一次检查。成功返回本平台判定；失败返回 null（状态保持上一次成功值不变，只更新 lastCheckedAt）。
  Future<UpdateDecision?> check(AppUpdateTrigger trigger) {
    final pending = _inFlight;
    if (pending != null) return pending;
    final f = _doCheck().whenComplete(() => _inFlight = null);
    _inFlight = f;
    return f;
  }

  Future<UpdateDecision?> _doCheck() async {
    final env = _env;
    // 🔴 先记「发出请求」时刻，失败也记：弱网下避免每次回前台都打一次接口。
    state = state.copyWith(lastCheckedAt: env.now());
    try {
      final String current;
      try {
        current = await env.loadCurrentVersion();
      } catch (_) {
        return null; // 取不到版本号 → 放行
      }
      final info = await ref.read(appVersionRepositoryProvider).fetch();
      if (info == null) return null; // 接口失败 → 放行（PRD AC5）
      // 冷却键读失败 → 按无冷却记录继续判定（绝不能连带丢掉强制判定）。
      String? skippedVersion;
      DateTime? skippedAt;
      try {
        final prefs = await env.loadPrefs();
        skippedVersion = prefs.updateSkippedVersion;
        skippedAt = prefs.updateSkippedAt;
      } catch (_) {
        skippedVersion = null;
        skippedAt = null;
      }

      final latest = info.latestFor(isIos: env.isIos);
      final decision = AppVersionCheck.decide(
          current: current, latest: latest, minSupported: info.minSupportedVersion);
      final bannerVisible = decision == UpdateDecision.recommended &&
          info.storeUrlFor(isIos: env.isIos) != null &&
          !AppVersionCheck.cooldownActive(
              latest: latest, skippedVersion: skippedVersion, skippedAt: skippedAt, now: env.now());

      state = AppUpdateState(
        info: info,
        currentVersion: current,
        decision: decision,
        lastCheckedAt: state.lastCheckedAt,
        skippedVersion: skippedVersion,
        skippedAt: skippedAt,
        bannerVisible: bannerVisible,
        bannerShownReported: state.bannerShownReported,
      );
      return decision;
    } catch (_) {
      return null; // 任何异常都放行，绝不影响启动
    }
  }

  /// 回前台：冷启动检查尚未发出（lastCheckedAt == null）或距今 < 6h → 直接返回 null、不发请求。
  ///
  /// ⚠️ iOS 的 ATT / 通知权限系统框关闭会触发 resumed，此时冷启动检查还排在启动链末尾没发，
  /// 这里若照常检查会出现两次请求、甚至两次判定。
  Future<UpdateDecision?> onResumed() async {
    final pending = _inFlight;
    if (pending != null) return pending;
    final last = state.lastCheckedAt;
    if (last == null) return null;
    if (_env.now().difference(last) < kUpdateResumeCheckInterval) return null;
    return check(AppUpdateTrigger.resume);
  }

  /// 设置页手动检查（Story 3.4 调用）：无视冷却与 6h 间隔。
  Future<ManualCheckOutcome> checkManually() async {
    final d = await check(AppUpdateTrigger.manual);
    switch (d) {
      case null:
        return ManualCheckOutcome.failed;
      case UpdateDecision.forced:
        return ManualCheckOutcome.forced;
      case UpdateDecision.recommended:
        return ManualCheckOutcome.recommended; // 冷却期内也返回 recommended
      case UpdateDecision.none:
        return ManualCheckOutcome.upToDate;
    }
  }

  /// 记 72h 冷却：skippedVersion = 本平台最新版本号，skippedAt = env.now()；写 AppPrefs；
  /// bannerVisible 立刻置 false。info 为 null（从未成功拉到）时无可跳过的版本，空转。
  Future<void> recordSkip() async {
    final latest = latestForPlatform;
    if (latest == null) return;
    final env = _env;
    final at = env.now();
    state = state.copyWith(skippedVersion: latest, skippedAt: at, bannerVisible: false);
    final prefs = await env.loadPrefs();
    await prefs.setUpdateSkipped(latest, at);
  }

  /// 横幅曝光去重（Story 3.3 用）：本次运行首次调用返回 true 并置位，之后恒 false。
  bool markBannerShownReported() {
    if (state.bannerShownReported) return false;
    state = state.copyWith(bannerShownReported: true);
    return true;
  }

  /// 本平台最新版本号（info 为 null 时返回 null）。
  String? get latestForPlatform => state.info?.latestFor(isIos: _env.isIos);

  /// 本平台商店链接（info 为 null 或链接为空时返回 null）。
  String? get storeUrlForPlatform => state.info?.storeUrlFor(isIos: _env.isIos);
}
