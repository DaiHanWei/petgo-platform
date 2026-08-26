import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_render_pipeline.dart';

/// Story 9.1 · 卡片生成基建。
///
/// 🔴 本文件钉两件"看着好、实际废掉"的事：
/// 1. **导出分辨率下二维码边长 ≥ 140px** —— 按预览像素出图的话码只有几十像素，**扫不出来**。
///    这条不靠算术断言，**真的去量导出图的像素**。
/// 2. **管线不认识"分享卡"** —— AD-15 Rule 1c：V1.2.0 的 FR-65 直接复用本基建，
///    写死成分享卡专用即为违规。
void main() {
  /// 把一张卡挂到很小的预览尺寸上（这正是"按预览像素出图会扫不出来"的现场），
  /// 走管线出图，返回**导出图的像素**。
  ///
  /// ⚠️ 出图与解码必须包在 `tester.runAsync` 里：`toImage` / `instantiateImageCodec`
  /// 是真实引擎异步操作，在 widget test 默认的 fake-async 时钟里**永远不会完成**（直接挂死）。
  Future<_Shot> exportCard(
    WidgetTester tester, {
    required Widget content,
    required CardCanvas canvas,
    Widget? watermark,
    double previewWidth = 216,
  }) async {
    final boundaryKey = GlobalKey();
    await tester.pumpWidget(MaterialApp(
      home: Center(
        child: SizedBox(
          width: previewWidth,
          child: CardFrame(
            boundaryKey: boundaryKey,
            canvas: canvas,
            watermark: watermark,
            child: content,
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    final shot = await tester.runAsync(() async {
      final bytes = await CardRenderPipeline.capture(boundaryKey: boundaryKey, canvas: canvas);
      if (bytes == null) return null;
      final codec = await ui.instantiateImageCodec(bytes);
      final img = (await codec.getNextFrame()).image;
      final rgba = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final result = _Shot(img.width, img.height, rgba.buffer.asUint8List());
      img.dispose();
      return result;
    });
    expect(shot, isNotNull, reason: '管线应当出图');
    return shot!;
  }

  group('AC2 · 双尺寸输出是通用能力（不写死单一比例）', () {
    testWidgets('9:16 画布', (tester) async {
      final shot = await exportCard(
        tester,
        content: const ColoredBox(color: Colors.white),
        canvas: CardCanvas.story,
      );
      expect(shot.width, closeTo(CardCanvas.story.width, 2));
      expect(shot.height, closeTo(CardCanvas.story.height, 2));
      expect(shot.width * 16, closeTo(shot.height * 9, 32), reason: '9:16');
    });

    testWidgets('1:1 画布', (tester) async {
      final shot = await exportCard(
        tester,
        content: const ColoredBox(color: Colors.white),
        canvas: CardCanvas.square,
      );
      expect(shot.width, closeTo(CardCanvas.square.width, 2));
      expect(shot.height, closeTo(shot.width, 2));
    });

    /// 画布是自定义值对象，不是三选一的枚举 —— 任意尺寸都能出。
    testWidgets('任意自定义画布也能出图', (tester) async {
      final shot = await exportCard(
        tester,
        content: const ColoredBox(color: Colors.white),
        canvas: const CardCanvas(size: Size(800, 600)),
      );
      expect(shot.width, closeTo(800, 2));
      expect(shot.height, closeTo(600, 2));
    });
  });

  group('AC2 · 🔴 管线不认识"分享卡"（AD-15 Rule 1c）', () {
    /// V1.2.0 的 FR-65（宠物年龄换算卡）会直接复用本基建。这条用例传的内容
    /// **与分享卡毫无关系**：它能跑通，就说明管线只做"把给定 widget 出成指定尺寸的图"。
    testWidgets('一段"非分享卡"的内容（年龄换算卡雏形）能跑通管线', (tester) async {
      final shot = await exportCard(
        tester,
        content: const ColoredBox(
          color: Colors.white,
          child: Center(
            child: Text('3 岁 = 人类 28 岁',
                textDirection: TextDirection.ltr, style: TextStyle(fontSize: 64)),
          ),
        ),
        canvas: CardCanvas.square,
      );
      expect(shot.width, closeTo(CardCanvas.square.width, 2));
    });

    /// AD-15 Rule 3：纯文字帖也要能出卡 ⇒ 管线必须支持"没有图片区"的卡面。
    testWidgets('没有图片区的卡面也能出图', (tester) async {
      final shot = await exportCard(
        tester,
        content: const ColoredBox(
          color: Colors.white,
          child: Padding(
            padding: EdgeInsets.all(64),
            child: Text('纯文字内容，没有任何图片区', textDirection: TextDirection.ltr),
          ),
        ),
        canvas: CardCanvas.story,
      );
      expect(shot.height, closeTo(CardCanvas.story.height, 2));
    });
  });

  group('AC4 · 白底合成防黑角', () {
    /// 圆角外是 alpha=0 的透明像素。PNG 存透明本身没问题，但相册深色主题 /
    /// IM 转发压缩会把透明平铺成**黑角**（身份证导出踩过：bug 20260731-441）。
    testWidgets('圆角卡面导出后四角不透明', (tester) async {
      final shot = await exportCard(
        tester,
        content: ClipRRect(
          borderRadius: BorderRadius.circular(120),
          child: const ColoredBox(color: Colors.purple),
        ),
        canvas: CardCanvas.square,
      );
      final px = shot.px;
      final w = shot.width, h = shot.height;
      int alphaAt(int x, int y) => px[(y * w + x) * 4 + 3];

      for (final corner in [
        (0, 0),
        (w - 1, 0),
        (0, h - 1),
        (w - 1, h - 1),
      ]) {
        expect(alphaAt(corner.$1, corner.$2), 255,
            reason: '角 ${corner.$1},${corner.$2} 透明 → 相册里会显示成黑角');
      }
    });
  });

  group('AC5 · 水印挂兄弟节点，故导出图不带水印', () {
    /// 这个位置是**有意的取舍**：预览与手动截屏带水印、高清导出不带。
    /// 结构断言：水印不能是截图区的后代（真机视觉留 L2）。
    testWidgets('水印不在 RepaintBoundary 子树内', (tester) async {
      final boundaryKey = GlobalKey();
      const watermarkKey = ValueKey('testWatermark');
      await tester.pumpWidget(MaterialApp(
        home: Center(
          child: SizedBox(
            width: 216,
            child: CardFrame(
              boundaryKey: boundaryKey,
              canvas: CardCanvas.square,
              watermark: const Positioned.fill(
                child: IgnorePointer(child: ColoredBox(key: watermarkKey, color: Colors.black12)),
              ),
              child: const ColoredBox(color: Colors.white),
            ),
          ),
        ),
      ));

      expect(find.byKey(watermarkKey), findsOneWidget, reason: '水印本身要在预览里出现');
      expect(
        find.descendant(
          of: find.byKey(boundaryKey),
          matching: find.byKey(watermarkKey),
        ),
        findsNothing,
        reason: '水印一旦挪进截图区，付费/正式导出图就被污染了',
      );
    });
  });

  group('AC3 · 🔴 二维码在导出图里真的够大', () {
    const shareUrl = 'https://tailtopia.id/p/AbCdEf123456';

    /// 🔴 **本 story 最容易做错的一处，所以这条测试去量导出图的像素**。
    ///
    /// 现场：预览宽度只有 216 逻辑像素，卡面画布 1080 ⇒ 缩放 1/5。
    /// 二维码在屏幕上只有 220/5 = 44px —— 若按预览像素出图就是 44px 的码，扫不出来。
    /// 走管线（反算倍率）后，导出图里应当回到 ~220px。
    testWidgets('导出图里二维码边长 ≥ 140px', (tester) async {
      final shot = await exportCard(
        tester,
        canvas: CardCanvas.square,
        content: const ColoredBox(
          color: Colors.white,
          child: Center(child: CardQr(data: shareUrl, side: 220)),
        ),
      );

      final px = shot.px;
      final w = shot.width, h = shot.height;
      int minX = w, minY = h, maxX = -1, maxY = -1;
      for (var y = 0; y < h; y++) {
        for (var x = 0; x < w; x++) {
          final i = (y * w + x) * 4;
          // 码元是黑的，底板与卡面都是白的 ⇒ 暗像素的外接矩形就是码本体。
          final lum = (px[i] + px[i + 1] + px[i + 2]) / 3;
          if (lum < 128) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
          }
        }
      }

      expect(maxX, greaterThan(-1), reason: '导出图里没找到任何暗像素 —— 二维码根本没画出来');
      final measured = maxX - minX + 1;
      // 屏幕上它只有 ~44px；量到的必须是导出分辨率下的尺寸。
      expect(measured, greaterThanOrEqualTo(CardQr.minExportSide),
          reason: '导出图里二维码只有 ${measured}px（< ${CardQr.minExportSide}）⇒ 扫不出来');
      expect(CardQr.minExportSide, 140, reason: 'AD-15 Rule 4 第 3 条');
    });

    /// AD-15 Rule 4 第 2 条。按"最少码元 = 21"反推，对任何实际版本都 ≥4 码元。
    test('四周留白 ≥ 4 个码元宽度', () {
      expect(CardQr.quietZoneModules, greaterThanOrEqualTo(4));
      const side = 220.0;
      final quiet = CardQr.quietZonePxFor(side);
      final widestModule = side / 21; // QR version 1，码元最宽的情形
      expect(quiet, greaterThanOrEqualTo(4 * widestModule - 0.001));
      expect(CardQr.footprintFor(side), closeTo(side + 2 * quiet, 0.001));
    });

    /// AD-15 Rule 4 第 1 条：内容是**该条内容的分享链接**，组件自己不造链接。
    ///
    /// ⚠️ `QrImageView` 的 payload 是私有字段、读不到，所以这里换个角度证伪：
    /// **换一条链接就必须画出不一样的码**。若组件偷偷塞了个固定值（比如通用下载页），
    /// 两次出图的像素会完全相同。
    /// （「链接本身拼得对不对」在 9-3 —— 链接是那边造的。）
    testWidgets('换链接 → 码真的变了（payload 确实进了编码器）', (tester) async {
      Future<Uint8List> pxFor(String url) async {
        final shot = await exportCard(
          tester,
          canvas: CardCanvas.square,
          content: ColoredBox(
            color: Colors.white,
            child: Center(child: CardQr(data: url, side: 220)),
          ),
        );
        return shot.px;
      }

      // 用校验和比，不用 orderedEquals：后者失败时会往终端糊 460 万个字节。
      int checksum(Uint8List px) {
        var h = 17;
        for (var i = 0; i < px.length; i += 97) {
          h = (h * 31 + px[i]) & 0x3FFFFFFF;
        }
        return h;
      }

      final a = await pxFor('https://tailtopia.id/p/AbCdEf123456');
      final b = await pxFor('https://tailtopia.id/p/ZzYyXx987654');
      expect(a.length, b.length);
      expect(checksum(a), isNot(checksum(b)));
    });

    /// AD-15 Rule 4 第 4 条：纯文字模板背景是紫色渐变，压上去识别率显著下降。
    /// 默认必须带白底板。
    testWidgets('默认带白色底板', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Center(child: CardQr(data: shareUrl, side: 220)),
      ));
      final container = tester.widget<Container>(find.ancestor(
        of: find.byType(QrImageView),
        matching: find.byType(Container),
      ));
      expect((container.decoration as BoxDecoration?)?.color, Colors.white);
    });

    test('低于 140px 的边长在 debug 下直接被 assert 挡住', () {
      expect(() => CardQr(data: shareUrl, side: 60), throwsAssertionError);
    });
  });
}

/// 一张导出图：尺寸 + 原始 RGBA 像素。
class _Shot {
  _Shot(this.width, this.height, this.px);
  final int width;
  final int height;
  final Uint8List px;
}
