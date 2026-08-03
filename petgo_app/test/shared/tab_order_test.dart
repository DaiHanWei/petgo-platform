import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// Story 1.1 · L0：底部 Tab 顺序与底层索引一致性（AD-3）。
///
/// <p>AD-3 的风险不在代码量，而在**漏改**：Tab 索引被多处引用，改一处漏一处就是
/// 「点推送进错页面」这类难排查的问题。本文件把「顺序一致」变成可机械验证的断言。
///
/// <p>路由分支顺序不在此断言——实现改为**按 `AppTab.values` 循环生成分支**，
/// 顺序一致由构造方式保证（不可能漂移），再写断言就是同义反复。
void main() {
  group('AD-3 Tab 顺序与索引一致性', () {
    test('AC2：枚举顺序 == 视觉顺序 Diary / Health / [+] / Discovery / Me', () {
      expect(
        AppTab.values,
        <AppTab>[AppTab.profile, AppTab.triage, AppTab.home, AppTab.me],
        reason: 'Diary(profile) → Health(triage) → [+] → Discovery(home) → Me；'
            '[+] 是独立凸起按钮，不占导航分支',
      );
    });

    test('AC3③：路径映射内嵌在枚举上，不存在会走歧的并行数组', () {
      expect(AppTab.profile.location, '/profile');
      expect(AppTab.triage.location, '/triage');
      expect(AppTab.home.location, '/home');
      expect(AppTab.me.location, '/me');
      // 顺序与枚举一致（等价于原 _tabLocations 数组，但无法与枚举脱节）
      expect(
        AppTab.values.map((t) => t.location).toList(),
        <String>['/profile', '/triage', '/home', '/me'],
      );
    });

    test('AC3①：免门控判定语义化（Story 2.4 起含 Diary）', () {
      // 去索引化：白名单按 Tab 语义表达，不再比较裸索引。
      // 集合内容的守门断言在 test/shared/diary_gating_and_landing_test.dart（Story 2.4，双向门控）；
      // 这里只锁「判定是语义化的、且 Health/Me 不在其中」。
      expect(kUngatedTabs, <AppTab>{AppTab.home, AppTab.profile},
          reason: 'Story 2.4 放行 Diary 主页给游客（其子页仍受深链门控）');
      expect(kUngatedTabs.contains(AppTab.triage), isFalse);
      expect(kUngatedTabs.contains(AppTab.me), isFalse);
    });
  });

  group('AC1 底栏渲染顺序与文案', () {
    Future<List<String>> renderLabels(WidgetTester tester, Locale locale) async {
      await tester.pumpWidget(MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          bottomNavigationBar: BottomTabBar(currentIndex: 0, onTabSelected: (_) {}),
        ),
      ));
      await tester.pumpAndSettle();
      // 按渲染树顺序收集标签 = 视觉从左到右
      return tester.widgetList<Text>(find.byType(Text)).map((t) => t.data ?? '').toList();
    }

    testWidgets('英文：Diary / Health / Discovery / Me', (tester) async {
      final labels = await renderLabels(tester, const Locale('en'));
      expect(labels, <String>['Diary', 'Health', 'Discovery', 'Me']);
    });

    testWidgets('印尼文：Diary / Kesehatan / Jelajah / Saya', (tester) async {
      final labels = await renderLabels(tester, const Locale('id'));
      expect(labels, <String>['Diary', 'Kesehatan', 'Jelajah', 'Saya']);
    });

    testWidgets('点击第 1 位回传的索引指向 Diary 分支', (tester) async {
      int? tapped;
      await tester.pumpWidget(MaterialApp(
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          bottomNavigationBar: BottomTabBar(currentIndex: 0, onTabSelected: (i) => tapped = i),
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Diary'));
      await tester.pumpAndSettle();

      expect(tapped, AppTab.profile.index);
      expect(AppTab.values[tapped!].location, '/profile');
    });
  });
}
