import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（Story 8.5）：三级庆祝按级渲染 + S 自动消失 + L 开宝箱交互。视觉/计时观感 L2 待本地。
MilestoneItem _item(MilestoneLevel level) => MilestoneItem(
      code: 'C-X', title: '里程碑', level: level,
      trigger: MilestoneTrigger.userCheckin, completed: true, completedAt: DateTime(2026, 6, 1));

Widget _host(MilestoneItem item) => MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: ElevatedButton(
              onPressed: () => showMilestoneCelebration(context, item),
              child: const Text('go'),
            ),
          ),
        ),
      ),
    );

void main() {
  testWidgets('S 级半屏弹层渲染后自动消失', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.s)));
    await tester.tap(find.text('go'));
    await tester.pump(); // 打开
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneCelebrationS')), findsOneWidget);
    // S 级 hold 3.5s（widget `_holdDuration`）后自动关闭。确定性推进越过该计时 + 跑完出场过场，
    // 避免之前 pump 1.6s（< 3.5s）靠 pumpAndSettle 偶然多跑才过的「伪 flaky」。
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCelebrationS')), findsNothing);
  });

  testWidgets('M 级全屏动效渲染', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.m)));
    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneCelebrationM')), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 3100));
    await tester.pumpAndSettle();
  });

  testWidgets('L 级开宝箱：点击宝箱 → 解锁徽章', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.l)));
    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneCelebrationL')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneChestTap')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('milestoneChestTap')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    // 开箱后宝箱消失（进入解锁态）。
    expect(find.byKey(const ValueKey('milestoneChestTap')), findsNothing);
    await tester.pump(const Duration(milliseconds: 4100));
    await tester.pumpAndSettle();
  });
}
