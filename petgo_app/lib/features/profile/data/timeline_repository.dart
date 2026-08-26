import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/archive_scope.dart';
import '../domain/archive_stats.dart';
import '../domain/calendar_month.dart';
import '../domain/day_detail.dart';
import '../domain/timeline_item.dart';
import '../domain/visitor_profile.dart';

/// 成长档案数据层（Story 2.4）。时间线游标分页 + 日历月视图 + 当天详情 + 统计栏。
///
/// V1.1.6 Story 2.3 起每个方法都带一个 [ArchiveScope]：作者态走 `/me`，访客态走分享 token。
/// **取数只有这一份实现**，两种作用域只是地址不同 —— 不复制第二套，
/// 否则两套迟早漂移，而漂移的方向永远是访客那套更宽松。
abstract class TimelineRepository {
  Future<TimelinePage> getTimeline({
    String? cursor,
    int limit = 20,
    ArchiveScope scope = const ArchiveScope.me(),
  });

  /// 日历月视图（按 event_date 聚合有记录日）。
  Future<CalendarMonth> getCalendar(int year, int month,
      {ArchiveScope scope = const ArchiveScope.me()});

  /// 当天详情（某事件日期当天条目，created_at 正序）。
  Future<DayDetail> getDay(DateTime date, {ArchiveScope scope = const ArchiveScope.me()});

  /// 统计栏（快乐时刻数 / 问诊数 / 里程碑零态）。
  Future<ArchiveStats> getStats({ArchiveScope scope = const ArchiveScope.me()});

  /// 访客看到的宠物档案（**只有访客态有**：作者态走 `ProfileRepository.getMyProfile`）。
  Future<VisitorProfile> getVisitorProfile(String token);
}

class DioTimelineRepository implements TimelineRepository {
  DioTimelineRepository(this.dio);

  final Dio dio;

  @override
  Future<TimelinePage> getTimeline({
    String? cursor,
    int limit = 20,
    ArchiveScope scope = const ArchiveScope.me(),
  }) async {
    if (scope.isVisitor) {
      // ⚠️ 访客侧**不分页**：服务端只给「最近 N 条」（访客是看一眼别人的宠物，
      // 不是翻完整个档案），故不传 cursor、恒当作没有下一页。
      final resp = await dio.get<Map<String, dynamic>>(
        ApiPaths.sharedPetTimeline(scope.token!),
        queryParameters: {'limit': limit},
      );
      final items = (resp.data!['items'] as List<dynamic>? ?? const [])
          .map((e) => TimelineItem.fromJson(e as Map<String, dynamic>))
          .toList();
      return TimelinePage(items: items, nextCursor: null, hasMore: false);
    }
    final query = <String, dynamic>{'limit': limit};
    if (cursor != null) query['cursor'] = cursor;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.petProfileTimeline,
      queryParameters: query,
    );
    return TimelinePage.fromJson(resp.data!);
  }

  @override
  Future<CalendarMonth> getCalendar(int year, int month,
      {ArchiveScope scope = const ArchiveScope.me()}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      scope.isVisitor ? ApiPaths.sharedPetCalendar(scope.token!) : ApiPaths.petProfileCalendar,
      queryParameters: {'year': year, 'month': month},
    );
    return CalendarMonth.fromJson(resp.data!);
  }

  @override
  Future<DayDetail> getDay(DateTime date,
      {ArchiveScope scope = const ArchiveScope.me()}) async {
    final iso = '${date.year}-${date.month.toString().padLeft(2, '0')}'
        '-${date.day.toString().padLeft(2, '0')}';
    final resp = await dio.get<Map<String, dynamic>>(
      scope.isVisitor ? ApiPaths.sharedPetDay(scope.token!) : ApiPaths.petProfileDay,
      queryParameters: {'date': iso},
    );
    return DayDetail.fromJson(resp.data!);
  }

  @override
  Future<ArchiveStats> getStats({ArchiveScope scope = const ArchiveScope.me()}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      scope.isVisitor ? ApiPaths.sharedPetStats(scope.token!) : ApiPaths.petProfileArchiveStats,
    );
    return ArchiveStats.fromJson(resp.data!);
  }

  @override
  Future<VisitorProfile> getVisitorProfile(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.sharedPetProfile(token));
    return VisitorProfile.fromJson(resp.data!);
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

// ===== V1.1.6 Story 2.3：访客态数据源 =====
//
// 🛡 这几个 provider 与上面那几个作者态的**共用同一个 repository 方法**，
// 只是传的作用域不同 —— 取数逻辑只有一份，不会漂移。
// 下面的 `xxxProviderFor(scope)` 选择器是**唯一的分叉点**，
// 页面组件只认选择器，不自己判断作用域。

/// 访客档案（family：分享 token）。
final visitorProfileProvider = FutureProvider.family<VisitorProfile, String>(
  (ref, token) => ref.read(timelineRepositoryProvider).getVisitorProfile(token),
);

/// 访客统计栏（family：分享 token）。
final visitorStatsProvider = FutureProvider.family<ArchiveStats, String>(
  (ref, token) =>
      ref.read(timelineRepositoryProvider).getStats(scope: ArchiveScope.visitor(token)),
);

/// 访客时间线（family：分享 token）。服务端不分页，一次给最近 N 条。
final visitorTimelineProvider = FutureProvider.family<TimelinePage, String>(
  (ref, token) =>
      ref.read(timelineRepositoryProvider).getTimeline(scope: ArchiveScope.visitor(token)),
);

/// 访客日历月视图（family：token + 年月）。
final visitorCalendarProvider =
    FutureProvider.family<CalendarMonth, ({String token, int year, int month})>(
  (ref, arg) => ref.read(timelineRepositoryProvider).getCalendar(
        arg.year,
        arg.month,
        scope: ArchiveScope.visitor(arg.token),
      ),
);

/// 访客某天详情（family：token + 日期）。
final visitorDayDetailProvider =
    FutureProvider.family<DayDetail, ({String token, DateTime date})>(
  (ref, arg) => ref
      .read(timelineRepositoryProvider)
      .getDay(arg.date, scope: ArchiveScope.visitor(arg.token)),
);

// ===== 作用域选择器 =====
//
// 页面组件（`_ArchiveBody` / `ArchiveCalendar` / 某天详情）通过这几个函数拿 provider，
// **自己不判断作用域**。作者态与访客态的分叉因此只存在于这一处，
// 而不是散落在每个 `ref.watch` 旁边。

FutureProvider<ArchiveStats> archiveStatsProviderFor(ArchiveScope scope) =>
    scope.isVisitor ? visitorStatsProvider(scope.token!) : archiveStatsProvider;

FutureProvider<TimelinePage> timelineFirstPageProviderFor(ArchiveScope scope) =>
    scope.isVisitor ? visitorTimelineProvider(scope.token!) : timelineFirstPageProvider;

FutureProvider<CalendarMonth> calendarMonthProviderFor(
  ArchiveScope scope,
  int year,
  int month,
) =>
    scope.isVisitor
        ? visitorCalendarProvider((token: scope.token!, year: year, month: month))
        : calendarMonthProvider((year: year, month: month));

FutureProvider<DayDetail> dayDetailProviderFor(
  ArchiveScope scope,
  DateTime date,
) =>
    scope.isVisitor
        ? visitorDayDetailProvider((token: scope.token!, date: date))
        : dayDetailProvider(date);
