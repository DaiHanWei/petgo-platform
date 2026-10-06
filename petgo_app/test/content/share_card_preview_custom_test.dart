import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/share_card_data.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_render_pipeline.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 4.1 · AC2：预览页的通用卡（custom）形态。帖子形态行为不变。
void main() {
  Widget app(Widget home) => MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: home,
      );

  Widget card(CardCanvas c) => ColoredBox(
        key: const ValueKey('customCard'),
        color: const Color(0xFF7B5CFA),
        child: SizedBox(width: c.width, height: c.height),
      );

  ShareCardPreviewPage custom({
    bool watermarked = false,
    void Function(int)? onGenerated,
    ShareCardPreviewAction? primaryAction,
  }) =>
      ShareCardPreviewPage.custom(
        builder: card,
        watermarked: watermarked,
        exportName: 'test_card',
        onGenerated: onGenerated,
        primaryAction: primaryAction,
      );

  testWidgets('帖子形态不变：有尺寸切换、无水印', (tester) async {
    await tester.pumpWidget(app(const ShareCardPreviewPage(
      data: ShareCardData(authorName: 'Sari', type: 'DAILY', shareUrl: 'https://x/c/t', body: 'halo'),
    )));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsOneWidget);
    expect(find.byType(CardWatermark), findsNothing);
  });

  testWidgets('custom：固定 9:16、不渲染尺寸切换；watermarked 决定有无水印', (tester) async {
    await tester.pumpWidget(app(custom()));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsNothing);
    expect(find.byType(CardWatermark), findsNothing);
    expect(tester.widget<CardFrame>(find.byType(CardFrame)).canvas, CardCanvas.story);

    await tester.pumpWidget(app(custom(watermarked: true)));
    await tester.pumpAndSettle();
    expect(find.byType(CardWatermark), findsOneWidget);
  });

  testWidgets('custom：captureForTest 生效、onGenerated 被调；出图后弹存相册 / 分享选单', (tester) async {
    CardCanvas? captured;
    ShareCardPreviewPage.captureForTest = (c) async {
      captured = c;
      return Uint8List.fromList(const [1, 2, 3]);
    };
    addTearDown(() => ShareCardPreviewPage.captureForTest = null);
    int? generatedMs;

    await tester.pumpWidget(app(custom(onGenerated: (ms) => generatedMs = ms)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
    await tester.pumpAndSettle();

    expect(captured, CardCanvas.story);
    expect(generatedMs, isNotNull);
    expect(find.byKey(const ValueKey('cardSaveToGallery')), findsOneWidget);
  });

  testWidgets('无主操作 = 单个主按钮；有主操作 = 主操作 FilledButton + 分享降为次按钮', (tester) async {
    await tester.pumpWidget(app(custom()));
    await tester.pumpAndSettle();
    expect(find.byType(FilledButton), findsOneWidget);
    expect(find.byType(OutlinedButton), findsNothing);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).key, const ValueKey('shareCardShareCta'));

    var tapped = 0;
    await tester.pumpWidget(app(custom(
      primaryAction: ShareCardPreviewAction(
          key: const ValueKey('primary'), label: 'Pamer', onPressed: () => tapped++),
    )));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).key, const ValueKey('primary'));
    expect(tester.widget<OutlinedButton>(find.byType(OutlinedButton)).key, const ValueKey('shareCardShareCta'));
    await tester.tap(find.byKey(const ValueKey('primary')));
    expect(tapped, 1);
  });

  /// 🔴 水印导出：`watermarked:true` 的导出图必须带水印（截外层 boundary），
  /// 与 `watermarked:false` 的导出像素不同。只给 CardFrame 传水印是做不到的（水印在内层 boundary 外）。
  testWidgets('watermarked:true 导出 PNG 含水印，与 false 的导出像素不同', (tester) async {
    Future<Uint8List> export({required bool watermarked}) async {
      Uint8List? bytes;
      ShareCardPreviewPage.captureForTest = null;
      await tester.pumpWidget(app(custom(watermarked: watermarked)));
      await tester.pumpAndSettle();
      if (watermarked) {
        // 水印图是异步解码的资产：先在真实时钟里解好，再重绘一帧。
        final ctx = tester.element(find.byType(CardWatermark));
        await tester.runAsync(() => precacheImage(const AssetImage('assets/ktp/watermark_tile.png'), ctx));
        await tester.pumpAndSettle();
      }
      // 取预览页实际会截的那一层：带水印 = 包住 CardFrame 的外层；否则 = CardFrame 内层。
      final boundary = watermarked
          ? find.ancestor(of: find.byType(CardFrame), matching: find.byType(RepaintBoundary)).first
          : find.descendant(of: find.byType(CardFrame), matching: find.byType(RepaintBoundary)).first;
      final key = tester.widget<RepaintBoundary>(boundary).key! as GlobalKey;
      if (watermarked) {
        expect(find.descendant(of: boundary, matching: find.byType(CardWatermark)), findsOneWidget);
      }
      await tester.runAsync(() async {
        final png = await CardRenderPipeline.capture(boundaryKey: key, canvas: CardCanvas.story);
        final codec = await ui.instantiateImageCodec(png!);
        final img = (await codec.getNextFrame()).image;
        expect(img.width, closeTo(CardCanvas.story.width, 2));
        bytes = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!.buffer.asUint8List();
        img.dispose();
      });
      return bytes!;
    }

    final clean = await export(watermarked: false);
    final marked = await export(watermarked: true);
    expect(marked.length, clean.length);
    var diff = 0;
    for (var i = 0; i < clean.length; i++) {
      if (clean[i] != marked[i]) diff++;
    }
    expect(diff, greaterThan(clean.length ~/ 100), reason: '带水印导出应与无水印导出有明显像素差');
  });
}
