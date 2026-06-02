import 'package:google_sign_in/google_sign_in.dart';

/// Google 账号选择器抽象。返回 Google ID Token（后端据此校验建号）。
///
/// 抽象成接口便于 L0 测试注入 fake（免真实 Google 凭证）；真实链路是 L2 节点。
abstract class GoogleAuthClient {
  /// 触发系统账号选择器并取得 ID Token；用户取消时返回 null。
  Future<String?> signInAndGetIdToken();

  Future<void> signOut();
}

/// 基于 `google_sign_in` 7.x 的真实实现。
///
/// serverClientId / clientId 经配置注入（不硬编码敏感值）；真实授权需 L2（真机 + 真实 OAuth client）。
class GoogleSignInAuthClient implements GoogleAuthClient {
  GoogleSignInAuthClient({this.serverClientId, this.clientId});

  final String? serverClientId;
  final String? clientId;
  bool _initialized = false;

  Future<void> _ensureInit() async {
    if (_initialized) return;
    await GoogleSignIn.instance.initialize(clientId: clientId, serverClientId: serverClientId);
    _initialized = true;
  }

  @override
  Future<String?> signInAndGetIdToken() async {
    await _ensureInit();
    try {
      final GoogleSignInAccount account = await GoogleSignIn.instance.authenticate();
      return account.authentication.idToken;
    } on GoogleSignInException catch (e) {
      if (e.code == GoogleSignInExceptionCode.canceled) {
        return null; // 用户取消，非错误
      }
      rethrow;
    }
  }

  @override
  Future<void> signOut() async {
    await _ensureInit();
    await GoogleSignIn.instance.signOut();
  }
}
