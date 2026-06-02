import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/timeline_item.dart';

/// 成长时间线数据层（Story 2.4）。游标分页读 `/pet-profiles/me/timeline`。
abstract class TimelineRepository {
  Future<TimelinePage> getTimeline({String? cursor, int limit = 20});
}

class DioTimelineRepository implements TimelineRepository {
  DioTimelineRepository(this.dio);

  final Dio dio;

  @override
  Future<TimelinePage> getTimeline({String? cursor, int limit = 20}) async {
    final query = <String, dynamic>{'limit': limit};
    if (cursor != null) query['cursor'] = cursor;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.petProfileTimeline,
      queryParameters: query,
    );
    return TimelinePage.fromJson(resp.data!);
  }
}

final Provider<TimelineRepository> timelineRepositoryProvider =
    Provider<TimelineRepository>((ref) => DioTimelineRepository(ref.read(dioProvider)));

/// 首屏时间线（AsyncValue）。无限滚动的后续页由页面控制器追加。
final FutureProvider<TimelinePage> timelineFirstPageProvider = FutureProvider<TimelinePage>(
  (ref) => ref.read(timelineRepositoryProvider).getTimeline(),
);
