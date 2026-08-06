import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/core/theme/motion.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// Story 1.2 · L0：Tab 激活态萌化「方案A」（FR-78A / UI 稿 T3）。
///
/// 方案A 三要素：① glyph 转品牌紫 + 柔和圆角高亮底 ② 叠加一处宠物特征装饰
/// ③ 一次 ≤150ms 轻弹跳。**替换**（非叠加）V1.0 pop-art 的红 (3,3) 错位投影。
/// 硬约束：glyph 尺寸/形状两态一致——用户靠形状辨识 Tab，不能改辨识锚点。
const String _violetHex = '#845EC9';

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

  group('AC1 逐条对齐 T3 稿的几何/配色（原先四处装饰被塞成同一个「右上角小紫图标」，与稿差很远）', () {
    testWidgets('高亮底：44×44 实底 violet-tint、圆角 13（不是半透明小方块）', (tester) async {
      await pump(tester, currentIndex: 0);

      final box = tester.getSize(find.byKey(const ValueKey('activeTabHighlight')));
      expect(box.width, 44);
      expect(box.height, 44);

      final deco = tester
          .widget<DecoratedBox>(find.byKey(const ValueKey('activeTabHighlight')))
          .decoration as BoxDecoration;
      expect(deco.color, AppColors.mintTint, reason: 'T3：violet-tint #F8F2FF 实底，非紫色半透明');
      expect(deco.borderRadius, BorderRadius.circular(13));
    });

    testWidgets('Diary 猫耳：顶部居中长在书上沿（不是右上角挂饰）+ 含粉色内耳', (tester) async {
      await pump(tester, currentIndex: AppTab.profile.index);

      final charm = find.byKey(const ValueKey('activeTabCharm'));
      final slot = find.byKey(const ValueKey('activeTabHighlight'));
      final charmRect = tester.getRect(charm);
      final slotRect = tester.getRect(slot);

      // 水平居中（容 1px 舍入）
      expect((charmRect.center.dx - slotRect.center.dx).abs() < 1, isTrue,
          reason: '猫耳应在书的正上方居中，实测偏移 ${charmRect.center.dx - slotRect.center.dx}');
      // 贴着高亮底顶部（top≈5）
      expect((charmRect.top - (slotRect.top + 5)).abs() < 1.5, isTrue,
          reason: '猫耳应距顶 5px');
      // 扁形：宽 23 高 ≈11（不是被拉成方的）
      expect(charmRect.width, 23);
      expect(charmRect.height < charmRect.width * 0.6, isTrue, reason: '猫耳是扁的，不能拉方');

      final ears = tabCharmSvg(AppTab.profile);
      expect(ears.contains('#F7C9D9'), isTrue, reason: 'T3：粉色内耳');
      expect(ears.contains('#845EC9'), isTrue, reason: '紫色外耳');
    });

    testWidgets('Health 爪印：右上角外沿 16×16 + 白描边（叠在图标上不糊）', (tester) async {
      await pump(tester, currentIndex: AppTab.triage.index);

      final charmRect = tester.getRect(find.byKey(const ValueKey('activeTabCharm')));
      final slotRect = tester.getRect(find.byKey(const ValueKey('activeTabHighlight')));
      expect(charmRect.width, 16);
      expect((charmRect.top - (slotRect.top - 3)).abs() < 1.5, isTrue);
      expect((charmRect.right - (slotRect.right + 3)).abs() < 1.5, isTrue);

      expect(tabCharmSvg(AppTab.triage).contains('#FFFFFF'), isTrue,
          reason: '白描边垫底（近似稿子的白发光）');
    });

    testWidgets('Discovery 尾巴：**右下角** 18×18 且为描边弧线（不是实心团）', (tester) async {
      await pump(tester, currentIndex: AppTab.home.index);

      final charmRect = tester.getRect(find.byKey(const ValueKey('activeTabCharm')));
      final slotRect = tester.getRect(find.byKey(const ValueKey('activeTabHighlight')));
      expect(charmRect.width, 18);
      expect((charmRect.bottom - (slotRect.bottom + 1)).abs() < 1.5, isTrue,
          reason: 'T3：尾巴在右下角，不是右上角');
      expect((charmRect.right - (slotRect.right + 4)).abs() < 1.5, isTrue);

      final tail = tabCharmSvg(AppTab.home);
      expect(tail.contains('fill="none"'), isTrue, reason: '尾巴是描边弧线，填充会糊成一坨');
      expect(tail.contains('stroke-width="2.6"'), isTrue);
    });

    testWidgets('Me 项圈铃铛：居中盖在人像上、26×26、**金色**（不是紫色角标）', (tester) async {
      await pump(tester, currentIndex: AppTab.me.index);

      final charmRect = tester.getRect(find.byKey(const ValueKey('activeTabCharm')));
      final slotRect = tester.getRect(find.byKey(const ValueKey('activeTabHighlight')));
      expect(charmRect.width, 26);
      expect((charmRect.center.dx - slotRect.center.dx).abs() < 1, isTrue);
      expect((charmRect.center.dy - slotRect.center.dy).abs() < 1, isTrue);

      final collar = tabCharmSvg(AppTab.me);
      expect(collar.contains('#F6A609'), isTrue, reason: 'T3：项圈铃铛是金色');
      expect(collar.contains('#845EC9'), isFalse, reason: '不该是紫色（那是早期实现的偏差）');
    });

    testWidgets('Health 激活 glyph 仍为描边（听诊器实心会糊）；其余三个为实心', (tester) async {
      final steth = tabActiveGlyphSvg(AppTab.triage);
      expect(steth.contains('fill="none"'), isTrue);
      expect(steth.contains('stroke-width="1.8"'), isTrue, reason: 'T3 激活描边 1.8');

      for (final tab in [AppTab.profile, AppTab.home, AppTab.me]) {
        expect(tabActiveGlyphSvg(tab).contains('fill="$_violetHex"'), isTrue,
            reason: '$tab 激活态应为紫色实心');
      }
      // 两态形状一致（辨识锚点）：未选态用同一组 path，仅换成描边。
      for (final tab in AppTab.values) {
        expect(tabInactiveGlyphSvg(tab).contains('stroke-opacity="0.55"'), isTrue);
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
