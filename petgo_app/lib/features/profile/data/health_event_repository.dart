import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 存档决策。
enum ArchiveDecision {
  archived('ARCHIVED'),
  skipped('SKIPPED');

  const ArchiveDecision(this.wire);
  final String wire;
}

/// 问诊来源类型。
enum HealthSourceType {
  aiTriage('AI_TRIAGE'),
  vetConsult('VET_CONSULT');

  const HealthSourceType(this.wire);
  final String wire;
}

/// 问诊存档数据层（Story 2.5）。记录决策（幂等）+ 查是否已决策。
abstract class HealthEventRepository {
  Future<void> recordDecision({
    required HealthSourceType sourceType,
    required String sourceRef,
    required int petId,
    required ArchiveDecision decision,
    String? symptomSummary,
    String? aiLevel,
    String? adviceSummary,
    List<String> imImageRefs,
  });

  Future<bool> hasDecision(String sourceRef);
}

class DioHealthEventRepository implements HealthEventRepository {
  DioHealthEventRepository(this.dio);

  final Dio dio;

  @override
  Future<void> recordDecision({
    required HealthSourceType sourceType,
    required String sourceRef,
    required int petId,
    required ArchiveDecision decision,
    String? symptomSummary,
    String? aiLevel,
    String? adviceSummary,
    List<String> imImageRefs = const [],
  }) async {
    final data = <String, dynamic>{
      'sourceType': sourceType.wire,
      'sourceRef': sourceRef,
      'petId': petId,
      'decision': decision.wire,
    };
    if (symptomSummary != null) data['symptomSummary'] = symptomSummary;
    if (aiLevel != null) data['aiLevel'] = aiLevel;
    if (adviceSummary != null) data['adviceSummary'] = adviceSummary;
    if (imImageRefs.isNotEmpty) data['imImageRefs'] = imImageRefs;
    await dio.post<Map<String, dynamic>>(ApiPaths.healthArchiveDecisions, data: data);
  }

  @override
  Future<bool> hasDecision(String sourceRef) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.healthDecision,
      queryParameters: <String, dynamic>{'sourceRef': sourceRef},
    );
    return (resp.data?['decided'] ?? false) as bool;
  }
}

final Provider<HealthEventRepository> healthEventRepositoryProvider =
    Provider<HealthEventRepository>((ref) => DioHealthEventRepository(ref.read(dioProvider)));
