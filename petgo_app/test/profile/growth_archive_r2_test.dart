import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/profile/data/profile_repository.dart';
import 'package:petgo/features/profile/data/timeline_repository.dart';
import 'package:petgo/features/profile/domain/archive_stats.dart';
import 'package:petgo/features/profile/domain/calendar_month.dart';
import 'package:petgo/features/profile/domain/day_detail.dart';
import 'package:petgo/features/profile/domain/pet_profile.dart';
import 'package:petgo/features/profile/domain/share_service.dart';
import 'package:petgo/features/profile/domain/timeline_item.dart';
import 'package:petgo/features/profile/presentation/day_detail_page.dart';
import 'package:petgo/features/profile/presentation/growth_archive_page.dart';
import 'package:petgo/features/profile/presentation/widgets/archive_calendar.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

AuthState _authA() => const AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
    );

const _profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T', breed: 'Shiba');
const _stats =
    ArchiveStats(happyMomentCount: 5, consultCount: 2, milestoneCompleted: 0, milestoneTotal: 30);

Widget _wrapPage({
  TimelinePage? page,
  Object? timelineError,
  ArchiveStats? stats = _stats,
}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(_authA())),
      petProfileProvider.overrideWith((ref) async => _profile),
      timelineFirstPageProvider.overrideWith((ref) async {
        if (timelineError != null) throw timelineError;
        return page ?? const TimelinePage(items: []);
      }),
      archiveStatsProvider.overrideWith((ref) async => stats!),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(),
    ),
  );
}

TimelineItem _happy(int id, String eventDate) => TimelineItem(
      kind: TimelineKind.happyMoment,
      date: DateTime.parse('${eventDate}T10:00:00Z'),
      eventDate: DateTime.parse(eventDate),
      postId: id,
      imageUrls: const [],
      text: 'moment$id',
    );

void main() {
  // ===== AC5 统计栏 / 里程碑 / 第一条 🌟 / 视图切换 =====

  testWidgets('AC5: 统计栏显示快乐时刻/问诊数', (tester) async {
    await tester.pumpWidget(_wrapPage());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveStatsBar')), findsOneWidget);
    expect(find.textContaining('5'), findsWidgets); // happy 5
    expect(find.textContaining('2'), findsWidgets); // consult 2
  });

  testWidgets('AC5: 里程碑入口零态进度 0 / N', (tester) async {
    await tester.pumpWidget(_wrapPage());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveMilestoneBar')), findsOneWidget);
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.growthMilestoneProgress(0, 30)), findsOneWidget);
  });

  testWidgets('AC5: 仅第一条快乐时刻显 🌟 标签', (tester) async {
    final page = TimelinePage(items: [_happy(1, '2026-06-10'), _happy(2, '2026-06-09')]);
    await tester.pumpWidget(_wrapPage(page: page));
    await tester.pumpAndSettle();
    // 两条快乐时刻，但 🌟 仅出现一次（首条）。
    expect(find.byKey(const ValueKey('firstHappyStar')), findsOneWidget);
    expect(find.byKey(const ValueKey('happyMomentTile')), findsNWidgets(2));
  });

  testWidgets('AC5: 视图切换到日历视图', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(_authA())),
        petProfileProvider.overrideWith((ref) async => _profile),
        timelineFirstPageProvider.overrideWith((ref) async => const TimelinePage(items: [])),
        archiveStatsProvider.overrideWith((ref) async => _stats),
        shareFabAnimatedShownProvider.overrideWith((ref) async => true),
        calendarMonthProvider.overrideWith(
            (ref, ym) async => CalendarMonth(year: ym.year, month: ym.month, days: const [])),
      ],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: GrowthArchivePage(),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('archiveViewCalendar')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('calNextMonth')), findsOneWidget); // 日历渲染
  });

  // ===== AC7 加载失败态 =====

  testWidgets('AC7: 时间线加载失败 → 失败态+重试，信息卡/统计栏保留', (tester) async {
    await tester.pumpWidget(_wrapPage(timelineError: Exception('boom')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('timelineError')), findsOneWidget);
    expect(find.byKey(const ValueKey('timelineRetry')), findsOneWidget);
    // 信息卡 + 统计栏仍在（未被失败态覆盖）。
    expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('archiveStatsBar')), findsOneWidget);
  });

  // ===== AC6 日历未来格子置灰 / 记录格 / 空格「+」 =====

  testWidgets('AC6: 日历下个月全为未来 → 格子灰显不可点', (tester) async {
    final tapped = <DateTime>[];
    final added = <DateTime>[];
    await tester.pumpWidget(ProviderScope(
      overrides: [
        calendarMonthProvider.overrideWith(
            (ref, ym) async => CalendarMonth(year: ym.year, month: ym.month, days: const [])),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ListView(children: [ArchiveCalendar(onOpenDay: tapped.add, onAddOnDate: added.add)]),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    // 跳到下个月：全月皆未来 → 第 15 天必为未来灰格。
    await tester.tap(find.byKey(const ValueKey('calNextMonth')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('calDayFuture_15')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('calDayFuture_15')));
    await tester.pumpAndSettle();
    expect(tapped, isEmpty); // 未来格不可点
    expect(added, isEmpty);
  });

  testWidgets('AC6: 日历有记录格点击 → onOpenDay', (tester) async {
    final tapped = <DateTime>[];
    await tester.pumpWidget(ProviderScope(
      overrides: [
        // 当月 1 号有记录（1 号必非未来）。
        calendarMonthProvider.overrideWith((ref, ym) async => CalendarMonth(
              year: ym.year,
              month: ym.month,
              days: const [CalendarDayCell(day: 1, hasHappyMoment: true, hasHealthEvent: false)],
            )),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ListView(children: [ArchiveCalendar(onOpenDay: tapped.add, onAddOnDate: (_) {})]),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('calDayRecord_1')));
    await tester.pumpAndSettle();
    expect(tapped, hasLength(1));
    expect(tapped.first.day, 1);
  });

  // ===== AC6 当天详情页（无「+」无删除） =====

  testWidgets('AC6: 当天详情页渲染条目，无「+」无删除入口', (tester) async {
    final date = DateTime(2026, 6, 2);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dayDetailProvider.overrideWith((ref, d) async =>
            DayDetail(date: date, items: [_happy(1, '2026-06-02')])),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: DayDetailPage(date: date),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('happyMomentTile')), findsOneWidget);
    expect(find.byType(FloatingActionButton), findsNothing); // 无「+」
    expect(find.byIcon(Icons.delete), findsNothing); // 无删除
  });

  testWidgets('AC7: 当天详情加载失败 → 失败态+重试', (tester) async {
    final date = DateTime(2026, 6, 2);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dayDetailProvider.overrideWith((ref, d) async => throw Exception('boom')),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: DayDetailPage(date: date),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('dayDetailError')), findsOneWidget);
  });
}
