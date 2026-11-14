import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget：里程碑页顶部新手任务卡（Story 7.3 · FR-47；V1.5.0 onboarding-recall Story 3.1 · FR-176 按设计改写：
/// 7 项账号级、收起只显示下一项、每项「去完成」、未知 key 不渲染）。C1 ~ C5 结构断言（视觉还原度 L2）。
const _emptyMilestones = MilestoneList(
  petName: 'Momo',
  completedCount: 0,
  totalCount: 0,
  groups: [],
);

const _keys = [
  'FIRST_DIARY',
  'FIRST_LIKE_RECEIVED',
  'FIRST_FOLLOW',
  'FIRST_LIKE_GIVEN',
  'FIRST_COMMENT',
  'FIRST_REPLY',
  'ONE_WEEK',
];

NewbieTasks _tasks(Set<String> done,
        {bool unlocked = false, bool hasPet = true, int daysLeft = 3, int? replyPost, List<String> keys = _keys}) =>
    NewbieTasks(
      items: [for (final k in keys) NewbieTaskItem(key: k, done: done.contains(k))],
      completedCount: done.length,
      total: keys.length,
      lulusPemulaUnlocked: unlocked,
      hasPet: hasPet,
      oneWeekDaysLeft: daysLeft,
      replyGoPostId: replyPost,
    );

late List<String> _pushed;

Widget _wrap({NewbieTasks? tasks, Object? error}) {
  _pushed = [];
  final router = GoRouter(
    initialLocation: '/m',
    routes: [
      GoRoute(path: '/m', builder: (c, s) => const MilestoneListPage()),
      GoRoute(path: '/publish', builder: (c, s) {
        _pushed.add(s.uri.toString());
        return const Scaffold(body: Text('PUBLISH'));
      }),
      GoRoute(path: '/profile/create', builder: (c, s) {
        _pushed.add(s.uri.toString());
        return const Scaffold(body: Text('CREATE'));
      }),
      GoRoute(path: '/pet-recommendations', builder: (c, s) {
        _pushed.add(s.uri.toString());
        return const Scaffold(body: Text('RECS'));
      }),
      GoRoute(path: '/home', builder: (c, s) {
        _pushed.add(s.uri.toString());
        return const Scaffold(body: Text('HOME'));
      }),
      GoRoute(path: '/content/:id', builder: (c, s) {
        _pushed.add(s.uri.toString());
        return const Scaffold(body: Text('POST'));
      }),
    ],
  );
  return ProviderScope(
    overrides: [
      effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
      milestoneListProvider.overrideWith((ref, _) async => _emptyMilestones),
      newbieTasksProvider.overrideWith((ref) async {
        if (error != null) throw error;
        return tasks ?? _tasks({'FIRST_DIARY', 'FIRST_FOLLOW'});
      }),
    ],
    child: MaterialApp.router(
      locale: const Locale('id'),
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  );
}

void main() {
  late AppLocalizations l10n;
  final events = <(String, Map<String, Object>?)>[];
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, Widget w) async {
    tester.view.physicalSize = const Size(1080, 4000);
    tester.view.devicePixelRatio = 2.75;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(w);
    await tester.pumpAndSettle();
  }

  testWidgets('C1 收起：x/7 + 浅紫块里是第一项未完成（②，只有提示无按钮）+「+6 tugas lainnya」+ 曝光埋点', (tester) async {
    await pump(tester, _wrap());
    expect(find.byKey(const ValueKey('newbieCard')), findsOneWidget);
    expect(find.text('2/7'), findsOneWidget);
    expect(find.text(l10n.newbieCardProgress(2, 7)), findsOneWidget);
    final nextUp = find.byKey(const ValueKey('newbieCardNextUp'));
    expect(find.descendant(of: nextUp, matching: find.text(l10n.newbieCardNextUp)), findsOneWidget);
    expect(find.descendant(of: nextUp, matching: find.text(l10n.newbieTaskFirstLikeReceived)), findsOneWidget);
    expect(find.descendant(of: nextUp, matching: find.text(l10n.newbieTaskHintLikeReceived)), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_LIKE_RECEIVED')), findsNothing);
    expect(find.text('+${l10n.newbieCardShowMore(6)}'), findsOneWidget);
    // 收起时只显示这一项，不再露前 4 项。
    expect(find.text(l10n.newbieTaskFirstDiary), findsNothing);
    expect(events.where((e) => e.$1 == 'newbie_task_card_viewed').single.$2,
        {'page': 'pet_milestones', 'done_count': 2});
  });

  testWidgets('C2 展开：7 项按序；④⑤ 有 Yuk!、⑥ 无人评论只提示、⑦ 剩余天数；完成项无按钮', (tester) async {
    await pump(tester, _wrap());
    await tester.tap(find.byKey(const ValueKey('newbieCardToggle')));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.check_circle_rounded), findsNWidgets(2));
    expect(find.byIcon(Icons.radio_button_unchecked), findsNWidgets(5));
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_LIKE_GIVEN')), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_COMMENT')), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_DIARY')), findsNothing);
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_REPLY')), findsNothing);
    expect(find.byKey(const ValueKey('newbieTaskHint_FIRST_REPLY')), findsOneWidget);
    expect(find.text(l10n.newbieTaskDaysLeft(3)), findsOneWidget);
    expect(find.text(l10n.newbieCardShowLess), findsOneWidget);
    // 行顺序 = 7 项固定顺序。
    final ys = [
      for (final k in _keys) tester.getTopLeft(find.byKey(ValueKey('newbieTaskRow_$k'))).dy,
    ];
    expect(ys, [...ys]..sort());
  });

  testWidgets('C3 变体：① 未完成 ①② 都是 Yuk! → 发布页；⑥ 有人评论 → 帖子评论区', (tester) async {
    await pump(tester, _wrap(tasks: _tasks({'FIRST_FOLLOW', 'FIRST_LIKE_GIVEN'}, daysLeft: 5, replyPost: 88)));
    await tester.tap(find.byKey(const ValueKey('newbieCardToggle')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('newbieTaskGo_FIRST_LIKE_RECEIVED')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('newbieTaskGo_FIRST_REPLY')));
    await tester.pumpAndSettle();
    expect(_pushed.last, '/content/88?focus=comments');
    expect(events.where((e) => e.$1 == 'newbie_task_go_tapped').last.$2, {'task': 6});
  });

  testWidgets('① 没宠物：按钮是 Buat profil → 建档', (tester) async {
    await pump(tester, _wrap(tasks: _tasks({}, hasPet: false)));
    final go = find.byKey(const ValueKey('newbieTaskGo_FIRST_DIARY'));
    expect(find.descendant(of: go, matching: find.text(l10n.newbieTaskCreateProfile)), findsOneWidget);
    await tester.tap(go);
    await tester.pumpAndSettle();
    expect(_pushed.last, '/profile/create');
    expect(events.where((e) => e.$1 == 'newbie_task_go_tapped').last.$2, {'task': 1});
  });

  testWidgets('C4 已解锁（含按旧 6 项解锁的老用户）只显示横幅', (tester) async {
    await pump(tester, _wrap(tasks: _tasks({'FIRST_DIARY'}, unlocked: true)));
    expect(find.byKey(const ValueKey('newbieCardDone')), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieCard')), findsNothing);
    expect(find.text(l10n.newbieCardAllDone), findsOneWidget);
  });

  testWidgets('C4 7 项全完成也只显示横幅', (tester) async {
    await pump(tester, _wrap(tasks: _tasks(_keys.toSet())));
    expect(find.byKey(const ValueKey('newbieCardDone')), findsOneWidget);
  });

  testWidgets('C5 失败态：错误文案 + 重试', (tester) async {
    await pump(tester, _wrap(error: Exception('boom')));
    expect(find.byKey(const ValueKey('newbieCardError')), findsOneWidget);
    expect(find.text(l10n.newbieCardRetry), findsOneWidget);
    await tester.tap(find.text(l10n.newbieCardRetry));
    await tester.pump();
  });

  testWidgets('未知 key 的行不渲染、不露原始 key', (tester) async {
    await pump(tester, _wrap(tasks: _tasks({}, keys: ['CREATE_PROFILE', ..._keys])));
    await tester.tap(find.byKey(const ValueKey('newbieCardToggle')));
    await tester.pumpAndSettle();
    expect(find.text('CREATE_PROFILE'), findsNothing);
    expect(find.byKey(const ValueKey('newbieTaskRow_CREATE_PROFILE')), findsNothing);
    expect(find.byKey(const ValueKey('newbieTaskRow_FIRST_DIARY')), findsOneWidget);
  });
}
