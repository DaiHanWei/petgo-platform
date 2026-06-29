import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（Story 8.5）：三级庆祝按级渲染 + S 自动消失 + L 开宝箱交互。视觉/计时观感 L2 待本地。
MilestoneItem _item(MilestoneLevel level) => MilestoneItem(
      code: 'C-X', title: '里程碑', level: level,
      trigger: MilestoneTrigger.userCheckin, completed: true, completedAt: DateTime(2026, 6, 1));

Widget _host(MilestoneItem item, {String petName = 'Momo', void Function()? onShare}) => MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: ElevatedButton(
              onPressed: () =>
                  showMilestoneCelebration(context, item, petName: petName, onShare: onShare),
              child: const Text('go'),
            ),
          ),
        ),
      ),
    );

void main() {
  testWidgets('统一 P-35 庆祝：S/M/L 都用同一全屏弹层（无半屏/全屏之分）', (tester) async {
    for (final level in MilestoneLevel.values) {
      await tester.pumpWidget(_host(_item(level)));
      await tester.tap(find.text('go'));
      await tester.pump(); // 打开
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
      // 不再有分级 key / 开宝箱。
      expect(find.byKey(const ValueKey('milestoneChestTap')), findsNothing);
      // 关闭，进入下一级别。
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrateSeeAll')));
      await tester.pumpAndSettle();
    }
  });

  testWidgets('统一庆祝不自动消失（停留至用户关闭）', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.s)));
    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    // 等远超旧的自动消失时长，仍在。
    await tester.pump(const Duration(seconds: 5));
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('milestoneCelebrateSeeAll')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });

  testWidgets('分享按钮：有 onShare 时显示，点击触发并关闭', (tester) async {
    var shared = false;
    await tester.pumpWidget(_host(_item(MilestoneLevel.l), onShare: () => shared = true));
    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('milestoneShare')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('milestoneShare')));
    await tester.pumpAndSettle();
    expect(shared, isTrue);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });

  testWidgets('卡片渲染庆祝标题 + 正文，并把 {name} 替换为宠物名（en）', (tester) async {
    await tester.pumpWidget(_host(
      MilestoneItem(
        code: 'C-S5',
        title: 'x',
        level: MilestoneLevel.s,
        trigger: MilestoneTrigger.userCheckin,
        completed: true,
        completedAt: DateTime(2026, 6, 1),
      ),
      petName: 'Momo',
    ));
    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    // 标题（emoji 结尾）+ 正文均出现，且 {name} 已替换为 Momo。
    expect(find.text('First post is live! ✨'), findsOneWidget);
    expect(find.text("Momo's story can now be seen by everyone."), findsOneWidget);
  });
}
