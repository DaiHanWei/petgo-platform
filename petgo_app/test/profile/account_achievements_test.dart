import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/account_milestone_repository.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/account_milestones_page.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 onboarding-recall Story 3.2 —— 宠物里程碑页账号成就区（C1）、账号级里程碑页（D2 / D3）、分发路由。
MilestoneItem _item(String code, {bool done = false, String level = 'S'}) => MilestoneItem.fromJson({
      'code': code,
      'title': code,
      'level': level,
      'triggerType': 'SYSTEM_AUTO',
      'completed': done,
      'completedAt': done ? '2026-11-15T03:00:00Z' : null,
      'celebratedAt': done ? '2026-11-15T03:00:00Z' : null,
    });

const _tasks = NewbieTasks(
  items: [NewbieTaskItem(key: 'FIRST_DIARY', done: false)],
  completedCount: 0,
  total: 7,
  lulusPemulaUnlocked: false,
  hasPet: false,
);

Future<void> _pumpPetPage(WidgetTester tester, MilestoneList list, {bool celebrateLulus = false}) async {
  // 庆祝页日期胶囊在测试字体（Ahem，字宽偏大）下需要更宽的逻辑宽度，不是产品布局问题。
  tester.view.physicalSize = Size(celebrateLulus ? 1200 : 1080, 5000);
  tester.view.devicePixelRatio = celebrateLulus ? 1.0 : 2.75;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
      milestoneListProvider.overrideWith((ref, _) async => list),
      newbieTasksProvider.overrideWith((ref) async => _tasks),
      petListProvider.overrideWith((ref) async => const [PetListItem(id: 1, name: 'Orenji', petType: 'CAT')]),
    ],
    child: MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: MilestoneListPage(celebrateLulus: celebrateLulus),
    ),
  ));
  await tester.pump();
  await tester.pump();
  await tester.pump(const Duration(seconds: 1));
}

Future<void> _pumpAccountPage(WidgetTester tester, {Object? tasksError, Object? accountError}) async {
  tester.view.physicalSize = const Size(1080, 5000);
  tester.view.devicePixelRatio = 2.75;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      newbieTasksProvider.overrideWith((ref) async {
        if (tasksError != null) throw tasksError;
        return _tasks;
      }),
      accountMilestonesProvider.overrideWith((ref) async {
        if (accountError != null) throw accountError;
        return const AccountMilestones(items: [
          AccountMilestoneEntry(flagKey: 'FIRST_FOLLOW_USER', code: 'C-S19', completed: true),
          AccountMilestoneEntry(flagKey: 'FIRST_COLLECT_CONTENT', code: 'C-S20', completed: false),
          AccountMilestoneEntry(flagKey: 'LULUS_PEMULA', code: 'C-S16', completed: false),
        ], completedCount: 1, totalCount: 3);
      }),
    ],
    child: const MaterialApp(
      locale: Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: AccountMilestonesPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  late AppLocalizations l10n;
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));

  testWidgets('C1：账号成就区在 L/M/S 上方，x/3 与 3 枚徽章取 accountGroup；页头总数用后端新口径', (tester) async {
    final list = MilestoneList(
      petName: 'Orenji',
      completedCount: 6,
      totalCount: 32,
      groups: [
        MilestoneGroup(level: MilestoneLevel.l, completedCount: 0, totalCount: 1, items: [_item('C-L1', level: 'L')]),
        MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 1, items: [_item('C-S1', done: true)]),
      ],
      accountGroup: MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 3, items: [
        _item('C-S19', done: true),
        _item('C-S20'),
        _item('C-S16'),
      ]),
    );
    await _pumpPetPage(tester, list);
    expect(find.text(l10n.accountAchievementsTitle), findsOneWidget);
    expect(find.byKey(const ValueKey('accountAchievementsCount')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('accountAchievementsCount'))).data, '1/3');
    for (final c in ['C-S19', 'C-S20', 'C-S16']) {
      expect(find.byKey(ValueKey('milestoneBadge_$c')), findsOneWidget);
    }
    final account = tester.getTopLeft(find.byKey(const ValueKey('accountAchievementsSection'))).dy;
    final l = tester.getTopLeft(find.byKey(const ValueKey('milestoneSection_l'))).dy;
    expect(account, lessThan(l));
    expect(find.textContaining('6 / 32'), findsWidgets);
    expect(list.allItems.map((i) => i.code), containsAll(['C-S19', 'C-S16', 'C-S1']));
  });

  testWidgets('旧后端无 accountGroup → 不渲染账号成就区', (tester) async {
    await _pumpPetPage(
        tester,
        const MilestoneList(petName: 'Orenji', completedCount: 0, totalCount: 0, groups: []));
    expect(find.byKey(const ValueKey('accountAchievementsSection')), findsNothing);
  });

  testWidgets('D2：账号级页 = 新手任务卡（①「Buat profil」）+ Pencapaian akun；无 L/M/S', (tester) async {
    await _pumpAccountPage(tester);
    expect(find.text(l10n.myAchievementsTitle), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieCard')), findsOneWidget);
    expect(find.text(l10n.newbieTaskCreateProfile), findsOneWidget);
    expect(find.byKey(const ValueKey('accountAchievementsSection')), findsOneWidget);
    expect(find.byKey(const ValueKey('accountBadge_LULUS_PEMULA')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_s')), findsNothing);
  });

  testWidgets('D3：任一接口失败 → 整页失败态 + 重试，不显示半截内容', (tester) async {
    await _pumpAccountPage(tester, accountError: Exception('down'));
    expect(find.byKey(const ValueKey('accountMilestonesError')), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieCard')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('accountMilestonesRetry')));
    await tester.pump();
  });

  testWidgets('D3：新手任务接口失败也整页失败', (tester) async {
    await _pumpAccountPage(tester, tasksError: Exception('down'));
    expect(find.byKey(const ValueKey('accountMilestonesError')), findsOneWidget);
  });

  test('分发路由：有宠物 → 宠物里程碑页，无宠物 → 账号级页；query 透传', () {
    expect(milestonesEntryLocation(hasPet: true), '/profile/milestones');
    expect(milestonesEntryLocation(hasPet: false), '/me/achievements');
    expect(milestonesEntryLocation(hasPet: false, query: {'celebrate': 'lulus'}), '/me/achievements?celebrate=lulus');
    expect(milestonesEntryLocation(hasPet: true, query: {'celebrate': 'lulus'}), '/profile/milestones?celebrate=lulus');
  });

  test('MilestoneList.fromJson 解析 accountGroup，缺字段为 null', () {
    final a = MilestoneList.fromJson({'petName': 'x', 'groups': [], 'accountGroup': {
      'level': 'ACCOUNT', 'completedCount': 0, 'totalCount': 3, 'items': []}});
    expect(a.accountGroup, isNotNull);
    expect(a.accountGroup!.totalCount, 3);
    expect(MilestoneList.fromJson({'petName': 'x', 'groups': []}).accountGroup, isNull);
  });

  /// V1.5.0 Story 3.3 · AC5：`celebrate=lulus` → 补弹优先 Lulus（即便还有更高级别的未庆祝）。
  testWidgets('celebrateLulus：补弹优先 Lulus Pemula', (tester) async {
    MilestoneItem pending(String code, String level) => MilestoneItem.fromJson({
          'code': code, 'title': code, 'level': level, 'triggerType': 'SYSTEM_AUTO', 'completed': true,
          'completedAt': '2026-11-15T03:00:00Z', 'celebratedAt': null,
        });
    final list = MilestoneList(
      petName: 'Orenji',
      completedCount: 1,
      totalCount: 32,
      groups: [MilestoneGroup(level: MilestoneLevel.l, completedCount: 1, totalCount: 1, items: [pending('C-L2', 'L')])],
      accountGroup: MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 3, items: [
        _item('C-S19'), _item('C-S20'), pending('C-S16', 'S'),
      ]),
    );
    await _pumpPetPage(tester, list, celebrateLulus: true);
    expect(find.text('Lulus Pemula! 🎓'), findsOneWidget);
  });
}
