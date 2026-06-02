import 'package:dio/dio.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/storage/secure_storage.dart';
import '../domain/login_response.dart';
import 'google_auth_client.dart';

/// 取消登录的哨兵（区别于失败）。
class LoginCancelled implements Exception {
  const LoginCancelled();
}

/// auth 数据层：Google 授权 → 后端换取自签 JWT → 安全存储；refresh 轮换。
class AuthRepository {
  AuthRepository({required this.dio, required this.tokenStore, required this.googleClient});

  final Dio dio;
  final TokenStore tokenStore;
  final GoogleAuthClient googleClient;

  /// 完整 Google 登录链路。用户取消抛 [LoginCancelled]。
  Future<LoginResponse> loginWithGoogle() async {
    final idToken = await googleClient.signInAndGetIdToken();
    if (idToken == null) throw const LoginCancelled();
    return exchangeIdToken(idToken);
  }

  /// 用 ID Token 向后端换取自签 JWT（拆出便于测试）。
  Future<LoginResponse> exchangeIdToken(String idToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.authGoogle,
      data: {'idToken': idToken},
    );
    final login = LoginResponse.fromJson(resp.data!);
    await tokenStore.saveTokens(access: login.accessToken, refresh: login.refreshToken);
    return login;
  }

  /// refresh 轮换：成功返回 true 并落盘新令牌；失败返回 false。
  Future<bool> refresh() async {
    final current = await tokenStore.readRefresh();
    if (current == null) return false;
    try {
      final resp = await dio.post<Map<String, dynamic>>(
        ApiPaths.authRefresh,
        data: {'refreshToken': current},
        // 标记为 auth 自身请求，拦截器不对其 401 再触发刷新（防死循环）。
        options: Options(extra: {AuthExtraKeys.skipRefresh: true}),
      );
      final access = resp.data!['accessToken'] as String;
      final refresh = resp.data!['refreshToken'] as String;
      await tokenStore.saveTokens(access: access, refresh: refresh);
      return true;
    } on DioException {
      return false;
    }
  }

  Future<void> logout() async {
    await tokenStore.clear();
    try {
      await googleClient.signOut();
    } catch (_) {
      // 注销 Google 失败不阻塞本地落游客态。
    }
  }
}

/// dio request `extra` 约定键。
class AuthExtraKeys {
  AuthExtraKeys._();

  /// 本请求不参与 401→refresh（如 refresh 端点自身）。
  static const String skipRefresh = 'petgo.skipRefresh';

  /// 本请求已重放过一次（防死循环）。
  static const String retried = 'petgo.retried';
}
