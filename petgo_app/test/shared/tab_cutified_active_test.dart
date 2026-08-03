import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/motion.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// Story 1.2 · L0：Tab 激活态萌化「方案A」（FR-78A / UI 稿 T3）。
///
/// 方案A 三要素：① glyph 转品牌紫 + 柔和圆角高亮底 ② 叠加一处宠物特征装饰
/// ③ 一次 ≤150ms 轻弹跳。**替换**（非叠加）V1.0 pop-art 的红 (3,3) 错位投影。
/// 硬约束：glyph 尺寸/形状两态一致——用户靠形状辨识 Tab，不能改辨识锚点。
void main() {
  Future<void> pump(WidgetTester tester, {required int currentIndex, bool reduceMotion = false}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Builder(builder: (context) {
        return MediaQuery(
          data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion),
          child: Scaffold(
            bottomNavigationBar: BottomTabBar(currentIndex: currentIndex, onTabSelected: (_) {}),
          ),
        );
      }),
    ));
    await tester.pumpAndSettle();
  }

  group('AC1 方案A 激活态三要素', () {
    testWidgets('激活态：紫 glyph + 柔和圆角高亮底 + 一处宠物特征装饰', (tester) async {
      await pump(tester, currentIndex: 0); // Diary 激活

      expect(find.byKey(const ValueKey('activeTabIcon')), findsOneWidget);
      expect(find.byKey(const ValueKey('activeTabHighlight')), findsOneWidget,
          reason: '柔和圆角高亮底');
      expect(find.byKey(const ValueKey('activeTabCharm')), findsOneWidget,
          reason: '一处宠物特征装饰（Diary=猫耳）');
    });

    testWidgets('未选中态：无高亮底、无装饰，维持描边', (tester) async {
      await pump(tester, currentIndex: 0);
      // 全栏 4 位，仅 1 位激活 → 高亮底/装饰各只有 1 个（其余 3 位皆无）
      expect(find.byKey(const ValueKey('activeTabHighlight')), findsOneWidget);
      expect(find.byKey(const ValueKey('activeTabCharm')), findsOneWidget);
      expect(find.byKey(const ValueKey('inactiveTabIcon')), findsNWidgets(3));
    });

    testWidgets('AC1 硬约束：glyph 尺寸两态一致（辨识锚点不变）', (tester) async {
      await pump(tester, currentIndex: 0);
      final active = tester.widget<SvgPicture>(find.byKey(const ValueKey('activeTabIcon')));
      final inactive = tester.widget<SvgPicture>(find.byKey(const ValueKey('inactiveTabIcon')).first);
      expect(active.width, inactive.width);
      expect(active.height, inactive.height);
    });

    testWidgets('每个 Tab 激活时都只有一处装饰（猫耳/爪印/尾巴/项圈铃铛）', (tester) async {
      for (int i = 0; i < AppTab.values.length; i++) {
        await pump(tester, currentIndex: i);
        expect(find.byKey(const ValueKey('activeTabCharm')), findsOneWidget,
            reason: '${AppTab.values[i]} 激活时应有且仅有一处装饰');
      }
    });
  });

  group('AC3 替换 pop-art 错位投影（非叠加）', () {
    testWidgets('激活态不再出现红色错位投影层', (tester) async {
      await pump(tester, currentIndex: 0);
      expect(find.byKey(const ValueKey('activeTabPopShadow')), findsNothing,
          reason: '方案A 为「替换」——两者叠加视觉过噪');
    });
  });

  group('AC2 入场动效与 reduced-motion', () {
    test('弹跳时长 ≤150ms', () {
      expect(AppMotion.tabCharmBounce.inMilliseconds, lessThanOrEqualTo(150));
    });

    testWidgets('开启「减少动态效果」时不播动效，但激活态照常切换', (tester) async {
      await pump(tester, currentIndex: 0, reduceMotion: true);

      // 状态仍然切换（icon-system.md：duration=0，仅去除动画过程）
      expect(find.byKey(const ValueKey('activeTabIcon')), findsOneWidget);
      expect(find.byKey(const ValueKey('activeTabCharm')), findsOneWidget);

      final scale = tester.widget<AnimatedScale>(
        find.byKey(const ValueKey('activeTabBounce')),
      );
      expect(scale.duration, Duration.zero, reason: 'reduced-motion 下时长归 0');
    });

    testWidgets('未开启时按 tabCharmBounce 时长播放', (tester) async {
      await pump(tester, currentIndex: 0);
      final scale = tester.widget<AnimatedScale>(
        find.byKey(const ValueKey('activeTabBounce')),
      );
      expect(scale.duration, AppMotion.tabCharmBounce);
    });
  });
}
