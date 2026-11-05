import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/following_feed_controller.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';

FeedItem _item(int id) =>
    FeedItem(id: id, authorId: 7, authorDeleted: false, type: 'DAILY', createdAt: DateTime.utc(2026));

class _Repo implements FeedRepository {
  int calls = 0;
  final cursors = <String?>[];
  List<List<int>> pages = [
    [1, 2],
    [3],
  ];

  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) async {
    cursors.add(cursor);
    final idx = cursor == null ? 0 : int.parse(cursor);
    if (cursor == null) calls++;
    final ids = pages[idx.clamp(0, pages.length - 1)];
    final more = idx + 1 < pages.length;
    return FeedPage(
      items: [for (final i in ids) _item(i)],
      nextCursor: more ? '${idx + 1}' : null,
      hasMore: more,
      rankMode: 'chrono',
      // V1.4.2 Story 1.5：计数只首页下发。
      followingUserCount: cursor == null ? 4 : null,
      followingPetCount: cursor == null ? 2 : null,
    );
  }

  @override
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20}) =>
      throw UnimplementedError();

  @override
  Future<PinnedSlot?> getPinnedSlot() async => null;
}

void main() {
  late _Repo repo;
  late ProviderContainer c;

  setUp(() {
    repo = _Repo();
    c = ProviderContainer(overrides: [feedRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(c.dispose);
  });

  test('loadsFirstPageAndMore', () async {
    final first = await c.read(followingFeedProvider.future);
    expect(first.items.map((i) => i.id), [1, 2]);
    expect(first.hasMore, isTrue);
    expect(first.category, FeedCategory.all);

    await c.read(followingFeedProvider.notifier).loadMore();
    final s = c.read(followingFeedProvider).value!;
    expect(s.items.map((i) => i.id), [1, 2, 3]);
    expect(s.hasMore, isFalse);
    expect(s.pagesLoaded, 2);
    expect(repo.cursors, [null, '1']);
  });

  test('refreshReplacesItems', () async {
    await c.read(followingFeedProvider.future);
    repo.pages = [
      [9]
    ];
    await c.read(followingFeedProvider.notifier).refresh();
    final s = c.read(followingFeedProvider).value!;
    expect(s.items.map((i) => i.id), [9]);
    expect(s.hasMore, isFalse);
  });

  test('removeByAuthor 与 removeItem 本地移除', () async {
    await c.read(followingFeedProvider.future);
    c.read(followingFeedProvider.notifier).removeItem(1);
    expect(c.read(followingFeedProvider).value!.items.map((i) => i.id), [2]);
    c.read(followingFeedProvider.notifier).removeByAuthor(7);
    expect(c.read(followingFeedProvider).value!.items, isEmpty);
  });

  testWidgets('resetUserScopedCachesInvalidatesFollowing', (tester) async {
    late WidgetRef captured;
    await tester.pumpWidget(ProviderScope(
      overrides: [feedRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        home: Consumer(builder: (context, ref, _) {
          captured = ref;
          ref.watch(followingFeedProvider);
          return const SizedBox();
        }),
      ),
    ));
    await tester.pumpAndSettle();
    expect(repo.calls, 1);
    resetUserScopedCaches(captured);
    await tester.pumpAndSettle();
    expect(repo.calls, 2);
  });

  test('FollowListItem 解析 petType / petBirthday（缺省为 null）', () {
    final a = FollowListItem.fromJson({
      'userId': 1,
      'petType': 'CAT',
      'petBirthday': '2024-03-05',
    });
    expect(a.petType, 'CAT');
    expect(a.petBirthday, DateTime(2024, 3, 5));
    final b = FollowListItem.fromJson({'userId': 2});
    expect(b.petType, isNull);
    expect(b.petBirthday, isNull);
  });

  test('V1.4.2 Story 1.5 · AC5.2：首页解析两个计数；加载更多不覆盖首页计数', () async {
    final first = await c.read(followingFeedProvider.future);
    expect(first.followingUserCount, 4);
    expect(first.followingPetCount, 2);

    await c.read(followingFeedProvider.notifier).loadMore();
    final s = c.read(followingFeedProvider).value!;
    expect(s.items.map((i) => i.id), [1, 2, 3]);
    expect(s.followingUserCount, 4);
    expect(s.followingPetCount, 2);
  });

  test('V1.4.2 Story 1.5：FeedPage / FeedItem 解析 reason 与两个计数；缺键 null；copyWith 保留 reason', () {
    final page = FeedPage.fromJson({
      'items': [
        {'id': 1, 'authorId': 7, 'type': 'DAILY', 'createdAt': '2026-10-01T00:00:00Z', 'reason': 'followed_pet'},
        {'id': 2, 'authorId': 7, 'type': 'DAILY', 'createdAt': '2026-10-01T00:00:00Z'},
      ],
      'hasMore': false,
      'followingUserCount': 1,
      'followingPetCount': 0,
    });
    expect(page.items.first.reason, 'followed_pet');
    expect(page.items.first.copyWith(likeCount: 3).reason, 'followed_pet');
    expect(page.items.last.reason, isNull);
    expect(page.followingUserCount, 1);
    expect(page.followingPetCount, 0);
    final old = FeedPage.fromJson({'items': [], 'hasMore': false});
    expect(old.followingUserCount, isNull);
    expect(old.followingPetCount, isNull);
  });
}
