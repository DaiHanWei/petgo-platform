import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';

/// 假 HttpClientAdapter：返回给定 JSON 体与状态码，或直接抛错。
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter({this.body = '{}', this.status = 200, this.throwError = false});

  final String body;
  final int status;
  final bool throwError;

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (throwError) {
      throw DioException.connectionError(requestOptions: options, reason: 'offline');
    }
    return ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

AppVersionRepository _repo(_FakeAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  return AppVersionRepository(dio: dio);
}

/// V1.3.4 Story 3.2 · AC2：接口模型换字段、任何异常一律放行（null）。
void main() {
  test('正常 5 字段解析 + 按平台取值', () async {
    final info = await _repo(_FakeAdapter(body: '''
{"iosLatestVersion":"1.3.3","androidLatestVersion":"1.3.5","minSupportedVersion":"1.0.0",
 "iosStoreUrl":"https://apps.apple.com/app/id1","androidStoreUrl":"https://play.google.com/x"}
''')).fetch();
    expect(info, isNotNull);
    expect(info!.iosLatestVersion, '1.3.3');
    expect(info.androidLatestVersion, '1.3.5');
    expect(info.minSupportedVersion, '1.0.0');
    expect(info.latestFor(isIos: true), '1.3.3');
    expect(info.latestFor(isIos: false), '1.3.5');
    expect(info.storeUrlFor(isIos: true), 'https://apps.apple.com/app/id1');
    expect(info.storeUrlFor(isIos: false), 'https://play.google.com/x');
  });

  test('缺 androidLatestVersion → null（不再用 1.0.0 兜底）', () async {
    final info = await _repo(_FakeAdapter(
            body: '{"iosLatestVersion":"1.3.3","minSupportedVersion":"1.0.0"}'))
        .fetch();
    expect(info, isNull);
  });

  test('版本字段为空串 / 非字符串 → null', () async {
    expect(
        await _repo(_FakeAdapter(
                body: '{"iosLatestVersion":"","androidLatestVersion":"1.3.3",'
                    '"minSupportedVersion":"1.0.0"}'))
            .fetch(),
        isNull);
    expect(
        await _repo(_FakeAdapter(
                body: '{"iosLatestVersion":"1.3.3","androidLatestVersion":"1.3.3",'
                    '"minSupportedVersion":100}'))
            .fetch(),
        isNull);
  });

  test('iosStoreUrl 为空串 → storeUrlFor(isIos: true) 为 null', () async {
    final info = await _repo(_FakeAdapter(
            body: '{"iosLatestVersion":"1.3.3","androidLatestVersion":"1.3.3",'
                '"minSupportedVersion":"1.0.0","iosStoreUrl":"","androidStoreUrl":null}'))
        .fetch();
    expect(info, isNotNull);
    expect(info!.storeUrlFor(isIos: true), isNull);
    expect(info.storeUrlFor(isIos: false), isNull);
  });

  test('Dio 抛错 → null', () async {
    expect(await _repo(_FakeAdapter(throwError: true)).fetch(), isNull);
  });

  test('503（配置缺失）→ null', () async {
    expect(
        await _repo(_FakeAdapter(body: '{"status":503}', status: 503)).fetch(), isNull);
  });
}
