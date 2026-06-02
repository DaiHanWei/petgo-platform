/// STS 上传凭证（后端 `StsCredentialResponse` 的客户端不可变模型）。
///
/// 客户端用此凭证直传 OSS（不经后端）。[cdnBaseUrl] 仅公开桶有值；私密桶上传后
/// 只返回对象 key 供后端签名访问，绝不拼公开 URL。
class StsCredential {
  const StsCredential({
    required this.accessKeyId,
    required this.accessKeySecret,
    required this.securityToken,
    required this.expiration,
    required this.bucket,
    required this.region,
    required this.endpoint,
    required this.uploadDir,
    this.cdnBaseUrl,
  });

  final String accessKeyId;
  final String accessKeySecret;
  final String securityToken;
  final String expiration;
  final String bucket;
  final String region;
  final String endpoint;

  /// 允许写入的对象前缀（如 `public/42/`）。客户端只能 Put 到此前缀下。
  final String uploadDir;

  /// 公开桶 CDN base（私密桶为 null）。
  final String? cdnBaseUrl;

  factory StsCredential.fromJson(Map<String, dynamic> json) => StsCredential(
        accessKeyId: json['accessKeyId'] as String,
        accessKeySecret: json['accessKeySecret'] as String,
        securityToken: json['securityToken'] as String,
        expiration: json['expiration'] as String? ?? '',
        bucket: json['bucket'] as String,
        region: json['region'] as String? ?? '',
        endpoint: json['endpoint'] as String,
        uploadDir: json['uploadDir'] as String,
        cdnBaseUrl: json['cdnBaseUrl'] as String?,
      );
}
