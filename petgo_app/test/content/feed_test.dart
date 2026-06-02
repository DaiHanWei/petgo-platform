import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/content/data/feed_repository.dart';
import 'package:petgo/features/content/domain/feed_item.dart';
import 'package:petgo/features/content/presentation/feed_tab_row.dart';
import 'package:petgo/features/content/presentation/home_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/masonry_card.dart';
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
}
