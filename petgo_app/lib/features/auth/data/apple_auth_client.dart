import 'package:sign_in_with_apple/sign_in_with_apple.dart';

/// Apple 登录抽象（FR-44）。返回 Apple identity token（后端据此校验建号）。
///
/// 与 [GoogleAuthClient] 平行：抽象成接口便于 L0 测试注入 fake（免真实 Apple 凭证）；
/// 真实链路是 L2 节点（需 iOS 真机 + 「Sign in with Apple」能力 + 真实 client id / bundle id）。
abstract class AppleAuthClient {
  /// 触发系统 Apple 授权并取得 identity token；用户取消时返回 null。
  Future<String?> signInAndGetIdentityToken();
}

/// 基于 `sign_in_with_apple` 的真实实现。
///
/// 仅 iOS 调用（按钮本就只在 iOS 显示）；Apple 仅在首次授权时返回姓名（不进 identity token），
/// 故昵称由新用户引导补齐——这里只取 identityToken 交给后端校验。
class SignInWithAppleClient implements AppleAuthClient {
  @override
  Future<String?> signInAndGetIdentityToken() async {
    try {
      final credential = await SignInWithApple.getAppleIDCredential(
        scopes: const [
          AppleIDAuthorizationScopes.email,
          AppleIDAuthorizationScopes.fullName,
        ],
      );
      return credential.identityToken;
    } on SignInWithAppleAuthorizationException catch (e) {
      if (e.code == AuthorizationErrorCode.canceled) {
        return null; // 用户取消，非错误
      }
      rethrow;
    }
  }
}
