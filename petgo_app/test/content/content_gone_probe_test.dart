import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';

/// 2026-10-13 决定（3.4 复审 F5）：通知中心的「帖子已删」探测带 countView=false，不计浏览。
class _Adapter implements HttpClientAdapter {
  _Adapter(this.status);
  final int status;
  final List<RequestOptions> seen = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    seen.add(options);
    return ResponseBody.fromString(jsonEncode({'id': 1}), status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  Future<(bool, _Adapter)> probe(int status) async {
    final adapter = _Adapter(status);
    final dio = Dio(BaseOptions(baseUrl: 'http://x'))..httpClientAdapter = adapter;
    return (await ContentGoneProbe(dio).isGone(77), adapter);
  }

  test('请求带 countView=false（不计浏览）', () async {
    final (_, adapter) = await probe(200);
    expect(adapter.seen.single.uri.queryParameters['countView'], 'false');
    expect(adapter.seen.single.uri.path, endsWith('/content-posts/77'));
  });

  test('404 → 已删；200 / 403 / 500 → 不算已删', () async {
    expect((await probe(404)).$1, isTrue);
    expect((await probe(200)).$1, isFalse);
    expect((await probe(403)).$1, isFalse);
    expect((await probe(500)).$1, isFalse);
  });
}
