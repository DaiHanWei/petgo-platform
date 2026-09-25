import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/presentation/pet_recommendation_list_controller.dart';

/// code review #8：加载更多 / 重试第一页的请求在途时页面关闭（autoDispose 回收），
/// 请求回来后写 state 会抛 —— debug 包红屏。
class _GatedRepo implements PetRecommendationRepository {
  final List<Completer<RecommendedPetPage>> pending = [];
  bool firstServed = false;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) {
    if (!firstServed) {
      firstServed = true;
      return Future.value(RecommendedPetPage(
        items: [RecommendedPet(petId: 1, name: 'A', avatarUrl: '', petType: 'CAT', companionDays: 1)],
        nextCursor: 'c1',
        hasMore: true,
      ));
    }
    final c = Completer<RecommendedPetPage>();
    pending.add(c);
    return c.future;
  }
}

void main() {
  Future<(ProviderContainer, _GatedRepo, ProviderSubscription<AsyncValue<RecommendedPetPage>>)> setup() async {
    final repo = _GatedRepo();
    final container = ProviderContainer(overrides: [
      petRecommendationRepositoryProvider.overrideWithValue(repo),
    ]);
    final sub = container.listen(petRecommendationListProvider, (_, _) {});
    await container.read(petRecommendationListProvider.future);
    return (container, repo, sub);
  }

  test('loadMore 在途时页面关闭 → 请求成功回来不抛', () async {
    final (container, repo, sub) = await setup();
    final notifier = container.read(petRecommendationListProvider.notifier);

    final f = notifier.loadMore();
    sub.close();
    await Future<void>.delayed(Duration.zero); // 让 autoDispose 回收
    repo.pending.single.complete(RecommendedPetPage.empty);

    await expectLater(f, completes);
    container.dispose();
  });

  test('loadMore 在途时页面关闭 → 请求失败回来也不抛（catch 分支）', () async {
    final (container, repo, sub) = await setup();
    final notifier = container.read(petRecommendationListProvider.notifier);

    final f = notifier.loadMore();
    sub.close();
    await Future<void>.delayed(Duration.zero);
    repo.pending.single.completeError(Exception('boom'));

    await expectLater(f, completes);
    container.dispose();
  });

  test('retryFirstPage 在途时页面关闭 → 不抛', () async {
    final (container, repo, sub) = await setup();
    final notifier = container.read(petRecommendationListProvider.notifier);

    final f = notifier.retryFirstPage();
    sub.close();
    await Future<void>.delayed(Duration.zero);
    repo.pending.single.complete(RecommendedPetPage.empty);

    await expectLater(f, completes);
    container.dispose();
  });
}
