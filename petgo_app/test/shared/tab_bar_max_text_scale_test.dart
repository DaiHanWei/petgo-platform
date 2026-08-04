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
void main() {
  Widget bar(double scale) => MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'), // 印尼语标签最长（Jelajah / Health）
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

  const labels = ['Diary', 'Health', 'Jelajah', 'Saya'];

  for (final scale in <double>[1.0, 1.15, 1.3]) {
    testWidgets('字号 ×$scale 下底栏不溢出', (tester) async {
      await tester.binding.setSurfaceSize(referenceDevice);
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(bar(scale));
      await tester.pumpAndSettle();

      // RenderFlex 溢出会以 FlutterError 形式抛出，被测试框架捕获 → 这里断言没有。
      expect(tester.takeException(), isNull);
    });

    testWidgets('字号 ×$scale 下四个标签一个都不少', (tester) async {
      await tester.binding.setSurfaceSize(referenceDevice);
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(bar(scale));
      await tester.pumpAndSettle();

      for (final label in labels) {
        expect(
          find.descendant(of: find.byType(BottomTabBar), matching: find.text(label)),
          findsOneWidget,
          reason: '标签 $label 不该消失 —— 「把文字删掉」不是修溢出的正确姿势',
        );
      }
    });
  }

  testWidgets('默认字号下四个标签都完整显示，没有一个被省略号截断', (tester) async {
    // **默认字号是底线**：绝大多数用户就在这一档，标签被切在这里是产品缺陷而不是取舍。
    // 这条断言是 OQ-19 把印尼语第 2 位 Tab 定为 `Health`（而非 `Kesehatan`）的直接依据 ——
    // `Kesehatan` 在 411dp × 1.0 下就已经显示成「Kesehata…」。
    await tester.binding.setSurfaceSize(referenceDevice);
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(bar(1.0));
    await tester.pumpAndSettle();

    for (final label in labels) {
      final finder = find.descendant(
        of: find.byType(BottomTabBar),
        matching: find.text(label),
      );
      expect(
        tester.renderObject<RenderParagraph>(finder).didExceedMaxLines,
        isFalse,
        reason: '标签 $label 在默认字号下被省略号截断了 —— '
            '要么缩短文案（OQ-19 就是这么定的），要么给标签更多横向空间',
      );
    }
  });

  testWidgets('放大字号时允许标签截断，但必须是「截断」而不是「溢出」', (tester) async {
    // 📌 已知取舍（code-review 2026-08-04）：底栏固定 66px 高、每格标签只有约 66px 宽，
    // `Jelajah` 在 ×1.15 起会被省略号截断（`Health`/`Diary`/`Saya` 三档都放得下）。
    // 之所以接受：图标承担辨识、标签是辅助信息，而另一条路（让栏体随字号变高）会破坏设计稿。
    // 若要在放大字号下也完整显示，需产品先定更短的 Discovery 文案 —— 与 OQ-19 同类问题，
    // 已记入 story 6-1 的 Review Findings 待定项。这条测试锁住的是「不许再回到溢出」。
    await tester.binding.setSurfaceSize(referenceDevice);
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(bar(1.3));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull, reason: '放大字号下也不许出现 RenderFlex 溢出');
    for (final label in const ['Diary', 'Health', 'Saya']) {
      final finder = find.descendant(
        of: find.byType(BottomTabBar),
        matching: find.text(label),
      );
      expect(
        tester.renderObject<RenderParagraph>(finder).didExceedMaxLines,
        isFalse,
        reason: '$label 连放大字号都放得下，若这里变红说明标签区被谁改窄了',
      );
    }
  });
}
