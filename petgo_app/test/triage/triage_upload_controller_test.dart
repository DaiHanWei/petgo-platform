import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/media/data/media_repository.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/upload_ticket.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_result_controller.dart';
import 'package:tailtopia/features/triage/domain/triage_result_state.dart';
import 'package:tailtopia/features/triage/domain/triage_upload_controller.dart';

class _FakeRepo implements MediaRepository {
  @override
  Future<UploadTicket> requestUploadTicket(MediaScope scope, {String? contentType}) =>
      throw UnimplementedError();
}

class _FakeMediaUseCase extends MediaUploadUseCase {
  _FakeMediaUseCase() : super(repository: _FakeRepo());
  int uploadCalls = 0;

  @override
  Future<OssUploadResult> uploadBytes({required MediaScope scope, required Uint8List bytes}) async {
    uploadCalls++;
    return OssUploadResult(objectKey: 'key-$uploadCalls');
  }
}

class _FakeTriageRepo implements TriageRepository {
  final List<String> submittedKeys = <String>[];

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async {
    submittedKeys
      ..clear()
      ..addAll(imageObjectKeys);
    return 1;
  }

  @override
  Future<TriageResult> pollTriage(int triageId) async =>
      const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.green);

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async =>
      throw UnimplementedError();

  @override
  Future<FreeQuotaView> fetchFreeQuota() async => throw UnimplementedError();
}

ProviderContainer _container(_FakeMediaUseCase media, _FakeTriageRepo triage) {
  final c = ProviderContainer(overrides: [
    mediaUploadUseCaseProvider.overrideWithValue(media),
    triageRepositoryProvider.overrideWithValue(triage),
    triagePollIntervalProvider.overrideWithValue(const Duration(milliseconds: 1)),
    triageTimeoutProvider.overrideWithValue(const Duration(milliseconds: 200)),
  ]);
  addTearDown(c.dispose);
  return c;
}

void main() {
  Uint8List bytes(int n) => Uint8List.fromList(List<int>.filled(n, 1));

  test('addImage 上限 3 张，超出忽略', () {
    final c = _container(_FakeMediaUseCase(), _FakeTriageRepo());
    final ctrl = c.read(triageUploadProvider.notifier);
    expect(ctrl.addImage(bytes(1)), isTrue);
    expect(ctrl.addImage(bytes(2)), isTrue);
    expect(ctrl.addImage(bytes(3)), isTrue);
    expect(ctrl.addImage(bytes(4)), isFalse);
    expect(c.read(triageUploadProvider).images.length, 3);
  });

  test('removeImageAt 删除指定图', () {
    final c = _container(_FakeMediaUseCase(), _FakeTriageRepo());
    final ctrl = c.read(triageUploadProvider.notifier);
    ctrl.addImage(bytes(1));
    ctrl.addImage(bytes(2));
    ctrl.removeImageAt(0);
    expect(c.read(triageUploadProvider).images.length, 1);
  });

  test('canSubmit[AC5]：文字必填、图片选填——仅文字可提交，仅图片不可', () {
    final c = _container(_FakeMediaUseCase(), _FakeTriageRepo());
    final ctrl = c.read(triageUploadProvider.notifier);
    // 空表单不可提交。
    expect(c.read(triageUploadProvider).canSubmit, isFalse);
    // 空白文字不可提交。
    ctrl.setSymptom('  ');
    expect(c.read(triageUploadProvider).canSubmit, isFalse);
    // 仅加图、无文字 → 仍不可提交（图片选填、文字必填）。
    ctrl.addImage(bytes(1));
    ctrl.addImage(bytes(2));
    expect(c.read(triageUploadProvider).canSubmit, isFalse);
    // 仅文字、无图 → 可提交（图片选填）。
    final c2 = _container(_FakeMediaUseCase(), _FakeTriageRepo());
    c2.read(triageUploadProvider.notifier).setSymptom('咳嗽');
    expect(c2.read(triageUploadProvider).canSubmit, isTrue);
  });

  test('submit 上传未上传图并提交；重提交复用对象 key 不重传', () async {
    final media = _FakeMediaUseCase();
    final triage = _FakeTriageRepo();
    final c = _container(media, triage);
    final ctrl = c.read(triageUploadProvider.notifier);
    ctrl.setSymptom('误食巧克力');
    ctrl.addImage(bytes(1));
    ctrl.addImage(bytes(2));

    await ctrl.submit();
    expect(media.uploadCalls, 2); // 两张图各上传一次
    expect(triage.submittedKeys, ['key-1', 'key-2']);
    expect(c.read(triageResultProvider).phase, TriagePhase.done);

    // 重新提交：图已带 objectKey → 不再上传
    await ctrl.submit();
    expect(media.uploadCalls, 2); // 未增加
    expect(triage.submittedKeys, ['key-1', 'key-2']);
  });

  test('reset 清空草稿（无持久草稿）', () {
    final c = _container(_FakeMediaUseCase(), _FakeTriageRepo());
    final ctrl = c.read(triageUploadProvider.notifier);
    ctrl.setSymptom('x');
    ctrl.addImage(bytes(1));
    ctrl.reset();
    expect(c.read(triageUploadProvider).images, isEmpty);
    expect(c.read(triageUploadProvider).symptomText, '');
  });
}
