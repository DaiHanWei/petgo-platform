import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// L0：Feed 卡正文里点话题 vs 点整块（V1.4.2 social-topic · Story 4.4 · AC2.4）。
/// 同 `mention_tap_priority_test` 写法：TextSpan 上的 recognizer 命中更深，同一次点击只有一个赢家。
void main() {
  FeedItem item(String body, {List<String> topicNames = const ['kucing']}) => FeedItem(
        id: 1,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Author',
        type: 'DAILY',
        body: body,
        createdAt: DateTime.utc(2026, 11, 5),
        topicNames: topicNames,
      );

  Future<void> pumpCard(WidgetTester tester, FeedItem feedItem,
      {required VoidCallback onTap, void Function(String)? onTapTopic}) async {
    // V1.5.0 FR-210：卡片里的 LikeButton 读 postLikeStoreProvider，需要 ProviderScope（断言不变）。
    await tester.pumpWidget(ProviderScope(child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: MasonryCard(item: feedItem, deletedUserLabel: 'x', onTap: onTap, onTapTopic: onTapTopic),
      ),
    )));
    await tester.pumpAndSettle();
  }

  testWidgets('点正文话题 → 只走 onTapTopic（标准名），不进详情', (tester) async {
    String? topic;
    var cardTapped = false;
    await pumpCard(tester, item('halo #Kucing apa kabar'),
        onTap: () => cardTapped = true, onTapTopic: (n) => topic = n);
    await tester.tapOnText(find.textRange.ofSubstring('#Kucing'));
    await tester.pumpAndSettle();
    expect(topic, 'kucing');
    expect(cardTapped, isFalse);
  });

  testWidgets('点正文其余位置 → 照旧进详情', (tester) async {
    String? topic;
    var cardTapped = false;
    await pumpCard(tester, item('halo #Kucing apa kabar'),
        onTap: () => cardTapped = true, onTapTopic: (n) => topic = n);
    await tester.tapOnText(find.textRange.ofSubstring('apa kabar'));
    await tester.pumpAndSettle();
    expect(cardTapped, isTrue);
    expect(topic, isNull);
  });

  testWidgets('不在 topicNames 的话题 → 只是整块进详情', (tester) async {
    String? topic;
    var cardTapped = false;
    await pumpCard(tester, item('halo #Kucing', topicNames: const []),
        onTap: () => cardTapped = true, onTapTopic: (n) => topic = n);
    await tester.tapOnText(find.textRange.ofSubstring('#Kucing'));
    await tester.pumpAndSettle();
    expect(cardTapped, isTrue);
    expect(topic, isNull);
  });

  test('FeedItem.fromJson：topicNames 缺键 → 空表；有值照读', () {
    final base = {'id': 1, 'authorId': 2, 'createdAt': '2026-11-05T00:00:00Z'};
    expect(FeedItem.fromJson(base).topicNames, isEmpty);
    expect(FeedItem.fromJson({...base, 'topicNames': ['kucing', 'lucu']}).topicNames, ['kucing', 'lucu']);
    expect(FeedItem.fromJson({...base, 'topicNames': ['a']}).copyWith(likeCount: 3).topicNames, ['a']);
  });

  test('ContentDetail.fromJson：topicNames 缺键 → 空表；withFollowEpoch 不丢', () {
    final base = {'id': 1, 'authorId': 2, 'createdAt': '2026-11-05T00:00:00Z'};
    expect(ContentDetail.fromJson(base).topicNames, isEmpty);
    final d = ContentDetail.fromJson({...base, 'topicNames': ['kucing']});
    expect(d.topicNames, ['kucing']);
    expect(d.withFollowEpoch(3).topicNames, ['kucing']);
  });
}
