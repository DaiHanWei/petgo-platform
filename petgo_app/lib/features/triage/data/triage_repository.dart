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
    this.locked,
    this.unlockSource,
  });

  final TriageStatus status;
  final DangerLevel? dangerLevel;
  final String? advice;
  final String? medicationRef;
  final String? disclaimer;
  final TriageObservation? observation;

  /// 详建（SARAN PERAWATAN）是否锁定（Story 2.2/2.4）。DONE 时后端下发；null=非 DONE/未知。
  /// 仅 `status==done && locked==true && dangerLevel!=red` 时渲染 paywall（红色永不锁）。
  final bool? locked;

  /// 解锁来源（LOCKED/FREE_QUOTA/PAID，Story 2.2）。展示/调试用；渲染以 [locked] 为准。
  final String? unlockSource;

  /// 红色态对症院前应急（仅红色态后端产出；安全层升红/AI 失败时为空 → UI 回退通用步骤）。
  final List<String> emergencySteps;
  final List<String> emergencyAvoid;

  /// AI 归纳的症状摘要（原型「RINGKASAN GEJALA」）。后端结果可回传；为空时 UI 回退用户输入的症状文本。
  final String? symptomSummary;

  bool get isTerminal =>
      status == TriageStatus.done || status == TriageStatus.failed;

  /// 覆盖部分字段（历史快照回看时用历史条目的 [symptomSummary] 补齐——后端
  /// `GET /triage/{id}` 的 TriageResultResponse 不回传该字段，避免结果视图回退串味）。
  TriageResult copyWith({String? symptomSummary}) => TriageResult(
        status: status,
        dangerLevel: dangerLevel,
        advice: advice,
        medicationRef: medicationRef,
        disclaimer: disclaimer,
        observation: observation,
        symptomSummary: symptomSummary ?? this.symptomSummary,
        emergencySteps: emergencySteps,
        emergencyAvoid: emergencyAvoid,
        locked: locked,
        unlockSource: unlockSource,
      );

  /// 详建是否应显示 paywall（Story 2.4）：仅 DONE + 后端标 locked + 非红色（红色永不锁，前端双保险）。
  bool get isDetailLocked =>
      status == TriageStatus.done && locked == true && dangerLevel != DangerLevel.red;

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
        locked: json['locked'] as bool?,
        unlockSource: json['unlockSource'] as String?,
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

/// 解锁方式（Story 2.4，对齐后端 `UnlockMethod`）。免费额度 / PawCoin 同步；QRIS 现金异步。
enum UnlockMethod {
  freeQuota('FREE_QUOTA'),
  pawcoin('PAWCOIN'),
  qris('QRIS');

  const UnlockMethod(this.wire);

  final String wire;
}

/// 现金支付信息（Story 2.4，对齐后端 `PaymentIntentResponse`）。QR/deeplink 呈现字段暂缺（stub 无真付，L2 前定）。
class PaymentInfo {
  const PaymentInfo({
    required this.token,
    required this.channel,
    required this.amount,
    required this.status,
  });

  final String token;
  final String channel;
  final int amount;
  final String status;

  factory PaymentInfo.fromJson(Map<String, dynamic> json) => PaymentInfo(
        token: json['token'] as String? ?? '',
        channel: json['channel'] as String? ?? '',
        amount: (json['amount'] as num?)?.toInt() ?? 0,
        status: json['status'] as String? ?? '',
      );
}

/// 解锁响应（Story 2.4，对齐后端 `UnlockResponse`）。同步：[unlocked]=true+[result]；现金：false+[payment]。
class UnlockResult {
  const UnlockResult({required this.unlocked, this.result, this.payment});

  final bool unlocked;
  final TriageResult? result;
  final PaymentInfo? payment;

  factory UnlockResult.fromJson(Map<String, dynamic> json) => UnlockResult(
        unlocked: json['unlocked'] as bool? ?? false,
        result: json['result'] is Map<String, dynamic>
            ? TriageResult.fromJson(json['result'] as Map<String, dynamic>)
            : null,
        payment: json['payment'] is Map<String, dynamic>
            ? PaymentInfo.fromJson(json['payment'] as Map<String, dynamic>)
            : null,
      );
}

/// 本月免费额度（Story 2.1/2.4，对齐后端 `FreeQuotaView`）。[remaining]>0 → 免费方式可选。
class FreeQuotaView {
  const FreeQuotaView({required this.limit, required this.used, required this.remaining});

  final int limit;
  final int used;
  final int remaining;

  factory FreeQuotaView.fromJson(Map<String, dynamic> json) => FreeQuotaView(
        limit: (json['limit'] as num?)?.toInt() ?? 0,
        used: (json['used'] as num?)?.toInt() ?? 0,
        remaining: (json['remaining'] as num?)?.toInt() ?? 0,
      );
}

/// 分诊契约客户端（Story 4.1 · F1；Story 2.4 加解锁）。
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

  /// 解锁 AI 详建（Story 2.4）。免费/PawCoin 同步返回已解锁结果；现金返回支付信息。
  /// 额度/余额不足后端 409（ProblemDetail），由拦截器/调用方处理。
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method);

  /// 本月免费额度（Story 2.4，供解锁方式面板判「免费可选 + 剩余」）。
  Future<FreeQuotaView> fetchFreeQuota();
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

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.triageUnlock(triageId),
      data: <String, dynamic>{'method': method.wire},
    );
    return UnlockResult.fromJson(resp.data!);
  }

  @override
  Future<FreeQuotaView> fetchFreeQuota() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.freeQuota);
    return FreeQuotaView.fromJson(resp.data!);
  }
}

final Provider<TriageRepository> triageRepositoryProvider = Provider<TriageRepository>(
  (ref) => DioTriageRepository(ref.read(dioProvider)),
);
