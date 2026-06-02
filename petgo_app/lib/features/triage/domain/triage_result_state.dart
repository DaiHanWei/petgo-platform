import '../data/triage_repository.dart';

/// 分诊流程阶段（Story 4.1 · F2）。驱动 4.3 等待 spinner / 4.4 三态卡 / 降级 UI（文案在 4.3/4.4）。
enum TriagePhase {
  /// 未开始。
  idle,

  /// 提交中（POST /triage）。
  submitting,

  /// 短轮询中（GET /triage/{id} 直到 DONE/FAILED/超时）。
  polling,

  /// 就绪（DONE）。
  done,

  /// 服务降级（FAILED）。映射 4.3「AI 服务暂时不可用」。
  failed,

  /// 轮询超时（>15s 未就绪）。映射 4.3「分析时间较长」降级。
  timedOut,

  /// 提交/网络错误。
  error,
}

/// 分诊流程不可变状态。副作用进 controller，不写 widget build。
class TriageResultState {
  const TriageResultState({
    this.phase = TriagePhase.idle,
    this.triageId,
    this.result,
  });

  final TriagePhase phase;
  final int? triageId;
  final TriageResult? result;

  bool get isBusy =>
      phase == TriagePhase.submitting || phase == TriagePhase.polling;

  TriageResultState copyWith({
    TriagePhase? phase,
    int? triageId,
    TriageResult? result,
  }) =>
      TriageResultState(
        phase: phase ?? this.phase,
        triageId: triageId ?? this.triageId,
        result: result ?? this.result,
      );
}
