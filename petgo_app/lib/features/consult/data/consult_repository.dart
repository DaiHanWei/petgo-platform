import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
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

  /// 当前用户的占用态会话（无则 null）。入口据此显示「查看进行中 →」。
  Future<ConsultSession?> active() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultSessionActive);
    if (resp.statusCode == 204 || resp.data == null) return null;
    return ConsultSession.fromJson(resp.data!);
  }

  /// 发起咨询（DIRECT）。已有占用态会话则返回现有（alreadyActive=true）。
  Future<ConsultSession> create() async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.consultSessions, data: {});
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
}

final consultRepositoryProvider =
    Provider<ConsultRepository>((ref) => ConsultRepository(dio: ref.read(dioProvider)));

/// 兽医咨询可用性（FutureProvider，入口渲染前读取）。
final consultAvailabilityProvider =
    FutureProvider.autoDispose<ConsultAvailability>((ref) => ref.read(consultRepositoryProvider).availability());
