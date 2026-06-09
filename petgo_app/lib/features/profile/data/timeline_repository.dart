import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/archive_stats.dart';
import '../domain/calendar_month.dart';
import '../domain/day_detail.dart';
import '../domain/timeline_item.dart';

/// 成长档案数据层（Story 2.4）。时间线游标分页 + 日历月视图 + 当天详情 + 统计栏。
abstract class TimelineRepository {
  Future<TimelinePage> getTimeline({String? cursor, int limit = 20});

  /// 日历月视图（按 event_date 聚合有记录日）。
  Future<CalendarMonth> getCalendar(int year, int month);

  /// 当天详情（某事件日期当天条目，created_at 正序）。
  Future<DayDetail> getDay(DateTime date);

  /// 统计栏（快乐时刻数 / 问诊数 / 里程碑零态）。
  Future<ArchiveStats> getStats();
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

  @override
  Future<CalendarMonth> getCalendar(int year, int month) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.petProfileCalendar,
      queryParameters: {'year': year, 'month': month},
    );
    return CalendarMonth.fromJson(resp.data!);
  }

  @override
  Future<DayDetail> getDay(DateTime date) async {
    final iso = '${date.year}-${date.month.toString().padLeft(2, '0')}'
        '-${date.day.toString().padLeft(2, '0')}';
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.petProfileDay,
      queryParameters: {'date': iso},
    );
    return DayDetail.fromJson(resp.data!);
  }

  @override
  Future<ArchiveStats> getStats() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileArchiveStats);
    return ArchiveStats.fromJson(resp.data!);
  }
}

final Provider<TimelineRepository> timelineRepositoryProvider =
    Provider<TimelineRepository>((ref) => DioTimelineRepository(ref.read(dioProvider)));

/// 首屏时间线（AsyncValue）。无限滚动的后续页由页面控制器追加。
final FutureProvider<TimelinePage> timelineFirstPageProvider = FutureProvider<TimelinePage>(
  (ref) => ref.read(timelineRepositoryProvider).getTimeline(),
);

/// 档案统计栏（AC5）。状态切换/发布后失效刷新。
final FutureProvider<ArchiveStats> archiveStatsProvider = FutureProvider<ArchiveStats>(
  (ref) => ref.read(timelineRepositoryProvider).getStats(),
);

/// 日历月视图（family：(year, month)）。
final calendarMonthProvider =
    FutureProvider.family<CalendarMonth, ({int year, int month})>(
  (ref, ym) => ref.read(timelineRepositoryProvider).getCalendar(ym.year, ym.month),
);

/// 当天详情（family：DateTime 取年月日）。
final dayDetailProvider = FutureProvider.family<DayDetail, DateTime>(
  (ref, date) => ref.read(timelineRepositoryProvider).getDay(date),
);
