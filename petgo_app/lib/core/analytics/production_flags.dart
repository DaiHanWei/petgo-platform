import 'dart:async';
import 'dart:io' show Platform;

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 安装来源（analytics-pipeline Story 2.5，架构 A5）。线上值 [wire] 为小写字符串，
/// 与后端 `ProductionFlag.NON_PRODUCTION_INSTALL_SOURCES`（`simulator` / `testflight` / `sideload`）对齐。
enum InstallSource {
  store,
  testflight,
  simulator,
  sideload;

  String get wire => name;
}

/// 判定安装来源。纯函数，L0 可测。
///
/// 1. `isPhysicalDevice == false` → [InstallSource.simulator]（两端都先判这一条）；
/// 2. iOS：`com.apple.testflight` → testflight；`com.apple.simulator` → simulator；
///    `com.apple`（App Store，`package_info_plus 8.3.1` 实际返回值）、null、其他 → store；
/// 3. Android：只有 `com.android.vending` → store；其他（含 null、系统安装器、厂商商店）→ sideload。
///
/// [platform] 取 `android` / `ios`；其他值按 Android 规则（只认 Google Play），宁可少算生产。
InstallSource classifyInstallSource({
  required String platform,
  required String? installerStore,
  required bool isPhysicalDevice,
}) {
  if (!isPhysicalDevice) return InstallSource.simulator;
  if (platform == 'ios') {
    switch (installerStore) {
      case 'com.apple.testflight':
        return InstallSource.testflight;
      case 'com.apple.simulator':
        return InstallSource.simulator;
      default:
        return InstallSource.store;
    }
  }
  return installerStore == 'com.android.vending' ? InstallSource.store : InstallSource.sideload;
}

/// 只有商店安装算生产。debug 包本来就不上报（Story 2.1），这里不再判 build mode。
bool isProduction(InstallSource s) => s == InstallSource.store;

/// `PackageInfo` 里用到的三项。
@immutable
class PackageFacts {
  const PackageFacts({this.appVersion, this.appBuild, this.installerStore});
  final String? appVersion;
  final String? appBuild;
  final String? installerStore;
}

/// `DeviceInfoPlugin` 里用到的三项。🔴 不含设备名 / Android ID / IDFV 等可识别字段（NFR-3）。
@immutable
class DeviceFacts {
  const DeviceFacts({required this.isPhysicalDevice, this.osVersion, this.deviceModel});
  final bool isPhysicalDevice;
  final String? osVersion;
  final String? deviceModel;
}

/// 由采集结果拼出上报用的扁平 `context`（键名 camelCase，与后端 Story 1.6「上下文键约定」一致）。
/// 纯函数，L0 可测。
///
/// 任何一项采集失败（[platform] / [pkg] / [device] 为 null）：对应字段缺省，`installSource` 缺省，
/// `isProduction` 记 **false**（宁可少算生产，不让脏数据混进统计）。
/// `screen` 不在这里放：它在入队时由上报器按当时的页面写入。
Map<String, Object> buildEnvironmentContext({
  required String? platform,
  required PackageFacts? pkg,
  required DeviceFacts? device,
}) {
  final out = <String, Object>{};
  void put(String k, String? v) {
    if (v != null && v.isNotEmpty) out[k] = v;
  }

  put('appVersion', pkg?.appVersion);
  put('appBuild', pkg?.appBuild);
  put('platform', platform);
  put('osVersion', device?.osVersion);
  put('deviceModel', device?.deviceModel);
  if (device != null) out['isPhysicalDevice'] = device.isPhysicalDevice;
  // 原样留一份，便于后端审计与 L2 核对 TestFlight 实测值。
  put('installerStore', pkg?.installerStore);
  if (platform != null && pkg != null && device != null) {
    final src = classifyInstallSource(
      platform: platform,
      installerStore: pkg.installerStore,
      isPhysicalDevice: device.isPhysicalDevice,
    );
    out['installSource'] = src.wire;
    out['isProduction'] = isProduction(src);
  } else {
    out['isProduction'] = false;
  }
  return out;
}

/// 事件环境上下文（Story 2.5）：启动时采集一次并缓存，之后每条事件只读缓存，不再调插件。
///
/// [load] 由 `Analytics.init()` 在 sink 启动前 await（整体仍在 `unawaited(Analytics.init())` 内，
/// 不拖首帧）；重复调用返回同一个 Future。每个插件调用限时 3 秒，失败 / 超时降级见
/// [buildEnvironmentContext]，永不抛。
class AnalyticsEnvironment {
  AnalyticsEnvironment._();

  static const Duration timeout = Duration(seconds: 3);

  static Future<Map<String, Object>>? _loading;
  static Map<String, Object>? _context;

  /// 已加载的上下文（不可变）；[load] 完成前为 null。
  static Map<String, Object>? get context => _context;

  /// 采集并缓存；只真正执行一次。
  static Future<Map<String, Object>> load() => _loading ??= _collect();

  /// 测试注入：替换平台 / 插件读取。生产路径恒为 null。
  @visibleForTesting
  static String? Function()? debugPlatform;
  @visibleForTesting
  static Future<PackageFacts> Function()? debugReadPackage;
  @visibleForTesting
  static Future<DeviceFacts> Function(String platform)? debugReadDevice;

  /// 测试用：清缓存与注入。
  @visibleForTesting
  static void debugReset() {
    _loading = null;
    _context = null;
    debugPlatform = null;
    debugReadPackage = null;
    debugReadDevice = null;
  }

  static Future<Map<String, Object>> _collect() async {
    String? platform;
    try {
      platform = (debugPlatform ?? _currentPlatform)();
    } catch (_) {}
    final p = platform;
    final results = await Future.wait<Object?>([
      _guard(() => (debugReadPackage ?? _readPackage)(), 'package'),
      p == null ? Future<Object?>.value() : _guard(() => (debugReadDevice ?? _readDevice)(p), 'device'),
    ]);
    final ctx = Map<String, Object>.unmodifiable(buildEnvironmentContext(
      platform: platform,
      pkg: results[0] as PackageFacts?,
      device: results[1] as DeviceFacts?,
    ));
    _context = ctx;
    return ctx;
  }

  static Future<T?> _guard<T>(Future<T> Function() read, String what) async {
    try {
      return await read().timeout(timeout);
    } catch (e) {
      debugPrint('[Analytics] environment $what unavailable: ${e.runtimeType}');
      return null;
    }
  }

  /// 与 `ClientInfoInterceptor` 的 `X-App-Platform` 同值。
  static String? _currentPlatform() {
    if (Platform.isAndroid) return 'android';
    if (Platform.isIOS) return 'ios';
    return null;
  }

  static Future<PackageFacts> _readPackage() async {
    final i = await PackageInfo.fromPlatform();
    return PackageFacts(appVersion: i.version, appBuild: i.buildNumber, installerStore: i.installerStore);
  }

  static Future<DeviceFacts> _readDevice(String platform) async {
    final plugin = DeviceInfoPlugin();
    if (platform == 'ios') {
      // 🔴 不取 `IosDeviceInfo.name`：用户自起的设备名，常含真实姓名（PII）。
      final i = await plugin.iosInfo;
      return DeviceFacts(
        isPhysicalDevice: i.isPhysicalDevice,
        osVersion: i.systemVersion,
        deviceModel: i.utsname.machine,
      );
    }
    // 🔴 不取 `device` / `host` / `id` / `fingerprint` 等可识别字段。
    final a = await plugin.androidInfo;
    return DeviceFacts(
      isPhysicalDevice: a.isPhysicalDevice,
      osVersion: a.version.release,
      deviceModel: '${a.manufacturer} ${a.model}'.trim(),
    );
  }
}
