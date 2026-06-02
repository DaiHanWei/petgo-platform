import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 令牌安全存储（access/refresh 走 [FlutterSecureStorage]，绝不入 prefs/日志）。
///
/// 抽象成接口便于测试注入内存实现。
abstract class TokenStore {
  Future<String?> readAccess();
  Future<String?> readRefresh();
  Future<void> saveTokens({required String access, required String refresh});
  Future<void> clear();
}

class SecureTokenStore implements TokenStore {
  SecureTokenStore([FlutterSecureStorage? storage])
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  static const _kAccess = 'petgo.access_token';
  static const _kRefresh = 'petgo.refresh_token';

  @override
  Future<String?> readAccess() => _storage.read(key: _kAccess);

  @override
  Future<String?> readRefresh() => _storage.read(key: _kRefresh);

  @override
  Future<void> saveTokens({required String access, required String refresh}) async {
    await _storage.write(key: _kAccess, value: access);
    await _storage.write(key: _kRefresh, value: refresh);
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _kAccess);
    await _storage.delete(key: _kRefresh);
  }
}

/// 内存令牌存储（测试/占位用）。
class InMemoryTokenStore implements TokenStore {
  String? _access;
  String? _refresh;

  @override
  Future<String?> readAccess() async => _access;

  @override
  Future<String?> readRefresh() async => _refresh;

  @override
  Future<void> saveTokens({required String access, required String refresh}) async {
    _access = access;
    _refresh = refresh;
  }

  @override
  Future<void> clear() async {
    _access = null;
    _refresh = null;
  }
}
