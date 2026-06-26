import 'dart:convert';

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

  /// 完整 Apple 登录链路（FR-44，iOS）。用户取消抛 [LoginCancelled]。
  ///
  /// iOS 真机需接 `sign_in_with_apple` 取 identityToken（+ iOS「Sign in with Apple」
  /// 能力 + 后端 /auth/apple 校验器，留作 L2 接入点）。
  Future<LoginResponse> loginWithApple() async {
    // TODO(L2): 接入 sign_in_with_apple 取真实 identityToken；当前暂作取消处理。
    throw const LoginCancelled();
  }

  /// 用 Apple identityToken 向后端换取自签 JWT（拆出便于测试）。
  Future<LoginResponse> exchangeAppleToken(String identityToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.authApple,
      data: {'identityToken': identityToken},
    );
    final login = LoginResponse.fromJson(resp.data!);
    await tokenStore.saveTokens(access: login.accessToken, refresh: login.refreshToken);
    return login;
  }

  /// 解码本地 access token 的 `role` claim（不验签，仅冷启动本地路由分流用；权威校验仍走后端）。
  /// 返回 'USER' / 'VET' / 'ADMIN' / null。
  Future<String?> readTokenRole() async {
    final access = await tokenStore.readAccess();
    if (access == null) return null;
    try {
      final parts = access.split('.');
      if (parts.length < 2) return null;
      final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final role = (jsonDecode(payload) as Map<String, dynamic>)['role'];
      return role is String ? role : null;
    } catch (_) {
      return null;
    }
  }

  /// 冷启动恢复兽医会话：校验 `/vet/me`（access 过期由拦截器续期重放）；成功 true，失败/无 token false。
  Future<bool> restoreVetSession() async {
    if (await tokenStore.readAccess() == null) return false;
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetMe);
      return resp.data != null;
    } on DioException {
      return false;
    }
  }

  /// 冷启动恢复会话：本地有 access token 则调 `/me` 验证并返回 profile；
  /// access 过期时鉴权拦截器会自动用 refresh 续期重放；无 token / 刷新失败返回 null（保持游客）。
  Future<UserProfile?> restoreSession() async {
    final access = await tokenStore.readAccess();
    if (access == null) return null;
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.me);
      final data = resp.data;
      if (data == null) return null;
      return UserProfile.fromJson(data);
    } on DioException {
      return null;
    }
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
