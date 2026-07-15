import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（Story 7.3 · FR-47）：里程碑页顶部新手卡半完成 / 达成 / 失败重试三态。
const _emptyMilestones = MilestoneList(
  petName: 'Momo',
  completedCount: 0,
  totalCount: 0,
  groups: [],
);

NewbieTasks _tasks({required int done, bool unlocked = false}) {
  const keys = [
    'CREATE_PROFILE',
    'FIRST_PHOTO',
    'SHARE_CARD',
    'SAVE_CONSULT',
    'FIRST_DAILY',
    'FIRST_HEALTH_RECORD',
  ];
  return NewbieTasks(
    items: [
      for (var i = 0; i < keys.length; i++) NewbieTaskItem(key: keys[i], done: i < done),
    ],
    completedCount: done,
    total: keys.length,
    lulusPemulaUnlocked: unlocked,
  );
}

Widget _wrap({NewbieTasks? tasks, Object? error}) => ProviderScope(
      overrides: [
        milestoneListProvider.overrideWith((ref) async => _emptyMilestones),
        newbieTasksProvider.overrideWith((ref) async {
          if (error != null) throw error;
          return tasks ?? _tasks(done: 3);
        }),
      ],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: MilestoneListPage(),
      ),
    );

void main() {
  testWidgets('半完成态：进度 3/6 + 6 行清单 + 标签本地化', (tester) async {
    await tester.pumpWidget(_wrap(tasks: _tasks(done: 3)));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('newbieCard')), findsOneWidget);
    expect(find.text('3/6 done'), findsOneWidget);
    // 6 行任务标签（英文本地化）。
    expect(find.text('Create pet profile'), findsOneWidget);
    expect(find.text('Add a health record'), findsOneWidget);
    // 3 完成（勾）+ 3 未完成（空圈）。
    expect(find.byIcon(Icons.check_circle_rounded), findsNWidgets(3));
    expect(find.byIcon(Icons.radio_button_unchecked), findsNWidgets(3));
  });

  testWidgets('全完成态：显 Lulus Pemula 达成横幅，不铺开清单', (tester) async {
    await tester.pumpWidget(_wrap(tasks: _tasks(done: 6, unlocked: true)));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('newbieCardDone')), findsOneWidget);
    expect(find.byKey(const ValueKey('newbieCard')), findsNothing);
    expect(find.textContaining('Lulus Pemula'), findsOneWidget);
  });

  testWidgets('失败态：错误文案 + 重试按钮（可点）', (tester) async {
    await tester.pumpWidget(_wrap(error: Exception('boom')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('newbieCardError')), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    await tester.tap(find.text('Retry'));
    await tester.pump();
  });
}
