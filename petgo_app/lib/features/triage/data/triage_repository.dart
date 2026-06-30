import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 分诊任务状态（对齐后端 {@code TriageStatus}）。
enum TriageStatus { pending, processing, done, failed, unknown }

/// 分诊危险级别（对齐后端 {@code DangerLevel}）。绿/黄/红三态由 4.4/4.5 渲染。
enum DangerLevel { green, yellow, red }

/// 黄色「条件倒计时协议」三要素（Story 4.4 · FR-2）。由后端结构化产出，前端按结构分区呈现。
class TriageObservation {
  const TriageObservation({
    this.indicators = const <String>[],
    this.timeWindow,
    this.escalationTriggers = const <String>[],
  });

  final List<String> indicators;
  final String? timeWindow;
  final List<String> escalationTriggers;

  bool get hasContent =>
      indicators.isNotEmpty ||
      (timeWindow != null && timeWindow!.isNotEmpty) ||
      escalationTriggers.isNotEmpty;

  factory TriageObservation.fromJson(Map<String, dynamic> json) => TriageObservation(
        indicators: _strList(json['indicators']),
        timeWindow: json['timeWindow'] as String?,
        escalationTriggers: _strList(json['escalationTriggers']),
      );

  static List<String> _strList(Object? raw) =>
      raw is List ? raw.map((Object? e) => e.toString()).toList() : const <String>[];
}

/// 分诊结果（Story 4.1）。短轮询两态：处理中仅 [status]；DONE 带完整结构。
///
/// 真正的三态卡 / 红色半屏 UI 在 4.4/4.5；本故事仅薄客户端驱动契约。
class TriageResult {
  const TriageResult({
    required this.status,
    this.dangerLevel,
    this.advice,
    this.medicationRef,
    this.disclaimer,
    this.observation,
    this.symptomSummary,
    this.emergencySteps = const <String>[],
    this.emergencyAvoid = const <String>[],
  });

  final TriageStatus status;
  final DangerLevel? dangerLevel;
  final String? advice;
  final String? medicationRef;
  final String? disclaimer;
  final TriageObservation? observation;

  /// 红色态对症院前应急（仅红色态后端产出；安全层升红/AI 失败时为空 → UI 回退通用步骤）。
  final List<String> emergencySteps;
  final List<String> emergencyAvoid;

  /// AI 归纳的症状摘要（原型「RINGKASAN GEJALA」）。后端结果可回传；为空时 UI 回退用户输入的症状文本。
  final String? symptomSummary;

  bool get isTerminal =>
      status == TriageStatus.done || status == TriageStatus.failed;

  factory TriageResult.fromJson(Map<String, dynamic> json) => TriageResult(
        status: _status(json['status'] as String?),
        dangerLevel: _danger(json['dangerLevel'] as String?),
        advice: json['advice'] as String?,
        medicationRef: json['medicationRef'] as String?,
        disclaimer: json['disclaimer'] as String?,
        observation: json['observation'] is Map<String, dynamic>
            ? TriageObservation.fromJson(json['observation'] as Map<String, dynamic>)
            : null,
        symptomSummary: json['symptomSummary'] as String?,
        emergencySteps: _emergencyList(json['emergencySteps']),
        emergencyAvoid: _emergencyList(json['emergencyAvoid']),
      );

  static List<String> _emergencyList(Object? raw) =>
      raw is List ? raw.map((Object? e) => e.toString()).toList() : const <String>[];

  static TriageStatus _status(String? raw) {
    switch (raw) {
      case 'PENDING':
        return TriageStatus.pending;
      case 'PROCESSING':
        return TriageStatus.processing;
      case 'DONE':
        return TriageStatus.done;
      case 'FAILED':
        return TriageStatus.failed;
      default:
        return TriageStatus.unknown;
    }
  }

  static DangerLevel? _danger(String? raw) {
    switch (raw) {
      case 'GREEN':
        return DangerLevel.green;
      case 'YELLOW':
        return DangerLevel.yellow;
      case 'RED':
        return DangerLevel.red;
      default:
        return null;
    }
  }
}

/// 分诊契约客户端（Story 4.1 · F1）。
abstract class TriageRepository {
  /// 提交分诊（202 异步受理）。返回 triageId。[idempotencyKey] 去重重复提交。
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys,
    int? petId,
    String? idempotencyKey,
  });

  /// 短轮询取结果（处理中 / 就绪 / 失败三态映射）。
  Future<TriageResult> pollTriage(int triageId);
}

class DioTriageRepository implements TriageRepository {
  DioTriageRepository(this.dio);

  final Dio dio;

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async {
    final data = <String, dynamic>{};
    if (symptomText != null) data['symptomText'] = symptomText;
    if (imageObjectKeys.isNotEmpty) data['imageObjectKeys'] = imageObjectKeys;
    if (petId != null) data['petId'] = petId;

    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.triage,
      data: data,
      options: idempotencyKey == null
          ? null
          : Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return resp.data!['triageId'] as int;
  }

  @override
  Future<TriageResult> pollTriage(int triageId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.triageResult(triageId));
    return TriageResult.fromJson(resp.data!);
  }
}

final Provider<TriageRepository> triageRepositoryProvider = Provider<TriageRepository>(
  (ref) => DioTriageRepository(ref.read(dioProvider)),
);
