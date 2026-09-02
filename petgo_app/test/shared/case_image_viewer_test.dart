import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/case_image_viewer.dart';

/// 全屏看图（黑底 + 双指缩放 + 点击关闭）。
///
/// 2026-09-02 为 R-1（商品主图看全貌）从单图扩成整组，
/// 单图入口 `showCaseImageFullScreen` 降为快捷方式 —— 实现只有一份。
/// ⚠️ 既有调用方是兽医上下文卡 / 待接单预览 / 会话病例摘要 / IM 气泡，
/// 它们全走单图那条，所以「单图行为一个字不变」是本组的第一条。
void main() {
  Future<void> open(
    WidgetTester tester, {
    required List<String> srcs,
    int initialIndex = 0,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (ctx) => ElevatedButton(
          onPressed: () =>
              showImageGalleryFullScreen(ctx, srcs: srcs, initialIndex: initialIndex),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  group('单图（既有四处调用方的路径）', () {
    testWidgets('contain + 可缩放 + 不画页码', (tester) async {
      await open(tester, srcs: ['https://cdn.test/a.jpg']);

      final img = tester.widget<Image>(find.descendant(
          of: find.byType(InteractiveViewer), matching: find.byType(Image)));
      expect(img.fit, BoxFit.contain);
      expect(find.byKey(const ValueKey('imageViewerPageIndicator')), findsNothing,
          reason: '只有一张时写「1/1」是噪音');
    });

    testWidgets('🔴 单图时禁掉滑动 —— 可滑但滑不动会像是还有一张没加载出来', (tester) async {
      await open(tester, srcs: ['https://cdn.test/a.jpg']);

      final pv = tester.widget<PageView>(find.byType(PageView));
      expect(pv.physics, isA<NeverScrollableScrollPhysics>());
    });

    testWidgets('showCaseImageFullScreen 与多图版同一实现', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => showCaseImageFullScreen(ctx, 'https://cdn.test/a.jpg'),
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.byType(InteractiveViewer), findsOneWidget);
      expect(find.byKey(const ValueKey('imageViewerPageIndicator')), findsNothing);
    });
  });

  group('多图', () {
    testWidgets('🔴 从传入的下标打开，不是无脑从头', (tester) async {
      await open(tester,
          srcs: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg', 'https://cdn.test/c.jpg'],
          initialIndex: 1);

      final indicator = tester.widget<Text>(
          find.byKey(const ValueKey('imageViewerPageIndicator')));
      expect(indicator.data, '2/3',
          reason: '用户点的是第 2 张，从第 1 张打开会让他以为自己点错了');
    });

    testWidgets('🔴 越界下标夹回合法范围，而不是崩在 PageController 里', (tester) async {
      await open(tester,
          srcs: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg'],
          initialIndex: 99);

      expect(tester.takeException(), isNull);
      final indicator = tester.widget<Text>(
          find.byKey(const ValueKey('imageViewerPageIndicator')));
      expect(indicator.data, '2/2');
    });

    testWidgets('多图时可滑动', (tester) async {
      await open(tester, srcs: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg']);

      final pv = tester.widget<PageView>(find.byType(PageView));
      expect(pv.physics, isNot(isA<NeverScrollableScrollPhysics>()));
    });
  });

  testWidgets('空列表 → 什么都不弹（不是弹一个空黑屏）', (tester) async {
    await open(tester, srcs: const []);
    expect(find.byType(InteractiveViewer), findsNothing);
  });
}
