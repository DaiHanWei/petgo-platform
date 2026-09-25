import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_log_interceptor.dart';

/// batch-b1 复审 F7：位置坐标（NFR-4）在请求体里同样要打码 ——
/// `POST /places` 的请求体带设备 GPS，此前只打码了 query string。
class _Handler extends RequestInterceptorHandler {}

void main() {
  test('请求体里的 latitude / longitude / lat / lng 打码', () {
    final lines = <String>[];
    final original = debugPrint;
    debugPrint = (String? m, {int? wrapWidth}) => lines.add(m ?? '');
    addTearDown(() => debugPrint = original);

    ApiLogInterceptor().onRequest(
      RequestOptions(path: '/api/v1/places', method: 'POST', data: {
        'name': 'Kopi',
        'latitude': -6.2351,
        'longitude': 106.8102,
        'nested': {'lat': -6.2, 'lng': 106.8},
      }),
      _Handler(),
    );

    final out = lines.join('\n');
    expect(out, isNot(contains('-6.2351')));
    expect(out, isNot(contains('106.8102')));
    expect(out, isNot(contains('106.8}')));
    expect(out, contains('Kopi'));
  });
}
