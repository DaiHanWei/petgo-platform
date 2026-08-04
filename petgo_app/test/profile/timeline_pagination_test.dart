import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/day_detail.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.2 · Story 3.3 遗留项补齐（2026-08-04）：时间线**游标分页**。
///
/// 修的是一个真实功能缺口：此前前端只取第一页（20 条），**第 21 条起的旧记录在 App 里
/// 根本看不到** —— 后端早就支持 `cursor`，缺的就是这一段。顺带让 Story 3.3 写好却
/// 「无从触发」的「增量失败 → 底部重试」有了真实入口。
///
/// 三条不可回退的口径：
/// 1. **失败不清空**已加载内容（只在末尾加一行重试），否则用户翻页失败就丢掉已看到的日记；
/// 2. **失败态下不自动重试** —— 用户停在底部会被无限重试刷屏，且看不到那行提示；
/// 3. 「Pertama 🌟」只在**确认没有更多页**时才标，否则会把「当前最旧」误标成「第一条」。
class _FakeTimelineRepo implements TimelineRepository {
  _FakeTimelineRepo({required this.page2, this.failPage2 = false});

  final TimelinePage page2;
  bool failPage2;
  int page2Calls = 0;
  String? lastCursor;

  @override
  Future<TimelinePage> getTimeline({String? cursor, int limit = 20}) async {
    page2Calls++;
    lastCursor = cursor;
    if (failPage2) {
      throw Exception('network down');
    }
    return page2;
  }

  @override
  Future<CalendarMonth> getCalendar(int year, int month) async =>
      CalendarMonth(year: year, month: month, days: const []);

  @override
  Future<DayDetail> getDay(DateTime date) async => DayDetail(date: date, items: const []);

  @override
  Future<ArchiveStats> getStats() async => const ArchiveStats(
      happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30);
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

TimelineItem _item(int i) => TimelineItem(
      kind: TimelineKind.happyMoment,
      itemType: TimelineItemType.happyMoment,
      date: DateTime(2026, 5, 1).subtract(Duration(days: i)),
      text: 'item-$i',
      postId: 1000 + i,
    );

Future<_FakeTimelineRepo> _pump(
  WidgetTester tester, {
  required TimelinePage firstPage,
  required TimelinePage page2,
  bool failPage2 = false,
}) async {
  // 视口压矮，保证第一页撑出可滚动区域（不滚就不会触发翻页，测不到东西）。
  await tester.binding.setSurfaceSize(const Size(500, 700));
  addTearDown(() => tester.binding.setSurfaceSize(null));

  final repo = _FakeTimelineRepo(page2: page2, failPage2: failPage2);
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (_, _) => const GrowthArchivePage()),
      GoRoute(path: '/content/:id', builder: (_, _) => const Scaffold(body: Text('detail'))),
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
          id: 7,
          name: 'Mochi',
          cardToken: 'T',
          petType: 'CAT',
          birthday: DateTime(2025, 1, 1))),
      timelineRepositoryProvider.overrideWithValue(repo),
      timelineFirstPageProvider.overrideWith((ref) async => firstPage),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

/// 滚到底（一次大拖拽足够：预取阈值是距底 400px）。
Future<void> _scrollToBottom(WidgetTester tester) async {
  await tester.drag(find.byType(ListView).first, const Offset(0, -4000));
  await tester.pumpAndSettle();
}

void main() {
  final firstPage = TimelinePage(
    items: [for (var i = 0; i < 20; i++) _item(i)],
    nextCursor: 'CURSOR-20',
    hasMore: true,
  );

  testWidgets('滚到底 → 用第一页给的游标取下一页，并把新条目接在后面', (tester) async {
    final repo = await _pump(
      tester,
      firstPage: firstPage,
      page2: TimelinePage(items: [_item(20), _item(21)], hasMore: false),
    );

    expect(repo.page2Calls, 0, reason: '进页只取第一页，不该预先多打一次请求');
    expect(find.text('item-20'), findsNothing);

    await _scrollToBottom(tester);

    expect(repo.page2Calls, 1);
    expect(repo.lastCursor, 'CURSOR-20', reason: '必须用后端给的游标，不能自己算 offset');
    expect(find.text('item-20'), findsOneWidget, reason: '第 21 条以前根本看不到，这就是本次修的缺口');
  });

  testWidgets('没有下一页（hasMore=false）→ 滚到底也不发请求', (tester) async {
    final repo = await _pump(
      tester,
      firstPage: TimelinePage(items: [for (var i = 0; i < 20; i++) _item(i)], hasMore: false),
      page2: const TimelinePage(items: []),
    );

    await _scrollToBottom(tester);
    expect(repo.page2Calls, 0, reason: '到底了还反复打空请求是白烧流量');
  });

  testWidgets('增量失败 → 底部出现重试行，已加载内容一条不少', (tester) async {
    final repo = await _pump(
      tester,
      firstPage: firstPage,
      page2: const TimelinePage(items: []),
      failPage2: true,
    );

    await _scrollToBottom(tester);

    expect(find.byKey(const ValueKey('timelineLoadMoreError')), findsOneWidget);
    // 整页失败态**不该**出现：那个会替换掉整个内容区。
    expect(find.byKey(const ValueKey('timelineError')), findsNothing);
    expect(find.text('item-19'), findsOneWidget, reason: '翻页失败不得抹掉用户已经看到的日记');
    expect(repo.page2Calls, 1);
  });

  testWidgets('失败态下继续滚不会自动重试（否则无限重试刷屏、提示行也看不到）', (tester) async {
    final repo = await _pump(
      tester,
      firstPage: firstPage,
      page2: const TimelinePage(items: []),
      failPage2: true,
    );

    await _scrollToBottom(tester);
    expect(repo.page2Calls, 1);

    await _scrollToBottom(tester);
    await _scrollToBottom(tester);
    expect(repo.page2Calls, 1, reason: '重试必须由用户点，不能靠滚动反复触发');
  });

  testWidgets('点重试 → 重新取下一页，成功后重试行消失', (tester) async {
    final repo = await _pump(
      tester,
      firstPage: firstPage,
      page2: TimelinePage(items: [_item(20)], hasMore: false),
      failPage2: true,
    );

    await _scrollToBottom(tester);
    expect(find.byKey(const ValueKey('timelineLoadMoreError')), findsOneWidget);

    repo.failPage2 = false; // 网络恢复
    await tester.tap(find.byKey(const ValueKey('timelineLoadMoreRetry')));
    await tester.pumpAndSettle();

    expect(repo.page2Calls, 2);
    expect(find.byKey(const ValueKey('timelineLoadMoreError')), findsNothing);
    // 新条目接在列表末尾，需再滚一下才进视口（外层 ListView 懒构建）。
    await _scrollToBottom(tester);
    // 用 textContaining：这条现在是全列表最旧的一条，标题被加上了 debut「🌟」前缀。
    expect(find.textContaining('item-20'), findsOneWidget);
  });

  testWidgets('还有下一页时不标「第一条快乐时刻」——它未必真是最旧的那条', (tester) async {
    await _pump(
      tester,
      firstPage: firstPage,
      page2: TimelinePage(items: [_item(20)], hasMore: false),
    );
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));

    expect(find.text(l10n.growthFirstHappyMoment), findsNothing,
        reason: 'hasMore=true 时还看不到真正最旧的一条');
  });
}
