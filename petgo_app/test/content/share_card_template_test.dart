import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/content/domain/share_card_data.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_template.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_render_pipeline.dart';

/// Story 9.2 · 两套模板与下载二维码。
///
/// 🔴 本文件钉的三件事：
/// 1. **纯文字帖也能出卡**（AC1 的"保证所有内容都能出卡"）—— 出不了就等于这功能
///    对 Moment / Knowledge 里大量纯文字内容不存在。
/// 2. **两套模板 × 两种尺寸 = 四种组合都出得来、尺寸对**（AC1 + AC3）。
/// 3. **导出图里二维码 ≥140px**（AC2 第 3 条）—— 尤其 1:1 小画布，量的是**导出 PNG 的像素**。
void main() {
  const shareUrl = 'https://tailtopia.id/p/AbCdEf123456';

  ShareCardData dataWith({String? imageUrl, String? body}) => ShareCardData(
        authorName: 'Sari Wulandari',
        type: 'KNOWLEDGE',
        shareUrl: shareUrl,
        body: body ??
            'Musim hujan bikin anabul gampang masuk angin. '
                'Pastikan tempat tidurnya kering & hangat, lap kaki tiap habis jalan ya',
        imageUrl: imageUrl,
      );

  Future<_Shot> exportShareCard(
    WidgetTester tester, {
    required ShareCardData data,
    required CardCanvas canvas,
  }) async {
    final boundaryKey = GlobalKey();
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Center(
        child: SizedBox(
          width: 216,
          child: CardFrame(
            boundaryKey: boundaryKey,
            canvas: canvas,
            child: ShareCardTemplate(data: data, canvas: canvas),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    // 预览坐标系下的两个矩形：整张卡 与 二维码。用来把"码在哪"映射到导出图坐标。
    final frameRect = tester.getRect(find.byKey(boundaryKey));
    final qrRect = tester.getRect(find.byType(CardQr));

    final shot = await tester.runAsync(() async {
      final bytes = await CardRenderPipeline.capture(boundaryKey: boundaryKey, canvas: canvas);
      if (bytes == null) return null;
      final codec = await ui.instantiateImageCodec(bytes);
      final img = (await codec.getNextFrame()).image;
      final rgba = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final result = _Shot(img.width, img.height, rgba.buffer.asUint8List(), frameRect, qrRect);
      img.dispose();
      return result;
    });
    expect(shot, isNotNull);
    return shot!;
  }

  group('AC1 · 两套模板由「有没有图」单点决定', () {
    test('无图 → hasImage 为 false（纯文字模板）', () {
      expect(dataWith().hasImage, isFalse);
      expect(dataWith(imageUrl: '').hasImage, isFalse);
      expect(dataWith(imageUrl: 'https://x/a.jpg').hasImage, isTrue);
    });

    /// 🛡 这条是 AC1 的核心断言：**纯文字帖不得因"没有图"而无法分享**。
    testWidgets('纯文字帖能出卡（不放图片区，仍出图成功）', (tester) async {
      final shot = await exportShareCard(
        tester,
        data: dataWith(),
        canvas: CardCanvas.story,
      );
      expect(shot.width, closeTo(CardCanvas.story.width, 2));
      expect(shot.height, closeTo(CardCanvas.story.height, 2));
    });

    /// 极端：连正文都空（只有作者 + 品牌 + 码）也不能崩。
    testWidgets('正文为空也能出卡', (tester) async {
      final shot = await exportShareCard(
        tester,
        data: dataWith(body: ''),
        canvas: CardCanvas.square,
      );
      expect(shot.width, closeTo(CardCanvas.square.width, 2));
    });

    /// 超长正文：`Text` 只认 maxLines、不会按剩余高度自己收住，
    /// 所以模板按可用高度算行数。这条用例会在溢出时直接红（widget test 把溢出当错误）。
    testWidgets('超长正文不破版（不溢出）', (tester) async {
      final shot = await exportShareCard(
        tester,
        data: dataWith(body: 'Anabul ' * 400),
        canvas: CardCanvas.square,
      );
      expect(shot.width, closeTo(CardCanvas.square.width, 2));
    });
  });

  group('AC1 · 从 ContentDetail 取卡片数据', () {
    ContentDetail detail({List<String> images = const [], bool deleted = false}) => ContentDetail(
          id: 1,
          authorId: 9,
          authorDeleted: deleted,
          type: 'DAILY',
          likeCount: 0,
          commentCount: 0,
          liked: false,
          isAuthor: false,
          createdAt: DateTime.utc(2026, 8, 21),
          authorNickname: 'Sari',
          authorAvatarUrl: 'https://example.invalid/av.jpg',
          body: 'Jalan pagi',
          imageUrls: images,
        );

    test('多图帖只取首图（卡片是一张静态图，轮播在卡上没有意义）', () {
      final d = ShareCardData.fromDetail(
        detail(images: const ['https://x/1.jpg', 'https://x/2.jpg']),
        shareUrl: shareUrl,
        fallbackAuthorName: 'Pengguna dihapus',
      );
      expect(d.imageUrl, 'https://x/1.jpg');
      expect(d.hasImage, isTrue);
    });

    test('无图帖 → 走纯文字模板', () {
      final d = ShareCardData.fromDetail(detail(),
          shareUrl: shareUrl, fallbackAuthorName: 'Pengguna dihapus');
      expect(d.hasImage, isFalse);
    });

    /// 注销作者不带出头像与昵称（与详情页一致的口径）。
    test('注销作者 → 占位名、无头像', () {
      final d = ShareCardData.fromDetail(detail(deleted: true),
          shareUrl: shareUrl, fallbackAuthorName: 'Pengguna dihapus');
      expect(d.authorName, 'Pengguna dihapus');
      expect(d.authorAvatarUrl, isNull);
    });
  });

  group('AC1 + AC3 · 两套模板 × 两种尺寸 = 四种组合', () {
    for (final (label, data) in [
      ('图文', ShareCardData(
        authorName: 'Sari',
        type: 'DAILY',
        shareUrl: shareUrl,
        body: 'Jalan pagi bareng Bobby',
        imageUrl: 'https://example.invalid/a.jpg',
      )),
      ('纯文字', ShareCardData(
        authorName: 'Andi',
        type: 'KNOWLEDGE',
        shareUrl: shareUrl,
        body: 'Musim hujan bikin anabul gampang masuk angin',
      )),
    ]) {
      for (final (sizeLabel, canvas) in [
        ('9:16', CardCanvas.story),
        ('1:1', CardCanvas.square),
      ]) {
        testWidgets('$label模板 · $sizeLabel', (tester) async {
          final shot = await exportShareCard(tester, data: data, canvas: canvas);
          expect(shot.width, closeTo(canvas.width, 2));
          expect(shot.height, closeTo(canvas.height, 2));
        });
      }
    }
  });

  group('AC2 · 🔴 二维码在导出图里够大', () {
    /// 量的是**导出 PNG 的暗像素外接矩形**，不是算术。
    ///
    /// 1:1 是更严的那一档：画布只有 1080 高，按比例算出的码边长会撞到 140 下限
    /// 被抬上去 —— 正是「小画布上码不能跟着一起缩」这条护栏。
    for (final (label, canvas) in [
      ('9:16', CardCanvas.story),
      ('1:1（会撞到 140 下限）', CardCanvas.square),
    ]) {
      testWidgets('$label 导出图里二维码边长 ≥ 140px', (tester) async {
        final shot = await exportShareCard(tester, data: dataWith(), canvas: canvas);
        final side = _measureQrSide(shot);
        expect(side, greaterThanOrEqualTo(CardQr.minExportSide),
            reason: '导出图里二维码只有 ${side}px ⇒ 扫不出来');
      });
    }

    /// AC2 的旁注：纯文字模板的卡面有圆角（`ClipRRect`），圆角外是透明像素。
    /// 必须走到 9-1 链路里的「白底合成防黑角」那一段，否则相册深色主题 /
    /// IM 转发压缩会把四角平铺成黑色。真机上才看得见，所以这里用像素钉住。
    testWidgets('纯文字模板走到了「白底合成防黑角」（四角不透明）', (tester) async {
      final shot = await exportShareCard(
        tester,
        data: dataWith(),
        canvas: CardCanvas.story,
      );
      int alphaAt(int x, int y) => shot.px[(y * shot.width + x) * 4 + 3];
      for (final c in [
        (0, 0),
        (shot.width - 1, 0),
        (0, shot.height - 1),
        (shot.width - 1, shot.height - 1),
      ]) {
        expect(alphaAt(c.$1, c.$2), 255, reason: '角 ${c.$1},${c.$2} 透明 → 相册里是黑角');
      }
    });

    test('留白与下限的口径来自 9-1 的基建（未被本 story 改动）', () {
      expect(CardQr.minExportSide, 140);
      expect(CardQr.quietZoneModules, greaterThanOrEqualTo(4));
    });
  });
}

/// 量导出图里二维码的实际像素边长。
///
/// 做法：先用**预览坐标系**下二维码的矩形，按「导出图宽 / 预览卡宽」映射到导出图坐标，
/// 只在那一小块里找暗像素的外接矩形。
///
/// ⚠️ 这不是循环论证：区域来自预览布局 + **导出图的真实宽度**。
/// 若管线没做反算倍率，导出图就只有预览那么大，映射出的区域随之变小、量到的码也小 → 断言红；
/// 若模板给的边长过小，区域本身就小 → 同样红。
/// （不能简单扫"右下角一片"：widget test 用的是 Ahem 测试字体，**每个字形都是实心黑块**，
/// 作者名会比真实渲染宽得多、横跨到右半边，把外接矩形撑大成假绿——第一版就栽在这。）
double _measureQrSide(_Shot shot) {
  final ratio = shot.width / shot.frameRect.width;
  int mapX(double x) => ((x - shot.frameRect.left) * ratio).round().clamp(0, shot.width - 1);
  int mapY(double y) => ((y - shot.frameRect.top) * ratio).round().clamp(0, shot.height - 1);

  final x0 = mapX(shot.qrRect.left), x1 = mapX(shot.qrRect.right);
  final y0 = mapY(shot.qrRect.top), y1 = mapY(shot.qrRect.bottom);

  var minX = shot.width, maxX = -1;
  for (var y = y0; y <= y1; y++) {
    for (var x = x0; x <= x1; x++) {
      final i = (y * shot.width + x) * 4;
      final lum = (shot.px[i] + shot.px[i + 1] + shot.px[i + 2]) / 3;
      if (lum < 128) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
      }
    }
  }
  if (maxX < 0) return 0;
  return (maxX - minX + 1).toDouble();
}

class _Shot {
  _Shot(this.width, this.height, this.px, this.frameRect, this.qrRect);
  final int width;
  final int height;
  final Uint8List px;

  /// 预览坐标系下整张卡的矩形。
  final Rect frameRect;

  /// 预览坐标系下二维码（含白色底板）的矩形。
  final Rect qrRect;
}
