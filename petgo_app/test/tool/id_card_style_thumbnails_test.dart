import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_fields.dart';

/// 「Pilih Kartu」8 张缩略图生成器（V1.3.4 ktp Story 1.5，D-2 / AD-8）。**不是测试**：默认 `flutter test` 跳过，
/// 只由 `scripts/gen-id-card-style-thumbnails.sh` 设 `GEN_ID_CARD_THUMBS=1` 运行。
///
/// 对 8 个 [IdCardStyle] 用**注册表的卡面构建函数**渲染固定示例数据（不带水印、不带宠物照片 T-11），
/// 输出 PNG 到 `build/id_card_style_thumbs/<snake>.png`：16:10 卡面宽 600，护照高同为 362、宽按比例。
/// 包装脚本再转 `assets/id_card/styles/<snake>.webp`。设计日后可同名替换图片，代码不改。
void main() {
  final enabled = Platform.environment.containsKey('GEN_ID_CARD_THUMBS');

  /// 示例数据（UI 稿 B1 示例卡面）：13+10=23 日（公）、03 月、26 年、猫 02。只在此处，不进 lib/。
  final sample = IdCardData(
    generated: true,
    serialId: 1,
    name: 'Oyen',
    petType: 'CAT',
    breed: 'Domestik',
    birthday: DateTime(2026, 3, 13),
    gender: 'MALE',
    birthCity: 'Bandung',
    address: 'Jl. Melati No. 25',
    occupation: 'Chief Happiness Officer',
    school: 'TAILTOPIA ACADEMY',
    faculty: 'FAKULTAS KEBAHAGIAAN',
    cardNo: 'TT230326020110',
    passportNo: buildPreviewPassportNo(petType: 'CAT', year: 2026),
    createdAt: DateTime(2026, 10, 12),
  );

  /// 输出文件名取注册表 thumbnailAsset 的文件名（去扩展名），保证与网格加载路径一致。
  String baseName(IdCardStyle s) => s.thumbnailAsset.split('/').last.replaceAll(RegExp(r'\.webp$'), '');

  Future<void> loadFont(String family, String path) async {
    final loader = FontLoader(family)
      ..addFont(File(path).readAsBytes().then((b) => ByteData.view(b.buffer)));
    await loader.load();
  }

  testWidgets('生成 8 张样式缩略图', (tester) async {
    await tester.runAsync(() async {
      await loadFont('Rubik', 'assets/fonts/Rubik-VariableFont_wght.ttf');
      final root = Platform.environment['FLUTTER_ROOT'];
      if (root != null) {
        await loadFont('MaterialIcons', '$root/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf');
      }
    });
    final out = Directory('build/id_card_style_thumbs')..createSync(recursive: true);
    const thumbHeight = 362.0;

    for (final style in IdCardStyle.values) {
      final canvas = style.canvas;
      tester.view.physicalSize = canvas;
      tester.view.devicePixelRatio = 1.0;
      final key = GlobalKey();
      await tester.pumpWidget(MaterialApp(
        debugShowCheckedModeBanner: false,
        // 透明 Material：提供默认文字样式（否则 Text 走 debug 兜底样式带黄色下划线），且不给卡面圆角外加底色。
        home: Material(
          type: MaterialType.transparency,
          child: Align(
          alignment: Alignment.topLeft,
          child: RepaintBoundary(
            key: key,
            child: SizedBox(width: canvas.width, height: canvas.height, child: style.buildFront(sample)),
          ),
          ),
        ),
      ));
      // 资源图异步解码：真实时钟里等所有 Image 加载完再截图。
      await tester.runAsync(() async {
        for (final el in find.byType(Image).evaluate()) {
          await precacheImage((el.widget as Image).image, el);
        }
      });
      await tester.pumpAndSettle();

      final width16x10 = 600.0;
      final ratio = canvas.width / canvas.height > 1.5
          ? width16x10 / canvas.width // 16:10 系：宽 600
          : thumbHeight / canvas.height; // 护照：高与 16:10 缩略图同高
      final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final bytes = await tester.runAsync(() async {
        final ui.Image img = await boundary.toImage(pixelRatio: ratio);
        final data = await img.toByteData(format: ui.ImageByteFormat.png);
        return data!.buffer.asUint8List();
      });
      File('${out.path}/${baseName(style)}.png').writeAsBytesSync(bytes!);
    }
    tester.view.reset();
  }, skip: !enabled);
}
