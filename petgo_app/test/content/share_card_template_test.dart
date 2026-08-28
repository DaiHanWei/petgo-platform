import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:tailtopia/core/theme/app_theme.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/content/domain/share_card_data.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_template.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_render_pipeline.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';

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

  /// 🔴 **卡面三段的固定占比**：图片 65% / 作者+正文 20% / 品牌 15%（产品 2026-08-28 定稿）。
  ///
  /// <h2>它取代了什么</h2>
  /// 本组上一版钉的是「下半部分按内容收缩、图片在 42%~64% 之间浮动」——
  /// 那是为了修「短文案时卡面中间空一大条白」。修好了，但带来一个新问题：
  /// **同一批分享卡长得不一样高**（文案长短决定图片多大），发到 Story 里排在一起时不成套。
  /// 产品因此改为固定三段。旧断言不是写错了，是**口径被产品换掉了** —— 故整组重写。
  ///
  /// ⚠️ 固定比例的代价是明知故犯的：一行短文案时内容段会剩下约 100px 空白。
  /// 要么每张卡一样高、要么不留空白，二者不可兼得。
  group('bug 20260828 · 卡面三段固定占比 65 / 20 / 15', () {
    /// 量某一段占画布高度的比例。比例与预览缩放无关，故可在预览坐标系里量。
    Future<double> bandFraction(
      WidgetTester tester, {
      required String key,
      required String body,
      required CardCanvas canvas,
      String? imageUrl = 'https://x/a.jpg',
    }) async {
      final boundaryKey = GlobalKey();
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.light,
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
              child: ShareCardTemplate(
                data: dataWith(imageUrl: imageUrl, body: body),
                canvas: canvas,
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      final card = tester.getRect(find.byKey(boundaryKey)).height;
      return tester.getRect(find.byKey(ValueKey(key))).height / card;
    }

    const shortBody = 'disini';
    const longBody = 'Musim hujan bikin anabul gampang masuk angin. '
        'Pastikan tempat tidurnya kering dan hangat, lap kaki tiap habis jalan ya. '
        'Jangan lupa cek telinga dan bulunya juga supaya tidak lembap dan berjamur '
        'sepanjang musim hujan yang panjang ini, ya bund.';

    /// 🔴 三段占比**不随文案长短变化** —— 这正是固定比例要买到的东西：每张卡一样高。
    for (final (label, body) in [('短正文', shortBody), ('长正文', longBody)]) {
      testWidgets('9:16 · $label → 65 / 20 / 15', (tester) async {
        expect(
            await bandFraction(tester,
                key: ShareCardTemplate.shareCardImageAreaKey,
                body: body,
                canvas: CardCanvas.story),
            closeTo(0.65, 0.005));
        expect(
            await bandFraction(tester,
                key: ShareCardTemplate.shareCardContentAreaKey,
                body: body,
                canvas: CardCanvas.story),
            closeTo(0.20, 0.005));
        expect(
            await bandFraction(tester,
                key: ShareCardTemplate.shareCardBrandAreaKey,
                body: body,
                canvas: CardCanvas.story),
            closeTo(0.15, 0.005),
            reason: '🔴 品牌段占比变了 —— 一批卡混着发到 Story 里就不成套了');
      });
    }

    /// 🔴 **1:1 上品牌段必须比 15% 高** —— 这不是没照产品的数做，是**物理装不下**。
    ///
    /// 二维码可扫底线 140px，而它四周必须留 4 个码元的静默区 ⇒ 实际占位 140×1.381≈193px。
    /// 1:1 画布只有 1080 高，15% = 162px < 193px。硬按 15% 的结果是码被压到 87px、
    /// 扫不出来（既有的「导出图里码 ≥140」那条用例当场抓住过）。
    /// 产品 2026-08-27 拍板：9:16 严格 15%，1:1 抬到装得下为止，差额从图片与内容按 65:20 扣。
    ///
    /// ⚠️ 断言的是「>15% 且刚好够用」，不是写死 19.2% ——
    /// 那个数由画布高、可扫底线、静默区规则三者算出，写死会在任何一处变动时变成假绿。
    testWidgets('1:1 · 品牌段被二维码底线抬高，图片:内容仍是 65:20', (tester) async {
      final brand = await bandFraction(tester,
          key: ShareCardTemplate.shareCardBrandAreaKey,
          body: shortBody,
          canvas: CardCanvas.square);
      final image = await bandFraction(tester,
          key: ShareCardTemplate.shareCardImageAreaKey,
          body: shortBody,
          canvas: CardCanvas.square);
      final content = await bandFraction(tester,
          key: ShareCardTemplate.shareCardContentAreaKey,
          body: shortBody,
          canvas: CardCanvas.square);

      final needed =
          CardQr.footprintFor(CardQr.minExportSide) / CardCanvas.square.height;
      expect(brand, greaterThan(0.15),
          reason: '🔴 1:1 上还按 15% ⇒ 二维码扫不出来');
      expect(brand, greaterThanOrEqualTo(needed),
          reason: '🔴 品牌段装不下二维码的静默区 ⇒ 扫不出来');
      expect(brand, lessThan(needed + 0.03),
          reason: '品牌段比需要的大出一截 ⇒ 白占了图片和内容的地方');

      // 剩下的仍按 65:20 分 —— 抬品牌段不该顺手改变图文比例。
      expect(image / content, closeTo(0.65 / 0.20, 0.02));
      expect(image + content + brand, closeTo(1.0, 0.005),
          reason: '三段没铺满卡面 ⇒ 中间会多出一条谁也没注意到的缝');
    });

    /// 🛡 纯文字模板的品牌段**也是 15%** —— 两套模板混着发才成套。
    testWidgets('纯文字模板：品牌段同样 15%', (tester) async {
      expect(
          await bandFraction(tester,
              key: ShareCardTemplate.shareCardBrandAreaKey,
              body: longBody,
              canvas: CardCanvas.story,
              imageUrl: null),
          closeTo(0.15, 0.005));
    });
  });

  group('bug 20260826 · 卡面按 UI 稿 SH2 的字号与页脚排版', () {
    /// 把卡面渲出来，返回「取 widget」的工具。9:16 下排版单位恰等于卡宽，
    /// 所以稿上的比例可以直接乘 `canvas.width` 来断言。
    Future<void> pumpCard(WidgetTester tester, {String body = 'disini'}) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.light,
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
              boundaryKey: GlobalKey(),
              canvas: CardCanvas.story,
              child: ShareCardTemplate(
                data: dataWith(imageUrl: 'https://x/a.jpg', body: body),
                canvas: CardCanvas.story,
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    double fontOf(WidgetTester tester, Finder f) =>
        tester.widget<Text>(f).style!.fontSize!;

    /// 🔴 卡面文字整体只有设计稿的三分之二（2026-08-26 实机反馈「字体太小、
    /// 和设计不一样」）。稿上卡宽 210px：正文 13px → 0.062，扫码提示 9px → 0.043。
    /// 修复前分别是 0.042 / 0.026。
    ///
    /// ⚠️ 断言留 ±8% 余量：钉的是「有没有按稿放大」，不是像素级复刻 ——
    /// 卡死会让后续微调每次都要改测试，那样它很快就会被顺手改绿。
    testWidgets('正文与扫码提示的字号按 UI 稿 SH2（不再是原来的三分之二）', (tester) async {
      await pumpCard(tester);
      const w = 1080.0; // 9:16 画布宽 = 排版单位

      expect(fontOf(tester, find.text('disini')), closeTo(w * 0.062, w * 0.062 * 0.08),
          reason: '正文字号偏离设计稿 0.062 —— 小了就是修复前那个「太小不好看」的状态');
      expect(fontOf(tester, find.textContaining('Scan')), closeTo(w * 0.043, w * 0.043 * 0.08),
          reason: '扫码提示字号偏离设计稿 0.043');
    });

    /// 🔴 品牌字标资产是**纯白**的（给紫底启动页做的）。卡面白底，不上色就是
    /// 白字画在白纸上 —— 整个 logo 隐形，而**不会有任何报错**。
    /// 实机反馈「设计稿里的 logo 为什么不见了」就是这个。
    testWidgets('品牌字标必须带上色滤镜，否则白底上隐形', (tester) async {
      await pumpCard(tester);
      final svg = tester.widget<SvgPicture>(find.byType(SvgPicture));
      expect(svg.colorFilter, isNotNull,
          reason: '🔴 字标资产是纯白的，去掉 colorFilter 它在白卡上就彻底看不见了');
    });

    /// 页脚行高应当**紧贴二维码**（实机要求「比二维码上下宽 1px 就够」）。
    /// 修复前左列是 `mainAxisAlignment.end`、行按二维码高度撑开，
    /// 提示文字被压到行底，看着就是二维码那一格空出一大块。
    testWidgets('页脚只占一行：行高紧贴二维码（上下各 1px）', (tester) async {
      await pumpCard(tester);
      final qr = tester.getRect(find.byType(CardQr));
      final row = tester.getRect(find.ancestor(
          of: find.byType(CardQr), matching: find.byType(Row)).first);

      // 行高只比二维码多出那 1px×2（换算到预览坐标系不到半个像素）。
      // ⚠️ 阈值必须**卡死**：写成「小于二维码高度的 5%」等于没写 —— 行高本来就
      //    等于二维码高度（左列比它矮），松阈值在回退后照样绿。已实测过这个假绿。
      expect(row.height - qr.height, lessThan(1.0),
          reason: '页脚行比二维码高出一截 ⇒ 又空出多余留白');

      // 🔴 左列（字标 + 扫码提示）必须与二维码**垂直居中对齐**，不能沉到行底。
      //    修复前是 `mainAxisAlignment.end`：左列被压到行底，字标上方空出一大块 ——
      //    加上字标当时还是隐形的，实机看到的就是「二维码那一格空了一大片、
      //    提示文字孤零零掉在左下角」。
      //    判据取「提示文字的底边明显高于二维码底边」：沉底时两者会齐平。
      final hint = tester.getRect(find.textContaining('Scan'));
      expect(hint.bottom, lessThan(qr.bottom - qr.height * 0.1),
          reason: '扫码提示与二维码底边齐平 ⇒ 左列又沉到行底了（应垂直居中）');
    });
  });

  /// 🔴 **内容分享卡不加水印**（产品 2026-08-26 决定）。
  ///
  /// 水印这套是从身份证卡继承来的，在那边有明确用途：高清无水印图是**付费**的，
  /// 预览带水印才防得住截屏白嫖。内容分享卡本身免费出图、且越多人转发越好 ——
  /// 加水印既没有要保护的收入，又让预览满屏花纹看不清卡面。
  ///
  /// ⚠️ 这条钉的是**预览页**。正式导出本来就没有水印（水印挂在截图区之外），
  /// 所以别把这条读成「导出图曾经带过水印」。
  testWidgets('分享卡预览页不挂水印', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.light,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: ShareCardPreviewPage(data: dataWith(imageUrl: 'https://x/a.jpg')),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(CardWatermark), findsNothing,
        reason: '🔴 分享卡是免费出图、鼓励转发的，不该给预览盖水印');
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
