import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/auth/data/apple_auth_client.dart';
import 'package:tailtopia/features/auth/data/auth_repository.dart';
import 'package:tailtopia/features/auth/data/google_auth_client.dart';

/// 不参与本套用例的 Google 桩。
class _NoopGoogleClient implements GoogleAuthClient {
  @override
  Future<String?> signInAndGetIdToken() async => null;
  @override
  Future<void> signOut() async {}
}

/// 可编程 Apple 桩：返回固定 token（null = 用户取消）。
class _FakeAppleClient implements AppleAuthClient {
  _FakeAppleClient(this._token);
  final String? _token;
  @override
  Future<String?> signInAndGetIdentityToken() async => _token;
}

/// 假适配器：记录请求路径/体，对 /auth/apple 返回固定 LoginResponse JSON。
class _CaptureAdapter implements HttpClientAdapter {
  String? lastPath;
  Object? lastBody;

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    lastPath = options.path;
    lastBody = options.data;
    return ResponseBody.fromString(
      '{"accessToken":"acc","refreshToken":"ref","role":"USER",'
      '"isNewUser":true,"onboardingCompleted":false}',
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

AuthRepository _repo(_CaptureAdapter adapter, TokenStore store, AppleAuthClient? apple) {
  final dio = Dio(BaseOptions(baseUrl: 'http://test.local'));
  dio.httpClientAdapter = adapter;
  return AuthRepository(
    dio: dio,
    tokenStore: store,
    googleClient: _NoopGoogleClient(),
    appleClient: apple,
  );
}

void main() {
  test('FR-44: loginWithApple 取 identityToken → POST /auth/apple 换 JWT 并落盘', () async {
    final adapter = _CaptureAdapter();
    final store = InMemoryTokenStore();
    final repo = _repo(adapter, store, _FakeAppleClient('apple-identity-token'));

    final resp = await repo.loginWithApple();

    expect(resp.accessToken, 'acc');
    expect(resp.isNewUser, isTrue);
    // 打到 Apple 端点，携带 identityToken 字段（非 Google 的 idToken）。
    expect(adapter.lastPath, contains('/auth/apple'));
    expect((adapter.lastBody as Map)['identityToken'], 'apple-identity-token');
    // 令牌已落盘。
    expect(await store.readAccess(), 'acc');
    expect(await store.readRefresh(), 'ref');
  });

  test('FR-44: 用户取消 Apple 授权（token 为 null）→ 抛 LoginCancelled，不落盘', () async {
    final adapter = _CaptureAdapter();
    final store = InMemoryTokenStore();
    final repo = _repo(adapter, store, _FakeAppleClient(null));

    expect(repo.loginWithApple(), throwsA(isA<LoginCancelled>()));
    expect(adapter.lastPath, isNull); // 未发起后端请求
    expect(await store.readAccess(), isNull);
  });

  test('FR-44: 未注入 appleClient（非 iOS）→ loginWithApple 视作取消', () async {
    final adapter = _CaptureAdapter();
    final store = InMemoryTokenStore();
    final repo = _repo(adapter, store, null);

    expect(repo.loginWithApple(), throwsA(isA<LoginCancelled>()));
    expect(adapter.lastPath, isNull);
  });
}
