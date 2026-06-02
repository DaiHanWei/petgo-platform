import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/notification_item.dart';

/// 通知中心数据层（Story 6.6）。列表 / 未读角标 / 标记已读。
class NotificationRepository {
  NotificationRepository({required this.dio});

  final Dio dio;

  Future<NotificationPage> list({String? cursor, int limit = 20}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.notifications,
      queryParameters: {'cursor': ?cursor, 'limit': limit},
    );
    return NotificationPage.fromJson(resp.data!);
  }

  /// 未读角标。失败按 0（不显示角标，不阻塞）。
  Future<int> unreadCount() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.notificationsUnreadCount);
      return (resp.data!['count'] as num?)?.toInt() ?? 0;
    } on DioException {
      return 0;
    }
  }

  Future<void> markRead(String token) => dio.post<void>(ApiPaths.notificationRead(token));

  Future<void> markAllRead() => dio.post<void>(ApiPaths.notificationsReadAll);
}

final notificationRepositoryProvider =
    Provider<NotificationRepository>((ref) => NotificationRepository(dio: ref.read(dioProvider)));

/// 未读角标计数（铃铛红点）。autoDispose，进前台/收推送时 invalidate 刷新。
final unreadCountProvider =
    FutureProvider.autoDispose<int>((ref) => ref.read(notificationRepositoryProvider).unreadCount());
