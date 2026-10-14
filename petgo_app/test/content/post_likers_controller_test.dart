import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/post_likers_repository.dart';
import 'package:tailtopia/features/content/presentation/post_likers_controller.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';

FollowListItem _u(int id) => FollowListItem(userId: id, deleted: false, nickname: 'u$id');

class _Repo implements PostLikersRepository {
  _Repo(this.handler);
  final Future<FollowListPageData> Function(String? cursor) handler;
  final cursors = <String?>[];

  @override
  Future<FollowListPageData> getLikers(int postId, {String? cursor}) {
    cursors.add(cursor);
    return handler(cursor);
  }
}

DioException _http(int code) => DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: code),
    );

ProviderContainer _c(_Repo repo) {
  final c = ProviderContainer(overrides: [postLikersRepositoryProvider.overrideWithValue(repo)]);
  addTearDown(c.dispose);
  return c;
}

void main() {
  test('loadsAndPaginates', () async {
    final repo = _Repo((cur) async => cur == null
        ? FollowListPageData(items: [_u(1), _u(2)], nextCursor: 'c1', hasMore: true)
        : FollowListPageData(items: [_u(3)]));
    final c = _c(repo);
    c.listen(postLikersProvider(9), (_, _) {});
    final s = await c.read(postLikersProvider(9).future);
    final epoch = c.read(followStateProvider.notifier).currentEpoch;
    expect(s.items.map((e) => e.userId), [1, 2]);
    expect(s.hasMore, isTrue);
    expect(s.items.first.followEpoch, epoch);

    await c.read(postLikersProvider(9).notifier).loadMore();
    final s2 = c.read(postLikersProvider(9)).value!;
    expect(s2.items.map((e) => e.userId), [1, 2, 3]);
    expect(s2.hasMore, isFalse);
    expect(repo.cursors, [null, 'c1']);

    await c.read(postLikersProvider(9).notifier).loadMore(); // 无更多：不再请求
    expect(repo.cursors.length, 2);
  });

  test('goneOn404', () async {
    final c = _c(_Repo((_) async => throw _http(404)));
    c.listen(postLikersProvider(9), (_, _) {});
    final s = await c.read(postLikersProvider(9).future);
    expect(s.gone, isTrue);
    expect(s.items, isEmpty);
  });

  test('errorThenRetry', () async {
    var fail = true;
    final c = _c(_Repo((_) async {
      if (fail) throw _http(500);
      return FollowListPageData(items: [_u(1)]);
    }));
    c.listen(postLikersProvider(9), (_, _) {});
    await expectLater(c.read(postLikersProvider(9).future), throwsA(isA<DioException>()));
    expect(c.read(postLikersProvider(9)).hasError, isTrue);

    fail = false;
    await c.read(postLikersProvider(9).notifier).retry();
    final s = c.read(postLikersProvider(9)).value!;
    expect(s.items.single.userId, 1);
    expect(s.gone, isFalse);
  });

  test('loadMoreFailureKeepsItems', () async {
    final c = _c(_Repo((cur) async => cur == null
        ? FollowListPageData(items: [_u(1)], nextCursor: 'c1', hasMore: true)
        : throw _http(500)));
    c.listen(postLikersProvider(9), (_, _) {});
    await c.read(postLikersProvider(9).future);
    await c.read(postLikersProvider(9).notifier).loadMore();
    final s = c.read(postLikersProvider(9)).value!;
    expect(s.items.length, 1);
    expect(s.loadMoreFailed, isTrue);
    expect(s.loadingMore, isFalse);
  });
}
