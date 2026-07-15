import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_result_controller.dart';
import 'package:tailtopia/features/triage/domain/triage_result_state.dart';

/// 可编排的假仓储：按序返回轮询结果，记录提交参数。
class _FakeTriageRepo implements TriageRepository {
  _FakeTriageRepo({required this.pollResults, this.submitThrows = false});

  final List<TriageResult> pollResults;
  final bool submitThrows;
  int pollCalls = 0;
  String? lastIdempotencyKey;
  bool submitted = false;

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async {
    if (submitThrows) throw Exception('submit failed');
    submitted = true;
    lastIdempotencyKey = idempotencyKey;
    return 42;
  }

  @override
  Future<TriageResult> pollTriage(int triageId) async {
    final r = pollResults[pollCalls < pollResults.length ? pollCalls : pollResults.length - 1];
    pollCalls++;
    return r;
  }

  @override
  Future<UnlockResult> unlockTriage(int triageId, UnlockMethod method) async =>
      throw UnimplementedError();

  @override
  Future<FreeQuotaView> fetchFreeQuota() async => throw UnimplementedError();
}

ProviderContainer _container(_FakeTriageRepo repo) {
  final c = ProviderContainer(overrides: [
    triageRepositoryProvider.overrideWithValue(repo),
    // 极短间隔/超时，避免测试慢。
    triagePollIntervalProvider.overrideWithValue(const Duration(milliseconds: 1)),
    triageTimeoutProvider.overrideWithValue(const Duration(milliseconds: 200)),
  ]);
  addTearDown(c.dispose);
  return c;
}

void main() {
  test('提交 → 轮询直到 DONE，落 done 态带结果', () async {
    final repo = _FakeTriageRepo(pollResults: <TriageResult>[
      const TriageResult(status: TriageStatus.processing),
      const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.green, advice: 'ok'),
    ]);
    final c = _container(repo);

    await c.read(triageResultProvider.notifier).submitAndPoll(
          symptomText: 'x',
          idempotencyKey: 'idem-1',
        );

    final state = c.read(triageResultProvider);
    expect(state.phase, TriagePhase.done);
    expect(state.triageId, 42);
    expect(state.result?.dangerLevel, DangerLevel.green);
    expect(repo.lastIdempotencyKey, 'idem-1');
  });

  test('FAILED 落降级态', () async {
    final repo = _FakeTriageRepo(
        pollResults: <TriageResult>[const TriageResult(status: TriageStatus.failed)]);
    final c = _container(repo);

    await c.read(triageResultProvider.notifier).submitAndPoll(symptomText: 'x');

    expect(c.read(triageResultProvider).phase, TriagePhase.failed);
  });

  test('一直处理中 → 超时降级，保留 triageId', () async {
    final repo = _FakeTriageRepo(
        pollResults: <TriageResult>[const TriageResult(status: TriageStatus.processing)]);
    final c = _container(repo);

    await c.read(triageResultProvider.notifier).submitAndPoll(symptomText: 'x');

    final state = c.read(triageResultProvider);
    expect(state.phase, TriagePhase.timedOut);
    expect(state.triageId, 42);
  });

  test('提交失败 → error 态', () async {
    final repo = _FakeTriageRepo(pollResults: const <TriageResult>[], submitThrows: true);
    final c = _container(repo);

    await c.read(triageResultProvider.notifier).submitAndPoll(symptomText: 'x');

    expect(c.read(triageResultProvider).phase, TriagePhase.error);
    expect(repo.submitted, isFalse);
  });
}
