import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/storage/secure_storage.dart';
import '../domain/login_response.dart';
import 'google_auth_client.dart';

/// DEV-ONLY 开关：debug 构建默认开启「假登录」（见 [AuthRepository.loginWithGoogle]）。
/// 关闭：`--dart-define=PETGO_DEV_STUB_LOGIN=false`。release 恒不生效（双护栏 kDebugMode）。
const bool _kDevStubLogin = bool.fromEnvironment('PETGO_DEV_STUB_LOGIN', defaultValue: true);

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
    // 🔧 DEV-ONLY：debug 构建跳过真 Google OAuth + 后端，直接置已登录态——
    // 便于无真实凭证时验视觉二级页。release 构建恒不走此路（kDebugMode）。
    if (kDebugMode && _kDevStubLogin) {
      return _devStubLoginResponse();
    }
    final idToken = await googleClient.signInAndGetIdToken();
    if (idToken == null) throw const LoginCancelled();
    return exchangeIdToken(idToken);
  }

  /// DEV-ONLY 合成登录响应（已登录老用户）。
  Future<LoginResponse> _devStubLoginResponse() async {
    await tokenStore.saveTokens(access: 'dev-stub-access', refresh: 'dev-stub-refresh');
    return const LoginResponse(
      accessToken: 'dev-stub-access',
      refreshToken: 'dev-stub-refresh',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true, // → 进 App 主框架（已登录），受控 Tab 可进
      profile: UserProfile(
        nickname: 'Dev',
        displayName: 'Dev User',
        petStatus: 'A',
        onboardingCompleted: true,
      ),
    );
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
    // 退出登录（Story 7.3 AC1）：先作废服务端 refresh（best-effort），再清本地态。不删任何数据。
    try {
      final refresh = await tokenStore.readRefresh();
      if (refresh != null) {
        await dio.post<void>(ApiPaths.authLogout,
            data: {'refreshToken': refresh},
            options: Options(extra: {AuthExtraKeys.skipRefresh: true}));
      }
    } catch (_) {
      // 服务端失败不阻塞本地登出。
    }
    await tokenStore.clear();
    try {
      await googleClient.signOut();
    } catch (_) {
      // 注销 Google 失败不阻塞本地落游客态。
    }
  }

  /// 账号注销（Story 7.3 AC2）：双重确认后 DELETE /me（带确认短语）。202 受理异步级联删除。
  Future<void> deleteAccount(String confirmation) async {
    await dio.delete<void>(ApiPaths.me, data: {'confirmation': confirmation});
    await tokenStore.clear();
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
