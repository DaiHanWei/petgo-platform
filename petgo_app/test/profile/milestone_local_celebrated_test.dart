import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/milestone_celebration_reporter.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';

/// batch-a 复审 #1：「本机已庆祝」集合的生命周期。
///
/// 回报成功 → 保留（挡住旧缓存再补弹）并刷新列表缓存；
/// 回报失败 → 撤回（保持「失败 = 下次进列表页再补弹」的既有语义）。
class _Repo implements MilestoneRepository {
  final completer = Completer<void>();
  final reported = <List<String>>[];

  @override
  Future<void> reportCelebrations(List<String> codes) {
    reported.add(codes);
    return completer.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late _Repo repo;
  late ProviderContainer c;
  var listBuilds = 0;

  setUp(() {
    repo = _Repo();
    listBuilds = 0;
    c = ProviderContainer(overrides: [
      milestoneRepositoryProvider.overrideWithValue(repo),
      milestoneListProvider.overrideWith((ref) async {
        listBuilds++;
        return const MilestoneList(petName: 'Momo', completedCount: 0, totalCount: 0, groups: []);
      }),
    ]);
    addTearDown(c.dispose);
  });

  test('发起回报即记本地（回报在途期间就能挡住补弹）', () {
    c.read(locallyCelebratedMilestonesProvider.notifier).report(['C-M8']);
    expect(c.read(locallyCelebratedMilestonesProvider), {'C-M8'});
    expect(repo.reported, [
      ['C-M8']
    ]);
  });

  test('回报成功 → 保留，并刷新列表缓存', () async {
    final sub = c.listen(milestoneListProvider, (_, _) {});
    addTearDown(sub.close);
    await c.read(milestoneListProvider.future);
    expect(listBuilds, 1);

    var done = false;
    c.read(locallyCelebratedMilestonesProvider.notifier).report(['C-M8'], onDone: () => done = true);
    repo.completer.complete();
    await Future<void>.delayed(Duration.zero);
    await c.read(milestoneListProvider.future);

    expect(c.read(locallyCelebratedMilestonesProvider), {'C-M8'});
    expect(listBuilds, 2, reason: '缓存里是未庆祝的旧值，必须重拉');
    expect(done, isTrue);
  });

  test('回报失败 → 撤回（下次进列表页照常补弹）', () async {
    c.read(locallyCelebratedMilestonesProvider.notifier).report(['C-M8']);
    repo.completer.completeError(Exception('offline'));
    await Future<void>.delayed(Duration.zero);

    expect(c.read(locallyCelebratedMilestonesProvider), isEmpty);
  });

  test('换账号 invalidate 后，旧账号迟到的失败回调不改新账号的状态', () async {
    c.read(locallyCelebratedMilestonesProvider.notifier).report(['C-M8']);
    c.invalidate(locallyCelebratedMilestonesProvider);
    // 新账号恰好也弹过同一个 code —— 旧账号的撤回若漏过来，会把它也摘掉。
    c.read(locallyCelebratedMilestonesProvider.notifier).add(['C-M8']);

    repo.completer.completeError(Exception('offline'));
    await Future<void>.delayed(Duration.zero);

    expect(c.read(locallyCelebratedMilestonesProvider), {'C-M8'});
  });
}
