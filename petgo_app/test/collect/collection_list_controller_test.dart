import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/collect/data/collect_providers.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/collect/domain/collect_target.dart';
import 'package:tailtopia/features/collect/presentation/collection_list_controller.dart';

import 'collect_fakes.dart';

/// V1.4.0 ④ Story 2.4 · AC2.6 / AC6.1：列表 controller（首页、加载更多、失败、本地移除、信号重拉）。
void main() {
  late FakeCollectRepo repo;
  late ProviderContainer c;

  setUp(() {
    repo = FakeCollectRepo();
    c = ProviderContainer(overrides: [collectRepositoryProvider.overrideWithValue(repo)]);
  });

  /// autoDispose：保持订阅，否则 await 之间会被回收。先喂好 fake 再订阅（订阅即 build）。
  Future<void> start() async {
    c.listen(collectedPostsProvider, (_, _) {});
    await c.read(collectedPostsProvider.future);
  }
  tearDown(() => c.dispose());

  test('首页 → loadMore 用 nextCursor 追加 → 末页不再请求', () async {
    repo.postPages.addAll([
      (items: [visiblePost(1), visiblePost(2)], nextCursor: 'c1'),
      (items: [visiblePost(3)], nextCursor: null),
    ]);
    await start();
    final first = c.read(collectedPostsProvider).value!;
    expect(first.items.map((e) => e.postId), [1, 2]);
    expect(first.hasMore, isTrue);
    await c.read(collectedPostsProvider.notifier).loadMore();
    final s = c.read(collectedPostsProvider).value!;
    expect(s.items.map((e) => e.postId), [1, 2, 3]);
    expect(s.hasMore, isFalse);
    expect(repo.postCursors, [null, 'c1']);
    await c.read(collectedPostsProvider.notifier).loadMore();
    expect(repo.postFetches, 2);
  });

  test('loadMore 失败保留已加载 + loadMoreFailed；再次成功清标记', () async {
    repo.postPages.addAll([
      (items: [visiblePost(1)], nextCursor: 'c1'),
      (items: [visiblePost(2)], nextCursor: null),
    ]);
    await start();
    repo.loadMoreError = Exception('net');
    await c.read(collectedPostsProvider.notifier).loadMore();
    var s = c.read(collectedPostsProvider).value!;
    expect(s.items.length, 1);
    expect(s.loadMoreFailed, isTrue);
    repo.loadMoreError = null;
    await c.read(collectedPostsProvider.notifier).loadMore();
    s = c.read(collectedPostsProvider).value!;
    expect(s.items.length, 2);
    expect(s.loadMoreFailed, isFalse);
  });

  test('removeLocal 只删目标行、不发请求', () async {
    repo.postPages.add((items: [visiblePost(1), hiddenPost(2), visiblePost(3)], nextCursor: null));
    await start();
    c.read(collectedPostsProvider.notifier).removeLocal(2);
    expect(c.read(collectedPostsProvider).value!.items.map((e) => e.postId), [1, 3]);
    expect(repo.postFetches, 1);
  });

  test('收藏变更信号 bump → 重拉首页；场所按 placeToken 移除', () async {
    await start();
    c.read(collectionsRevisionProvider(CollectTarget.post).notifier).bump();
    await c.read(collectedPostsProvider.future);
    expect(repo.postFetches, 2);

    repo.placePages.add((items: [visiblePlace('a'), hiddenPlace('b')], nextCursor: null));
    c.listen(collectedPlacesProvider, (_, _) {});
    await c.read(collectedPlacesProvider.future);
    c.read(collectedPlacesProvider.notifier).removeLocal('b');
    expect(c.read(collectedPlacesProvider).value!.items.map((e) => e.placeToken), ['a']);
  });

  test('code-review：loadMore 在途时本地移除的行不会被拼回来', () async {
    repo.postPages.addAll([
      (items: [visiblePost(1), hiddenPost(2)], nextCursor: 'c1'),
      (items: [visiblePost(3)], nextCursor: null),
    ]);
    await start();
    final pending = c.read(collectedPostsProvider.notifier).loadMore();
    c.read(collectedPostsProvider.notifier).removeLocal(2);
    await pending;
    expect(c.read(collectedPostsProvider).value!.items.map((e) => e.postId), [1, 3]);
  });

  test('code-review：loadMore 在途时信号重拉 → 旧页结果被丢弃，不拼回新首页', () async {
    repo.postPages.addAll([
      (items: [visiblePost(1)], nextCursor: 'c1'),
    ]);
    await start();
    repo.postPages.addAll([
      (items: [visiblePost(9)], nextCursor: null), // loadMore 取到的旧第二页
      (items: [visiblePost(7)], nextCursor: null), // 重拉后的新首页
    ]);
    final pending = c.read(collectedPostsProvider.notifier).loadMore();
    c.read(collectionsRevisionProvider(CollectTarget.post).notifier).bump();
    await pending;
    await c.read(collectedPostsProvider.future);
    expect(c.read(collectedPostsProvider).value!.items.map((e) => e.postId), [7]);
  });
}
