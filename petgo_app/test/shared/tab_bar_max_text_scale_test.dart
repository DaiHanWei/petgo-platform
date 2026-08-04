import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// 底部导航在**最大字号**下不得溢出（2026-08-04 模拟器实测发现）。
///
/// 为什么这是真缺陷而不是「极端情况」：`app.dart` 把全局 `textScaler` 明确 clamp 到 **1.3**
/// （NFR-13），也就是说 1.3 是**受支持的状态**，不是越界输入。系统字号调到最大的用户
/// 在真机上能看到底栏冒出「BOTTOM OVERFLOWED BY 2.0 PIXELS」的红条 —— 印尼语标签更长，
/// 更容易触发。
///
/// 底栏是**固定高度**（66px，对齐原型），所以标签不能无限跟着系统字号放大。
/// 修法：只给标签单独一个更紧的 clamp（图标承担辨识，标签是辅助信息），栏高与设计稿不变。
void main() {
  Widget bar(double scale) => MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'), // 印尼语标签最长（Kesehatan / Jelajah）
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: Scaffold(
          bottomNavigationBar: BottomTabBar(currentIndex: 0, onTabSelected: (_) {}),
        ),
      );

  for (final scale in <double>[1.0, 1.15, 1.3]) {
    testWidgets('字号 ×$scale 下底栏不溢出', (tester) async {
      // 411dp：Pixel 类机型的实际逻辑宽度（1080px / 420dpi），也是本项目模拟器的宽度。
      await tester.binding.setSurfaceSize(const Size(411, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(bar(scale));
      await tester.pumpAndSettle();

      // RenderFlex 溢出会以 FlutterError 形式抛出，被测试框架捕获 → 这里断言没有。
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('标签在大字号下仍然渲染（不是靠隐藏文字来避免溢出的）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(411, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(bar(1.3));
    await tester.pumpAndSettle();

    // 四个标签一个都不能少 —— 「把文字删掉」不是修溢出的正确姿势。
    expect(find.descendant(of: find.byType(BottomTabBar), matching: find.text('Diary')),
        findsOneWidget);
    expect(find.descendant(of: find.byType(BottomTabBar), matching: find.text('Kesehatan')),
        findsOneWidget);
    expect(find.descendant(of: find.byType(BottomTabBar), matching: find.text('Jelajah')),
        findsOneWidget);
    expect(
        find.descendant(of: find.byType(BottomTabBar), matching: find.text('Saya')), findsOneWidget);
  });
}
