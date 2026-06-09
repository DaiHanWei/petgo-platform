import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/health_event_repository.dart';

/// 挂起的存档意图（Story 2.5 AC3/AC4 · 三态承接）。
///
/// 当用户在「② A 未建档」/「③ B/C」存档弹窗选「立即创建 / 去创建」时，把本次问诊存档 payload
/// （除 petId 外，建档后才知）挂起；FR-11 建档成功后用**同一 sourceRef** 回灌 `recordDecision(ARCHIVED)`。
/// 用户放弃建档则永不回灌（无副作用）；幂等键 `uq_health_events_source_ref` 兜底防重存。
class PendingArchive {
  const PendingArchive({
    required this.sourceRef,
    required this.sourceType,
    this.symptomSummary,
    this.aiLevel,
    this.adviceSummary,
    this.imImageRefs = const [],
    this.returnToResult = false,
  });

  final String sourceRef;
  final HealthSourceType sourceType;
  final String? symptomSummary;
  final String? aiLevel;
  final String? adviceSummary;
  final List<String> imImageRefs;

  /// 红色态结果页触发（AC4）：回灌后语义上「返回问诊结果页」（导航近似回档案，见 create 页注释）。
  final bool returnToResult;
}

/// 全局挂起存档意图（单条；建档完成回灌后清空）。
class PendingArchiveController extends Notifier<PendingArchive?> {
  @override
  PendingArchive? build() => null;

  void set(PendingArchive intent) => state = intent;

  void clear() => state = null;
}

final pendingArchiveProvider =
    NotifierProvider<PendingArchiveController, PendingArchive?>(PendingArchiveController.new);
