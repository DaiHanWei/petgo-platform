/// 发布端的超界判定（V1.1.6 Story 3.5 · FR-71）。
///
/// V1.3.4 起不再物理裁剪，见 image_exposure.dart（露出区域，原图完整上传）。
///
/// ## 🔴 容差区间只有一份
/// 判"要不要裁"用的就是展示端那对常量（[kFeedRatioMin] / [kFeedRatioMax]），**不另立一份**。
/// 两份一旦漂移，用户会遇到最费解的一类行为：**上传时被判定"不用裁"、展示时却被裁了**
/// （或反过来，白裁一次）。这类不一致没有任何错误信息，只会被当成玄学。
///
/// 闭区间口径也照搬：**只有越界才判需裁**，端点算区间内 —— 3:4 竖拍恰为 0.75 且最常见。
library;

import 'dart:typed_data';

import 'package:image/image.dart' as img;

import 'feed_image_layout.dart';

/// 这张图是否需要裁剪。
///
/// 落在闭区间内 → **直接使用，不弹裁剪框、不做任何裁切**。
bool needsCrop(int width, int height) {
  if (width <= 0 || height <= 0) return false; // 量不出来的不打扰用户
  final ratio = width / height;
  return ratio < kFeedRatioMin || ratio > kFeedRatioMax;
}

/// 只读图片头拿宽高，**不整张解码**。
///
/// 判定"要不要裁"要对每张选中的图都做一次，整张解码 9 遍会让选完图之后卡住好几秒。
/// 解码器的 `startDecode` 只读文件头，代价可以忽略。
ImageSize? imageSizeOf(Uint8List bytes) {
  try {
    final decoder = img.findDecoderForData(bytes);
    if (decoder == null) return null;
    final info = decoder.startDecode(bytes);
    if (info == null) return null;
    final size = ImageSize(info.width, info.height);
    return size.isUsable ? size : null;
  } catch (_) {
    return null;
  }
}
