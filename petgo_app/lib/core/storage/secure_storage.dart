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
      : _storage = storage ?? const FlutterSecureStorage(aOptions: _aOptions);

  final FlutterSecureStorage _storage;

  /// Android 覆盖安装（不卸载直接装新 APK）后，EncryptedSharedPreferences 的旧密文
  /// 与新 Keystore 密钥不匹配 → 读取抛 BadPaddingException/BAD_DECRYPT。
  /// resetOnError=true：底层遇解密错误自动清空并返回 null（自愈），不再抛异常。
  static const AndroidOptions _aOptions = AndroidOptions(resetOnError: true);

  static const _kAccess = 'petgo.access_token';
  static const _kRefresh = 'petgo.refresh_token';

  @override
  Future<String?> readAccess() => _read(_kAccess);

  @override
  Future<String?> readRefresh() => _read(_kRefresh);

  /// 读取容错兜底：即便 resetOnError 未覆盖到（或 iOS/其它异常），也绝不让异常
  /// 冒泡进 [AuthInterceptor.onRequest] —— 否则请求永不下发，表现为首页骨架无限加载。
  /// 任何读失败都当作"无令牌"，并尽力清掉损坏数据。
  Future<String?> _read(String key) async {
    try {
      return await _storage.read(key: key);
    } catch (_) {
      try {
        await _storage.deleteAll();
      } catch (_) {/* 清理失败无所谓，下次仍走无令牌路径 */}
      return null;
    }
  }

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
