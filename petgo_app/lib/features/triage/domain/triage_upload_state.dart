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
  });

  final List<TriageDraftImage> images;
  final String symptomText;

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
      );
}
