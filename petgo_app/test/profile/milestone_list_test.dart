import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/data/milestone_repository.dart';
import 'package:petgo/features/profile/domain/milestone.dart';
import 'package:petgo/features/profile/presentation/milestone_list_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// L0 widget（Story 8.2 · FR-42）：L/M/S 分区 + 进度 + 徽章彩色/灰锁 + 点击弹层分流 + 失败重试。
MilestoneList _sample() => const MilestoneList(
      petName: 'Momo',
      completedCount: 2,
      totalCount: 5,
      groups: [
        MilestoneGroup(level: MilestoneLevel.l, completedCount: 0, totalCount: 1, items: [
          MilestoneItem(
              code: 'C-L1', title: '第一个生日', level: MilestoneLevel.l,
              trigger: MilestoneTrigger.pushPublish, completed: false),
        ]),
        MilestoneGroup(level: MilestoneLevel.m, completedCount: 1, totalCount: 1, items: [
          MilestoneItem(
              code: 'C-M8', title: '陪伴满 30 天', level: MilestoneLevel.m,
              trigger: MilestoneTrigger.systemAuto, completed: true),
        ]),
        MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 3, items: [
          MilestoneItem(
              code: 'C-S1', title: '宠物档案创建完成', level: MilestoneLevel.s,
              trigger: MilestoneTrigger.systemAuto, completed: true),
          MilestoneItem(
              code: 'C-S6', title: '第一次洗澡', level: MilestoneLevel.s,
              trigger: MilestoneTrigger.userCheckin, completed: false),
        ]),
      ],
    );

Widget _wrap({MilestoneList? data, Object? error}) => ProviderScope(
      overrides: [
        milestoneListProvider.overrideWith((ref) async {
          if (error != null) throw error;
          return data ?? _sample();
        }),
      ],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: MilestoneListPage(),
      ),
    );

void main() {
  testWidgets('header + 三级分区 + 各级进度渲染', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('milestoneHeader')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_l')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_m')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneSection_s')), findsOneWidget);
    expect(find.text('Momo'), findsOneWidget);
    expect(find.text('1/3'), findsOneWidget); // S 级进度
  });

  testWidgets('徽章彩色（已完成）/灰锁（未完成）', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('milestoneBadge_C-S1')), findsOneWidget);
    // 已完成 → 奖杯图标；未完成 → 锁图标。
    expect(find.byIcon(Icons.emoji_events_rounded), findsWidgets);
    expect(find.byIcon(Icons.lock_outline_rounded), findsWidgets);
  });

  testWidgets('点击系统自动徽章 → 只读说明（无打卡按钮）', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsNothing);
    expect(find.byKey(const ValueKey('milestoneGoPublish')), findsNothing);
  });

  testWidgets('点击用户打卡未完成徽章 → 「已打卡 / 去发布」两入口', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S6')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCheckedIn')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneGoPublish')), findsOneWidget);
  });

  testWidgets('加载失败 → F13 失败态 + 重试入口', (tester) async {
    await tester.pumpWidget(_wrap(error: Exception('boom')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneRetry')), findsOneWidget);
  });
}
