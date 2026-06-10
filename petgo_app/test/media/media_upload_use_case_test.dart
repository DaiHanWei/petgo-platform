import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/media/data/media_repository.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/sts_credential.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/shared/utils/media_permission.dart';

class _FakeGateway implements PermissionGateway {
  _FakeGateway(this.outcome);
  final MediaPermissionOutcome outcome;
  int openSettingsCalls = 0;

  @override
  Future<MediaPermissionOutcome> request(MediaSource source) async => outcome;

  @override
  Future<bool> openSettings() async {
    openSettingsCalls++;
    return true;
  }
}

class _FakeRepo implements MediaRepository {
  MediaScope? lastScope;

  @override
  Future<StsCredential> requestStsCredential(MediaScope scope, {String? contentType, int count = 1}) async {
    lastScope = scope;
    return const StsCredential(
      accessKeyId: 'ak',
      accessKeySecret: 'sk',
      securityToken: 'st',
      expiration: '',
      bucket: 'petgo-public',
      region: 'ap-southeast-5',
      endpoint: 'https://oss-ap-southeast-5.aliyuncs.com',
      uploadDir: 'public/42/',
      cdnBaseUrl: 'https://cdn.petgo.example',
    );
  }
}

class _FakeUploader extends OssUploader {
  String? putKey;

  @override
  Future<OssUploadResult> put(
    StsCredential cred, {
    required String objectKey,
    required Uint8List bytes,
    required String contentType,
  }) async {
    putKey = objectKey;
    return OssUploadResult(objectKey: objectKey, publicUrl: '${cred.cdnBaseUrl}/$objectKey');
  }
}

void main() {
  test('权限被拒（无 context）→ 返回 null，不上传', () async {
    final repo = _FakeRepo();
    final useCase = MediaUploadUseCase(
      repository: repo,
      permissionGateway: _FakeGateway(MediaPermissionOutcome.permanentlyDenied),
      uploader: _FakeUploader(),
    );
    final result = await useCase.pickAndUploadOne(
      scope: MediaScope.public,
      source: MediaSource.gallery,
    );
    expect(result, isNull);
    expect(repo.lastScope, isNull); // 未请求 STS
  });

  test('uploadBytes：请求 STS → 直传 → 返回 key 落在前缀下 + 公开 URL', () async {
    final repo = _FakeRepo();
    final uploader = _FakeUploader();
    final useCase = MediaUploadUseCase(repository: repo, uploader: uploader);

    final result = await useCase.uploadBytes(
      scope: MediaScope.public,
      bytes: Uint8List.fromList([1, 2, 3]),
    );

    expect(repo.lastScope, MediaScope.public);
    expect(result.objectKey, startsWith('public/42/'));
    expect(result.publicUrl, startsWith('https://cdn.petgo.example/public/42/'));
    expect(uploader.putKey, result.objectKey);
  });
}
