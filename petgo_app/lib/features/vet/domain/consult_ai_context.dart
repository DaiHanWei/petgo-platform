/// 会话 AI 上下文（对应后端 `ConsultAiContextResponse`，Story 5.4）。
///
/// DIRECT 会话 `hasAiContext=false`，前端不渲染上下文卡。图片为私密桶短 TTL 签名 URL。
class ConsultAiContext {
  const ConsultAiContext({
    required this.hasAiContext,
    this.dangerLevel,
    this.symptomText,
    this.imageUrls = const [],
  });

  final bool hasAiContext;
  final String? dangerLevel; // GREEN | YELLOW
  final String? symptomText;
  final List<String> imageUrls;

  factory ConsultAiContext.fromJson(Map<String, dynamic> json) => ConsultAiContext(
        hasAiContext: (json['hasAiContext'] ?? false) as bool,
        dangerLevel: json['dangerLevel'] as String?,
        symptomText: json['symptomText'] as String?,
        imageUrls: (json['imageUrls'] as List?)?.cast<String>() ?? const [],
      );
}
