import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'upload_ticket.dart';

/// 直传结果。公开域返回 [publicUrl]（公网 URL）；私密域 [publicUrl] 为 null，仅 [objectKey] 供后端签名。
class OssUploadResult {
  const OssUploadResult({required this.objectKey, this.publicUrl});

  final String objectKey;
  final String? publicUrl;
}

/// OSS 客户端直传（Story 2.1 · F2）：用后端签发的**预签名 URL** PUT 字节，**真 key 不经手机**。
///
/// 无需客户端签名——票据里的 [UploadTicket.uploadUrl] 已含签名，[UploadTicket.headers] 是必须
/// 原样回带的头（Content-Type 已签入；公开域含 `x-oss-object-acl: public-read`）。
class OssUploader {
  OssUploader({Dio? dio}) : _dio = dio ?? Dio();

  final Dio _dio;

  /// 按票据 PUT 对象到 OSS（L2 真实网络）。返回落桶 key +（公开域）公网 URL。
  Future<OssUploadResult> put(
    UploadTicket ticket, {
    required Uint8List bytes,
  }) async {
    // 原样回带后端签入的头，并补 Content-Length（OSS PUT 要求）。
    final headers = <String, dynamic>{
      ...ticket.headers,
      'Content-Length': bytes.length,
    };

    await _dio.put<void>(
      ticket.uploadUrl,
      data: Stream<List<int>>.fromIterable([bytes]),
      options: Options(headers: headers),
    );

    return OssUploadResult(objectKey: ticket.objectKey, publicUrl: ticket.publicUrl);
  }
}
