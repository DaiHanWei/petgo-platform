import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';

/// V1.3.4 Story 2.5（L0）：发帖页点缩略图看大图（FR-139 · AD-9）。
///
/// 只加入口、灯箱零改动 —— 这里钉住入口这一侧：
/// 下标与顺序跟格子走（AC1）、传的是完整字节（AC2）、关掉后发帖页原样（AC3）、
/// ✕ / 长按不误开（AC4）、上传中 / 失败照样能开（AC4/AC5）、键盘规则（AC6）、埋点 source（AC7）。
class _FakeRepo implements ContentRepository {
  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    List<int> petIds = const [],
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    List<double?> imageCropOffsets = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async =>
      123;
}

/// 灯箱 / 上传中格子都有常转的 spinner，`pumpAndSettle` 等不到安静 ——
/// 推进一段足以覆盖路由过渡（260ms）与单击判定窗的固定时长。
extension _Settle on WidgetTester {
  Future<void> settleLightbox() async {
    for (var i = 0; i < 60; i++) {
      await pump(const Duration(milliseconds: 16));
    }
  }
}

void main() {
  /// 真 PNG：缩略图 / 灯箱都要真的解码出来，塞假字节会在绘制时抛异常。
  Uint8List png(int r, int g, int b) {
    final im = img.Image(width: 8, height: 8);
    img.fill(im, color: img.ColorRgb8(r, g, b));
    return Uint8List.fromList(img.encodePng(im));
  }

  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<PublishController> pumpGrid(
    WidgetTester tester, {
    int images = 4,
    Future<String> Function(Uint8List)? uploadOne,
  }) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final c = PublishController(
      repository: _FakeRepo(),
      // 默认挂起不返回：需要「上传在途」的用例直接 uploadAll 即可。
      uploadOne: uploadOne ?? (b) => Completer<String>().future,
    );
    for (var i = 0; i < images; i++) {
      // 第 2 张带露出位置：开关灯箱后它必须原样还在（AC3）。
      c.addImage(png(10 * i, 20, 30), cropOffset: i == 1 ? 0.3 : null);
    }
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(c)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: const Scaffold(body: PublishComposePage()),
      ),
    ));
    await tester.pump();
    return c;
  }

  ImageLightbox lightbox(WidgetTester tester) =>
      tester.widget<ImageLightbox>(find.byType(ImageLightbox));

  bool hasEvent(String name, {String? source}) => events.any(
      (e) => e.$1 == name && (source == null || e.$2?['source'] == source));

  testWidgets('AC1/AC2/AC7：点第 2 张 → 灯箱定位第 2 张，images 即 controller 的完整字节', (tester) async {
    final c = await pumpGrid(tester);

    await tester.tap(find.byKey(const ValueKey('publishThumb_1')));
    await tester.settleLightbox();

    expect(find.byType(ImageLightbox), findsOneWidget);
    final lb = lightbox(tester);
    expect(lb.initialIndex, 1);
    expect(lb.source, 'publish_compose');
    expect(lb.heroTagPrefix, 'publish_compose');
    expect(lb.images, hasLength(4));
    for (var i = 0; i < 4; i++) {
      expect(identical(lb.images![i], c.items[i].bytes), isTrue, reason: '第 $i 张必须是原字节本身');
    }
    final opened = events.where((e) => e.$1 == 'lightbox_opened').toList();
    expect(opened, hasLength(1));
    expect(opened.single.$2, {'source': 'publish_compose'});
  });

  testWidgets('AC3/AC7：返回键关闭 → 回发帖页，图 / 顺序 / 露出位置 / 正文 / 类型 / 开关全不变',
      (tester) async {
    final c = await pumpGrid(tester);
    await tester.enterText(find.byKey(const ValueKey('publishText')), 'hello world');
    await tester.pump();
    // 收键盘焦点，免得点缩略图被「键盘弹着只收键盘」吃掉（那条另有用例）。
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();

    final bytesBefore = [for (final i in c.items) i.bytes];
    final offsetsBefore = [for (final i in c.items) i.cropOffset];
    final statusBefore = [for (final i in c.items) i.status];
    final typeBefore = c.type;
    final syncBefore = c.syncToMoment;

    await tester.tap(find.byKey(const ValueKey('publishThumb_2')));
    await tester.settleLightbox();
    expect(find.byType(ImageLightbox), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.settleLightbox();

    expect(find.byType(ImageLightbox), findsNothing);
    expect(find.byType(PublishComposePage), findsOneWidget);
    expect([for (final i in c.items) i.bytes], bytesBefore);
    expect([for (final i in c.items) i.cropOffset], offsetsBefore);
    expect(offsetsBefore[1], 0.3);
    expect([for (final i in c.items) i.status], statusBefore);
    expect(c.text, 'hello world');
    expect(find.text('hello world'), findsOneWidget);
    expect(c.type, typeBefore);
    expect(c.syncToMoment, syncBefore);
    expect(hasEvent('lightbox_dismissed', source: 'publish_compose'), isTrue);
  });

  testWidgets('AC1：重排落定后再点开，images 是新顺序', (tester) async {
    final c = await pumpGrid(tester);
    final original4th = c.items[3].bytes;

    c.reorderImage(3, 0);
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('publishThumb_0')));
    await tester.settleLightbox();

    final lb = lightbox(tester);
    expect(lb.initialIndex, 0);
    expect(identical(lb.images![0], c.items[0].bytes), isTrue);
    expect(identical(lb.images![0], original4th), isTrue, reason: '首位是原第 4 张');
  });

  testWidgets('AC4：点 ✕ 只删除，不开灯箱', (tester) async {
    final c = await pumpGrid(tester);

    await tester.tap(find.byKey(const ValueKey('publishRemoveImage_2')));
    await tester.settleLightbox();

    expect(c.items, hasLength(3));
    expect(find.byType(ImageLightbox), findsNothing);
    expect(hasEvent('lightbox_opened'), isFalse);
  });

  testWidgets('AC4：长按原地松手 → 不开灯箱', (tester) async {
    final c = await pumpGrid(tester);
    final before = [for (final i in c.items) i.bytes];

    await tester.longPress(find.byKey(const ValueKey('publishThumb_0')));
    await tester.settleLightbox();

    expect(find.byType(ImageLightbox), findsNothing);
    expect([for (final i in c.items) i.bytes], before);
  });

  testWidgets('AC4/AC5：上传在途（拖拽入口不挂）→ 点缩略图仍打开灯箱', (tester) async {
    final c = await pumpGrid(tester);
    unawaited(c.uploadAll());
    await tester.pump();

    expect(c.canReorder, isFalse);
    expect(find.byType(LongPressDraggable<int>), findsNothing);

    await tester.tap(find.byKey(const ValueKey('publishThumb_3')));
    await tester.settleLightbox();

    expect(find.byType(ImageLightbox), findsOneWidget);
    expect(lightbox(tester).initialIndex, 3);
  });

  testWidgets('AC5：上传失败的格子 → 点开看本地字节', (tester) async {
    final c = await pumpGrid(
      tester,
      images: 3,
      uploadOne: (b) async => throw Exception('upload failed'),
    );
    await c.uploadAll();
    await tester.pump();

    expect(c.items[1].status, ImageUploadStatus.failed);
    expect(find.byKey(const ValueKey('publishFailedMark_1')), findsOneWidget);
    // D-14（2026-10-14）：⚠ 与 ✕ 同时存在且不重叠（⚠ 在左下、✕ 在右上）。
    final mark = tester.getRect(find.byKey(const ValueKey('publishFailedMark_1')));
    final rm = tester.getRect(find.byKey(const ValueKey('publishRemoveImage_1')));
    expect(mark.overlaps(rm), isFalse);

    await tester.tap(find.byKey(const ValueKey('publishThumb_1')));
    await tester.settleLightbox();

    expect(find.byType(ImageLightbox), findsOneWidget);
    final lb = lightbox(tester);
    expect(lb.initialIndex, 1);
    expect(identical(lb.images![1], c.items[1].bytes), isTrue);
  });

  testWidgets('AC6：键盘弹着 → 第一下只收键盘；键盘收起后再点才打开', (tester) async {
    await pumpGrid(tester);
    await tester.tap(find.byKey(const ValueKey('publishText')));
    await tester.pump();
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetViewInsets);
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('publishThumb_0')));
    await tester.settleLightbox();
    expect(find.byType(ImageLightbox), findsNothing, reason: '这一下只收键盘');

    tester.view.viewInsets = FakeViewPadding.zero;
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('publishThumb_0')));
    await tester.settleLightbox();
    expect(find.byType(ImageLightbox), findsOneWidget);
  });
}
