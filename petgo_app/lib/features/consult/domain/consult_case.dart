/// 用户自己提交的病例（会话页顶部摘要条展开用）。
///
/// 对应后端 `GET /api/v1/consult-sessions/{id}/case`（复用 `ConsultAiContextResponse`）。
/// 图片为私密桶短 TTL 签名 URL；DIRECT 无病例时 [hasCase]=false。
class ConsultCase {
  const ConsultCase({
    required this.hasCase,
    this.symptomText,
    this.imageUrls = const [],
  });

  final bool hasCase;
  final String? symptomText;
  final List<String> imageUrls;

  bool get isEmpty =>
      !hasCase || ((symptomText == null || symptomText!.trim().isEmpty) && imageUrls.isEmpty);

  factory ConsultCase.fromJson(Map<String, dynamic> json) => ConsultCase(
        hasCase: (json['hasAiContext'] ?? false) as bool,
        symptomText: json['symptomText'] as String?,
        imageUrls: (json['imageUrls'] as List?)?.cast<String>() ?? const [],
      );
}
