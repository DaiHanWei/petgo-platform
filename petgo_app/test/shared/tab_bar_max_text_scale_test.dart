import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// 底部导航在**最大字号**下不得溢出，也不得靠切掉文字来避免溢出（2026-08-04 模拟器实测发现，
/// 同日 code-review 补强）。
///
/// 为什么这是真缺陷而不是「极端情况」：`app.dart` 把全局 `textScaler` 明确 clamp 到 **1.3**
/// （NFR-13），也就是说 1.3 是**受支持的状态**，不是越界输入。系统字号调到最大的用户
/// 在真机上能看到底栏冒出「BOTTOM OVERFLOWED BY 2.0 PIXELS」的红条。
///
/// 底栏是**固定高度**（66px，对齐原型），所以标签不能无限跟着系统字号放大。
/// 修法：只给标签单独一个更紧的 clamp（图标承担辨识，标签是辅助信息），栏高与设计稿不变。
///
/// ⚠️ 这里**不能用 `find.text` 来断言「标签还在」**：`find.text` 匹配的是 `Text.data`，
/// 文字被省略号截断时它照样能找到。原先那条断言因此是空的 —— 实测印尼语 `Kesehatan`
/// 在 411dp 默认字号下就已经显示成「Kesehata…」而测试全绿。改用 `didExceedMaxLines`
/// 直接问渲染对象「你有没有被截断」，这也是 OQ-19 把印尼语标签定为 `Health` 的直接原因。
///
/// 📌 2026-08-04 用户决策：第 1 位 Tab 由 `Jelajah` / `Discovery` 改为 **`Sosial` / `Social`**。
/// 起因是 `Jelajah`（7 字符）从 ×1.15 起被省略号截断，此前作为「已知取舍」接受了；实测发现
/// 英文侧 `Discovery`（9 字符）在 ×1.3 下同样被截断，而**当时这条测试只跑 id locale，
/// 英文侧的同一个缺陷一直没被看见**。因此本轮同时做两件事：
///   1. 换成实测放得下的词（每格约 66px，6 字符是天花板；`Beranda` 7 字符同样不行）；
///   2. 把断言从「允许放大字号下截断」收紧为「**两种语言 × 全部受支持档位都不许截断**」，
///      并补上 en locale 覆盖 —— 缺失的覆盖面本身就是上次漏判的原因。
void main() {
  Widget bar(double scale, Locale locale) => MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: locale,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: Scaffold(
          bottomNavigationBar: BottomTabBar(currentIndex: 0, onTabSelected: (_) {}),
        ),
      );

  /// 411dp：Pixel 类机型的实际逻辑宽度（1080px / 420dpi），也是本项目模拟器的宽度。
  const Size referenceDevice = Size(411, 900);

  /// 两种语言的四个标签全列出来 —— 只测一种语言正是上次放跑 `Discovery` 的原因。
  const labelsByLocale = <String, List<String>>{
    'id': ['Diary', 'Health', 'Sosial', 'Saya'],
    'en': ['Diary', 'Health', 'Social', 'Me'],
  };

  for (final entry in labelsByLocale.entries) {
    final locale = Locale(entry.key);
    final labels = entry.value;

    for (final scale in <double>[1.0, 1.15, 1.3]) {
      testWidgets('[${entry.key}] 字号 ×$scale 下底栏不溢出', (tester) async {
        await tester.binding.setSurfaceSize(referenceDevice);
        addTearDown(() => tester.binding.setSurfaceSize(null));

        await tester.pumpWidget(bar(scale, locale));
        await tester.pumpAndSettle();

        // RenderFlex 溢出会以 FlutterError 形式抛出，被测试框架捕获 → 这里断言没有。
        expect(tester.takeException(), isNull);
      });

      testWidgets('[${entry.key}] 字号 ×$scale 下四个标签一个都不少', (tester) async {
        await tester.binding.setSurfaceSize(referenceDevice);
        addTearDown(() => tester.binding.setSurfaceSize(null));

        await tester.pumpWidget(bar(scale, locale));
        await tester.pumpAndSettle();

        for (final label in labels) {
          expect(
            find.descendant(of: find.byType(BottomTabBar), matching: find.text(label)),
            findsOneWidget,
            reason: '标签 $label 不该消失 —— 「把文字删掉」不是修溢出的正确姿势',
          );
        }
      });

      testWidgets('[${entry.key}] 字号 ×$scale 下没有一个标签被省略号截断', (tester) async {
        // **这条是硬底线**：1.3 是 NFR-13 明确支持的档位，不是越界输入，所以「放大字号下
        // 允许截断」不再被接受。任一标签在这里变红，只有两条出路：换更短的词（`Sosial` /
        // `Social` 就是这么定的），或者给标签区更多横向空间 —— 不许再退回「已知取舍」。
        await tester.binding.setSurfaceSize(referenceDevice);
        addTearDown(() => tester.binding.setSurfaceSize(null));

        await tester.pumpWidget(bar(scale, locale));
        await tester.pumpAndSettle();

        for (final label in labels) {
          final finder = find.descendant(
            of: find.byType(BottomTabBar),
            matching: find.text(label),
          );
          expect(
            tester.renderObject<RenderParagraph>(finder).didExceedMaxLines,
            isFalse,
            reason: '标签 $label（${entry.key}）在 ×$scale 下被省略号截断了',
          );
        }
      });
    }
  }
}
