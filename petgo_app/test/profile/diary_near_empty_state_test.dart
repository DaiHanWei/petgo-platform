import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/domain/visitor_profile.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/day_detail.dart';
import 'package:tailtopia/features/profile/domain/pet_age.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/diary_header.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.2 · **A4 近空态**（刚建档、还没发第一条）——2026-08-04 用户实机对稿后补。
///
/// 真机上暴露出来的核心问题：建档完成里程碑（D-S1）自动占着时间线的一条，
/// 于是「时间线为空 → 显示引导卡」这个判定**永远不成立**，引导卡从未出现过，
/// banner 下面是一大片空白。判定必须是「还没有任何快乐时刻」而不是「时间线为空」。
class _FakeTimelineRepo implements TimelineRepository {
  /// 本假 repo 只服务作者态；访客态另有专门的测试夹具。
  @override
  Future<VisitorProfile> getVisitorProfile(String token) async =>
      throw UnimplementedError('作者态测试不该走访客接口');

  _FakeTimelineRepo(this.nextPage);

  final TimelinePage nextPage;

  @override
  Future<TimelinePage> getTimeline({String? cursor, int limit = 20, ArchiveScope scope = const ArchiveScope.me()}) async => nextPage;

  @override
  Future<CalendarMonth> getCalendar(int year, int month, {ArchiveScope scope = const ArchiveScope.me()}) async =>
      CalendarMonth(year: year, month: month, days: const []);

  @override
  Future<DayDetail> getDay(DateTime date, {ArchiveScope scope = const ArchiveScope.me()}) async => DayDetail(date: date, items: const []);

  @override
  Future<ArchiveStats> getStats({ArchiveScope scope = const ArchiveScope.me()}) async => const ArchiveStats(
      happyMomentCount: 0, consultCount: 0, milestoneCompleted: 1, milestoneTotal: 31);
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

/// 建档完成里程碑 banner（类③，系统自动、无绑定内容）。刚建档的时间线**只有这一条**。
final _profileCreatedBanner = TimelineItem(
  kind: TimelineKind.unknown, // 里程碑不来自「快乐时刻 / 健康事件」任一数据源
  itemType: TimelineItemType.milestoneBanner,
  date: DateTime(2026, 8, 4),
  milestoneCode: 'D-S1',
  milestoneLevel: 'S',
);

TimelineItem _happyMoment() => TimelineItem(
      kind: TimelineKind.happyMoment,
      itemType: TimelineItemType.happyMoment,
      date: DateTime(2026, 8, 3),
      text: 'main air pertama',
      postId: 501,
    );

Future<void> _pump(
  WidgetTester tester, {
  required TimelinePage firstPage,
  TimelinePage? nextPage,
  DateTime? birthday,
  int healthRecordCount = 0,
  String locale = 'en',
}) async {
  await tester.binding.setSurfaceSize(const Size(500, 1400));
  addTearDown(() => tester.binding.setSurfaceSize(null));

  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (_, _) => const GrowthArchivePage()),
      GoRoute(path: '/publish', builder: (_, _) => const Scaffold(body: Text('publish'))),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(AuthState(
            status: AuthStatus.authenticated,
            role: 'USER',
            profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
          ))),
      petProfileProvider.overrideWith((ref) async => PetProfile(
            id: 839,
            name: 'dada',
            cardToken: 'T',
            petType: 'DOG',
            breed: 'Pomeranian',
            birthday: birthday,
          )),
      timelineRepositoryProvider
          .overrideWithValue(_FakeTimelineRepo(nextPage ?? const TimelinePage(items: []))),
      timelineFirstPageProvider.overrideWith((ref) async => firstPage),
      archiveStatsProvider.overrideWith((ref) async => ArchiveStats(
            happyMomentCount: 0,
            consultCount: 0,
            milestoneCompleted: 1,
            milestoneTotal: 31,
            healthRecordCount: healthRecordCount,
          )),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale(locale),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  group('A4 近空态：引导卡的出现条件是「没有快乐时刻」，不是「时间线为空」', () {
    testWidgets('只有建档里程碑 banner → banner 与引导卡同时在（真机上这张卡从未出现过）',
        (tester) async {
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false));

      expect(find.byKey(const ValueKey('timelineFirstMomentCard')), findsOneWidget,
          reason: 'banner 占着一条不等于「已经有内容了」，引导卡必须照常出现');
      expect(find.byKey(const ValueKey('timelineEmptyCta')), findsOneWidget);
      expect(find.textContaining("dada's first diary entry"), findsOneWidget,
          reason: 'A4 稿的标题带宠物名；Diary 内一律用 diary entry，不再说 moment');
    });

    testWidgets('时间线彻底为空 → 也是同一张卡（不再走另一套裸文案）', (tester) async {
      await _pump(tester, firstPage: const TimelinePage(items: [], hasMore: false));

      expect(find.byKey(const ValueKey('timelineFirstMomentCard')), findsOneWidget);
    });

    testWidgets('已经有快乐时刻 → 不再催发第一条', (tester) async {
      await _pump(tester,
          firstPage:
              TimelinePage(items: [_profileCreatedBanner, _happyMoment()], hasMore: false));

      expect(find.byKey(const ValueKey('timelineFirstMomentCard')), findsNothing);
    });

    testWidgets('还有后续页未加载 → 先不催（旧照片可能在后面几页）', (tester) async {
      // ⚠️ 后续页必须**仍然 hasMore**（code-review 2026-08-04）：内容撑不出滚动余量时，
      // 页面现在会自动多取一页把视口填满（否则「第 21 条起看不到」会以另一种形态复活）。
      // 若这里让下一页就是最后一页，页面会当场翻到底、确认真没有快乐时刻，
      // 于是引导卡**应该**出现 —— 那测的就不是本用例要守的「还没翻到底」了。
      await _pump(
        tester,
        firstPage:
            TimelinePage(items: [_profileCreatedBanner], nextCursor: 'C-1', hasMore: true),
        nextPage:
            TimelinePage(items: [_profileCreatedBanner], nextCursor: 'C-2', hasMore: true),
      );

      expect(find.byKey(const ValueKey('timelineFirstMomentCard')), findsNothing,
          reason: '没翻到底就断言「一条都没有」会误催；等确认没有更多页再说');
    });
  });

  group('页头：物种 / 年龄 / 健康入口空态', () {
    testWidgets('meta 行带物种，且不满 1 个月按天显示（此前是「0y 0m」）', (tester) async {
      // 生日 = 2 天前（用相对日期，避免测试随真实时间失效）。
      final twoDaysAgo = DateTime.now().subtract(const Duration(days: 2));
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false),
          birthday: DateTime(twoDaysAgo.year, twoDaysAgo.month, twoDaysAgo.day));

      expect(find.text('Dog · Pomeranian · 2 days'), findsOneWidget);
      expect(find.textContaining('0y 0m'), findsNothing);
    });

    testWidgets('一条健康记录都没有 → 健康入口副文案改「还没有记录」', (tester) async {
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false));

      expect(find.text('No records yet'), findsOneWidget);
      expect(find.text('Vaccines · deworming · medical history'), findsNothing);
    });

    testWidgets('已有健康记录 → 恢复固定副文案', (tester) async {
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false),
          healthRecordCount: 3);

      expect(find.text('Vaccines · deworming · medical history'), findsOneWidget);
    });
  });

  group('分享名片按钮的位置（2026-08-04 用户要求：不再悬浮在内容上）', () {
    testWidgets('按钮在页头标题行里，且 Scaffold 不再挂 floatingActionButton', (tester) async {
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false));

      expect(
          find.descendant(
              of: find.byType(DiaryHeader),
              matching: find.byKey(const ValueKey('shareFab'))),
          findsOneWidget,
          reason: '悬浮 FAB 会盖住时间线 / 日历右下角的内容，必须待在页头');

      final scaffolds = tester.widgetList<Scaffold>(find.byType(Scaffold));
      expect(scaffolds.every((s) => s.floatingActionButton == null), isTrue,
          reason: '右下角不得再有悬浮按钮');
    });

    testWidgets('编辑按钮仍在，两者并存不互相顶掉', (tester) async {
      await _pump(tester,
          firstPage: TimelinePage(items: [_profileCreatedBanner], hasMore: false));

      expect(find.byKey(const ValueKey('editProfileButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('shareFab')), findsOneWidget);
    });
  });

  group('新手任务卡不再说「不计入上面的里程碑进度」（2026-08-04 用户要求删掉）', () {
    // 用源码 + 文案双层扫描守门：只断言页面上看不见，日后有人把这句加回别处照样过。
    // 事实是 6 个新手任务里 5 个本身就是里程碑，做完就会推动上面的进度条 —— 那句话与事实相反。
    test('两份 ARB 都没有这条文案，页面也不再引用它', () {
      for (final arb in ['lib/l10n/app_id.arb', 'lib/l10n/app_en.arb']) {
        expect(File(arb).readAsStringSync().contains('newbieCardSubtitle'), isFalse,
            reason: '$arb 仍留着该文案键');
      }
      final page =
          File('lib/features/profile/presentation/milestone_list_page.dart').readAsStringSync();
      expect(page.contains('newbieCardSubtitle'), isFalse);
    });
  });

  group('年龄格式化（纯函数）', () {
    late AppLocalizations l10n;

    setUpAll(() async {
      l10n = await AppLocalizations.delegate.load(const Locale('id'));
    });

    test('生日当天 → 返回 null，由调用方整段省略（不留「0 hari」）', () {
      final now = DateTime(2026, 8, 4, 12);
      expect(formatPetAge(l10n, DateTime(2026, 8, 4), now: now), isNull);
    });

    test('不满 1 个月 → 按天', () {
      final now = DateTime(2026, 8, 4);
      expect(formatPetAge(l10n, DateTime(2026, 8, 2), now: now), '2 hari');
    });

    test('满 1 个月起 → 恢复年 + 月', () {
      final now = DateTime(2026, 8, 4);
      expect(formatPetAge(l10n, DateTime(2025, 6, 2), now: now), '1th 2bln');
    });

    test('无生日 → null', () {
      expect(formatPetAge(l10n, null), isNull);
    });
  });
}
