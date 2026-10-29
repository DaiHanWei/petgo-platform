import 'dart:io' show Platform;

import 'package:dio/dio.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 客户端信息拦截器（V1.3.4 起）：每个 API 请求带上 App 版本与平台。
///
/// - `X-App-Version`：真机包版本（`PackageInfo.version`，如 `1.3.4`；stag 包可能带 `-stag` 后缀，原样上报）。
/// - `X-App-Platform`：`android` / `ios`。
/// - `X-App-Capabilities`：本版能显示的新内容类型（逗号分隔小写，专题线 video Story 2.2 · 决策 V-36）。
///   目前只有 `video`。后端没收到声明就当旧版，不下发视频帖 —— **不比较版本号**（stag 包有 `-stag` 后缀）。
///
/// 为什么要有：1.3.3 及以前的请求不带任何版本信息，后端无法区分新旧客户端，
/// 新增枚举 / 里程碑编码等只能「全量下发、旧版显示降级」。从 1.3.4 起带上，后端以后可按版本兜底
/// （缺这两个头 ⇒ 视为 ≤1.3.3 的旧客户端）。
///
/// 只挂在 API 用的 dio 上（`dio_client.dart`）；OSS 直传等第三方请求用独立 Dio，不会带出这两个头。
/// 读包信息失败不阻断请求，只是不带头。
class ClientInfoInterceptor extends Interceptor {
  ClientInfoInterceptor({Future<String?> Function()? loadVersion, String? platform})
      : _loadVersion = loadVersion ?? _platformVersion,
        _platform = platform ?? _currentPlatform();

  static const String versionHeader = 'X-App-Version';
  static const String platformHeader = 'X-App-Platform';
  static const String capabilitiesHeader = 'X-App-Capabilities';

  /// 本版声明的能力（专题线 video Story 2.2）。新增能力往后追加，逗号分隔。
  static const String capabilities = 'video';

  final Future<String?> Function() _loadVersion;
  final String? _platform;

  /// 包版本只读一次（进程内不变）；失败时为 null，之后不再重试。
  Future<String?>? _version;

  static Future<String?> _platformVersion() async => (await PackageInfo.fromPlatform()).version;

  static String? _currentPlatform() {
    if (Platform.isAndroid) return 'android';
    if (Platform.isIOS) return 'ios';
    return null;
  }

  Future<String?> _versionOnce() => _version ??= _loadVersion().then<String?>(
        (v) => (v == null || v.isEmpty) ? null : v,
        onError: (Object _) => null,
      );

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final version = await _versionOnce();
    if (version != null) options.headers[versionHeader] = version;
    if (_platform != null) options.headers[platformHeader] = _platform;
    options.headers[capabilitiesHeader] = capabilities;
    handler.next(options);
  }
}
