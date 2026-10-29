import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/content/video/video_cover_service.dart';

/// L0：专题线 video Story 1.6 —— 封面统一成 JPEG、短边 ≤ 720。
void main() {
  Uint8List png(int w, int h) => Uint8List.fromList(img.encodePng(img.Image(width: w, height: h)));

  test('竖屏 1080×1920 → 720×1280', () {
    final out = img.decodeJpg(coverToJpeg720(png(1080, 1920))!)!;
    expect([out.width, out.height], [720, 1280]);
  });

  test('横屏 1920×1080 → 1280×720', () {
    final out = img.decodeJpg(coverToJpeg720(png(1920, 1080))!)!;
    expect([out.width, out.height], [1280, 720]);
  });

  test('短边已 ≤ 720 不放大', () {
    final out = img.decodeJpg(coverToJpeg720(png(480, 640))!)!;
    expect([out.width, out.height], [480, 640]);
  });

  test('输出是 JPEG（FF D8 开头）', () {
    final out = coverToJpeg720(png(100, 100))!;
    expect(out.sublist(0, 2), [0xFF, 0xD8]);
  });

  test('坏字节 → null', () {
    expect(coverToJpeg720(Uint8List.fromList([1, 2, 3])), isNull);
  });
}
