import 'dart:async';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';

/// V1.3.4 follow · Story 2.1 · AC3.3 / T-18（code-review #6）：四个数据源**在发请求前**读全局关注版本号。
///
/// 每个用例都在请求「在路上」时写一条覆盖（版本号 +1），断言快照记的是**请求前**的值 ——
/// 把读版本号挪到 await 之后，这里就会红。
FeedItem _item(int id) => FeedItem(id: id, authorId: 7, authorDeleted: false, type: 'DAILY', createdAt: DateTime.utc(2026));

class _FeedRepo implements FeedRepository {
  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) => throw UnimplementedError();

  Completer<FeedPage>? pending;
  Completer<PinnedSlot?>? pinned;

  @override
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20}) =>
      (pending = Completer<FeedPage>()).future;

  @override
  Future<PinnedSlot?> getPinnedSlot() => (pinned = Completer<PinnedSlot?>()).future;
}

class _DetailRepo implements DetailRepository {
  int calls = 0;
  Completer<ContentDetail>? pending;

  @override
  Future<ContentDetail> getDetail(int id) {
    calls++;
    return (pending = Completer<ContentDetail>()).future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

ContentDetail _detail() => ContentDetail(
    id: 5, authorId: 7, authorDeleted: false, type: 'DAILY', likeCount: 0, commentCount: 0,
    liked: false, isAuthor: false, createdAt: DateTime.utc(2026));

void main() {
  late ProviderContainer c;
  late _FeedRepo feed;
  late _DetailRepo detail;

  setUp(() {
    feed = _FeedRepo();
    detail = _DetailRepo();
    c = ProviderContainer(overrides: [
      feedRepositoryProvider.overrideWithValue(feed),
      detailRepositoryProvider.overrideWithValue(detail),
    ]);
  });
  tearDown(() => c.dispose());

  FollowStateNotifier follow() => c.read(followStateProvider.notifier);
  void bumpEpoch() => follow().set(99, FollowState.none);

  test('Feed 首屏：快照记的是发请求前的版本号', () async {
    final sub = c.listen(feedProvider, (_, _) {});
    addTearDown(sub.close);
    final before = follow().currentEpoch;
    await Future<void>.delayed(Duration.zero);
    bumpEpoch(); // 请求在路上时用户点了关注
    feed.pending!.complete(FeedPage(items: [_item(1)], nextCursor: 'c', hasMore: true));
    final state = await c.read(feedProvider.future);
    expect(state.items.single.followEpoch, before);
  });

  test('Feed 翻页：只给新页打新版本号，已在屏上的卡不变', () async {
    final sub = c.listen(feedProvider, (_, _) {});
    addTearDown(sub.close);
    await Future<void>.delayed(Duration.zero);
    final first = follow().currentEpoch;
    feed.pending!.complete(FeedPage(items: [_item(1)], nextCursor: 'c', hasMore: true));
    await c.read(feedProvider.future);

    bumpEpoch();
    final beforeMore = follow().currentEpoch;
    final more = c.read(feedProvider.notifier).loadMore();
    await Future<void>.delayed(Duration.zero);
    bumpEpoch();
    feed.pending!.complete(FeedPage(items: [_item(2)], hasMore: false));
    await more;

    final items = c.read(feedProvider).value!.items;
    expect(items[0].followEpoch, first);
    expect(items[1].followEpoch, beforeMore);
  });

  test('syncCounts 回写计数不丢版本号', () async {
    final sub = c.listen(feedProvider, (_, _) {});
    addTearDown(sub.close);
    await Future<void>.delayed(Duration.zero);
    bumpEpoch();
    feed.pending!.complete(FeedPage(items: [_item(1)], hasMore: false));
    await c.read(feedProvider.future);
    final epoch = c.read(feedProvider).value!.items.single.followEpoch;
    c.read(feedProvider.notifier).syncCounts(1, likeCount: 9);
    expect(c.read(feedProvider).value!.items.single.followEpoch, epoch);
  });

  test('顶置位：快照记的是发请求前的版本号', () async {
    final sub = c.listen(pinnedSlotProvider, (_, _) {});
    addTearDown(sub.close);
    final before = follow().currentEpoch;
    await Future<void>.delayed(Duration.zero);
    bumpEpoch();
    feed.pinned!.complete(PinnedSlot(pinConfigId: 1, pinType: 'CONTENT', item: _item(3)));
    final slot = await c.read(pinnedSlotProvider.future);
    expect(slot!.item!.followEpoch, before);
  });

  test('详情（T-18）：评论引起的重拉沿用进入时的版本号；重新进入才更新', () async {
    final sub = c.listen(detailProvider(5), (_, _) {});
    final entry = follow().currentEpoch;
    await Future<void>.delayed(Duration.zero);
    detail.pending!.complete(_detail());
    expect((await c.read(detailProvider(5).future)).followEpoch, entry);

    bumpEpoch(); // 用户在详情页点了关注
    c.invalidate(detailProvider(5)); // 发评论 → commentsRefresh → 重拉
    await Future<void>.delayed(Duration.zero);
    detail.pending!.complete(_detail());
    expect((await c.read(detailProvider(5).future)).followEpoch, entry, reason: '评论重拉不算刷新');
    expect(detail.calls, 2);

    // 离开再进入：autoDispose 后重新取进入版本号。
    sub.close();
    await Future<void>.delayed(Duration.zero);
    final sub2 = c.listen(detailProvider(5), (_, _) {});
    addTearDown(sub2.close);
    await Future<void>.delayed(Duration.zero);
    detail.pending!.complete(_detail());
    expect((await c.read(detailProvider(5).future)).followEpoch, follow().currentEpoch);
  });

  group('解析（AC2.3）', () {
    final base = {'id': 1, 'authorId': 7, 'createdAt': '2026-10-01T00:00:00Z'};

    test('FeedItem：新字段缺失 / null 用默认值', () {
      final a = FeedItem.fromJson(base);
      final b = FeedItem.fromJson({...base, 'authorToken': null, 'authorFollowState': null});
      for (final i in [a, b]) {
        expect(i.authorToken, isNull);
        expect(i.authorFollowState, FollowState.none);
        expect(i.followEpoch, 0);
      }
      final c = FeedItem.fromJson({
        ...base,
        'authorToken': 't',
        'authorFollowState': {'iFollow': false, 'followsMe': true},
      });
      expect(c.authorToken, 't');
      expect(c.authorFollowState, const FollowState(iFollow: false, followsMe: true));
    });

    test('ContentDetail：新字段缺失 / null 用默认值', () {
      final d = ContentDetail.fromJson({...base, 'authorFollowState': null});
      expect(d.authorToken, isNull);
      expect(d.authorFollowState, FollowState.none);
      expect(d.followEpoch, 0);
    });
  });

  /// AC3.7：顶置卡与普通卡分配不同的 surfaceKey（同一帖可能同屏出现两次）。
  test('FeedMasonryView 给顶置卡 / 普通卡分配不同的位置键', () {
    final src = File('lib/features/content/presentation/feed_view.dart').readAsStringSync();
    expect(src, contains("followSurfaceKey: 'feed:pinned:"));
    // V1.3.4 following 流：普通卡前缀可配（Discover 默认 `feed`，关注流 `following`），两条流同帖不串位置。
    expect(src, contains(r"followSurfaceKey: '${widget.followSurfacePrefix}:item:"));
    expect(src, contains("this.followSurfacePrefix = 'feed'"));
  });
}
