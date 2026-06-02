import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../media/domain/media_upload_use_case.dart';
import 'triage_result_controller.dart';
import 'triage_upload_state.dart';

/// 分诊上传上限（FR-1 媒体规格：≤3 图）。
const int kTriageMaxImages = 3;

/// 分诊上传草稿控制器（Story 4.3 · F2/F4）。管理选图（缩略图字节 + 上传后对象 key）+ 症状文字，
/// 并编排「上传私密桶 → 提交分诊」。重新提交复用已上传对象 key（不重传图，NFR-10）。
class TriageUploadController extends Notifier<TriageUploadDraft> {
  @override
  TriageUploadDraft build() => const TriageUploadDraft();

  /// 加入一张已处理（压缩 + 剥 EXIF）的图字节。超过上限忽略（UI 已隐藏加号）。
  bool addImage(Uint8List bytes) {
    if (state.images.length >= kTriageMaxImages) {
      return false;
    }
    state = state.copyWith(
        images: <TriageDraftImage>[...state.images, TriageDraftImage(bytes: bytes)]);
    return true;
  }

  void removeImageAt(int index) {
    if (index < 0 || index >= state.images.length) {
      return;
    }
    final next = <TriageDraftImage>[...state.images]..removeAt(index);
    state = state.copyWith(images: next);
  }

  void setSymptom(String text) {
    state = state.copyWith(symptomText: text);
  }

  /// 退出上传页清空（NFR-10 无持久草稿）。
  void reset() {
    state = const TriageUploadDraft();
  }

  /// 上传未上传的图 → 提交分诊并短轮询。已上传的图复用对象 key（重试不重传）。
  ///
  /// 上传失败抛出原异常交页面 toast；提交后由 [triageResultProvider] 驱动等待/降级态。
  Future<void> submit() async {
    final useCase = ref.read(mediaUploadUseCaseProvider);
    final keys = <String>[];
    final updated = <TriageDraftImage>[];
    for (final img in state.images) {
      var key = img.objectKey;
      if (key == null) {
        final result = await useCase.uploadBytes(scope: MediaScope.private, bytes: img.bytes);
        key = result.objectKey;
      }
      keys.add(key);
      updated.add(img.copyWith(objectKey: key));
    }
    // 持久化对象 key 到草稿，供失败重提交复用（不重传图）。
    state = state.copyWith(images: updated);

    final symptom = state.symptomText.trim();
    // 每次提交用新 Idempotency-Key（重试触发新任务，与 4.1 幂等不冲突）。
    final idempotencyKey = 'triage-${DateTime.now().microsecondsSinceEpoch}';
    await ref.read(triageResultProvider.notifier).submitAndPoll(
          symptomText: symptom.isEmpty ? null : symptom,
          imageObjectKeys: keys,
          idempotencyKey: idempotencyKey,
        );
  }
}

final NotifierProvider<TriageUploadController, TriageUploadDraft> triageUploadProvider =
    NotifierProvider<TriageUploadController, TriageUploadDraft>(TriageUploadController.new);
