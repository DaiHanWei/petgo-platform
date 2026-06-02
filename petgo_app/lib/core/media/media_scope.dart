/// 媒体隐私域（与后端 `MediaScope` 对齐）。决定 STS 直传的目标桶与对象前缀。
///
/// - [MediaScope.public]：公开桶（Feed / 档案 / H5 名片图），经 CDN 分发。
/// - [MediaScope.private]：私密桶（AI 分诊图 / 健康历史图），仅短 TTL 签名 URL 访问。
enum MediaScope {
  public('PUBLIC'),
  private('PRIVATE');

  const MediaScope(this.wire);

  /// 后端枚举线格式（UPPER_SNAKE）。
  final String wire;
}
