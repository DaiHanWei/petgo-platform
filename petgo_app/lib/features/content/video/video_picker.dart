import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import 'video_spec_checker.dart';

/// 从相册选一个视频，返回文件路径；取消返回 null（专题线 video Story 1.5 · FR-6）。
///
/// 🔴 只用系统照片选择器（Android 13+ Photo Picker / iOS PHPicker），**不申请任何新权限**（一致性规则 9）；
/// 只拿路径，不读进内存（一致性规则 8）。抽成 provider 便于 widget 测试替换。
typedef VideoPicker = Future<String?> Function();

final videoPickerProvider = Provider<VideoPicker>((ref) {
  return () async {
    final file = await ImagePicker().pickVideo(source: ImageSource.gallery);
    return file?.path;
  };
});

/// MP4 规格检查器（可在测试里替换）。
final videoSpecCheckerProvider = Provider<VideoSpecChecker>((ref) => const VideoSpecChecker());
