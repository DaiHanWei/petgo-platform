import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/data/health_event_repository.dart';
import 'package:petgo/features/profile/domain/archive_prompt_guard.dart';

class _FakeRepo implements HealthEventRepository {
  _FakeRepo(this.decidedRefs);
  final Set<String> decidedRefs;
  int hasDecisionCalls = 0;

  @override
  Future<bool> hasDecision(String sourceRef) async {
    hasDecisionCalls++;
    return decidedRefs.contains(sourceRef);
  }

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
    decidedRefs.add(sourceRef);
  }
}

void main() {
  test('未决策 → 需弹；已决策（后端）→ 不弹（AC1）', () async {
    final guard = ArchivePromptGuard(_FakeRepo({'already'}));
    expect(await guard.needsPrompt('fresh'), isTrue);
    expect(await guard.needsPrompt('already'), isFalse);
  });

  test('本地置位后不再弹，且不再打后端', () async {
    final repo = _FakeRepo({});
    final guard = ArchivePromptGuard(repo);
    expect(await guard.needsPrompt('r1'), isTrue);
    guard.markHandled('r1');
    expect(await guard.needsPrompt('r1'), isFalse);
    // 第二次 needsPrompt 命中本地标记，未再调用后端 hasDecision
    expect(repo.hasDecisionCalls, 1);
  });

  test('后端已决策会缓存到本地，二次不再打后端', () async {
    final repo = _FakeRepo({'r2'});
    final guard = ArchivePromptGuard(repo);
    expect(await guard.needsPrompt('r2'), isFalse);
    expect(await guard.needsPrompt('r2'), isFalse);
    expect(repo.hasDecisionCalls, 1); // 第二次走本地缓存
  });
}
