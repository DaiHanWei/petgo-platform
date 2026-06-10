import 'dart:io';
import 'dart:typed_data';

import '../../features/media/data/media_repository.dart';
import '../../features/media/data/oss_uploader.dart';
import '../../features/media/data/sts_credential.dart';
import '../../features/media/domain/media_upload_use_case.dart';

/// Mock 上传器：跳过真实 OSS 直传(唯一不走 dio 的网络路径)。
///
/// 把刚选/拍的图字节落到临时目录并返回 `file:<path>` —— 配合 `AppImage` 解析，发布后 Feed/详情
/// 能即时显示**用户实际选的相册/相机图**（而非占位）。落盘失败兜底回占位 URL，不阻断发布。
class _MockOssUploader extends OssUploader {
  @override
  Future<OssUploadResult> put(
    StsCredential cred, {
    required String objectKey,
    required Uint8List bytes,
    required String contentType,
  }) async {
    try {
      final safe = objectKey.replaceAll('/', '_');
      final file = File('${Directory.systemTemp.path}/mockpub_$safe');
      await file.writeAsBytes(bytes, flush: true);
      return OssUploadResult(objectKey: objectKey, publicUrl: 'file:${file.path}');
    } catch (_) {
      return OssUploadResult(objectKey: objectKey, publicUrl: 'https://mock.example/$objectKey');
    }
  }
}

/// Mock 模式下覆盖上传用例:STS 仍经 dio mock 拦截器,直传换成占位结果。
final mediaUploadUseCaseMockOverride =
    mediaUploadUseCaseProvider.overrideWith(
  (ref) => MediaUploadUseCase(
    repository: ref.read(mediaRepositoryProvider),
    uploader: _MockOssUploader(),
  ),
);
