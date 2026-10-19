import 'dart:typed_data';

/// 分诊上传草稿单张图（Story 4.3）。[bytes] 供缩略图显示；[objectKey] 为 STS 直传私密桶后的对象 key
/// （上传成功后填充；重新提交时复用，不重传）。
class TriageDraftImage {
  const TriageDraftImage({required this.bytes, this.objectKey});

  final Uint8List bytes;
  final String? objectKey;

  bool get uploaded => objectKey != null;

  TriageDraftImage copyWith({String? objectKey}) =>
      TriageDraftImage(bytes: bytes, objectKey: objectKey ?? this.objectKey);
}

/// 分诊上传草稿（Story 4.3）。仅 session 内存保留，退出上传页清空（NFR-10 无持久草稿）。
class TriageUploadDraft {
  const TriageUploadDraft({
    this.images = const <TriageDraftImage>[],
    this.symptomText = '',
    this.petId,
  });

  final List<TriageDraftImage> images;
  final String symptomText;

  /// 所选宠物（V1.4.0 multi-pet Story 4.1 · H1）：提交时作 `petId`（AI 用其物种 / 年龄 / 体重，P-1），
  /// 结果页存档也存到这只。null = 不带（0 只宠物 / 旧路径）。
  final int? petId;

  /// 提交可用（Story 4.3 AC5 · R2）：**文字必填、图片选填**——仅以「症状文字非空」为准，
  /// 不再 require 图片（可仅凭文字提交）；图片仍受 ≤3 张 / ≤10MB 上限约束。后端 `@NotBlank` 为权威。
  bool get canSubmit => symptomText.trim().isNotEmpty;

  TriageUploadDraft copyWith({
    List<TriageDraftImage>? images,
    String? symptomText,
  }) =>
      TriageUploadDraft(
        images: images ?? this.images,
        symptomText: symptomText ?? this.symptomText,
        petId: petId,
      );

  /// 换所选宠物（可置 null，故不走 [copyWith]）。
  TriageUploadDraft withPetId(int? id) =>
      TriageUploadDraft(images: images, symptomText: symptomText, petId: id);
}
