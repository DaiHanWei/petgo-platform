import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/presentation/widgets/diary_header.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/count_badge.dart';

/// V1.3.0 批次 A · Story 1.5（L0）：未庆祝角标 + 庆祝埋点。
///
/// 角标的**像素观感**属 L2（真机看红点位置压不压字），这里只钉三件 L0 能钉的：
/// 有未庆祝才出角标、数字对、以及**它就是通知铃铛那颗角标**（同一个 [CountBadge]，不是另画的）。
void main() {
  Widget host(int uncelebrated) => MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: DiaryHeader(
              profile: const PetHeaderInfo(name: 'Momo', petType: 'CAT'),
              milestoneCompleted: 3,
              milestoneTotal: 30,
              milestoneUncelebrated: uncelebrated,
            ),
          ),
        ),
      );

  group('AC1 档案 Tab 角标', () {
    testWidgets('无未庆祝 → 不出角标（0 不该渲染一个空药丸）', (tester) async {
      await tester.pumpWidget(host(0));
      expect(find.byKey(const ValueKey('archiveMilestoneBar')), findsOneWidget);
      expect(find.byKey(const ValueKey('milestoneUncelebratedBadge')), findsNothing);
    });

    testWidgets('有未庆祝 → 出角标并显示条数', (tester) async {
      await tester.pumpWidget(host(2));
      expect(find.byKey(const ValueKey('milestoneUncelebratedBadge')), findsOneWidget);
      expect(find.text('2'), findsOneWidget);
    });

    /// AC1：角标**直接复用**通知铃铛那颗，不另画。
    /// 断言"是同一个组件类型"而不是逐条比对颜色圆角字号 —— 后者等于把样式抄第三遍。
    testWidgets('🔴 角标复用 CountBadge，不是另画的一个', (tester) async {
      await tester.pumpWidget(host(5));
      final badge = tester.widget<CountBadge>(
        find.byKey(const ValueKey('milestoneUncelebratedBadge')),
      );
      expect(badge.count, 5);
    });

    testWidgets('超过 99 显示 99+（与通知未读数同规则）', (tester) async {
      await tester.pumpWidget(host(120));
      expect(find.text('99+'), findsOneWidget);
    });

    /// AD-A2.1：档案 Tab **永不弹全屏庆祝**，只显示角标。
    /// 两处都弹的话，用户从这里点进列表页会被连弹两次。
    testWidgets('🔴 档案 Tab 只出角标，不弹全屏庆祝', (tester) async {
      await tester.pumpWidget(host(3));
      await tester.pump(const Duration(seconds: 2));
      expect(find.byType(Dialog), findsNothing);
      expect(find.byKey(const ValueKey('milestoneUncelebratedBadge')), findsOneWidget);
    });
  });

  group('AC6 milestone_celebration_shown 埋点', () {
    testWidgets('展示即上报，携带 code / level / path，且不含 PII', (tester) async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: Builder(
            builder: (context) => ElevatedButton(
              onPressed: () => showMilestoneCelebration(
                context,
                const MilestoneItem(
                  code: 'C-L2',
                  title: '陪伴满 100 天',
                  level: MilestoneLevel.l,
                  trigger: MilestoneTrigger.pushPublish,
                  completed: true,
                ),
                petName: 'Momo',
                path: MilestoneCelebrationPath.catchup,
              ),
              child: const Text('go'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('go'));
      await tester.pump();

      final ev = seen.firstWhere((e) => e.key == 'milestone_celebration_shown');
      expect(ev.value?['code'], 'C-L2');
      expect(ev.value?['level'], 'L');
      expect(ev.value?['path'], 'catchup');
      // NFR-5：埋点严禁 PII —— 宠物名传进了组件，但绝不能进埋点属性。
      expect(ev.value?.values.contains('Momo'), isFalse);
    });
  });
}
