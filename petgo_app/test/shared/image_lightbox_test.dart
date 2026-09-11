import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';

/// V1.3.0 批次 A · Story 3.1（L0）：灯箱上提为共享组件并全屏化（FR-115 · AD-A14 / AD-A15.1）。
///
/// 沉浸态好不好看、图铺没铺满是 L2；这里钉住 L0 能钉的三件事：
/// **接口形状**（AC4，它同时是批次 B1 的前置契约）、**系统栏恢复**（AC3，最容易漏路径的一条）、
/// 以及三条「不许做」的反向约束（AC6/AC7 + 两条既有 bug 修复）。
void main() {
  final File source = File('lib/shared/media/image_lightbox.dart');
  final String src = source.readAsStringSync();

  group('AC4 🔴 组件接口只认通用形状', () {
    /// 构造参数逐字钉死。多一个 `postId` / `placeId` / `ContentDetail`，
    /// 这个组件就从「通用查看器」变回「内容模块的私有件」，
    /// 批次 B1 的场所照片要么反向依赖 content，要么再写一个灯箱 —— 两条路都是 AD-A14 要挡的。
    test('只有 urls / initialIndex / heroTagPrefix / source 四个参数', () {
      final ctor = RegExp(r'const ImageLightbox\(\{(.*?)\}\)', dotAll: true)
          .firstMatch(src)!
          .group(1)!;
      final names = RegExp(r'required this\.(\w+)')
          .allMatches(ctor)
          .map((m) => m.group(1))
          .toList();

      expect(names, containsAll(['urls', 'initialIndex', 'heroTagPrefix', 'source']));
      expect(names, hasLength(4), reason: '多一个参数就要先回去改 AD-A14');
      expect(ctor, contains('super.key'));
    });

    /// 🔴 组件在**共享层**，且不反向依赖任何 feature。
    /// 一条 `import '../../features/...'` 就足以让 places 侧接入时把 content 整个拖进来。
    test('不 import 任何 features/ 下的东西', () {
      final imports = RegExp(r"^import .*$", multiLine: true)
          .allMatches(src)
          .map((m) => m.group(0)!)
          .toList();

      expect(imports.where((i) => i.contains('features/')), isEmpty);
      expect(source.path, startsWith('lib/shared/'));
    });

    /// 业务实体名一个都不许出现（连类型注解与文档里的字段名都不该有）。
    test('接口里不出现任何业务实体', () {
      final code = src
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('///') && !l.trimLeft().startsWith('//'))
          .join('\n');
      for (final banned in ['ContentDetail', 'postId', 'placeId', 'Place', 'Comment']) {
        expect(code, isNot(contains(banned)), reason: '$banned 属于业务实体，不该进通用查看器');
      }
    });

    /// Hero tag 由**前缀 + 下标**拼出。从 URL 推导的话，B1 场所页「头图 + 照片网格」
    /// 里同一张图出现两次 → 两个 Hero 同 tag 同屏 → Flutter 直接抛异常。
    test('heroTag 取自前缀与下标，不同下标必不同', () {
      expect(lightboxHeroTag('place_7', 0), isNot(lightboxHeroTag('place_7', 1)));
      // 同一张图在两个前缀下也是两个 tag —— 这正是前缀存在的理由。
      expect(lightboxHeroTag('place_7_cover', 0), isNot(lightboxHeroTag('place_7_grid', 0)));
      expect(lightboxHeroTag('p', 3), contains('3'));
    });
  });

  group('AC6/AC7 三条反向约束', () {
    /// AC6：**不提供保存到相册**。盗用顾虑 + 相册权限 + 与分享卡定位冲突。
    test('没有长按保存到相册的任何痕迹', () {
      for (final banned in ['onLongPress', 'saveImage', 'ImageGallerySaver', 'saveToGallery']) {
        expect(src, isNot(contains(banned)));
      }
    });

    /// AC5 第一条既有修复（bug 20260701-192）：**单击图片或黑边即关闭**。
    /// 改写外壳时顺手删掉就是回归 —— 所以在这里留一条明确的断言。
    test('保留「单击关闭」：opaque 命中 + 单击走关闭出口', () {
      expect(src, contains('HitTestBehavior.opaque'));
      // Story 3.2 起单击不再直接 pop，而是经 _close(LightboxDismissGesture.tap)
      // 统一出口（要记下"是怎么关的"给埋点）——关闭这件事本身一步没少。
      expect(src, contains('LightboxDismissGesture.tap'));
      expect(src, contains('onTapUp: _handleTapUp'));
    });

    /// AC5 第二条既有修复（bug 20260727-372）：**单图进灯箱后也能左右翻页**。
    /// 单图直接塞 InteractiveViewer 是它当初坏掉的写法 —— 必须仍走 PageView。
    test('保留「单图也可翻页」：仍由 PageView 承载', () {
      expect(src, contains('PageView.builder'));
      expect(src, contains('itemCount: widget.urls.length'));
    });

    /// AC7：病例图查看器是另一套，本 story 不动它，也不让它依赖新组件。
    test('不动病例图查看器', () {
      final caseViewer = File('lib/shared/widgets/case_image_viewer.dart').readAsStringSync();
      expect(caseViewer, isNot(contains('image_lightbox')));
      expect(caseViewer, isNot(contains('ImageLightbox')));
    });
  });

  group('AC1/AC3 沉浸态与系统栏恢复', () {
    /// 拦下 SystemChrome 的平台调用，看进出各下了什么指令。
    List<MethodCall> tapPlatformChannel(WidgetTester tester) {
      final calls = <MethodCall>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          calls.add(call);
          return null;
        },
      );
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));
      return calls;
    }

    /// 进沉浸态下的是 `setEnabledSystemUIMode`。
    bool wentImmersive(List<MethodCall> calls) => calls.any((c) =>
        c.method == 'SystemChrome.setEnabledSystemUIMode' &&
        '${c.arguments}'.contains('immersive'));

    /// ⚠️ 恢复走的是**另一个平台方法**：`SystemUiMode.manual` 在 Flutter 内部会转成
    /// `setEnabledSystemUIOverlays(overlays)`，而不是再发一次 setEnabledSystemUIMode。
    /// 直接按「状态栏回来了」断言，比按方法名断言更贴近 AC3 想保证的那件事。
    bool restoredSystemBars(List<MethodCall> calls) => calls.any((c) =>
        c.method == 'SystemChrome.setEnabledSystemUIOverlays' &&
        '${c.arguments}'.contains('SystemUiOverlay.top'));

    Future<void> openLightbox(WidgetTester tester, {int count = 3}) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('openIt'),
              onPressed: () => ImageLightbox.open(
                context,
                urls: [for (var i = 0; i < count; i++) 'asset:assets/demo_diary/demo_diary_night.jpg'],
                initialIndex: 1,
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
    }

    testWidgets('打开 → 进沉浸态；关闭 → 恢复系统栏', (tester) async {
      final calls = tapPlatformChannel(tester);
      await openLightbox(tester);

      expect(wentImmersive(calls), isTrue);
      calls.clear();

      await tester.tap(find.byKey(const ValueKey('lightboxClose')));
      await tester.pumpAndSettle();

      expect(restoredSystemBars(calls), isTrue,
          reason: '不恢复的话用户会停在一个没有状态栏、且已经不是灯箱的界面上');
    });

    /// 🔴 AC3 的重点在**异常退出路径**：不是只有点 ✕ 才恢复。
    /// 恢复动作因此放在 dispose —— 下面两条走的是完全不同的出口。
    testWidgets('单击图片退出也恢复系统栏', (tester) async {
      final calls = tapPlatformChannel(tester);
      await openLightbox(tester);
      calls.clear();

      await tester.tap(find.byKey(const ValueKey('lightboxPager')));
      // ⚠️ Story 3.2 起，单击关闭要等过双击仲裁窗口（kLightboxSingleTapDelay）才执行 ——
      // 不等这一下的话点了等于没点。窗口本身的约束在 lightbox_gesture_test.dart 里钉。
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(restoredSystemBars(calls), isTrue);
    });

    testWidgets('系统返回键退出也恢复系统栏', (tester) async {
      final calls = tapPlatformChannel(tester);
      await openLightbox(tester);
      calls.clear();

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      expect(restoredSystemBars(calls), isTrue);
    });

    testWidgets('AC1：没有 AppBar，只剩悬浮的 ✕ 与页码', (tester) async {
      await openLightbox(tester);

      expect(find.byType(AppBar), findsNothing, reason: '改前正是一个黑色 AppBar 占着状态栏那一条');
      expect(find.byKey(const ValueKey('lightboxClose')), findsOneWidget);
      expect(find.byKey(const ValueKey('lightboxCounter')), findsOneWidget);
    });

    testWidgets('AC2：页码胶囊从用户点的那张算起', (tester) async {
      await openLightbox(tester);
      // initialIndex = 1 → 第 2 张。
      expect(find.text('2/3'), findsOneWidget);
    });

    testWidgets('AC2：单图不显示页码（1/1 是噪音）', (tester) async {
      await openLightbox(tester, count: 1);
      expect(find.byKey(const ValueKey('lightboxCounter')), findsNothing);
      expect(find.byKey(const ValueKey('lightboxClose')), findsOneWidget);
    });

    /// 下标越界一律夹回合法范围：调用方的下标来自各自的轮播状态，
    /// 少一张图（加载失败被过滤）就会越界，不该因此崩在 PageController 里。
    testWidgets('越界下标夹回范围，不崩', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: ImageLightbox(
          urls: [
            'asset:assets/demo_diary/demo_diary_night.jpg',
            'asset:assets/demo_diary/demo_diary_balcony.jpg',
          ],
          initialIndex: 99,
          heroTagPrefix: 'p',
          source: 'content_detail',
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.text('2/2'), findsOneWidget);
    });

    testWidgets('空列表不打开任何页面', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            key: const ValueKey('openIt'),
            onPressed: () => ImageLightbox.open(context,
                urls: const [], initialIndex: 0, heroTagPrefix: 'p', source: 'content_detail'),
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('openIt')));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsNothing);
    });
  });
}
