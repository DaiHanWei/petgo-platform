import 'dart:typed_data';

import 'package:image/image.dart' as img;

/// 上传单张上限：10MB（架构 §Frontend Architecture：客户端压缩 ≤10MB 后 STS 直传）。
const int kMaxUploadBytes = 10 * 1024 * 1024;

/// 处理失败（无法解码图片）。
class ImageProcessingException implements Exception {
  const ImageProcessingException(this.message);
  final String message;
  @override
  String toString() => 'ImageProcessingException: $message';
}

/// 客户端图片处理（Story 2.1 · F3，主路径）。
///
/// 解码 → **清空 EXIF（含 GPS）** → 按需降质/降分辨率，重编码为 JPEG 直至 ≤[maxBytes]。
/// 因为是从像素重编码并显式清空元数据，**定位信息（GPS）必被剥离**（G-4）。纯 Dart，headless 可单测。
class ImageProcessor {
  const ImageProcessor();

  Uint8List process(Uint8List input, {int maxBytes = kMaxUploadBytes}) {
    img.Image? decoded;
    try {
      decoded = img.decodeImage(input);
    } catch (_) {
      decoded = null;
    }
    if (decoded == null) {
      throw const ImageProcessingException('无法解码所选图片');
    }

    var working = decoded;
    // 隐私：清空所有 EXIF（含 GPS），重编码不携带任何元数据。
    working.exif = img.ExifData();

    var quality = 90;
    var out = Uint8List.fromList(img.encodeJpg(working, quality: quality));

    // 1) 先降质（90→30）
    while (out.length > maxBytes && quality > 30) {
      quality -= 15;
      out = Uint8List.fromList(img.encodeJpg(working, quality: quality));
    }

    // 2) 仍超限则降分辨率（每轮 ×0.8），最低 256px 宽兜底
    while (out.length > maxBytes && working.width > 256) {
      working = img.copyResize(working, width: (working.width * 0.8).round());
      working.exif = img.ExifData();
      out = Uint8List.fromList(img.encodeJpg(working, quality: quality));
    }

    return out;
  }
}
