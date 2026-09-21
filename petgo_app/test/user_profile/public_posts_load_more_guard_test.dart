import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_user_posts_controller.dart';

/// batch-b1 复审 F4：他人主页「加载更多」连点 —— 同一游标发两次会把同一页追加两遍
/// （重复格子 + 重复 ValueKey('postGridTile_$id')）。
class _Repo implements PublicUserPostsRepository {
  final pending = <Completer<PublicUserPostPage>>[];

  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) {
    if (cursor == null) {
      return Future.value(const PublicUserPostPage(
          items: [PublicUserPost(id: 1, type: 'DAILY')], hasMore: true, nextCursor: 'c1'));
    }
    final c = Completer<PublicUserPostPage>();
    pending.add(c);
    return c.future;
  }
}

void main() {
  test('在途时再点 → 只发一次请求、只追加一次', () async {
    final repo = _Repo();
    final c = ProviderContainer(
        overrides: [publicUserPostsRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(c.dispose);
    final sub = c.listen(publicUserPostsProvider(7), (_, _) {});
    addTearDown(sub.close);
    await c.read(publicUserPostsProvider(7).future);

    final n = c.read(publicUserPostsProvider(7).notifier);
    final a = n.loadMore();
    final b = n.loadMore();
    expect(repo.pending, hasLength(1));

    repo.pending.single.complete(const PublicUserPostPage(
        items: [PublicUserPost(id: 2, type: 'DAILY')], hasMore: false));
    await Future.wait([a, b]);

    expect(c.read(publicUserPostsProvider(7)).value!.items.map((e) => e.id), [1, 2]);
  });
}
