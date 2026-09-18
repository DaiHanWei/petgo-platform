import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';

/// batch-b1 复审 F1：@ 候选集是按当前用户算的「最近互动的人」，不得常驻整个进程。
///
/// 常驻的后果：换账号后 B 看到 A 的联系人（隐私）；新互动的人要重启才出现；
/// 首次取数失败（关了自动重试）则整个会话都是空态。
class _Repo implements MentionCandidateRepository {
  int calls = 0;
  bool fail = false;

  @override
  Future<List<MentionCandidate>> getCandidates() async {
    calls++;
    if (fail) throw Exception('offline');
    return [MentionCandidate(userId: calls, nickname: 'u$calls')];
  }
}

void main() {
  test('浮层关掉即回收，下次弹出重新取数', () async {
    final repo = _Repo();
    final c = ProviderContainer(
        overrides: [mentionCandidateRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(c.dispose);

    // 第一次弹出浮层（watch）→ 关掉（取消监听）。
    final first = c.listen(mentionCandidatesProvider, (_, _) {});
    await c.read(mentionCandidatesProvider.future);
    first.close();
    await Future<void>.delayed(Duration.zero); // 让 autoDispose 生效

    // 第二次弹出。
    final second = c.listen(mentionCandidatesProvider, (_, _) {});
    addTearDown(second.close);
    final list = await c.read(mentionCandidatesProvider.future);

    expect(repo.calls, 2);
    expect(list.single.userId, 2, reason: '拿到的是新一次取数的结果');
  });

  test('首次取数失败不会把整个会话锁在空态', () async {
    final repo = _Repo()..fail = true;
    final c = ProviderContainer(
        overrides: [mentionCandidateRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(c.dispose);

    final first = c.listen(mentionCandidatesProvider, (_, _) {});
    await expectLater(c.read(mentionCandidatesProvider.future), throwsException);
    first.close();
    await Future<void>.delayed(Duration.zero);

    repo.fail = false;
    final second = c.listen(mentionCandidatesProvider, (_, _) {});
    addTearDown(second.close);
    expect(await c.read(mentionCandidatesProvider.future), isNotEmpty);
  });
}
