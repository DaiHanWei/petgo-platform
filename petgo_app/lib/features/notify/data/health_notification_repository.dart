import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/health_reminder_open.dart';

/// 健康提醒点开（V1.5.0 Story 3.4）。抽象便于测试注入 fake。
abstract class HealthNotificationRepository {
  /// `GET /api/v1/me/health-notifications/{token}/open?source=push|inbox`：后端置已读并返回落点。
  Future<HealthReminderOpenResult> open(String token, {required String source});
}

class DioHealthNotificationRepository implements HealthNotificationRepository {
  DioHealthNotificationRepository(this.dio);

  final Dio dio;

  @override
  Future<HealthReminderOpenResult> open(String token, {required String source}) async {
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.healthNotificationOpen(token),
        queryParameters: {'source': source});
    return HealthReminderOpenResult.fromJson(res.data ?? const {});
  }
}

final Provider<HealthNotificationRepository> healthNotificationRepositoryProvider =
    Provider<HealthNotificationRepository>((ref) => DioHealthNotificationRepository(ref.read(dioProvider)));
