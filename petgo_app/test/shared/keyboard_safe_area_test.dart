import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/keyboard_safe_area.dart';

void main() {
  group('KeyboardInset', () {
    testWidgets('键盘弹出时底部 padding == 键盘高度', (tester) async {
      const kbHeight = 300.0;
      tester.view.devicePixelRatio = 1.0;
      tester.view.viewInsets = const FakeViewPadding(bottom: kbHeight);
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        const MaterialApp(
          home: KeyboardInset(child: SizedBox(key: Key('body'), height: 10)),
        ),
      );

      final padding = tester.widget<AnimatedPadding>(
        find.byType(AnimatedPadding),
      );
      expect(padding.padding, const EdgeInsets.only(bottom: kbHeight));
    });

    testWidgets('键盘收起时底部 padding 归 0', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: KeyboardInset(child: SizedBox(key: Key('body'), height: 10)),
        ),
      );

      final padding = tester.widget<AnimatedPadding>(
        find.byType(AnimatedPadding),
      );
      expect(padding.padding, EdgeInsets.zero);
    });
  });

  group('KeyboardSafeArea', () {
    Widget buildHarness({required double height}) {
      return MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              height: height,
              child: KeyboardSafeArea(
                child: Column(
                  children: [
                    const Text('顶部内容'),
                    const Spacer(),
                    TextField(
                      key: const Key('bottomInput'),
                      decoration: const InputDecoration(hintText: 'input'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }

    testWidgets('空间充足时底部输入沉底（Spacer 生效）', (tester) async {
      await tester.pumpWidget(buildHarness(height: 600));
      await tester.pumpAndSettle();

      final topRect = tester.getRect(find.text('顶部内容'));
      final inputRect = tester.getRect(find.byKey(const Key('bottomInput')));
      // Spacer 把输入推到远离顶部内容的位置（沉底）。
      expect(inputRect.top - topRect.bottom, greaterThan(400));
    });

    testWidgets('可用高度被压缩（模拟键盘）时整体可滚', (tester) async {
      // 内容比可用高度更高 → 应可滚动。
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              height: 200,
              child: KeyboardSafeArea(
                child: Column(
                  children: [
                    const SizedBox(height: 400, child: Text('高内容')),
                    TextField(key: const Key('bottomInput')),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final scrollView = find.byType(SingleChildScrollView);
      expect(scrollView, findsOneWidget);
      final state = tester.state<ScrollableState>(
        find
            .descendant(of: scrollView, matching: find.byType(Scrollable))
            .first,
      );
      // 内容(400+输入)高于视口(200) → 可向下滚动露出底部输入。
      expect(state.position.maxScrollExtent, greaterThan(0));
      await tester.drag(scrollView, const Offset(0, -400));
      await tester.pumpAndSettle();
      expect(state.position.pixels, greaterThan(0));
    });
  });
}
