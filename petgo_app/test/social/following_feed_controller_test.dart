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
}
