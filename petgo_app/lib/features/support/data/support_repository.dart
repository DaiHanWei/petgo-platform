import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/support_ticket.dart';

/// 客服工单数据层（Story 4.2，消费 4-1 后端）。建单 / 我的工单列表 / 详情。
/// 401 由拦截器处理，repository 不自理；业务错误（422 校验等）以 [DioException] 抛给页面。
class SupportRepository {
  SupportRepository({required this.dio});

  final Dio dio;

  /// 建工单（`POST /support-tickets`）。附件 objectKey 由前端 media 预签名直传后回传。
  /// 成功 201 → 返回工单用户视图；≤5 超限 / contactType·label 非法 → 后端 422（抛给调用方）。
  Future<SupportTicket> createTicket({
    String? subject,
    required String body,
    required ContactType contactType,
    required String contactValue,
    bool needContact = true,
    String? relatedOrderToken,
    List<TicketLabelType> labels = const [],
    List<String> attachmentObjectKeys = const [],
  }) async {
    final data = <String, dynamic>{
      'body': body.trim(),
      'contactType': contactType.toApi(),
      'contactValue': contactValue.trim(),
      'needContact': needContact,
    };
    final s = subject?.trim();
    if (s != null && s.isNotEmpty) data['subject'] = s;
    if (relatedOrderToken != null && relatedOrderToken.isNotEmpty) {
      data['relatedOrderToken'] = relatedOrderToken;
    }
    if (labels.isNotEmpty) data['labels'] = labels.map((l) => l.toApi()).toList();
    if (attachmentObjectKeys.isNotEmpty) data['attachmentObjectKeys'] = attachmentObjectKeys;

    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.supportTickets, data: data);
    return SupportTicket.fromJson(resp.data!);
  }

  /// 我的工单列表（`GET /support-tickets`，created_at 倒序）。
  Future<List<SupportTicket>> myTickets() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.supportTickets);
    final list = resp.data ?? const [];
    return list
        .map((e) => SupportTicket.fromJson(e as Map<String, dynamic>))
        .toList(growable: false);
  }

  /// 工单详情（`GET /support-tickets/{token}`，非本人后端 404 → DioException 抛给调用方）。
  Future<SupportTicket> ticketDetail(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.supportTicketDetail(token));
    return SupportTicket.fromJson(resp.data!);
  }
}

final supportRepositoryProvider =
    Provider<SupportRepository>((ref) => SupportRepository(dio: ref.read(dioProvider)));

/// 我的工单列表（FutureProvider，列表页读取；autoDispose 便于下拉刷新 invalidate）。
final myTicketsProvider = FutureProvider.autoDispose<List<SupportTicket>>(
    (ref) => ref.read(supportRepositoryProvider).myTickets());

/// 工单详情（按 token，详情页读取）。
final ticketDetailProvider = FutureProvider.autoDispose.family<SupportTicket, String>(
    (ref, token) => ref.read(supportRepositoryProvider).ticketDetail(token));
