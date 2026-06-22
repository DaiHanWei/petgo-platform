import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/media/data/media_repository.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/upload_ticket.dart';
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
  Future<UploadTicket> requestUploadTicket(MediaScope scope, {String? contentType}) async {
    lastScope = scope;
    return const UploadTicket(
      uploadUrl: 'https://tailtopia.oss/public/42/obj.jpg?sig',
      objectKey: 'public/42/obj.jpg',
      method: 'PUT',
      headers: {'Content-Type': 'image/jpeg', 'x-oss-object-acl': 'public-read'},
      publicUrl: 'https://cdn.petgo.example/public/42/obj.jpg',
    );
  }
}

class _FakeUploader extends OssUploader {
  UploadTicket? putTicket;

  @override
  Future<OssUploadResult> put(UploadTicket ticket, {required Uint8List bytes}) async {
    putTicket = ticket;
    return OssUploadResult(objectKey: ticket.objectKey, publicUrl: ticket.publicUrl);
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
    expect(repo.lastScope, isNull); // 未请求上传票据
  });

  test('uploadBytes：请求预签名票据 → 直传 → 返回服务端 key + 公开 URL', () async {
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
    expect(uploader.putTicket!.objectKey, result.objectKey);
  });
}
