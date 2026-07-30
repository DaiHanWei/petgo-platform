import 'dart:typed_data';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/media/media_scope.dart';
import '../../../shared/utils/image_processor.dart';
import '../../../shared/utils/media_permission.dart';
import '../data/media_repository.dart';
import '../data/oss_uploader.dart';

/// 可复用「选图 → 权限 → 压缩剥离 → 请求 STS → 直传」用例（Story 2.1 · F5）。
/// 供 2.2/2.3/2.5/Epic4 复用。单/多图（≤9）。各依赖可注入，便于测试与替换。
class MediaUploadUseCase {
  MediaUploadUseCase({
    required this.repository,
    PermissionGateway? permissionGateway,
    ImagePicker? picker,
    this.processor = const ImageProcessor(),
    OssUploader? uploader,
  }) : permissionGateway =
           permissionGateway ?? const PermissionHandlerGateway(),
       picker = picker ?? ImagePicker(),
       uploader = uploader ?? OssUploader();

  final MediaRepository repository;
  final PermissionGateway permissionGateway;
  final ImagePicker picker;
  final ImageProcessor processor;
  final OssUploader uploader;

  /// 选取并上传单张。权限被拒时弹引导并返回 null（不抛）。压不下 [kMaxUploadBytes] 抛 [ImageProcessingException]。
  /// [context] 用于权限拒绝引导对话框；为 null 时跳过 UI 引导（如纯逻辑调用）。
  Future<OssUploadResult?> pickAndUploadOne({
    required MediaScope scope,
    required MediaSource source,
    BuildContext? context,
  }) async {
    final outcome = await permissionGateway.request(source);
    if (outcome != MediaPermissionOutcome.granted) {
      if (context != null && context.mounted) {
        await showMediaPermissionDeniedDialog(context, permissionGateway);
      }
      return null;
    }

    final picked = await _pick(source);
    if (picked == null) return null;

    final raw = await picked.readAsBytes();
    final processed = processor.process(raw);
    return uploadBytes(scope: scope, bytes: processed);
  }

  /// 仅选图 + 权限 + 压缩剥 EXIF，返回处理后的字节（**不上传**）。
  /// 供 2.3 发布的「先选图入草稿、后由上传状态机重传失败件」分步流程使用。
  /// 权限被拒时弹引导并返回 null。
  Future<Uint8List?> pickAndProcess({
    required MediaSource source,
    BuildContext? context,
  }) async {
    final outcome = await permissionGateway.request(source);
    if (outcome != MediaPermissionOutcome.granted) {
      if (context != null && context.mounted) {
        await showMediaPermissionDeniedDialog(context, permissionGateway);
      }
      return null;
    }
    final picked = await _pick(source);
    if (picked == null) return null;
    final raw = await picked.readAsBytes();
    return processor.process(raw);
  }

  Future<List<Uint8List>> pickMultiAndProcess({
    required int limit,
    BuildContext? context,
  }) async {
    final outcome = await permissionGateway.request(MediaSource.gallery);
    if (outcome != MediaPermissionOutcome.granted) {
      if (context != null && context.mounted) {
        await showMediaPermissionDeniedDialog(context, permissionGateway);
      }
      return const [];
    }
    final picked = await picker.pickMultiImage(
      maxWidth: 2048,
      maxHeight: 2048,
      imageQuality: 85,
    );
    final files = picked.take(limit);
    final result = <Uint8List>[];
    for (final file in files) {
      result.add(processor.process(await file.readAsBytes()));
    }
    return result;
  }

  /// 上传已处理好的字节（请求预签名票据 → 直传）。抽出便于单测后半段与复用。
  Future<OssUploadResult> uploadBytes({
    required MediaScope scope,
    required Uint8List bytes,
  }) async {
    final ticket = await repository.requestUploadTicket(
      scope,
      contentType: 'image/jpeg',
    );
    return uploader.put(ticket, bytes: bytes);
  }

  Future<XFile?> _pick(MediaSource source) {
    return picker.pickImage(
      source: source == MediaSource.camera
          ? ImageSource.camera
          : ImageSource.gallery,
      // 原生层降采样（不占主 isolate）：相机原图常 4000×3000/十几 MB，会让后续纯 Dart
      // 解码/压缩同步卡死主线程、预览迟迟不出。先压到 ≤2048px + 质量 85 再交给 process 剥 EXIF。
      maxWidth: 2048,
      maxHeight: 2048,
      imageQuality: 85,
    );
  }
}

final Provider<MediaUploadUseCase> mediaUploadUseCaseProvider =
    Provider<MediaUploadUseCase>(
      (ref) =>
          MediaUploadUseCase(repository: ref.read(mediaRepositoryProvider)),
    );
