import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/auth_interceptor.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/auth/data/auth_repository.dart' show AuthExtraKeys;

/// 可编程的假 HttpClientAdapter：按「路径 + 第几次调用」返回状态码。
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.statusFor);

  /// (path, callIndexForThatPath) → statusCode
  final int Function(String path, int call) statusFor;
  final Map<String, int> _calls = {};

  int callCount(String path) => _calls[path] ?? 0;

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final n = (_calls[options.path] ?? 0) + 1;
    _calls[options.path] = n;
    final status = statusFor(options.path, n);
    return ResponseBody.fromString(
      '{"ok":${status >= 200 && status < 300}}',
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Dio _dioWith(
  _FakeAdapter adapter,
  TokenStore store, {
  required Future<bool> Function() refresh,
  required void Function() onExpired,
}) {
  final dio = Dio(BaseOptions(baseUrl: 'http://test.local'));
  dio.httpClientAdapter = adapter;
  dio.interceptors.add(AuthInterceptor(
    dio: dio,
    tokenStore: store,
    refresh: refresh,
    onSessionExpired: onExpired,
    localeCode: () => 'en',
  ));
  return dio;
}

void main() {
  test('AC3: 401 → refresh 续期一次 → 重放成功', () async {
    final store = InMemoryTokenStore();
    await store.saveTokens(access: 'old', refresh: 'r');
    // /protected 第 1 次 401，重放（第 2 次）200。
    final adapter = _FakeAdapter((path, call) => path == '/protected' && call == 1 ? 401 : 200);
    var refreshCount = 0;
    final dio = _dioWith(adapter, store,
        refresh: () async {
          refreshCount++;
          await store.saveTokens(access: 'new', refresh: 'r2');
          return true;
        },
        onExpired: () {});

    final resp = await dio.get<dynamic>('/protected');

    expect(resp.statusCode, 200);
    expect(refreshCount, 1); // 只续期一次
    expect(adapter.callCount('/protected'), 2); // 原请求 + 重放
  });

  test('AC3: 重放仍 401 → 只续期一次，不死循环', () async {
    final store = InMemoryTokenStore();
    await store.saveTokens(access: 'old', refresh: 'r');
    final adapter = _FakeAdapter((path, call) => 401); // 始终 401
    var refreshCount = 0;
    final dio = _dioWith(adapter, store,
        refresh: () async {
          refreshCount++;
          return true;
        },
        onExpired: () {});

    await expectLater(dio.get<dynamic>('/protected'), throwsA(isA<DioException>()));
    expect(refreshCount, 1); // 仅一次（retried 标志防再次刷新）
  });

  test('AC3: refresh 失败 → 落游客态 + 清 token', () async {
    final store = InMemoryTokenStore();
    await store.saveTokens(access: 'old', refresh: 'r');
    final adapter = _FakeAdapter((path, call) => 401);
    var expired = false;
    final dio = _dioWith(adapter, store,
        refresh: () async => false,
        onExpired: () => expired = true);

    await expectLater(dio.get<dynamic>('/protected'), throwsA(isA<DioException>()));
    expect(expired, true);
    expect(await store.readAccess(), isNull);
  });

  test('注入 Authorization 与 Accept-Language', () async {
    final store = InMemoryTokenStore();
    await store.saveTokens(access: 'tok', refresh: 'r');
    RequestOptions? seen;
    final adapter = _FakeAdapter((path, call) => 200);
    final dio = Dio(BaseOptions(baseUrl: 'http://test.local'));
    dio.httpClientAdapter = adapter;
    dio.interceptors.add(AuthInterceptor(
      dio: dio,
      tokenStore: store,
      refresh: () async => true,
      onSessionExpired: () {},
      localeCode: () => 'id',
    ));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
      seen = o;
      h.next(o);
    }));

    await dio.get<dynamic>('/x');

    expect(seen?.headers['Authorization'], 'Bearer tok');
    expect(seen?.headers['Accept-Language'], 'id');
  });

  test('auth-self 请求(skipRefresh)不附带 access token（修复会话 15min 掉线 bug）', () async {
    // 回归：refresh/logout 打 permitAll 端点，若带上已过期的 access token，
    // 后端鉴权过滤器会在端点逻辑前 401 → refresh 永远失败 → access 过期即掉游客。
    final store = InMemoryTokenStore();
    await store.saveTokens(access: 'expired-access', refresh: 'r');
    RequestOptions? seen;
    final adapter = _FakeAdapter((path, call) => 200);
    final dio = Dio(BaseOptions(baseUrl: 'http://test.local'));
    dio.httpClientAdapter = adapter;
    dio.interceptors.add(AuthInterceptor(
      dio: dio,
      tokenStore: store,
      refresh: () async => true,
      onSessionExpired: () {},
      localeCode: () => 'en',
    ));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
      seen = o;
      h.next(o);
    }));

    await dio.post<dynamic>('/api/v1/auth/refresh',
        data: {'refreshToken': 'r'},
        options: Options(extra: {AuthExtraKeys.skipRefresh: true}));

    expect(seen?.headers.containsKey('Authorization'), false,
        reason: 'auth-self 请求不应带 access token，否则后端过期 Bearer→401');
    expect(seen?.headers['Accept-Language'], 'en'); // 语言头仍注入
  });
}
