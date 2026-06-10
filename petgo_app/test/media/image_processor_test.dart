import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/shared/utils/image_processor.dart';

void main() {
  const processor = ImageProcessor();

  Uint8List jpegWithGps() {
    final src = img.Image(width: 128, height: 128);
    // 填充一些像素，避免全 0
    img.fill(src, color: img.ColorRgb8(120, 180, 90));
    // 写入 GPS EXIF（模拟带定位的拍摄照片）
    src.exif.gpsIfd.gpsLatitude = 35.6762;
    src.exif.gpsIfd.gpsLongitude = 139.6503;
    return Uint8List.fromList(img.encodeJpg(src));
  }

  test('剥离 EXIF GPS：处理后无 GPS 元数据（G-4）', () {
    final input = jpegWithGps();
    // 前置确认：输入确实带 GPS
    final before = img.decodeJpg(input)!;
    expect(before.exif.gpsIfd.gpsLatitude, isNotNull);

    final output = processor.process(input);

    final after = img.decodeJpg(output)!;
    expect(after.exif.gpsIfd.gpsLatitude, isNull);
    expect(after.exif.gpsIfd.gpsLongitude, isNull);
    expect(after.exif.gpsIfd.isEmpty, isTrue);
  });

  test('压缩到 ≤10MB 且像素尺寸保留（小图无需降分辨率）', () {
    final input = jpegWithGps();
    final output = processor.process(input);
    expect(output.length, lessThanOrEqualTo(kMaxUploadBytes));
    final decoded = img.decodeJpg(output)!;
    expect(decoded.width, 128);
    expect(decoded.height, 128);
  });

  test('无法解码的字节抛 ImageProcessingException', () {
    expect(
      () => processor.process(Uint8List.fromList([1, 2, 3, 4])),
      throwsA(isA<ImageProcessingException>()),
    );
  });
}
