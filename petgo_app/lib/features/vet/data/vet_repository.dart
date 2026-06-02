import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/storage/secure_storage.dart';
import '../domain/vet_login_response.dart';

/// 兽医数据层（Story 5.1）：账密登录换取 role=VET JWT；GET /vet/me 探活。
///
/// 与用户侧 [AuthRepository] 隔离但复用同一 token 存储（单 App 同一时刻只有一个角色登录态）。
class VetRepository {
  VetRepository({required this.dio, required this.tokenStore});

  final Dio dio;
  final TokenStore tokenStore;

  /// 兽医账密登录。成功落 token；失败抛 [DioException]（401/429 由调用方映射文案）。
  Future<VetLoginResponse> login(String username, String password) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.authVetLogin,
      data: {'username': username, 'password': password},
    );
    final login = VetLoginResponse.fromJson(resp.data!);
    await tokenStore.saveTokens(access: login.accessToken, refresh: login.refreshToken);
    return login;
  }

  /// 登录后探活：返回兽医自身视图（验证 role=VET 门控链路通）。
  Future<VetMe> me() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetMe);
    return VetMe.fromJson(resp.data!);
  }

  /// 读自身在线态（工作台「我的」Tab 开关初值，Story 5.2）。
  Future<bool> readOnlineStatus() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetOnlineStatus);
    return (resp.data!['online'] ?? false) as bool;
  }

  /// 切在线/离线（写 Redis 在线集合）。返回服务端权威态。
  Future<bool> setOnline(bool online) async {
    final resp = await dio.put<Map<String, dynamic>>(
      ApiPaths.vetOnlineStatus,
      data: {'online': online},
    );
    return (resp.data!['online'] ?? false) as bool;
  }

  /// 前台心跳续期 TTL（防幽灵在线靠 TTL 兜底）。
  Future<void> heartbeat() => dio.post<void>(ApiPaths.vetHeartbeat);

  /// 登出即离线（服务端清在线态）+ 清本地 token。
  Future<void> logout() async {
    try {
      await dio.post<void>(ApiPaths.vetLogout);
    } catch (_) {
      // 网络失败不阻塞本地落游客态；TTL 会兜底离线。
    }
    await tokenStore.clear();
  }
}

final vetRepositoryProvider = Provider<VetRepository>((ref) => VetRepository(
      dio: ref.read(dioProvider),
      tokenStore: ref.read(tokenStoreProvider),
    ));
