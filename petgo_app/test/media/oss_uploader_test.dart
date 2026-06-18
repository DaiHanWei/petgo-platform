import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/upload_ticket.dart';

/// 捕获 PUT 请求并回 200 的假适配器，断言 OssUploader 发出的 URL/头。
class _CapturingAdapter implements HttpClientAdapter {
  RequestOptions? captured;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured = options;
    // 需消费 requestStream，否则 dio 卡住。
    if (requestStream != null) {
      await requestStream.drain<void>();
    }
    return ResponseBody.fromString('', 200);
  }
}

void main() {
  group('UploadTicket.fromJson', () {
    test('解析公开/私密票据', () {
      final pub = UploadTicket.fromJson({
        'uploadUrl': 'https://x/put?sig',
        'objectKey': 'public/42/a.jpg',
        'method': 'PUT',
        'headers': {'Content-Type': 'image/jpeg', 'x-oss-object-acl': 'public-read'},
        'publicUrl': 'https://cdn/public/42/a.jpg',
      });
      expect(pub.headers['x-oss-object-acl'], 'public-read');
      expect(pub.publicUrl, 'https://cdn/public/42/a.jpg');

      final priv = UploadTicket.fromJson({
        'uploadUrl': 'https://x/put?sig',
        'objectKey': 'private/7/b.jpg',
        'method': 'PUT',
        'headers': {'Content-Type': 'image/jpeg'},
      });
      expect(priv.publicUrl, isNull);
      expect(priv.headers.containsKey('x-oss-object-acl'), isFalse);
      expect(priv.method, 'PUT');
    });
  });

  group('OssUploader.put', () {
    test('PUT 到预签名 URL，原样带票据头 + Content-Length，返回 objectKey/publicUrl', () async {
      final adapter = _CapturingAdapter();
      final dio = Dio()..httpClientAdapter = adapter;
      final uploader = OssUploader(dio: dio);

      const ticket = UploadTicket(
        uploadUrl: 'https://tailtopia.oss/public/42/a.jpg?OSSAccessKeyId=k&Expires=1&Signature=s',
        objectKey: 'public/42/a.jpg',
        method: 'PUT',
        headers: {'Content-Type': 'image/jpeg', 'x-oss-object-acl': 'public-read'},
        publicUrl: 'https://cdn/public/42/a.jpg',
      );

      final result = await uploader.put(ticket, bytes: Uint8List.fromList([1, 2, 3]));

      expect(adapter.captured!.method, 'PUT');
      expect(adapter.captured!.uri.toString(), ticket.uploadUrl);
      // 票据头原样回带（OSS 预签名要求 Content-Type/ACL 与签名一致）。
      expect(adapter.captured!.headers['Content-Type'], 'image/jpeg');
      expect(adapter.captured!.headers['x-oss-object-acl'], 'public-read');
      expect(adapter.captured!.headers['Content-Length'], 3);
      expect(result.objectKey, 'public/42/a.jpg');
      expect(result.publicUrl, 'https://cdn/public/42/a.jpg');
    });

    test('私密票据无 ACL 头、publicUrl 为 null', () async {
      final adapter = _CapturingAdapter();
      final uploader = OssUploader(dio: Dio()..httpClientAdapter = adapter);
      const ticket = UploadTicket(
        uploadUrl: 'https://tailtopia.oss/private/7/b.jpg?sig',
        objectKey: 'private/7/b.jpg',
        method: 'PUT',
        headers: {'Content-Type': 'image/jpeg'},
      );

      final result = await uploader.put(ticket, bytes: Uint8List.fromList([9]));

      expect(adapter.captured!.headers.containsKey('x-oss-object-acl'), isFalse);
      expect(result.publicUrl, isNull);
    });
  });
}
