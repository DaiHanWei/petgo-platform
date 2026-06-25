import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/triage_repository.dart';
import 'triage_result_state.dart';

/// 轮询间隔（可在测试中 override 为极短值）。
final Provider<Duration> triagePollIntervalProvider =
    Provider<Duration>((ref) => const Duration(seconds: 1));

/// 轮询总超时。超时映射 4.3「分析时间较长」降级。
/// 原 15s（NFR-1 SLA，匹配 gemini-2.5-flash ~3-5s）；**临时提到 30s**：flash 全家 503 过载、
/// 生产临时切 gemini-2.5-pro，pro 带思考 ~15-18s，15s 会卡在后端已成功前误报失败。
/// flash 容量恢复、回退模型后应一并改回 15s。
final Provider<Duration> triageTimeoutProvider =
    Provider<Duration>((ref) => const Duration(seconds: 30));

/// 分诊流程控制器（Story 4.1 · F2）。封装「提交 → 短轮询直到 DONE/FAILED/超时」，
/// 仅薄客户端驱动契约；真正的上传页 / spinner / 三态卡 / 红色半屏在 4.3/4.4/4.5。
///
/// ⚠️ 取结果一律读经后置安全规则层（4.2）裁定的最终级别——前端不做任何「模型说绿就当绿」的快路径。
class TriageController extends Notifier<TriageResultState> {
  @override
  TriageResultState build() => const TriageResultState();

  /// 重置回初始空闲态：重新发起一次分诊前清除上次结果（避免重进分诊直接看到旧结果）。
  void reset() => state = const TriageResultState();

  /// 提交并短轮询。失败/超时落降级态供 4.3 重试 UI（复用上次提交内容）。
  Future<void> submitAndPoll({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async {
    final repo = ref.read(triageRepositoryProvider);
    state = const TriageResultState(phase: TriagePhase.submitting);

    final int triageId;
    try {
      triageId = await repo.submitTriage(
        symptomText: symptomText,
        imageObjectKeys: imageObjectKeys,
        petId: petId,
        idempotencyKey: idempotencyKey,
      );
    } catch (_) {
      state = const TriageResultState(phase: TriagePhase.error);
      return;
    }

    state = TriageResultState(phase: TriagePhase.polling, triageId: triageId);
    await _poll(triageId);
  }

  Future<void> _poll(int triageId) async {
    final interval = ref.read(triagePollIntervalProvider);
    final timeout = ref.read(triageTimeoutProvider);
    final deadline = DateTime.now().add(timeout);

    while (DateTime.now().isBefore(deadline)) {
      TriageResult result;
      try {
        result = await ref.read(triageRepositoryProvider).pollTriage(triageId);
      } catch (_) {
        // 单次轮询失败不立即放弃：等下一拍重试，直至超时。
        await Future<void>.delayed(interval);
        continue;
      }
      if (result.status == TriageStatus.done) {
        state = TriageResultState(
            phase: TriagePhase.done, triageId: triageId, result: result);
        return;
      }
      if (result.status == TriageStatus.failed) {
        state = TriageResultState(
            phase: TriagePhase.failed, triageId: triageId, result: result);
        return;
      }
      await Future<void>.delayed(interval);
    }
    // 超时降级：保留 triageId 供 4.3 重试。
    state = TriageResultState(phase: TriagePhase.timedOut, triageId: triageId);
  }
}

final NotifierProvider<TriageController, TriageResultState> triageResultProvider =
    NotifierProvider<TriageController, TriageResultState>(TriageController.new);
