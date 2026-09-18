import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/photo_lightbox.dart';

/// V1.3.0 batch-b1 Story 1.6 · L0：公共照片灯箱（AC1「抽组件不改行为」）。
///
/// <h2>这组测试守的是"搬家没弄丢东西"</h2>
/// 灯箱原先是 `content_detail_page.dart` 里的私有 `_Lightbox`，身上带着**两条历史 bug
/// 的修复**。搬成公共组件时把任何一条丢掉，界面都不会报错、也不会有别的测试变红 ——
/// 只是用户又回到了当初那个坏体验。所以两条各钉一例：
/// <ul>
///   <li>**bug 20260727-372**：单图进灯箱后无法滑动看其余图 → 外层必须是 `PageView`；</li>
///   <li>**bug 20260701-192**：点图片（或黑边）关闭大图。</li>
/// </ul>
void main() {
  Future<void> open(
    WidgetTester tester, {
    required List<String> urls,
    int initialIndex = 0,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (ctx) => ElevatedButton(
          onPressed: () =>
              openPhotoLightbox(ctx, urls: urls, initialIndex: initialIndex),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  group('🔴 AC1：从内容详情页原样搬过来的行为', () {
    testWidgets('多图用 PageView 承载，可左右翻页（bug 20260727-372）', (tester) async {
      await open(tester, urls: [
        'https://cdn.test/a.jpg',
        'https://cdn.test/b.jpg',
        'https://cdn.test/c.jpg',
      ]);

      expect(find.byType(PageView), findsOneWidget,
          reason: '🔴 换成裸 InteractiveViewer 就退回 bug 20260727-372：进了灯箱翻不了页');
      expect(find.text('1/3'), findsOneWidget);

      await tester.drag(find.byType(PageView), const Offset(-500, 0));
      await tester.pumpAndSettle();
      expect(find.text('2/3'), findsOneWidget);
    });

    testWidgets('初始页 = 点的那一张（不是第一张）', (tester) async {
      await open(
        tester,
        urls: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg', 'https://cdn.test/c.jpg'],
        initialIndex: 2,
      );
      expect(find.text('3/3'), findsOneWidget);
    });

    testWidgets('每页可捏合缩放（InteractiveViewer + BoxFit.contain）', (tester) async {
      await open(tester, urls: ['https://cdn.test/a.jpg']);

      expect(find.byType(InteractiveViewer), findsWidgets);
      final img = tester.widget<Image>(find.descendant(
          of: find.byType(InteractiveViewer).first, matching: find.byType(Image)));
      expect(img.fit, BoxFit.contain,
          reason: 'cover 会把长图裁掉 —— 灯箱的整个意义就是看全');
    });

    testWidgets('单图不画页码（「1/1」是噪音）', (tester) async {
      await open(tester, urls: ['https://cdn.test/a.jpg']);
      expect(find.text('1/1'), findsNothing);
    });

    testWidgets('点图片关闭（bug 20260701-192）', (tester) async {
      await open(tester, urls: ['https://cdn.test/a.jpg']);
      expect(find.byType(PhotoLightbox), findsOneWidget);

      await tester.tap(find.byType(InteractiveViewer).first);
      await tester.pumpAndSettle();
      expect(find.byType(PhotoLightbox), findsNothing);
    });
  });

  /// 🔴 测试环境里 `Image.network` 一律拿到 400 —— 所以这组用例本身就跑在失败路径上。
  /// 这恰好让「死链有可见占位」变成可验证的：没有 errorBuilder 的话下面这条会红，
  /// 而且那些图片异常会把整组用例判失败（**不要**用 `takeException` 一把吞掉了事：
  /// 那样真实的布局/断言异常也会被吞，这组"守搬家没弄丢东西"的用例就永远是绿的）。
  testWidgets('🔴 死链给可见占位，不是一屏全黑', (tester) async {
    await open(tester, urls: ['https://cdn.test/dead.jpg']);

    expect(find.byIcon(Icons.broken_image_outlined), findsOneWidget,
        reason: '横滑流的破图占位现在可点 —— 点它落进一屏纯黑，用户分不清加载中还是没了');
  });

  group('入口的边界情况', () {
    testWidgets('空列表不开灯箱（开出来是一屏黑）', (tester) async {
      await open(tester, urls: const []);
      expect(find.byType(PhotoLightbox), findsNothing);
    });

    testWidgets('越界下标夹回合法范围，不崩在 PageController 里', (tester) async {
      await open(tester,
          urls: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg'], initialIndex: 9);
      expect(find.text('2/2'), findsOneWidget);
    });

    /// 🔴 夹取必须在**组件里**，不只在 `openPhotoLightbox` 里：`PhotoLightbox` 是公共类，
    /// 谁都能直接构造它绕过那个入口。
    testWidgets('直接构造组件时同样夹取下标', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: PhotoLightbox(
          urls: ['https://cdn.test/a.jpg', 'https://cdn.test/b.jpg'],
          initialIndex: 5,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('2/2'), findsOneWidget, reason: '否则标题会是「6/2」、视口一片空白');
    });
  });

  /// 🔴 AC1 的另一半：内容详情页必须**真的换成了公共组件**，而不是留着一份私有副本。
  /// 留副本的话「抽组件」这件事等于没做 —— 两份实现会各自漂。
  test('内容详情页不再有私有灯箱实现，改为引用公共组件', () {
    final src =
        File('lib/features/content/presentation/content_detail_page.dart')
            .readAsStringSync();
    expect(src.contains('class _Lightbox'), isFalse,
        reason: '🔴 私有副本还在 = 两份实现会各自漂');
    expect(src.contains('openPhotoLightbox'), isTrue);
    expect(src.contains("shared/widgets/photo_lightbox.dart"), isTrue);
  });
}
