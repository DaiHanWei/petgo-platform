import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/image_exposure.dart';
import 'package:tailtopia/features/content/presentation/publish_exposure_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 Story 2.3：露出区域页（整图 + 拖白框，取代三档裁剪页）。
///
/// 页面不读写 provider，进出只靠构造参数与 `pop` 结果 —— 所以用一个宿主页 push 进去、
/// 把返回值存到外部变量，就能在 L0 钉住「默认居中 / 到边即停 / 多张各自独立 / ✕ = null」。
Uint8List _jpeg(int w, int h) =>
    Uint8List.fromList(img.encodeJpg(img.Image(width: w, height: h), quality: 80));

/// 超宽 3:1 全景 → 窗口 1.78。
ExposureImage get _wide => ExposureImage(bytes: _jpeg(300, 100), size: const ImageSize(3000, 1000));

/// 超竖 1:2 → 窗口 0.75。
ExposureImage get _tall => ExposureImage(bytes: _jpeg(100, 200), size: const ImageSize(1000, 2000));

class _Host {
  ExposureResult? result;
  bool returned = false;
}

Future<_Host> _open(WidgetTester tester, List<ExposureImage> images) async {
  final host = _Host();
  // 先清场：同一用例内二次打开时，避免沿用上一次 MaterialApp 的 Navigator 栈。
  await tester.pumpWidget(const SizedBox());
  await tester.pumpWidget(MaterialApp(
    locale: const Locale('id'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: TextButton(
            key: const ValueKey('hostOpen'),
            onPressed: () async {
              host.result = await Navigator.of(context).push<ExposureResult>(
                MaterialPageRoute(builder: (_) => PublishExposurePage(images: images)),
              );
              host.returned = true;
            },
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.byKey(const ValueKey('hostOpen')));
  await tester.pumpAndSettle();
  return host;
}

Future<void> _next(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('exposureNext')));
  await tester.pumpAndSettle();
}

String _indicator(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const ValueKey('exposurePageIndicator'))).data!;

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC1 骨架与文案', () {
    testWidgets('单张 3:1：标题 / 提示 / 按钮 / 胶囊左右箭头；无页码、无缩略图条、无旧档位与锁档文案',
        (tester) async {
      await _open(tester, [_wide]);
      expect(find.text('Sesuaikan foto'), findsOneWidget);
      expect(find.text('Bagian di dalam bingkai yang tampil di Sosial'), findsOneWidget);
      expect(find.text('Lanjut'), findsOneWidget);

      final grip = find.byKey(const ValueKey('exposureGrip'));
      expect(find.descendant(of: grip, matching: find.text('Geser')), findsOneWidget);
      expect(find.descendant(of: grip, matching: find.byIcon(Icons.chevron_left)), findsOneWidget);
      expect(find.descendant(of: grip, matching: find.byIcon(Icons.chevron_right)), findsOneWidget);

      expect(find.byKey(const ValueKey('exposurePageIndicator')), findsNothing);
      expect(find.byKey(const ValueKey('exposureStrip')), findsNothing);
      for (final k in ['cropPreset_1:1', 'cropPreset_4:5', 'cropPreset_3:4']) {
        expect(find.byKey(ValueKey(k)), findsNothing);
      }
      expect(find.textContaining('Rasio mengikuti'), findsNothing); // publishCropLocked
      expect(find.text('Terapkan'), findsNothing); // publishCropApply
    });

    testWidgets('白框比例：3:1 → 1.78；1:2 → 0.75 且胶囊为上下箭头', (tester) async {
      await _open(tester, [_wide]);
      final w = tester.getRect(find.byKey(const ValueKey('exposureFrame_0')));
      expect(w.width / w.height, closeTo(1.78, 0.01));

      await _open(tester, [_tall]);
      final t = tester.getRect(find.byKey(const ValueKey('exposureFrame_0')));
      expect(t.width / t.height, closeTo(0.75, 0.01));
      final grip = find.byKey(const ValueKey('exposureGrip'));
      expect(find.descendant(of: grip, matching: find.byIcon(Icons.keyboard_arrow_up)), findsOneWidget);
      expect(find.descendant(of: grip, matching: find.byIcon(Icons.keyboard_arrow_down)), findsOneWidget);
      expect(find.descendant(of: grip, matching: find.byIcon(Icons.chevron_left)), findsNothing);
    });

    /// 🔴 退出在**左上角**，前进在**底部**（照旧裁剪页口径）。
    testWidgets('布局：✕ 在左上，Lanjut 在底部且通栏居中', (tester) async {
      await _open(tester, [_wide]);
      final screen = tester.getRect(find.byType(Scaffold).last);
      final close = tester.getRect(find.byKey(const ValueKey('exposureClose')));
      final next = tester.getRect(find.byKey(const ValueKey('exposureNext')));

      expect(close.center.dx, lessThan(screen.center.dx), reason: '退出必须在左半边');
      expect(close.center.dy, lessThan(screen.height * 0.25), reason: '退出必须在顶部');
      expect(next.center.dy, greaterThan(screen.height * 0.75), reason: '前进动作在底部');
      expect(next.center.dx, closeTo(screen.center.dx, 1), reason: '底部主按钮通栏居中');
      expect(next.height, 46);
    });

    /// UI 稿 A1：图左右各留 16；提示句紧贴图正下方（间距 16），不沉到底部按钮上方。
    testWidgets('布局：图左右留 16，提示句紧贴图下方', (tester) async {
      await _open(tester, [_wide]);
      final screen = tester.getRect(find.byType(Scaffold).last);
      final image = tester.getRect(find.byType(Image).first);
      final hint = tester.getRect(find.byKey(const ValueKey('exposureHint')));
      expect(image.left - screen.left, closeTo(16, 0.01));
      expect(screen.right - image.right, closeTo(16, 0.01));
      expect(hint.top - image.bottom, closeTo(16, 0.01));
    });

    testWidgets('白框与压暗只落在图片显示范围内', (tester) async {
      await _open(tester, [_wide]);
      final frame = tester.getRect(find.byKey(const ValueKey('exposureFrame_0')));
      final image = tester.getRect(find.byType(Image).first);
      expect(frame.top, closeTo(image.top, 0.01));
      expect(frame.height, closeTo(image.height, 0.01), reason: '宽图窗口高 = 图片显示高');
      expect(frame.center.dx, closeTo(image.center.dx, 0.01), reason: '默认居中');
    });
  });

  group('AC2 / AC3 默认居中与拖动', () {
    testWidgets('不拖直接 Lanjut → [0.5] / [false]', (tester) async {
      final host = await _open(tester, [_wide]);
      await _next(tester);
      expect(host.returned, isTrue);
      expect(host.result!.offsets, [0.5]);
      expect(host.result!.adjusted, [false]);
    });

    testWidgets('宽图向左拖到底 → 0.0，adjusted = true（框跟手、贴左边）', (tester) async {
      final host = await _open(tester, [_wide]);
      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(-2000, 0));
      await tester.pumpAndSettle();
      final frame = tester.getRect(find.byKey(const ValueKey('exposureFrame_0')));
      final image = tester.getRect(find.byType(Image).first);
      expect(frame.left, closeTo(image.left, 0.01), reason: 'offset 0 = 白框贴图片左边');
      await _next(tester);
      expect(host.result!.offsets, [0.0]);
      expect(host.result!.adjusted, [true]);
    });

    testWidgets('宽图向右拖到底 → 1.0', (tester) async {
      final host = await _open(tester, [_wide]);
      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(2000, 0));
      await tester.pumpAndSettle();
      await _next(tester);
      expect(host.result!.offsets, [1.0]);
      expect(host.result!.adjusted, [true]);
    });

    testWidgets('竖图向上拖到底 → 0.0；横向拖竖图白框不动', (tester) async {
      final host = await _open(tester, [_tall]);
      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(0, -2000));
      await tester.pumpAndSettle();
      await _next(tester);
      expect(host.result!.offsets, [0.0]);
      expect(host.result!.adjusted, [true]);

      final host2 = await _open(tester, [_tall]);
      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(-300, 0));
      await tester.pumpAndSettle();
      await _next(tester);
      expect(host2.result!.offsets, [0.5]);
      expect(host2.result!.adjusted, [false]);
    });

    testWidgets('拖回 0.5 附近仍算 adjusted', (tester) async {
      final host = await _open(tester, [_wide]);
      final frame = find.byKey(const ValueKey('exposureFrame_0'));
      await tester.drag(frame, const Offset(-60, 0));
      await tester.pumpAndSettle();
      await tester.drag(frame, const Offset(60, 0));
      await tester.pumpAndSettle();
      await _next(tester);
      expect(host.result!.adjusted, [true]);
    });
  });

  group('AC4 / AC5 多张同页', () {
    testWidgets('3 张：页码 / 缩略图 / 各自独立，Lanjut 一次返回整批', (tester) async {
      final host = await _open(tester, [_wide, _tall, _wide]);
      expect(_indicator(tester), '1 / 3');
      final strip = find.byKey(const ValueKey('exposureStrip'));
      for (var i = 0; i < 3; i++) {
        expect(find.descendant(of: strip, matching: find.byKey(ValueKey('exposureThumb_$i'))),
            findsOneWidget);
      }
      expect(find.byKey(const ValueKey('exposureThumbRing_0')), findsOneWidget);

      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(-2000, 0));
      await tester.pumpAndSettle();
      expect(_indicator(tester), '1 / 3', reason: '框内起手的横拖归白框，不切图');

      await tester.tap(find.byKey(const ValueKey('exposureThumb_2')));
      await tester.pumpAndSettle();
      expect(_indicator(tester), '3 / 3');
      expect(find.byKey(const ValueKey('exposureThumbRing_2')), findsOneWidget);
      expect(find.byKey(const ValueKey('exposureThumbRing_0')), findsNothing);

      await _next(tester);
      expect(host.result!.offsets, [0.0, 0.5, 0.5]);
      expect(host.result!.adjusted, [true, false, false]);
    });

    testWidgets('竖图白框上横向滑动 → 切图（白框只吃纵向）', (tester) async {
      await _open(tester, [_tall, _wide]);
      expect(_indicator(tester), '1 / 2');
      await tester.drag(find.byKey(const ValueKey('exposureFrame_0')), const Offset(-500, 0));
      await tester.pumpAndSettle();
      expect(_indicator(tester), '2 / 2');
    });

    testWidgets('非当前缩略图半透明，当前张不透明', (tester) async {
      await _open(tester, [_wide, _tall]);
      Opacity op(int i) => tester.widget<Opacity>(find.descendant(
          of: find.byKey(ValueKey('exposureThumb_$i')), matching: find.byType(Opacity)));
      expect(op(0).opacity, 1);
      expect(op(1).opacity, 0.5);
    });
  });

  group('AC6 取消', () {
    testWidgets('✕ → null，且恰报一条 exit_tapped{original_ratio: 3.0}', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      final host = await _open(tester, [_wide]);
      await tester.tap(find.byKey(const ValueKey('exposureClose')));
      await tester.pumpAndSettle();
      expect(host.returned, isTrue);
      expect(host.result, isNull);
      final exits = events.where((e) => e.$1 == 'publish_image_crop_exit_tapped').toList();
      expect(exits, hasLength(1));
      expect(exits.single.$2!['original_ratio'], 3.0);
    });

    testWidgets('✕ 的 original_ratio 取当前张（切到第 2 张竖图 → 0.5）', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      final host = await _open(tester, [_wide, _tall]);
      await tester.tap(find.byKey(const ValueKey('exposureThumb_1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('exposureClose')));
      await tester.pumpAndSettle();
      expect(host.result, isNull);
      final exits = events.where((e) => e.$1 == 'publish_image_crop_exit_tapped').toList();
      expect(exits.single.$2!['original_ratio'], 0.5);
    });

    testWidgets('系统返回 → null，不报任何 publish_image_crop_* 事件', (tester) async {
      final events = <String>[];
      Analytics.debugCaptureSink = (e, p) => events.add(e);
      final host = await _open(tester, [_wide]);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(host.returned, isTrue);
      expect(host.result, isNull);
      expect(events.where((e) => e.startsWith('publish_image_crop')), isEmpty);
    });
  });

  group('image_exposure 纯函数', () {
    test('3:1 → 宽 / 1.78 / 1x78；1:2 → 竖 / 0.75 / 0x75', () {
      const w = ImageSize(3000, 1000);
      const t = ImageSize(1000, 2000);
      expect(exposureIsWide(w), isTrue);
      expect(exposureWindowAspect(w), 1.78);
      expect(exposureAnalyticsRatio(w), '1x78');
      expect(exposureIsWide(t), isFalse);
      expect(exposureWindowAspect(t), 0.75);
      expect(exposureAnalyticsRatio(t), '0x75');
      expect(kExposureDefaultOffset, 0.5);
    });

    /// offset 与 2.2 `feedImageAlignment` 同一把尺子：0 = 贴左 / 贴上，1 = 贴右 / 贴下。
    test('offset 语义与 feedImageAlignment 一致', () {
      const w = ImageSize(3000, 1000);
      const t = ImageSize(1000, 2000);
      expect(feedImageAlignment(w, 0), const Alignment(-1, 0));
      expect(feedImageAlignment(w, 1), const Alignment(1, 0));
      expect(feedImageAlignment(t, 0), const Alignment(0, -1));
      expect(feedImageAlignment(w, kExposureDefaultOffset), const Alignment(0, 0));
    });
  });

  testWidgets('9 张超界图（上限）在 360 宽屏上缩略图条不溢出、可点最后一张', (tester) async {
    tester.view.physicalSize = const Size(360 * 3, 780 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final host = await _open(tester, List.generate(9, (i) => i.isEven ? _wide : _tall));
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.byKey(const ValueKey('exposureThumb_8')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('exposureThumb_8')));
    await tester.pumpAndSettle();
    expect(find.text('9 / 9'), findsOneWidget);
    await _next(tester);
    expect(host.result!.offsets, hasLength(9));
  });
}
