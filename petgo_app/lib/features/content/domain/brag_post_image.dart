import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

import '../../../shared/utils/image_processor.dart';
import 'feed_image_layout.dart';

/// 「Pamer di postingan」发帖用图（V1.3.2 Story 4.4 · AD-11）：卡图 PNG → 可上传的 JPEG。
///
/// - 🔴 **必须转 JPEG**：上传票据的 content-type 写死 `image/jpeg`（`media_upload_use_case.dart`），
///   直接喂管线出的 PNG 会以 JPEG 名义存一张 PNG。转码复用 [ImageProcessor]（剥 EXIF、≤10MB）。
/// - 比例收进信息流免裁区间 `[kFeedRatioMin, kFeedRatioMax]`：在区间内原样转码；过高 / 过宽 →
///   以卡面同色底（取左上角像素：管线出图已合成到白底，圆角外即卡底色）**补边**到最近的边界比例，
///   **不裁卡面**（登机牌的 PASSPORT / SEAT 字段必须完整）。
///
/// 在后台 isolate 做（1080×1440 的纯 Dart 解码 / 编码放主 isolate 会掉帧）。
Future<Uint8List> prepareBragPostImage(Uint8List png) => compute(bragPostImageSync, png);

/// [prepareBragPostImage] 的同步纯函数（单测打它）。解不开时抛 [ImageProcessingException]。
Uint8List bragPostImageSync(Uint8List png) {
  img.Image? decoded;
  try {
    decoded = img.decodeImage(png);
  } catch (_) {
    decoded = null;
  }
  if (decoded == null) throw const ImageProcessingException('无法解码卡图');
  final w = decoded.width;
  final h = decoded.height;
  final ratio = w / h;
  if (ratio >= kFeedRatioMin && ratio <= kFeedRatioMax) {
    return const ImageProcessor().process(png);
  }
  // 过高 → 加宽；过宽 → 加高。向上取整，保证补边后比例落在闭区间内。
  final targetW = ratio < kFeedRatioMin ? (h * kFeedRatioMin).ceil() : w;
  final targetH = ratio > kFeedRatioMax ? (w / kFeedRatioMax).ceil() : h;
  final bg = decoded.getPixel(0, 0);
  final canvas = img.Image(width: math.max(targetW, w), height: math.max(targetH, h), numChannels: 3)
    ..clear(img.ColorRgb8(bg.r.toInt(), bg.g.toInt(), bg.b.toInt()));
  img.compositeImage(canvas, decoded, dstX: (canvas.width - w) ~/ 2, dstY: (canvas.height - h) ~/ 2);
  return const ImageProcessor().process(Uint8List.fromList(img.encodePng(canvas, level: 1)));
}
