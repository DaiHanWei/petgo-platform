import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/storage/prefs.dart';
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
  /// [diaryOnly]（2026-10-06「只看 Diary」开关）：只取主人自己发的内容，由服务端筛（分页才准）；
  /// 仅作者态有意义，访客态忽略。
  ///
  /// V1.4.0 multi-pet（Story 1.5 · AC7）：作者态四个方法的 [petId] 指定哪只宠物（`?petId=`），
  /// null = 后端默认宠物；**访客态忽略**（访客按 token / 路径里的 petId 寻址）。
  Future<TimelinePage> getTimeline({
    String? cursor,
    int limit = 20,
    ArchiveScope scope = const ArchiveScope.me(),
    bool diaryOnly = false,
    int? petId,
  });

  /// 日历月视图（按 event_date 聚合有记录日）。
  Future<CalendarMonth> getCalendar(int year, int month,
      {ArchiveScope scope = const ArchiveScope.me(), int? petId});

  /// 当天详情（某事件日期当天条目，created_at 正序）。
  Future<DayDetail> getDay(DateTime date,
      {ArchiveScope scope = const ArchiveScope.me(), int? petId});

  /// 统计栏（快乐时刻数 / 问诊数 / 里程碑零态）。
  Future<ArchiveStats> getStats({ArchiveScope scope = const ArchiveScope.me(), int? petId});

  /// 访客看到的宠物档案（**只有访客态有**：作者态走 `ProfileRepository.getMyProfile`）。
  Future<VisitorProfile> getVisitorProfile(String token);

  /// 站内访客档案（V1.3.0 batch-b1 Story 2.3）：按 petId、仅登录可用。
  ///
  /// ⚠️ 回的是**同一个** [VisitorProfile] —— 服务端两条路径落到同一层投影（AD-4 Rule 2），
  /// 客户端也就不该为它另起一个模型。
  Future<VisitorProfile> getInAppVisitorProfile(int petId);
}

/// Diary 客户端能力声明（V1.3.2 Story 1.6 · AD-9）：作者态时间线 / 日历 / 日详情请求带 `supports`。
///
/// 后端对未声明的新条目类型一律不下发（老 App 会把未知类型回落成照片卡）。
/// V1.3.2 Story 3.3 追加 `tailsonality`；**访客态请求不带**（访客层不出打卡 / Tailsonality 条目）。
const List<String> kTimelineSupports = ['place_checkin', 'tailsonality'];

class DioTimelineRepository implements TimelineRepository {
  DioTimelineRepository(this.dio);

  final Dio dio;

  @override
  Future<TimelinePage> getTimeline({
    String? cursor,
    int limit = 20,
    ArchiveScope scope = const ArchiveScope.me(),
    bool diaryOnly = false,
    int? petId,
  }) async {
    if (scope.isVisitor) {
      // ⚠️ 访客侧**不分页**：服务端只给「最近 N 条」（访客是看一眼别人的宠物，
      // 不是翻完整个档案），故不传 cursor、恒当作没有下一页。
      final resp = await dio.get<Map<String, dynamic>>(
        scope.isInApp
            ? ApiPaths.inAppPetTimeline(scope.petId!)
            : ApiPaths.sharedPetTimeline(scope.token!),
        queryParameters: {'limit': limit},
      );
      final items = (resp.data!['items'] as List<dynamic>? ?? const [])
          .map((e) => TimelineItem.fromJson(e as Map<String, dynamic>))
          .toList();
      return TimelinePage(items: items, nextCursor: null, hasMore: false);
    }
    final query = <String, dynamic>{'limit': limit, 'supports': kTimelineSupports};
    if (cursor != null) query['cursor'] = cursor;
    if (diaryOnly) query['diaryOnly'] = true;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.withPet(ApiPaths.petProfileTimeline, petId),
      queryParameters: query,
    );
    return TimelinePage.fromJson(resp.data!);
  }

  @override
  Future<CalendarMonth> getCalendar(int year, int month,
      {ArchiveScope scope = const ArchiveScope.me(), int? petId}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      // ⚠️ 站内访客态**没有**日历端点（访客视图没有日历，两次拍板不做）——
      // 这里只可能是分享 token 态或作者态。`token!` 是**刻意的 fail-fast**：
      // 真有人从站内态调到这儿，当场抛比拼一个 `//` 的畸形 URL（或者更糟，
      // 悄悄回落到作者态看到自己的档案）都容易发现。
      scope.isVisitor
          ? ApiPaths.sharedPetCalendar(scope.token!)
          : ApiPaths.withPet(ApiPaths.petProfileCalendar, petId),
      queryParameters: {
        'year': year,
        'month': month,
        if (!scope.isVisitor) 'supports': kTimelineSupports,
      },
    );
    return CalendarMonth.fromJson(resp.data!);
  }

  @override
  Future<DayDetail> getDay(DateTime date,
      {ArchiveScope scope = const ArchiveScope.me(), int? petId}) async {
    final iso = '${date.year}-${date.month.toString().padLeft(2, '0')}'
        '-${date.day.toString().padLeft(2, '0')}';
    final resp = await dio.get<Map<String, dynamic>>(
      // token! 同上：站内态没有某天详情，走到这儿就该当场炸。
      scope.isVisitor
          ? ApiPaths.sharedPetDay(scope.token!)
          : ApiPaths.withPet(ApiPaths.petProfileDay, petId),
      queryParameters: {'date': iso, if (!scope.isVisitor) 'supports': kTimelineSupports},
    );
    return DayDetail.fromJson(resp.data!);
  }

  @override
  Future<ArchiveStats> getStats(
      {ArchiveScope scope = const ArchiveScope.me(), int? petId}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      scope.isInApp
          ? ApiPaths.inAppPetStats(scope.petId!)
          : scope.isVisitor
              ? ApiPaths.sharedPetStats(scope.token!)
              : ApiPaths.withPet(ApiPaths.petProfileArchiveStats, petId),
    );
    return ArchiveStats.fromJson(resp.data!);
  }

  @override
  Future<VisitorProfile> getVisitorProfile(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.sharedPetProfile(token));
    return VisitorProfile.fromJson(resp.data!);
  }

  @override
  Future<VisitorProfile> getInAppVisitorProfile(int petId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.inAppPetProfile(petId));
    return VisitorProfile.fromJson(resp.data!);
  }
}

final Provider<TimelineRepository> timelineRepositoryProvider =
    Provider<TimelineRepository>((ref) => DioTimelineRepository(ref.read(dioProvider)));

// ===== V1.4.0 multi-pet（Story 1.5 · AC3.7）：作者态 provider 按 petId 分族 =====
//
// 切换宠物 = 换族键，旧键缓存保留（再切回来不白等）；发布 / 编辑 / 删除后的既有
// `ref.invalidate(xxxProvider)` 对整族失效，写法不变。petId 一律取 `effectivePetIdProvider`
// （无宠物时页面已走空态分支，不会构造这些 provider）。

/// 首屏时间线（AsyncValue，family：petId）。无限滚动的后续页由页面控制器追加。
/// 跟随 [diaryOnlyProvider]：开关一拨，第一页换成新实例 → 页面据此丢掉已翻的页重来。
final timelineFirstPageProvider = FutureProvider.family<TimelinePage, int>(
  (ref, petId) => ref
      .read(timelineRepositoryProvider)
      .getTimeline(diaryOnly: ref.watch(diaryOnlyProvider), petId: petId),
);

/// Diary 页「只看 Diary」开关（2026-10-06 产品）：开 = 只看主人自己发的帖子；关 = banner + 帖子全部。
///
/// 记在本机（[AppPrefs]），下次进来保持上次的选择；读不到偏好按「关」处理。
final NotifierProvider<DiaryOnlyNotifier, bool> diaryOnlyProvider =
    NotifierProvider<DiaryOnlyNotifier, bool>(DiaryOnlyNotifier.new);

class DiaryOnlyNotifier extends Notifier<bool> {
  static const String prefKey = 'petgo.diary_only';

  @override
  bool build() {
    _restore();
    return false;
  }

  Future<void> _restore() async {
    try {
      final saved = (await AppPrefs.create()).getBool(prefKey);
      if (saved != state) state = saved;
    } catch (_) {
      // 偏好读不到：保持默认「关」，不影响看时间线。
    }
  }

  Future<void> set(bool on) async {
    if (on == state) return;
    state = on;
    try {
      await (await AppPrefs.create()).setBool(prefKey, on);
    } catch (_) {
      // 存不下只影响「下次进来记不记得」，本次照常切换。
    }
  }
}

/// 档案统计栏（AC5，family：petId）。状态切换/发布后失效刷新。
final archiveStatsProvider = FutureProvider.family<ArchiveStats, int>(
  (ref, petId) => ref.read(timelineRepositoryProvider).getStats(petId: petId),
);

/// 日历月视图（family：(petId, year, month)）。
final calendarMonthProvider =
    FutureProvider.family<CalendarMonth, ({int petId, int year, int month})>(
  (ref, k) => ref.read(timelineRepositoryProvider).getCalendar(k.year, k.month, petId: k.petId),
);

/// 当天详情（family：(petId, DateTime 取年月日)）。
final dayDetailProvider = FutureProvider.family<DayDetail, ({int petId, DateTime date})>(
  (ref, k) => ref.read(timelineRepositoryProvider).getDay(k.date, petId: k.petId),
);

// ===== V1.1.6 Story 2.3：访客态数据源 =====
//
// 🛡 这几个 provider 与上面那几个作者态的**共用同一个 repository 方法**，
// 只是传的作用域不同 —— 取数逻辑只有一份，不会漂移。
// 下面的 `xxxProviderFor(scope)` 选择器是**唯一的分叉点**，
// 页面组件只认选择器，不自己判断作用域。

// ⚠️ 这三个 provider 的族键是 **[ArchiveScope] 本身**，不是分享 token。
// V1.3.0 batch-b1 Story 2.3 起访客有**两种**来源（分享 token / 站内 petId），
// 族键若还是 String，站内那条就只能靠 `scope.token!` 取值 —— 当场空指针。
// 用作用域本身做键，两种来源天然分成两族，选择器也不必再拆分支。

// 🔴 三个都是 `isAutoDispose: true`。Riverpod 3 的 family **默认 keep-alive**，
// 而 V1.3.0 batch-b1 Story 2.3 之后这一族是**可反复进出的站内页**（从别人主页点宠物卡）——
// 留着默认值有两个后果（code-review 2026-09-15）：
// ① 同一只宠物再进来看到的是本次进程里第一次取到的数据，永远不刷新；
// ② 换账号之后仍然吃上一个账号的缓存，于是**服务端那道拉黑守卫根本不会被调用**
//    （与 bug 20260730-421 / 446 同型）。
// ⚠️ 分享 token 那一支同样受益：那一屏也是 push 进来的一次性页面。

/// 访客档案（family：作用域）。
final visitorProfileProvider = FutureProvider.family<VisitorProfile, ArchiveScope>(
  (ref, scope) => scope.isInApp
      ? ref.read(timelineRepositoryProvider).getInAppVisitorProfile(scope.petId!)
      : ref.read(timelineRepositoryProvider).getVisitorProfile(scope.token!),
  isAutoDispose: true,
);

/// 访客统计栏（family：作用域）。
final visitorStatsProvider = FutureProvider.family<ArchiveStats, ArchiveScope>(
  (ref, scope) => ref.read(timelineRepositoryProvider).getStats(scope: scope),
  isAutoDispose: true,
);

/// 访客时间线（family：作用域）。服务端不分页，一次给最近 N 条。
final visitorTimelineProvider = FutureProvider.family<TimelinePage, ArchiveScope>(
  (ref, scope) => ref.read(timelineRepositoryProvider).getTimeline(scope: scope),
  isAutoDispose: true,
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

// V1.4.0 multi-pet：作者态分支多收 [ownerPetId]（当前宠物，取 `effectivePetIdProvider`）；访客分支不变、忽略它。

FutureProvider<ArchiveStats> archiveStatsProviderFor(ArchiveScope scope, int ownerPetId) =>
    scope.isVisitor ? visitorStatsProvider(scope) : archiveStatsProvider(ownerPetId);

FutureProvider<TimelinePage> timelineFirstPageProviderFor(ArchiveScope scope, int ownerPetId) =>
    scope.isVisitor ? visitorTimelineProvider(scope) : timelineFirstPageProvider(ownerPetId);

FutureProvider<CalendarMonth> calendarMonthProviderFor(
  ArchiveScope scope,
  int ownerPetId,
  int year,
  int month,
) =>
    // ⚠️ 站内访客态没有日历（两次拍板不做）—— 真有人调到这儿会因 token 为 null 而崩，
    // 那比悄悄回落到作者态（看到自己的档案）好。
    scope.isVisitor
        ? visitorCalendarProvider((token: scope.token!, year: year, month: month))
        : calendarMonthProvider((petId: ownerPetId, year: year, month: month));

FutureProvider<DayDetail> dayDetailProviderFor(
  ArchiveScope scope,
  int ownerPetId,
  DateTime date,
) =>
    scope.isVisitor
        ? visitorDayDetailProvider((token: scope.token!, date: date))
        : dayDetailProvider((petId: ownerPetId, date: date));
