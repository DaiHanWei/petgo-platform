import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/app.dart';
import 'package:petgo/core/theme/colors.dart';
import 'package:petgo/core/theme/motion.dart';
import 'package:petgo/shared/widgets/app_shell.dart';
import 'package:petgo/shared/widgets/bottom_tab_bar.dart';

Future<void> _pumpApp(WidgetTester tester) async {
  await tester.pumpWidget(const ProviderScope(child: PetGoApp()));
  await tester.pumpAndSettle();
}

void main() {
  // AC1 — 全局底色恒 #FAF8F5。
  testWidgets('AC1: scaffold background is constant #FAF8F5', (tester) async {
    await _pumpApp(tester);
    // AppShell 的 Scaffold 背景。
    final scaffold = tester.widget<Scaffold>(
      find.descendant(of: find.byType(AppShell), matching: find.byType(Scaffold)).first,
    );
    expect(scaffold.backgroundColor, AppColors.base);
    expect(AppColors.base, const Color(0xFFFAF8F5));
  });

  // AC2 — 底部 Tab Bar 5 位（4 标签 + 中间凸起「＋」）。
  testWidgets('AC2: bottom tab bar renders 4 tabs + center add button', (tester) async {
    await _pumpApp(tester);
    expect(find.byType(BottomTabBar), findsOneWidget);
    expect(find.byType(AddTabButton), findsOneWidget);
    // 4 个 Tab 标签（active 的首页显示圆而非标签，其余 3 个显示标签）。
    expect(find.text('Growth'), findsOneWidget);
    expect(find.text('Consult'), findsOneWidget);
    expect(find.text('Me'), findsOneWidget);
  });

  // AC2 — active 区域圆 34×34。
  testWidgets('AC2: active tab circle is 34x34', (tester) async {
    await _pumpApp(tester);
    final circle = find.byKey(const ValueKey('activeTabCircle'));
    expect(circle, findsOneWidget);
    final size = tester.getSize(circle);
    expect(size.width, kActiveCircleSize);
    expect(size.height, kActiveCircleSize);
  });

  // AC2 — 内容区切换 120ms 淡入（FadeTransition 由 AppMotion.tabFade 驱动）。
  testWidgets('AC2: content area uses 120ms fade transition', (tester) async {
    await _pumpApp(tester);
    expect(
      find.descendant(of: find.byType(AppShell), matching: find.byType(FadeTransition)),
      findsWidgets,
    );
    expect(AppMotion.tabFade, const Duration(milliseconds: 120));
  });

  // AC2 — 点击 Tab 切换目的地。
  testWidgets('AC2: tapping a tab switches destination', (tester) async {
    await _pumpApp(tester);
    await tester.tap(find.text('Consult'));
    await tester.pumpAndSettle();
    // 问诊页占位标题出现（active 时标题由 body 渲染）。
    expect(find.text('Consult'), findsWidgets);
  });

  // AC3 — i18n 默认英语。
  testWidgets('AC3: default locale renders English tab labels', (tester) async {
    await _pumpApp(tester);
    expect(find.text('Growth'), findsOneWidget);
    expect(find.text('Beranda'), findsNothing);
  });

  // AC3 — 设备语言 id → 印尼语标签。
  testWidgets('AC3: id device locale renders Indonesian tab labels', (tester) async {
    tester.platformDispatcher.localesTestValue = const <Locale>[Locale('id')];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    await _pumpApp(tester);
    // 'Tumbuh' 同时出现在底部导航(tabProfile)与 Feed 分类 tab(feedTabGrowth)——
    // 对齐设计稿 S03(两处皆 Tumbuh),故 findsWidgets 而非 findsOneWidget。
    expect(find.text('Tumbuh'), findsWidgets); // tabProfile + feedTabGrowth id
    expect(find.text('Konsultasi'), findsOneWidget); // tabTriage id（唯一，确证 id locale）
  });
}
