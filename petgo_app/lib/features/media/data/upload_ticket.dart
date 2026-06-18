/// 预签名上传票据（后端 `UploadUrlResponse` 的客户端不可变模型）。
///
/// 客户端用 [method]（PUT）把字节发到 [uploadUrl]，并**原样带上 [headers]**（至少含签入的
/// `Content-Type`；公开域还含 `x-oss-object-acl: public-read`）。真 AccessKey 始终只在后端，
/// 客户端只拿到这张限定对象 + 限定头 + 短 TTL 的票据。[publicUrl] 仅公开域有值；私密域为 null，
/// 上传后只返回对象 key 供后端签名访问，绝不拼公开 URL。
class UploadTicket {
  const UploadTicket({
    required this.uploadUrl,
    required this.objectKey,
    required this.method,
    required this.headers,
    this.publicUrl,
  });

  final String uploadUrl;
  final String objectKey;
  final String method;

  /// 客户端 PUT 必须原样携带的请求头（漏发/改动 → OSS SignatureDoesNotMatch）。
  final Map<String, String> headers;

  /// 公开域对外 URL（私密域为 null）。
  final String? publicUrl;

  factory UploadTicket.fromJson(Map<String, dynamic> json) => UploadTicket(
        uploadUrl: json['uploadUrl'] as String,
        objectKey: json['objectKey'] as String,
        method: json['method'] as String? ?? 'PUT',
        headers: (json['headers'] as Map?)
                ?.map((k, v) => MapEntry(k as String, '$v')) ??
            const <String, String>{},
        publicUrl: json['publicUrl'] as String?,
      );
}
