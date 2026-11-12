import 'dart:async';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'analytics.dart';
import 'anonymous_id.dart';
import 'event_names.dart';

/// 生命周期事件（analytics-pipeline Story 2.3，FR-3）：`Application Installed` /
/// `Application Updated` / `Application Opened`，取代 PostHog 自动采集的同名事件。
///
/// - 安装标记 = 上次版本号（`version+build`），存 `analytics/state.json` 的
///   [installedVersionField] 字段（Dai 2026-11-12 拍板：不存 `shared_preferences`）。
/// - 「旧版本地数据」判据：`shared_preferences` 里存在任一 `petgo.*` 键（见 [isLegacyDataKey]）。
///   快照必须在 `main()` 最早处、任何新代码写 `petgo.*` 键之前取（[takeSnapshot]）。
/// - 快照未知（读失败）时**跳过**本次 Installed / Updated 判定，宁缺勿错，下次启动再判。

/// `state.json` 里安装标记字段名（值 = 上次启动时的 `version+build`，如 `1.5.0+21`）。
const String installedVersionField = 'installedVersion';

/// 旧版本地数据判据：旧版（PostHog 时代）写的 `shared_preferences` 键都带 `petgo.` 前缀，
/// 其中 `petgo.push_permission_asked` 为每台设备首启必写（含游客、未引导）。
bool isLegacyDataKey(String key) => key.startsWith('petgo.');

/// 启动快照：判定新装 / 升级所需的全部输入。
@immutable
class LifecycleSnapshot {
  const LifecycleSnapshot({required this.hasLegacyData, this.markerVersion});

  /// `shared_preferences` 里有任一 `petgo.*` 键。
  final bool hasLegacyData;

  /// 安装标记里的版本（`version+build`）；`null` = 没有安装标记。
  final String? markerVersion;
}

/// [decideInstallEvent] 的结果。
sealed class InstallEvent {
  const InstallEvent();
}

/// 同版本重启：不发事件。
final class InstallNone extends InstallEvent {
  const InstallNone();
}

/// 新装首启 → `Application Installed`。
final class InstallInstalled extends InstallEvent {
  const InstallInstalled(this.version, this.build);
  final String version;
  final String build;
}

/// 升级首启 / 版本变化 → `Application Updated`。`previous*` 为 null = 旧版版本号不可知。
final class InstallUpdated extends InstallEvent {
  const InstallUpdated(this.version, this.build, {this.previousVersion, this.previousBuild});
  final String version;
  final String build;
  final String? previousVersion;
  final String? previousBuild;
}

/// 版本比较键：`PackageInfo.version + '+' + buildNumber`（stag 包的 `-stag` 后缀原样保留）。
String versionKey(String version, String build) => '$version+$build';

/// 纯函数判定（L0 可测）：
///
/// | 安装标记 | 旧版数据 | 标记版本 vs 当前 | 结果 |
/// |---|---|---|---|
/// | 无 | 无 | — | installed |
/// | 无 | 有 | — | updated（无 previous） |
/// | 有 | 任意 | 相同 | none |
/// | 有 | 任意 | 不同 | updated（previous 取标记） |
InstallEvent decideInstallEvent(
  LifecycleSnapshot s, {
  required String version,
  required String build,
}) {
  final marker = s.markerVersion;
  if (marker == null) {
    return s.hasLegacyData ? InstallUpdated(version, build) : InstallInstalled(version, build);
  }
  if (marker == versionKey(version, build)) return const InstallNone();
  final i = marker.lastIndexOf('+');
  return InstallUpdated(
    version,
    build,
    previousVersion: i < 0 ? marker : marker.substring(0, i),
    previousBuild: i < 0 ? null : marker.substring(i + 1),
  );
}

/// `Application Opened` 状态机（Story 2.3 AC4）。
///
/// - 冷启动：[attach] 时已 `resumed` → 立即报 `from_background: false`；否则（进程被系统在后台
///   拉起，如静默推送）等第一次 `resumed` 再报 false；从未到前台 → 一条不报。
/// - 回前台：经过 `paused` / `hidden` 后的 `resumed` 报 `from_background: true`。
/// - 只经过 `inactive` 的 `resumed`（系统弹窗、下拉通知栏）不算一次打开。
class AppOpenTracker with WidgetsBindingObserver {
  AppOpenTracker(this._report);

  final void Function(bool fromBackground) _report;
  bool _attached = false;
  bool _coldOpenReported = false;
  bool _wentBackground = false;

  /// 开始跟踪；[current] 为此刻的生命周期状态。只生效一次。
  void attach(AppLifecycleState? current) {
    if (_attached) return;
    _attached = true;
    if (current == AppLifecycleState.resumed) _reportCold();
  }

  void _reportCold() {
    _coldOpenReported = true;
    _wentBackground = false;
    _report(false);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!_attached) return;
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
        _wentBackground = true;
      case AppLifecycleState.resumed:
        if (!_coldOpenReported) {
          _reportCold();
        } else if (_wentBackground) {
          _wentBackground = false;
          _report(true);
        }
      case AppLifecycleState.inactive:
      case AppLifecycleState.detached:
        break;
    }
  }
}

/// 生命周期事件入口：`main.dart` 取快照，`Analytics.init()` 在 sink 启动后调 [start]。
abstract final class LifecycleEvents {
  static LifecycleSnapshot? _snapshot;
  static bool _started = false;
  static AppOpenTracker? _tracker;

  /// 取启动快照（同步，只读内存里的 prefs 与已读出的 `state.json`）。
  /// [state] 不可读时快照记为未知，本次跳过 Installed / Updated。
  static void takeSnapshot(SharedPreferences prefs, AnalyticsStateRead state) {
    if (!state.readable) {
      _snapshot = null;
      return;
    }
    final marker = state.data[installedVersionField];
    _snapshot = LifecycleSnapshot(
      hasLegacyData: prefs.getKeys().any(isLegacyDataKey),
      markerVersion: marker is String && marker.isNotEmpty ? marker : null,
    );
  }

  /// 本次启动的快照；`null` = 未知（未取 / 读失败）。
  @visibleForTesting
  static LifecycleSnapshot? get debugSnapshot => _snapshot;

  @visibleForTesting
  static AppOpenTracker? get debugOpenTracker => _tracker;

  /// 判定并发出 Installed / Updated（先入队、后写标记），然后开始跟踪 Opened。只生效一次，不抛。
  ///
  /// 同一函数内先处理安装事件、再挂 Opened 跟踪，保证 Installed / Updated 先于本次冷启动的
  /// 第一条 `Application Opened` 入队。
  static Future<void> start({
    @visibleForTesting Future<PackageInfo> Function()? packageInfo,
    @visibleForTesting AppLifecycleState? Function()? currentState,
    @visibleForTesting Directory? baseDir,
    @visibleForTesting bool registerObserver = true,
  }) async {
    if (_started) return;
    _started = true;
    final snapshot = _snapshot;
    _snapshot = null;
    if (snapshot != null) {
      try {
        final info = await (packageInfo ?? PackageInfo.fromPlatform)();
        await _emitInstallEvent(snapshot, info.version, info.buildNumber, baseDir);
      } catch (e) {
        debugPrint('[Analytics] lifecycle install check failed: ${e.runtimeType}');
      }
    }
    try {
      final tracker = AppOpenTracker(_reportOpened);
      _tracker = tracker;
      if (registerObserver) WidgetsBinding.instance.addObserver(tracker);
      tracker.attach((currentState ?? () => WidgetsBinding.instance.lifecycleState)());
    } catch (e) {
      debugPrint('[Analytics] lifecycle observer failed: ${e.runtimeType}');
    }
  }

  static Future<void> _emitInstallEvent(
    LifecycleSnapshot snapshot,
    String version,
    String build,
    Directory? baseDir,
  ) async {
    final decision = decideInstallEvent(snapshot, version: version, build: build);
    switch (decision) {
      case InstallNone():
        return;
      case InstallInstalled(:final version, :final build):
        await Analytics.capture(AnalyticsEvents.applicationInstalled, {
          'version': version,
          'build': build,
        });
      case InstallUpdated(:final version, :final build, :final previousVersion, :final previousBuild):
        await Analytics.capture(AnalyticsEvents.applicationUpdated, {
          'version': version,
          'build': build,
          'previous_version': ?previousVersion,
          'previous_build': ?previousBuild,
        });
    }
    // 先入队、后写标记：写标记失败最多下次重报；反过来会永久漏报。
    await updateAnalyticsState({installedVersionField: versionKey(version, build)}, baseDir: baseDir);
  }

  static void _reportOpened(bool fromBackground) {
    unawaited(Analytics.capture(AnalyticsEvents.applicationOpened, {
      'from_background': fromBackground,
    }));
  }

  @visibleForTesting
  static void debugReset() {
    final t = _tracker;
    if (t != null) {
      try {
        WidgetsBinding.instance.removeObserver(t);
      } catch (_) {}
    }
    _tracker = null;
    _snapshot = null;
    _started = false;
  }
}
