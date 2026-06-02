import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// App 版本信息（对应后端 `AppVersionResponse`，Story 6.5）。
class AppVersionInfo {
  const AppVersionInfo({
    required this.latestVersion,
    required this.minSupportedVersion,
    this.iosStoreUrl,
    this.androidStoreUrl,
  });

  final String latestVersion;
  final String minSupportedVersion;
  final String? iosStoreUrl;
  final String? androidStoreUrl;

  factory AppVersionInfo.fromJson(Map<String, dynamic> json) => AppVersionInfo(
        latestVersion: (json['latestVersion'] ?? '1.0.0') as String,
        minSupportedVersion: (json['minSupportedVersion'] ?? '1.0.0') as String,
        iosStoreUrl: json['iosStoreUrl'] as String?,
        androidStoreUrl: json['androidStoreUrl'] as String?,
      );
}

/// 版本检测数据层（Story 6.5）。冷启动拉版本信息；<b>失败默认放行</b>（返回 null，不阻断启动）。
class AppVersionRepository {
  AppVersionRepository({required this.dio});

  final Dio dio;

  Future<AppVersionInfo?> fetch() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.appVersion);
      return AppVersionInfo.fromJson(resp.data!);
    } on DioException {
      return null; // 检测失败默认放行，绝不因此挡用户在 App 外。
    }
  }
}

final appVersionRepositoryProvider =
    Provider<AppVersionRepository>((ref) => AppVersionRepository(dio: ref.read(dioProvider)));
