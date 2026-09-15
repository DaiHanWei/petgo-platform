import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/mention/domain/mention_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// L0：点 @ 与「点整块」是两个手势，不许打架
/// （V1.3.0 batch-b1 Story 3.3 · AC2）。
///
/// <h3>🔴 为什么这条必须有用例</h3>
/// Feed 卡片的正文区裹在一个 `GestureDetector(onTap: 进详情)` 里，评论正文裹在
/// 「点整条评论 = 回复」的手势里。@ 高亮片段的 `TapGestureRecognizer` 与它们
/// **在同一个手势竞技场里竞争** —— 如果判给了外层，点 @ 就变成了「进详情 / 弹回复框」，
/// AC2 整条失效；如果两个都触发，用户会看到主页压在详情页上面。
/// 这是那段代码注释里唯一一句"靠框架行为"的断言，必须钉住。
void main() {
  FeedItem item({required String body, List<MentionView> mentions = const []}) => FeedItem(
        id: 1,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Author',
        type: 'DAILY',
        body: body,
        createdAt: DateTime.utc(2026, 9, 15),
        mentions: mentions,
      );

  Future<void> pumpCard(
    WidgetTester tester, {
    required FeedItem feedItem,
    required VoidCallback onTap,
    required void Function(int userId)? onTapMention,
  }) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: MasonryCard(
          item: feedItem,
          deletedUserLabel: '已注销用户',
          onTap: onTap,
          onTapMention: onTapMention,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('点正文里的 @ → 只走 @ 的跳转，**不**顺带进详情页', (tester) async {
    int? mentioned;
    var cardTapped = false;
    await pumpCard(
      tester,
      feedItem: item(
        body: 'halo @Aurel',
        mentions: const [MentionView(userId: 42, nickname: 'Aurel', tappable: true)],
      ),
      onTap: () => cardTapped = true,
      onTapMention: (id) => mentioned = id,
    );

    await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
    await tester.pumpAndSettle();

    expect(mentioned, 42);
    expect(cardTapped, isFalse, reason: '同一次点击不该既跳主页又进详情页');
  });

  testWidgets('点正文里**不是** @ 的地方 → 照旧整块进详情页', (tester) async {
    int? mentioned;
    var cardTapped = false;
    await pumpCard(
      tester,
      feedItem: item(
        body: 'halo @Aurel apa kabar semua',
        mentions: const [MentionView(userId: 42, nickname: 'Aurel', tappable: true)],
      ),
      onTap: () => cardTapped = true,
      onTapMention: (id) => mentioned = id,
    );

    await tester.tapOnText(find.textRange.ofSubstring('apa kabar'));
    await tester.pumpAndSettle();

    expect(cardTapped, isTrue, reason: '正文非 @ 区域的既有行为一字不能变');
    expect(mentioned, isNull);
  });

  testWidgets('后端说不可点（拉黑 / 注销）→ 那处 @ 也只是整块进详情', (tester) async {
    int? mentioned;
    var cardTapped = false;
    await pumpCard(
      tester,
      feedItem: item(
        body: 'halo @Aurel',
        mentions: const [MentionView(userId: 42, tappable: false)],
      ),
      onTap: () => cardTapped = true,
      onTapMention: (id) => mentioned = id,
    );

    await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
    await tester.pumpAndSettle();

    expect(mentioned, isNull);
    expect(cardTapped, isTrue);
  });

  testWidgets('共享件：调用方不传 onTapMention → 正文里的 @ 一律普通文字', (tester) async {
    var cardTapped = false;
    await pumpCard(
      tester,
      feedItem: item(
        body: 'halo @Aurel',
        mentions: const [MentionView(userId: 42, nickname: 'Aurel', tappable: true)],
      ),
      onTap: () => cardTapped = true,
      onTapMention: null,
    );

    // 🛡 本组件是共享件：没有跳转语义的调用方不该凭空多一个能点的片段。
    expect(find.text('halo @Aurel'), findsOneWidget);
    await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
    await tester.pumpAndSettle();
    expect(cardTapped, isTrue);
  });
}
