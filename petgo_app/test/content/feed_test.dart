import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/feed_view.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';
import 'package:shared_preferences/shared_preferences.dart';

FeedItem _item({
  int id = 1,
  bool deleted = false,
  String? nickname = 'Alice',
  String? body = 'Hello pets',
  String? image,
  String type = 'DAILY',
}) =>
    FeedItem(
      id: id,
      authorId: 7,
      authorDeleted: deleted,
      authorNickname: nickname,
      authorAvatarUrl: null,
      type: type,
      body: body,
      firstImageUrl: image,
      createdAt: DateTime.utc(2026, 6, 2),
    );

/// 可注入的 fake：返回固定 items（一次性，hasMore=false）。
class _FakeFeedRepo implements FeedRepository {
  _FakeFeedRepo(this.items);
  final List<FeedItem> items;

  @override
  Future<FeedPage> getFeed({
    FeedCategory category = FeedCategory.all,
    String? cursor,
    int limit = 20,
  }) async =>
      FeedPage(items: items, nextCursor: null, hasMore: false);
}

/// 脚本化 fake：由注入函数决定每次 getFeed 的结果（含抛错），用于 AC5 失败态。
class _ScriptedFeedRepo implements FeedRepository {
  _ScriptedFeedRepo(this.handler);
  final Future<FeedPage> Function(FeedCategory category, String? cursor) handler;

  @override
  Future<FeedPage> getFeed({
    FeedCategory category = FeedCategory.all,
    String? cursor,
    int limit = 20,
  }) =>
      handler(category, cursor);
}

Future<void> _pumpHome(WidgetTester tester, List<FeedItem> items) async {
  final container = ProviderContainer(overrides: [
    feedRepositoryProvider.overrideWithValue(_FakeFeedRepo(items)),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: HomePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('MasonryCard 纯文字卡渲染昵称与正文（无点赞评论数）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: MasonryCard(item: _item(body: 'doggo day'), deletedUserLabel: 'Deleted user'),
      ),
    ));
    await tester.pump();
    expect(find.text('doggo day'), findsOneWidget);
    expect(find.text('Alice'), findsOneWidget);
  });

  testWidgets('MasonryCard 注销作者显示占位文案 + 默认头像', (tester) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: MasonryCard(
          item: _item(deleted: true, nickname: null),
          deletedUserLabel: 'Deleted user',
        ),
      ),
    ));
    await tester.pump();
    expect(find.text('Deleted user'), findsOneWidget);
    expect(find.byIcon(Icons.person_rounded), findsOneWidget); // 默认头像
  });

  testWidgets('FeedTabRow 高亮选中并回调切换', (tester) async {
    FeedCategory? tapped;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: FeedTabRow(
          selected: FeedCategory.all,
          labels: const {
            FeedCategory.all: 'All',
            FeedCategory.daily: 'Daily',
            FeedCategory.growthMoment: 'Growth',
            FeedCategory.knowledge: 'Tips',
          },
          onSelected: (c) => tapped = c,
        ),
      ),
    ));
    await tester.pump();
    expect(find.text('All'), findsOneWidget);
    await tester.tap(find.text('Daily'));
    expect(tapped, FeedCategory.daily);
  });

  testWidgets('AC2: Feed 有内容 → 渲染瀑布卡片', (tester) async {
    await _pumpHome(tester, [_item(id: 1, body: 'first'), _item(id: 2, body: 'second')]);
    expect(find.byType(MasonryCard), findsNWidgets(2));
    expect(find.text('first'), findsOneWidget);
  });

  testWidgets('AC4: Feed 空 → 空状态 + 发布 CTA', (tester) async {
    await _pumpHome(tester, const []);
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.feedEmptyTitle), findsOneWidget);
    expect(find.byKey(const ValueKey('emptyStateAction')), findsOneWidget);
  });

  // ===== AC5 加载失败态（F13） =====

  test('AC5: 首屏加载失败 → AsyncError；重试重建首屏成功', () async {
    var fail = true;
    final repo = _ScriptedFeedRepo((cat, cursor) async {
      if (fail) throw Exception('boom');
      return FeedPage(items: [_item(id: 1, body: 'recovered')], nextCursor: null, hasMore: false);
    });
    final container = ProviderContainer(overrides: [
      feedRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);

    // 监听以驱动首屏 build 并吸收错误（避免未捕获错误使测试失败）。
    container.listen(feedProvider, (_, _) {}, onError: (_, _) {}, fireImmediately: true);

    // 首屏失败 → provider 进入 AsyncError（home_page 据此渲染失败态 + 重试入口）。
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(container.read(feedProvider).hasError, isTrue);

    // 重试（下拉刷新 / 重试按钮 → invalidate 重建首屏）：网络恢复后取得内容。
    fail = false;
    container.invalidate(feedProvider);
    await Future<void>.delayed(const Duration(milliseconds: 50));
    final s = container.read(feedProvider).value;
    expect(s, isNotNull);
    expect(s!.items.map((e) => e.id), [1]);
    expect(s.items.first.body, 'recovered');
  });

  test('AC5: loadMore 失败 → 保留已加载 + loadMoreFailed；重试沿用 nextCursor 续拉', () async {
    var moreCalls = 0;
    final repo = _ScriptedFeedRepo((cat, cursor) async {
      if (cursor == null) {
        return FeedPage(items: [_item(id: 1)], nextCursor: 'c1', hasMore: true);
      }
      moreCalls++;
      if (moreCalls == 1) throw Exception('boom'); // 首次增量失败
      return FeedPage(items: [_item(id: 2)], nextCursor: null, hasMore: false); // 重试成功
    });
    final container = ProviderContainer(overrides: [
      feedRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);

    final initial = await container.read(feedProvider.future);
    expect(initial.items.map((e) => e.id), [1]);
    expect(initial.hasMore, isTrue);

    await container.read(feedProvider.notifier).loadMore(); // 失败
    var s = container.read(feedProvider).value!;
    expect(s.loadMoreFailed, isTrue);
    expect(s.items.map((e) => e.id), [1]); // 已加载保留
    expect(s.nextCursor, 'c1'); // 游标沿用

    await container.read(feedProvider.notifier).loadMore(); // 底部重试
    s = container.read(feedProvider).value!;
    expect(s.loadMoreFailed, isFalse);
    expect(s.items.map((e) => e.id), [1, 2]); // 续拉追加
  });

  testWidgets('AC5: FeedMasonryView loadMoreFailed → 底部重试按钮可点', (tester) async {
    var retried = false;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: FeedMasonryView(
          items: [_item(id: 1, body: 'kept')],
          hasMore: true,
          loadingMore: false,
          loadMoreFailed: true,
          loadMoreErrorLabel: 'tap retry',
          deletedUserLabel: 'x',
          onLoadMore: () async => retried = true,
          onRefresh: () async {},
        ),
      ),
    ));
    await tester.pump();
    expect(find.text('kept'), findsOneWidget); // 已加载内容仍在
    expect(find.byKey(const ValueKey('feedLoadMoreRetry')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('feedLoadMoreRetry')));
    expect(retried, isTrue);
  });
}
