import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/storage/secure_storage.dart';
import '../domain/consult_ai_context.dart';
import '../domain/vet_diagnosis_draft.dart';
import '../domain/vet_inbox_item.dart';
import '../domain/vet_login_response.dart';
import '../domain/vet_workbench_lists.dart';

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

  /// 会话 AI 上下文（Story 5.4，含私密图签名 URL）。DIRECT 会话返回 hasAiContext=false。
  Future<ConsultAiContext> aiContext(int sessionId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetConsultAiContext(sessionId));
    return ConsultAiContext.fromJson(resp.data!);
  }

  // ===== Story 5.5：待接单 / 接单 / 会话 / 辅助 =====

  /// 待接单列表（含 AI 上下文摘要）。
  Future<List<VetInboxItem>> waitingList() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.vetConsultWaiting);
    return (resp.data ?? [])
        .map((e) => VetInboxItem.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// 接单（CAS WAITING→IN_PROGRESS + IM 建会话）。被抢则抛 409 DioException。
  Future<VetSession> accept(int sessionId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.vetConsultAccept(sessionId));
    return VetSession.fromJson(resp.data!);
  }

  /// 进行中会话视图（含 im_conversation_id）。
  Future<VetSession> session(int sessionId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetConsultSession(sessionId));
    return VetSession.fromJson(resp.data!);
  }

  /// FR-5 辅助（AI 参考回复 + 冷启动空历史）。
  Future<ConsultAssist> assist(int sessionId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.vetConsultAssist(sessionId));
    return ConsultAssist.fromJson(resp.data!);
  }

  /// 「进行中」会话列表（工作台 Active Tab）。
  Future<List<VetActiveItem>> activeSessions() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.vetConsultInProgress);
    return (resp.data ?? [])
        .map((e) => VetActiveItem.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// 已结束「历史」列表（工作台 History Tab）。
  Future<List<VetHistoryEntry>> history() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.vetConsultHistory);
    return (resp.data ?? [])
        .map((e) => VetHistoryEntry.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// 兽医结束会话（Story 5.6 + Story C）：随结束提交最终诊断（Diagnosa 必填，后端校验）。
  /// IN_PROGRESS → PENDING_CLOSE；诊断定格存档 + 推用户。
  Future<VetSession> endSession(int sessionId, VetDiagnosisDraft diagnosis) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.vetConsultEnd(sessionId),
      data: diagnosis.toJson(),
    );
    return VetSession.fromJson(resp.data!);
  }

  /// 兽医发完回复后通知用户（Story 6.2）：触发「有新回复」推送。失败静默（不阻塞对话）。
  Future<void> notifyReply(int sessionId) async {
    try {
      await dio.post<void>(ApiPaths.vetConsultNotifyReply(sessionId));
    } catch (_) {
      // 推送是增强，失败不影响 IM 对话本身。
    }
  }

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
