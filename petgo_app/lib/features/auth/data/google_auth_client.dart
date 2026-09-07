import 'package:flutter/foundation.dart';
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

  /// 🔧 DEBUG ONLY：跳过 Google SDK，直接返回一个桩 idToken。
  ///
  /// **为什么需要**：后端 dev profile 的 [DevGoogleTokenVerifier] 本就「忽略 idToken、
  /// 恒解析成固定测试身份」，设计意图是「前端点 Google 登录即落到测试账号、换取真实自签 JWT」。
  /// 但该设计默认客户端能拿到<b>某个</b> idToken —— 而没登过 Google 账号的模拟器
  /// 连账号选择器都是空的，一个 token 都产不出来，整条链在第一步就断了。
  /// 这里把缺的那一环补上：桩 token 发给后端 → 后端 dev 桩接受 → 拿到<b>真</b> JWT，
  /// 之后 `/api/v1/me/*` 全部真实鉴权可用（不是假登录态）。
  ///
  /// 🔒 双重门：`kDebugMode`（release 编译期裁掉）+ `--dart-define=DEV_GOOGLE_STUB=true`
  ///    （不传则行为与从前逐字一致，走真实 SDK）。**线上零影响。**
  static const bool _devStub =
      kDebugMode && bool.fromEnvironment('DEV_GOOGLE_STUB');

  @override
  Future<String?> signInAndGetIdToken() async {
    if (_devStub) {
      return 'dev-stub-id-token';
    }
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
