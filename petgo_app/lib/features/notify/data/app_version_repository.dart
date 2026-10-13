import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/app_version_check.dart';

/// App 版本信息（对应后端 `AppVersionResponse`；V1.3.4 Story 3.1 起两端最新版本分开下发）。
class AppVersionInfo {
  const AppVersionInfo({
    required this.iosLatestVersion,
    required this.androidLatestVersion,
    required this.minSupportedVersion,
    this.iosStoreUrl,
    this.androidStoreUrl,
  });

  final String iosLatestVersion;
  final String androidLatestVersion;
  final String minSupportedVersion;
  final String? iosStoreUrl;
  final String? androidStoreUrl;

  /// 解析接口响应。三个版本字段任一缺失 / 不是非空字符串 → 抛 [FormatException]
  /// （由 [AppVersionRepository.fetch] 兜成 null）。
  ///
  /// ⚠️ 不再用 `'1.0.0'` 兜底：兜底会把坏响应误判成「强制更新」或「无更新」。
  factory AppVersionInfo.fromJson(Map<String, dynamic> json) => AppVersionInfo(
        iosLatestVersion: _requiredVersion(json, 'iosLatestVersion'),
        androidLatestVersion: _requiredVersion(json, 'androidLatestVersion'),
        minSupportedVersion: _requiredVersion(json, 'minSupportedVersion'),
        iosStoreUrl: json['iosStoreUrl'] as String?,
        androidStoreUrl: json['androidStoreUrl'] as String?,
      );

  static String _requiredVersion(Map<String, dynamic> json, String key) {
    final v = json[key];
    if (v is String && v.isNotEmpty) return v;
    throw FormatException('app-version: $key missing or empty');
  }

  /// 本平台最新版本号：iOS 用 [iosLatestVersion]，其余平台一律用 [androidLatestVersion]。
  String latestFor({required bool isIos}) => isIos ? iosLatestVersion : androidLatestVersion;

  /// 本平台商店链接（空串 / null → null）。
  String? storeUrlFor({required bool isIos}) => AppVersionCheck.storeUrl(
      isIos: isIos, iosStoreUrl: iosStoreUrl, androidStoreUrl: androidStoreUrl);
}

/// 版本检测数据层。<b>失败默认放行</b>：任何异常（网络 / 超时 / 类型错误 / 缺字段）都返回 null，
/// 绝不因此挡用户在 App 外（PRD §3.3 AC5、NFR5）。
class AppVersionRepository {
  AppVersionRepository({required this.dio});

  final Dio dio;

  Future<AppVersionInfo?> fetch() async {
    try {
      final resp = await dio
          .get<Map<String, dynamic>>(ApiPaths.appVersion)
          .timeout(const Duration(seconds: 10));
      final data = resp.data;
      if (data == null) return null;
      return AppVersionInfo.fromJson(data);
    } catch (_) {
      return null; // 检测失败默认放行。
    }
  }
}

final appVersionRepositoryProvider =
    Provider<AppVersionRepository>((ref) => AppVersionRepository(dio: ref.read(dioProvider)));
