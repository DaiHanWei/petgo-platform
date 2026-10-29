import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/client_info_interceptor.dart';

/// 不发网络：拦截器链末尾直接 resolve，回传请求头供断言。
Dio _dio(ClientInfoInterceptor interceptor) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.test'));
  dio.interceptors
    ..add(interceptor)
    ..add(InterceptorsWrapper(
      onRequest: (o, h) => h.resolve(Response(requestOptions: o, statusCode: 200, data: o.headers)),
    ));
  return dio;
}

void main() {
  test('每个请求带 X-App-Version 与 X-App-Platform', () async {
    final dio = _dio(ClientInfoInterceptor(loadVersion: () async => '1.3.4', platform: 'android'));
    final headers = (await dio.get<Map<String, dynamic>>('/x')).data!;
    expect(headers[ClientInfoInterceptor.versionHeader], '1.3.4');
    expect(headers[ClientInfoInterceptor.platformHeader], 'android');
  });

  test('包版本只读一次', () async {
    var calls = 0;
    final dio = _dio(ClientInfoInterceptor(loadVersion: () async {
      calls++;
      return '1.3.4';
    }, platform: 'ios'));
    await dio.get<dynamic>('/a');
    await dio.get<dynamic>('/b');
    expect(calls, 1);
  });

  test('读包信息失败不阻断请求，只是不带版本头', () async {
    final dio = _dio(ClientInfoInterceptor(loadVersion: () async => throw StateError('no plugin'), platform: 'ios'));
    final headers = (await dio.get<Map<String, dynamic>>('/x')).data!;
    expect(headers.containsKey(ClientInfoInterceptor.versionHeader), isFalse);
    expect(headers[ClientInfoInterceptor.platformHeader], 'ios');
  });

  test('每个请求声明视频能力 X-App-Capabilities: video（专题线 video Story 2.2）', () async {
    final dio = _dio(ClientInfoInterceptor(loadVersion: () async => null, platform: null));
    final headers = (await dio.get<Map<String, dynamic>>('/x')).data!;
    expect(headers[ClientInfoInterceptor.capabilitiesHeader], 'video');
  });
}
