import 'dart:typed_data';

import 'package:fc_native_video_thumbnail/fc_native_video_thumbnail.dart';
import 'package:fc_native_video_thumbnail/fc_native_video_thumbnail_platform_interface.dart'
    show FcVideoThumbnailTime, FcVideoThumbnailTimeUnit;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image/image.dart' as img;

import 'video_spec.dart';

/// 封面目标短边（FR-8）。
const int kVideoCoverShortSide = 720;

/// 把任意图片字节变成「JPEG、短边 ≤ 720」的封面（专题线 video Story 1.6 · FR-8）。
///
/// 短边超过 720 时等比缩小，不放大；重编码同时丢弃 EXIF / GPS。解码失败返回 null。
Uint8List? coverToJpeg720(Uint8List input) {
  img.Image? decoded;
  try {
    decoded = img.decodeImage(input);
  } catch (_) {
    decoded = null;
  }
  if (decoded == null) return null;
  var working = decoded;
  final shortSide = working.width < working.height ? working.width : working.height;
  if (shortSide > kVideoCoverShortSide) {
    working = working.width <= working.height
        ? img.copyResize(working, width: kVideoCoverShortSide)
        : img.copyResize(working, height: kVideoCoverShortSide);
  }
  working.exif = img.ExifData();
  return Uint8List.fromList(img.encodeJpg(working, quality: 85));
}

/// 默认封面：取视频第 1 秒的画面（专题线 video Story 1.6 · AD-9）。
///
/// 用 `fc_native_video_thumbnail`（系统接口）。⚠️ Android 上只有把源文件当 **Uri** 传
/// （`srcFileUri: true`）时才支持指定时间点 [at]，传纯路径会退回默认帧。失败一律返回 null，
/// 由页面提示「请选择封面」。
class VideoCoverService {
  const VideoCoverService();

  Future<Uint8List?> defaultCover(VideoDraft draft) async {
    try {
      final bytes = await FcNativeVideoThumbnail().saveThumbnailToBytes(
        srcFile: Uri.file(draft.path).toString(),
        srcFileUri: true,
        width: draft.width,
        height: draft.height,
        format: 'jpeg',
        at: FcVideoThumbnailTime(1, FcVideoThumbnailTimeUnit.seconds),
        quality: 85,
      );
      return bytes == null ? null : coverToJpeg720(bytes);
    } catch (_) {
      return null;
    }
  }
}

final videoCoverServiceProvider = Provider<VideoCoverService>((ref) => const VideoCoverService());
