import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/domain/growth_badge.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/shared/widgets/growth_avatar_decoration.dart';

/// V1.4.0 ① Story 3.2 · AC1：头像角标 + 三档框（L0 结构断言，不做 golden）。
const _avatar = ColoredBox(key: ValueKey('avatar'), color: Colors.grey);

Future<void> _pump(WidgetTester tester, Widget child, {bool still = false}) async {
  await tester.pumpWidget(MaterialApp(
    home: MediaQuery(
      data: MediaQueryData(disableAnimations: still),
      child: Scaffold(body: Center(child: child)),
    ),
  ));
}

GrowthAvatarDecoration _deco(double d, {GrowthFrame? frame, bool showLevelBadge = true, int level = 12}) =>
    GrowthAvatarDecoration(
      badge: GrowthBadge(level: level, tier: GrowthTier.guardian, frame: frame),
      diameter: d,
      showLevelBadge: showLevelBadge,
      child: SizedBox(width: d, height: d, child: _avatar),
    );

void main() {
  test('数字 / 圆点阈值是一个常量 = 32', () {
    expect(kGrowthBadgeNumberMinDiameter, 32);
  });

  testWidgets('badge == null → 原样返回 child', (tester) async {
    await _pump(tester, const GrowthAvatarDecoration(badge: null, diameter: 32, child: _avatar));
    expect(find.byKey(const ValueKey('growthLevelBadge')), findsNothing);
    expect(find.byKey(const ValueKey('growthLevelDot')), findsNothing);
    expect(find.byType(CustomPaint).evaluate().where((e) => e.widget.key is ValueKey<String>), isEmpty);
    expect(find.byKey(const ValueKey('avatar')), findsOneWidget);
  });

  testWidgets('直径 32 / 36 / 56 / 84 → 数字；30 / 24 → 圆点', (tester) async {
    for (final d in [32.0, 36.0, 56.0, 84.0]) {
      await _pump(tester, _deco(d));
      expect(find.text('12'), findsOneWidget, reason: '$d');
      expect(find.byKey(const ValueKey('growthLevelDot')), findsNothing);
    }
    for (final d in [30.0, 24.0]) {
      await _pump(tester, _deco(d));
      expect(find.text('12'), findsNothing, reason: '$d');
      expect(find.byKey(const ValueKey('growthLevelDot')), findsOneWidget);
    }
  });

  testWidgets('角标尺寸照 A1：32 → 17，84 → 26，圆点 30 → 9 / 24 → 8', (tester) async {
    await _pump(tester, _deco(32));
    expect(tester.getSize(find.byKey(const ValueKey('growthLevelBadge'))).height, 17);
    await _pump(tester, _deco(84));
    expect(tester.getSize(find.byKey(const ValueKey('growthLevelBadge'))).height, 26);
    await _pump(tester, _deco(30));
    expect(tester.getSize(find.byKey(const ValueKey('growthLevelDot'))), const Size(9, 9));
    await _pump(tester, _deco(24));
    expect(tester.getSize(find.byKey(const ValueKey('growthLevelDot'))), const Size(8, 8));
  });

  testWidgets('三档框：各自的 CustomPaint，占位 = 直径 + 2 × 框厚（基础 3 / 升级动效 4）', (tester) async {
    await _pump(tester, _deco(32, frame: GrowthFrame.basic));
    expect(tester.getSize(find.byKey(const ValueKey('growthFrame_basic'))), const Size(38, 38));
    await _pump(tester, _deco(32, frame: GrowthFrame.upgraded));
    expect(tester.getSize(find.byKey(const ValueKey('growthFrame_upgraded'))), const Size(40, 40));
    await _pump(tester, _deco(32, frame: GrowthFrame.animated), still: true);
    expect(tester.getSize(find.byKey(const ValueKey('growthFrame_animated'))), const Size(40, 40));
    // 头像本体尺寸不变（框画在外面）。
    expect(tester.getSize(find.byKey(const ValueKey('avatar'))), const Size(32, 32));
  });

  testWidgets('动效框：disableAnimations → 静止无 ticker；否则持续转（8 秒一圈）', (tester) async {
    await _pump(tester, _deco(32, frame: GrowthFrame.animated), still: true);
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);

    await _pump(tester, _deco(32, frame: GrowthFrame.animated));
    await tester.pump(const Duration(seconds: 4));
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pump(const Duration(seconds: 4));
    expect(tester.binding.hasScheduledFrame, isTrue, reason: 'repeat：一圈后继续');
    // 拆掉以免挂着 ticker。
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('showLevelBadge: false → 有框无角标', (tester) async {
    await _pump(tester, _deco(84, frame: GrowthFrame.basic, showLevelBadge: false));
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLevelBadge')), findsNothing);
  });

  testWidgets('角标不吃点击、不进读屏：点角标位置 = 点头像', (tester) async {
    var taps = 0;
    final semantics = tester.ensureSemantics();
    await _pump(tester, GestureDetector(onTap: () => taps++, child: _deco(56)));
    final badge = find.byKey(const ValueKey('growthLevelBadge'));
    await tester.tapAt(tester.getCenter(badge) - const Offset(2, 2));
    expect(taps, 1);
    expect(find.bySemanticsLabel('12'), findsNothing);
    semantics.dispose();
  });

  testWidgets('动效框：「移除动画」开 → 关 → 开、换框 animated → basic → animated，同一 State 反复建销 ticker 不报错', (tester) async {
    Widget host(bool still, GrowthFrame frame) => MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: still),
            child: Scaffold(body: Center(child: _deco(32, frame: frame))),
          ),
        );
    await tester.pumpWidget(host(false, GrowthFrame.animated));
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpWidget(host(true, GrowthFrame.animated));
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    await tester.pumpWidget(host(false, GrowthFrame.animated));
    await tester.pump(const Duration(seconds: 1));
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pumpWidget(host(false, GrowthFrame.basic));
    await tester.pump();
    await tester.pumpWidget(host(false, GrowthFrame.animated));
    await tester.pump(const Duration(seconds: 1));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
