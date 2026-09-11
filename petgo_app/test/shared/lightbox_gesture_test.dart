import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';
import 'package:tailtopia/shared/media/lightbox_gestures.dart';

/// V1.3.0 批次 A · Story 3.2（L0）：灯箱四个手势（FR-115 · AD-A15.2/.3/.4 · AD-A22）。
///
/// 「顺不顺手」是这条 story 的全部价值，而那件事 L0 看不见 —— **主要验收在真机**。
/// 这里只钉住三件 L0 能钉死的：
/// 1. **优先级顺序**（AC5 明文要求「有测试或注释明确钉住这一顺序」）；
/// 2. 下滑关闭的量化口径（阈值 / 回弹 / 只认下拖）；
/// 3. 单击与双击的仲裁 —— 它是 AC6 那条回归保护的实现基础；
/// 4. 没引任何新包（AC7）。
void main() {
  group('AC5 🔴 手势优先级：已放大→平移 > 到边缘续拖→翻页 > 未放大下拖→关闭', () {
    /// 真值表把三条规则的**全部输入组合**列完。顺序一旦被调换，
    /// 下面必定有一行变红 —— 这就是 AC5 要的「钉住」。
    ///
    /// | zoomed | horizontal | atEdge | 期望 |
    /// 顺序的理由见 `resolveLightboxGesture` 的文档。
    const cases = <({bool zoomed, bool horizontal, bool atEdge, LightboxGesture want})>[
      // —— 第 1 条：已放大 → 图内平移。压住第 3 条（AC2：放大后下拖不是关闭）。
      (zoomed: true, horizontal: false, atEdge: false, want: LightboxGesture.panInsideImage),
      (zoomed: true, horizontal: false, atEdge: true, want: LightboxGesture.panInsideImage),
      (zoomed: true, horizontal: true, atEdge: false, want: LightboxGesture.panInsideImage),
      // —— 第 2 条：已放大 + 横向 + 已贴边 → 翻页。第 1 条唯一的出口（AC4）。
      (zoomed: true, horizontal: true, atEdge: true, want: LightboxGesture.changePage),
      // —— 第 3 条：未放大才轮得到；横滑翻页、纵拖关闭（AC1）。
      (zoomed: false, horizontal: true, atEdge: false, want: LightboxGesture.changePage),
      (zoomed: false, horizontal: true, atEdge: true, want: LightboxGesture.changePage),
      (zoomed: false, horizontal: false, atEdge: false, want: LightboxGesture.dismiss),
      (zoomed: false, horizontal: false, atEdge: true, want: LightboxGesture.dismiss),
    ];

    for (final c in cases) {
      test('zoomed=${c.zoomed} horizontal=${c.horizontal} atEdge=${c.atEdge} → ${c.want.name}', () {
        expect(
          resolveLightboxGesture(
            zoomed: c.zoomed,
            horizontal: c.horizontal,
            atEdgeInDragDirection: c.atEdge,
          ),
          c.want,
        );
      });
    }

    /// 🔴 三条「常见错法」的反向断言。它们各自对应 story Dev Notes 里点名的一种写法：
    test('放大后下拖绝不关闭（错法①：下滑关闭挂最外层）', () {
      expect(
        resolveLightboxGesture(zoomed: true, horizontal: false, atEdgeInDragDirection: true),
        isNot(LightboxGesture.dismiss),
      );
    });

    test('放大态横滑先平移，没贴边不翻页（错法③：翻页与平移各自判定）', () {
      expect(
        resolveLightboxGesture(zoomed: true, horizontal: true, atEdgeInDragDirection: false),
        LightboxGesture.panInsideImage,
      );
    });

    test('未放大时贴边与否都不影响判定（边缘只是放大态的概念）', () {
      for (final horizontal in [true, false]) {
        expect(
          resolveLightboxGesture(zoomed: false, horizontal: horizontal, atEdgeInDragDirection: true),
          resolveLightboxGesture(
              zoomed: false, horizontal: horizontal, atEdgeInDragDirection: false),
        );
      }
    });
  });

  group('AC1 下滑关闭的量化口径', () {
    const double h = 800; // 视口高

    test('超过阈值 → 关闭', () {
      expect(
        LightboxDismissMetrics.shouldDismiss(dy: h * 0.2, velocity: 0, viewportHeight: h),
        isTrue,
      );
    });

    test('未过阈值 → 回弹（不关）', () {
      expect(
        LightboxDismissMetrics.shouldDismiss(dy: h * 0.05, velocity: 0, viewportHeight: h),
        isFalse,
      );
    });

    /// 没到位移阈值但甩得够快也要关 —— 否则用户「啪」地一甩反而关不掉，只能慢慢拖。
    test('快速下甩 → 关闭', () {
      expect(
        LightboxDismissMetrics.shouldDismiss(dy: 20, velocity: 1800, viewportHeight: h),
        isTrue,
      );
    });

    /// 🔴 只认**下**拖。上拖再远、再快都不关 —— 那是另一个方向的手势，
    /// 按绝对值判定会让「往上甩一下」也把图关掉。
    test('上拖一律不关闭，甩得再快也不关', () {
      expect(LightboxDismissMetrics.shouldDismiss(dy: -300, velocity: 0, viewportHeight: h), isFalse);
      expect(
          LightboxDismissMetrics.shouldDismiss(dy: -10, velocity: -3000, viewportHeight: h), isFalse);
    });

    test('背景渐透明：0 → 阈值处封顶，且不会全透', () {
      expect(LightboxDismissMetrics.progress(dy: 0, viewportHeight: h), 0);
      expect(LightboxDismissMetrics.progress(dy: h, viewportHeight: h), 1, reason: '超过阈值封顶在 1');
      expect(LightboxDismissMetrics.progress(dy: -50, viewportHeight: h), 0, reason: '上拖不算进度');
      final mid = LightboxDismissMetrics.progress(
          dy: h * LightboxDismissMetrics.travelRatio / 2, viewportHeight: h);
      expect(mid, closeTo(0.5, 0.01));
      // 全透的话正在拖的图会飘在详情页上，很怪。
      expect(LightboxDismissMetrics.maxFade, lessThan(1));
    });
  });

  group('AC6 单击关闭不被双击拖慢', () {
    /// 🔴 窗口必须**短于系统的双击判定**（300ms）。等满 300ms 才关，
    /// 就是 story 里点名的错法②「单击关闭延迟 300ms，手感变钝」。
    test('仲裁窗口短于 kDoubleTapTimeout', () {
      expect(kLightboxSingleTapDelay, lessThan(kDoubleTapTimeout));
      // 也不能短到把稍慢一点的双击误判成关闭。
      expect(kLightboxSingleTapDelay.inMilliseconds, greaterThanOrEqualTo(180));
    });

    Future<void> openLightbox(WidgetTester tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('openIt'),
              onPressed: () => ImageLightbox.open(
                context,
                urls: const [
                  'asset:assets/demo_diary/demo_diary_night.jpg',
                  'asset:assets/demo_diary/demo_diary_balcony.jpg',
                ],
                initialIndex: 0,
                heroTagPrefix: 'content_detail_42',
                source: 'content_detail',
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('openIt')));
      await tester.pumpAndSettle();
      expect(find.byType(ImageLightbox), findsOneWidget);
    }

    testWidgets('单击 → 过了窗口就关闭', (tester) async {
      await openLightbox(tester);

      await tester.tap(find.byKey(const ValueKey('lightboxPager')));
      await tester.pump(kLightboxSingleTapDelay + const Duration(milliseconds: 40));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsNothing);
    });

    /// 🔴 双击**不能**顺带把页面关掉 —— 第一下点击的关闭必须被第二下撤销。
    testWidgets('快速双击 → 缩放，且不关闭', (tester) async {
      await openLightbox(tester);

      final pager = find.byKey(const ValueKey('lightboxPager'));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsOneWidget, reason: '双击的第一下不该把灯箱关掉');
      // 放大后 InteractiveViewer 的矩阵不再是单位阵。
      final viewer = tester.widget<InteractiveViewer>(find.byType(InteractiveViewer).first);
      expect(viewer.transformationController!.value, isNot(Matrix4.identity()));
    });

    /// 再双击一次回到适应屏幕（AC3 的「两档切换」）。
    testWidgets('再双击一次 → 回到适应屏幕', (tester) async {
      await openLightbox(tester);
      final pager = find.byKey(const ValueKey('lightboxPager'));

      Future<void> doubleTap() async {
        await tester.tap(pager);
        await tester.pump(const Duration(milliseconds: 60));
        await tester.tap(pager);
        await tester.pump(const Duration(milliseconds: 400));
      }

      await doubleTap();
      await doubleTap();
      await tester.pumpAndSettle();

      final viewer = tester.widget<InteractiveViewer>(find.byType(InteractiveViewer).first);
      expect(viewer.transformationController!.value, Matrix4.identity());
      expect(find.byType(ImageLightbox), findsOneWidget);
    });

    /// 未放大时 InteractiveViewer 的平移**必须关掉**，否则它会和下滑关闭抢同一个手势
    /// （错法①的另一半）。
    testWidgets('未放大时 panEnabled=false，放大后才打开', (tester) async {
      await openLightbox(tester);
      InteractiveViewer viewer() =>
          tester.widget<InteractiveViewer>(find.byType(InteractiveViewer).first);

      expect(viewer().panEnabled, isFalse);

      final pager = find.byKey(const ValueKey('lightboxPager'));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(viewer().panEnabled, isTrue);
    });

    /// AC4 的接线：放大且没贴边时 PageView 不可滑；未放大时照常可翻页。
    testWidgets('放大且未贴边 → 翻页被禁；未放大 → 可翻页', (tester) async {
      await openLightbox(tester);
      PageView pager() => tester.widget<PageView>(find.byKey(const ValueKey('lightboxPager')));

      expect(pager().physics, isA<PageScrollPhysics>());

      // 双击放大：落点在左上角附近，放大后不在右边缘 → 翻页应被禁。
      await tester.tapAt(const Offset(60, 100));
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tapAt(const Offset(60, 100));
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(pager().physics, isA<NeverScrollableScrollPhysics>());
    });
  });

  group('AC1/AC2 下滑关闭的接线', () {
    Future<void> openLightbox(WidgetTester tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('openIt'),
              onPressed: () => ImageLightbox.open(
                context,
                urls: const ['asset:assets/demo_diary/demo_diary_night.jpg'],
                initialIndex: 0,
                heroTagPrefix: 'p',
                source: 'content_detail',
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('openIt')));
      await tester.pumpAndSettle();
    }

    testWidgets('下拖超过阈值 → 关闭', (tester) async {
      await openLightbox(tester);

      await tester.drag(find.byKey(const ValueKey('lightboxPager')), const Offset(0, 400));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsNothing);
    });

    testWidgets('下拖没过阈值 → 回弹复位，不关闭', (tester) async {
      await openLightbox(tester);

      await tester.drag(find.byKey(const ValueKey('lightboxPager')), const Offset(0, 20));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsOneWidget);
      // 回弹结束后位移归零：图必须回到原位，不能停在半路。
      final shifted = tester.widget<Transform>(find
          .ancestor(
            of: find.byKey(const ValueKey('lightboxPager')),
            matching: find.byType(Transform),
          )
          .first);
      expect(shifted.transform.getTranslation().y, closeTo(0, 0.01));
    });

    /// 🔴 AC1 的「背景渐透明」要求路由本身是透明的 ——
    /// 不透明路由之下的那一页根本不参与绘制，把黑底调淡只会露出一片虚空。
    testWidgets('路由非不透明（否则渐透明后面是虚空）', (tester) async {
      await openLightbox(tester);
      final route = ModalRoute.of(tester.element(find.byType(ImageLightbox)))!;
      expect(route.opaque, isFalse);
    });
  });

  group('AC7/AC8 不引包、不扩散', () {
    /// AD-A22：本批次**不新增任何第三方依赖**，Flutter 侧也一样。
    /// 四个手势用 SDK 自带能力实现 —— 一旦有人为了省事引了图片查看器包，这条会红。
    test('pubspec 没有图片查看器 / 手势 / 重排类第三方包', () {
      final pubspec = File('pubspec.yaml').readAsStringSync().toLowerCase();
      for (final banned in [
        'photo_view',
        'easy_image_viewer',
        'image_viewer',
        'dismissible_page',
        'zoom',
        'reorderable',
      ]) {
        expect(pubspec, isNot(contains(banned)), reason: '$banned 属于 AD-A22 禁止的新依赖');
      }
    });

    /// AC8：过渡动画与加载态属 Story 3.3，本 story 不碰。
    test('没有提前做 3.3 的 Hero 飞入与失败重试', () {
      final src = File('lib/shared/media/image_lightbox.dart').readAsStringSync();
      expect(src, isNot(contains('Hero(')));
      expect(src, isNot(contains('errorBuilder')));
    });

    /// 判定逻辑是**纯函数**、不依赖 widget —— 这正是它能被上面那张真值表逐格验的原因。
    test('判定文件不 import 任何 widget 层', () {
      final src = File('lib/shared/media/lightbox_gestures.dart').readAsStringSync();
      expect(src, isNot(contains("import 'package:flutter/material.dart'")));
      expect(src, isNot(contains('features/')));
    });
  });
}
