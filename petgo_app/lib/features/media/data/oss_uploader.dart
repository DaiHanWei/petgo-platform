import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';

import 'sts_credential.dart';

/// 直传结果。公开桶返回 [publicUrl]（CDN）；私密桶 [publicUrl] 为 null，仅 [objectKey] 供后端签名。
class OssUploadResult {
  const OssUploadResult({required this.objectKey, this.publicUrl});

  final String objectKey;
  final String? publicUrl;
}

/// OSS 客户端直传（Story 2.1 · F2）：用 STS 临时凭证 PUT 到 OSS，**不经后端**。
///
/// 采用 OSS V1 签名（HMAC-SHA1）+ `x-oss-security-token`。签名串构造为纯函数，L0 可单测；
/// 真正的网络 PUT 留 L2（真实雅加达桶）。
class OssUploader {
  OssUploader({Dio? dio}) : _dio = dio ?? Dio();

  final Dio _dio;
  static final Random _rng = Random.secure();

  /// 在凭证允许的前缀下生成不可枚举对象 key（前缀 + 随机 token + 扩展名）。
  static String buildObjectKey(String uploadDir, {required String extension}) {
    final dir = uploadDir.endsWith('/') ? uploadDir : '$uploadDir/';
    final ext = extension.startsWith('.') ? extension.substring(1) : extension;
    final token = List<int>.generate(16, (_) => _rng.nextInt(256));
    final name = base64Url.encode(token).replaceAll('=', '');
    return '$dir$name.$ext';
  }

  /// OSS V1 StringToSign（PUT）。纯函数，便于单测。
  static String stringToSign({
    required String verb,
    required String contentType,
    required String date,
    required String securityToken,
    required String bucket,
    required String objectKey,
  }) {
    const contentMd5 = '';
    // CanonicalizedOSSHeaders：仅 x-oss-security-token，小写 + 末尾换行。
    final canonicalizedHeaders = 'x-oss-security-token:$securityToken\n';
    final canonicalizedResource = '/$bucket/$objectKey';
    return '$verb\n$contentMd5\n$contentType\n$date\n$canonicalizedHeaders$canonicalizedResource';
  }

  /// Authorization 头：`OSS <ak>:<base64(hmacSha1(sk, stringToSign))>`。纯函数。
  static String authorization({
    required String accessKeyId,
    required String accessKeySecret,
    required String stringToSign,
  }) {
    final mac = Hmac(sha1, utf8.encode(accessKeySecret));
    final sig = base64.encode(mac.convert(utf8.encode(stringToSign)).bytes);
    return 'OSS $accessKeyId:$sig';
  }

  /// 虚拟主机式直传 URL：`https://<bucket>.<host>/<objectKey>`。
  static String uploadUrl(StsCredential cred, String objectKey) {
    final uri = Uri.parse(cred.endpoint);
    return '${uri.scheme}://${cred.bucket}.${uri.host}/$objectKey';
  }

  /// PUT 对象到 OSS（L2 真实网络）。返回落桶 key + （公开桶）CDN URL。
  Future<OssUploadResult> put(
    StsCredential cred, {
    required String objectKey,
    required Uint8List bytes,
    required String contentType,
  }) async {
    final date = _httpDate(DateTime.now().toUtc());
    final sts = stringToSign(
      verb: 'PUT',
      contentType: contentType,
      date: date,
      securityToken: cred.securityToken,
      bucket: cred.bucket,
      objectKey: objectKey,
    );
    final auth = authorization(
      accessKeyId: cred.accessKeyId,
      accessKeySecret: cred.accessKeySecret,
      stringToSign: sts,
    );

    await _dio.put<void>(
      uploadUrl(cred, objectKey),
      data: Stream.fromIterable([bytes]),
      options: Options(
        headers: <String, dynamic>{
          'Content-Type': contentType,
          'Content-Length': bytes.length,
          'Date': date,
          'x-oss-security-token': cred.securityToken,
          'Authorization': auth,
        },
      ),
    );

    final base = cred.cdnBaseUrl;
    return OssUploadResult(
      objectKey: objectKey,
      publicUrl: base == null ? null : '$base/$objectKey',
    );
  }

  /// RFC 1123 GMT 日期（OSS Date 头要求）。
  static String _httpDate(DateTime utc) {
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const months = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', //
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
    ];
    String two(int n) => n.toString().padLeft(2, '0');
    final wd = days[utc.weekday - 1];
    final mon = months[utc.month - 1];
    return '$wd, ${two(utc.day)} $mon ${utc.year} '
        '${two(utc.hour)}:${two(utc.minute)}:${two(utc.second)} GMT';
  }
}
