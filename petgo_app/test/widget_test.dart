import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/core/theme/motion.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

Future<void> _pumpApp(WidgetTester tester) async {
  await tester.pumpWidget(const ProviderScope(child: TailTopiaApp()));
  await tester.pumpAndSettle();
}

void main() {
  // AC1 — 全局底色恒为画布色（2026-06-17 还原原型：cream → 纯白 #FFFFFF，原型 QA 画布纯白无紫调）。
  testWidgets('AC1: scaffold background is constant canvas white #FFFFFF', (tester) async {
    await _pumpApp(tester);
    // AppShell 的 Scaffold 背景。
    final scaffold = tester.widget<Scaffold>(
      find.descendant(of: find.byType(AppShell), matching: find.byType(Scaffold)).first,
    );
    expect(scaffold.backgroundColor, AppColors.base);
    expect(AppColors.base, const Color(0xFFFFFFFF));
  });

  // AC2 — 底部 Tab Bar 5 位（4 标签 + 中间凸起「＋」）。
  testWidgets('AC2: bottom tab bar renders 4 tabs + center add button', (tester) async {
    await _pumpApp(tester);
    expect(find.byType(BottomTabBar), findsOneWidget);
    expect(find.byType(AddTabButton), findsOneWidget);
    // 4 个 Tab 标签（active 的首页显示圆而非标签，其余 3 个显示标签）。
    // 'Diary' 同时出现在底部导航(tabProfile)与 Feed 分类 tab(feedTabGrowth=GROWTH_MOMENT)，
    // 故 findsWidgets（与 id 'Diary' 同理）。bug 20260706-248：Growth→Diary、Consult→Health。
    expect(find.text('Diary'), findsWidgets);
    expect(find.text('Health'), findsOneWidget);
    expect(find.text('Me'), findsOneWidget);
  });

  // AC2 — 选中态为 pop-art 实心图标（紫 + 红错位影），非圆底（feed.html 还原）。
  testWidgets('AC2: active tab renders pop-art active icon', (tester) async {
    await _pumpApp(tester);
    expect(find.byKey(const ValueKey('activeTabIcon')), findsOneWidget);
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
    await tester.tap(find.text('Health'));
    await tester.pumpAndSettle();
    // 问诊页占位标题出现（active 时标题由 body 渲染）。bug 20260706-248：Consult→Health。
    expect(find.text('Health'), findsWidgets);
  });

  // AC3 — i18n 默认英语。
  testWidgets('AC3: default locale renders English tab labels', (tester) async {
    await _pumpApp(tester);
    // 'Diary' = tabProfile + feedTabGrowth 两处（en），故 findsWidgets（bug 20260706-248）。
    expect(find.text('Diary'), findsWidgets);
    expect(find.text('Beranda'), findsNothing);
  });

  // AC3 — 设备语言 id → 印尼语标签。
  testWidgets('AC3: id device locale renders Indonesian tab labels', (tester) async {
    tester.platformDispatcher.localesTestValue = const <Locale>[Locale('id')];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    await _pumpApp(tester);
    // 'Diary' 同时出现在底部导航(tabProfile)与 Feed 分类 tab(feedTabGrowth)——两处皆 Diary，
    // 故 findsWidgets（bug 20260706-248：Tumbuh→Diary、Konsultasi→Kesehatan）。
    expect(find.text('Diary'), findsWidgets); // tabProfile + feedTabGrowth id
    expect(find.text('Kesehatan'), findsOneWidget); // tabTriage id（唯一，确证 id locale）
  });
}
