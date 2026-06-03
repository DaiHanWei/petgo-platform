import 'dart:typed_data';

import '../../features/media/data/media_repository.dart';
import '../../features/media/data/oss_uploader.dart';
import '../../features/media/data/sts_credential.dart';
import '../../features/media/domain/media_upload_use_case.dart';

/// Mock 上传器：跳过真实 OSS 直传(唯一不走 dio 的网络路径),直接返回占位 URL。
class _MockOssUploader extends OssUploader {
  @override
  Future<OssUploadResult> put(
    StsCredential cred, {
    required String objectKey,
    required Uint8List bytes,
    required String contentType,
  }) async {
    return OssUploadResult(objectKey: objectKey, publicUrl: 'https://mock.example/$objectKey');
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
