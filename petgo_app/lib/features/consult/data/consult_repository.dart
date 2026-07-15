import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/consult_case.dart';
import '../domain/consult_diagnosis.dart';
import '../domain/consult_history_item.dart';
import '../domain/consult_pay_result.dart';
import '../domain/consult_request.dart';
import '../domain/consult_session.dart';

/// 用户侧问诊数据层（Story 5.2 起）。可用性查询 + 会话发起/轮询/继续等待/取消（Story 5.3）。
class ConsultRepository {
  ConsultRepository({required this.dio});

  final Dio dio;

  /// 兽医咨询可用性（在线 bool + 恢复时段配置 key）。失败保守按离线。
  Future<ConsultAvailability> availability() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultAvailability);
      return ConsultAvailability.fromJson(resp.data!);
    } on DioException {
      return const ConsultAvailability(vetOnline: false);
    }
  }

  /// 是否有兽医在线（仅 bool，兼容旧 indicator）。
  Future<bool> vetOnline() async => (await availability()).vetOnline;

  /// 本次会诊最终诊断（兽医结束时定格）。未出诊断(204)/失败 → null。「查看会诊结果」入口用。
  Future<ConsultDiagnosis?> diagnosis(int sessionId) async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultSessionDiagnosis(sessionId));
      if (resp.statusCode == 204 || resp.data == null) return null;
      return ConsultDiagnosis.fromJson(resp.data!);
    } on DioException {
      return null;
    }
  }

  /// 当前用户自己提交的病例（症状 + 私密图签名 URL）。会话页摘要条「View」展开用。失败/无病例按空处理。
  Future<ConsultCase> caseContext(int sessionId) async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultSessionCase(sessionId));
      return ConsultCase.fromJson(resp.data!);
    } on DioException {
      return const ConsultCase(hasCase: false);
    }
  }

  /// 当前用户的占用态会话（无则 null）。入口据此显示「查看进行中 →」。
  Future<ConsultSession?> active() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultSessionActive);
    if (resp.statusCode == 204 || resp.data == null) return null;
    return ConsultSession.fromJson(resp.data!);
  }

  /// 发起咨询（DIRECT）。已有占用态会话则返回现有（alreadyActive=true）。
  /// Story F：可带用户自填病例 —— [symptomText] 症状 + [imageObjectKeys] 私密桶对象 key（前端已直传）。
  Future<ConsultSession> create({String? symptomText, List<String>? imageObjectKeys}) async {
    final body = <String, dynamic>{};
    if (symptomText != null && symptomText.trim().isNotEmpty) body['symptomText'] = symptomText.trim();
    if (imageObjectKeys != null && imageObjectKeys.isNotEmpty) body['imageObjectKeys'] = imageObjectKeys;
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.consultSessions, data: body);
    return ConsultSession.fromJson(resp.data!);
  }

  /// 从 AI 分诊升级（Story 5.4）：只传 triageTaskId，评级/描述/图片由后端从 triage 拉取（前端不重传）。
  /// 红色态后端兜底拒绝（前端绿/黄才暴露入口）。
  Future<ConsultSession> createFromUpgrade(int triageTaskId) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.consultSessions,
      data: {'source': 'AI_UPGRADE', 'triageTaskId': triageTaskId},
    );
    return ConsultSession.fromJson(resp.data!);
  }

  /// 轮询会话状态（含 timedOut）。
  Future<ConsultSession> get(int id) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultSession(id));
    return ConsultSession.fromJson(resp.data!);
  }

  /// 继续等待（重置计时基准）。
  Future<ConsultSession> continueWaiting(int id) async {
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.consultSessionContinueWaiting(id));
    return ConsultSession.fromJson(resp.data!);
  }

  /// 取消（WAITING → CANCELLED + 出队）。
  Future<ConsultSession> cancel(int id) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.consultSession(id));
    return ConsultSession.fromJson(resp.data!);
  }

  /// 封禁挂起逃生（Story 3.8，H-5）：立即结束挂起会话 + 按支付方式退款（INTERRUPTED 终态）。
  Future<ConsultSession> escapeSuspended(int id) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.consultSessionEscape(id));
    return ConsultSession.fromJson(resp.data!);
  }

  /// 提交评分（1-5 星必填 + ≤100 字选填）→ CLOSED(RATED)（Story 5.6）。
  Future<ConsultSession> rate(int id, int stars, String? comment) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.consultSessionRating(id),
      data: {'stars': stars, if (comment != null && comment.isNotEmpty) 'comment': comment},
    );
    return ConsultSession.fromJson(resp.data!);
  }

  /// 待补弹评分的已关闭会话（无则 null）。
  Future<ConsultSession?> pendingRating() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultPendingRating);
    if (resp.statusCode == 204 || resp.data == null) return null;
    return ConsultSession.fromJson(resp.data!);
  }

  /// 补弹已展示 → 不再弹。
  Future<void> markRatingPrompted(int id) =>
      dio.patch<void>(ApiPaths.consultSessionRatingPrompted(id));

  // ===== 计费流下单链路（Story 3.5，consult_requests 两表流）=====

  /// 发起付费问诊入队（`POST /consultations`）。占用命中返现有（alreadyActive=true）。
  /// 无宠物档案 → 后端 409（调用方映射 l10n）。
  Future<ConsultRequest> createRequest() async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.consultations);
    return ConsultRequest.fromJson(resp.data!);
  }

  /// 轮询请求状态（`GET /consultations/{token}`）。请求已消失（超时删/转单删）→ 404（DioException 抛给调用方）。
  Future<ConsultRequestStatus> requestStatus(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultationStatus(token));
    return ConsultRequestStatus.fromJson(resp.data!);
  }

  /// 限时支付（`POST /pay {channel}`）。DONE=PawCoin 即时成功 / PAYMENT_REQUIRED=现金待付。
  /// 余额不足/支付窗过期/守卫不符 → 后端 409；IM 建会话失败 → 503（调用方映射 l10n）。
  Future<ConsultPayResult> payRequest(String token, String channel) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.consultationPay(token),
      data: {'channel': channel},
    );
    return ConsultPayResult.fromJson(resp.data!);
  }

  /// 跳充值暂停支付计时（A-4，`POST /pause`，服务端记 paused_at）。
  Future<void> pauseRequest(String token) => dio.post<void>(ApiPaths.consultationPause(token));

  /// 跳充值返回续（A-4，`POST /resume`，服务端按剩余顺延 pay_deadline）。
  Future<void> resumeRequest(String token) => dio.post<void>(ApiPaths.consultationResume(token));

  /// 用户主动取消（`POST /cancel`，物理删无痕）。
  Future<void> cancelRequest(String token) => dio.post<void>(ApiPaths.consultationCancel(token));

  /// 问诊历史（Story 5.8，AI + 兽医两类，游标分页）。
  Future<ConsultHistoryPage> history({String? cursor, int limit = 20}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.consultHistory,
      queryParameters: {'cursor': ?cursor, 'limit': limit},
    );
    return ConsultHistoryPage.fromJson(resp.data!);
  }
}

final consultRepositoryProvider =
    Provider<ConsultRepository>((ref) => ConsultRepository(dio: ref.read(dioProvider)));

/// 兽医咨询可用性（FutureProvider，入口渲染前读取）。
final consultAvailabilityProvider =
    FutureProvider.autoDispose<ConsultAvailability>((ref) => ref.read(consultRepositoryProvider).availability());
