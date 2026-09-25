import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/presentation/place_comment_section.dart';
import 'package:tailtopia/features/place/presentation/place_comments_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260924-569：场地详情页的评论要显示评论时间。
///
/// 口径与帖子评论同一个 `formatPublishTime`：7 天内相对时间、超 7 天绝对日期。
class _FakeComments extends PlaceCommentsController {
  _FakeComments(super.token, this.page);

  final PlaceCommentPage page;

  @override
  Future<PlaceCommentPage> build() async => page;
}

void main() {
  PlaceComment comment(int id, {DateTime? createdAt, bool mine = false, String body = 'Tempatnya nyaman'}) =>
      PlaceComment(
        id: id,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Budi',
        body: body,
        mine: mine,
        moderation: PlaceCommentModeration.visible,
        createdAt: createdAt,
      );

  Widget host(List<PlaceComment> items) => ProviderScope(
        overrides: [
          placeCommentsProvider('p1').overrideWith(() => _FakeComments(
              'p1', PlaceCommentPage(items: items, hasMore: false, total: items.length))),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('en'),
          home: Scaffold(body: SingleChildScrollView(child: PlaceCommentSection(token: 'p1'))),
        ),
      );

  testWidgets('评论行显示相对时间', (tester) async {
    await tester.pumpWidget(host([
      comment(1, createdAt: DateTime.now().subtract(const Duration(hours: 3))),
    ]));
    await tester.pumpAndSettle();

    final time = tester.widget<Text>(find.byKey(const ValueKey('placeCommentTime-1')));
    expect(time.data, '3 h ago');
  });

  testWidgets('超过 7 天显示日期而不是「N 天前」', (tester) async {
    await tester.pumpWidget(host([
      comment(2, createdAt: DateTime(2026, 1, 5, 10)),
    ]));
    await tester.pumpAndSettle();

    final time = tester.widget<Text>(find.byKey(const ValueKey('placeCommentTime-2')));
    expect(time.data, isNot(contains('ago')));
    expect(time.data, contains('2026'));
  });

  testWidgets('时间缺失时不渲染时间位（不编一个）', (tester) async {
    await tester.pumpWidget(host([comment(3)]));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('placeCommentTime-3')), findsNothing);
    expect(find.text('Tempatnya nyaman'), findsOneWidget);
  });

  // bug 20260924-571：本人评论（带删除钮）与他人评论的「昵称 → 正文」间距必须一致。
  // 原先删除钮在昵称行里、44 高的热区把那一行撑高，只有本人的评论中间多出一大段空。
  testWidgets('本人评论与他人评论的昵称→正文间距一致；删除钮热区仍 ≥ 44', (tester) async {
    await tester.pumpWidget(host([
      comment(10, mine: true, body: 'punyaku'),
      comment(11, body: 'punya orang'),
    ]));
    await tester.pumpAndSettle();

    double gap(String body) {
      final names = find.text('Budi');
      final b = tester.getTopLeft(find.text(body)).dy;
      // 取正文正上方那一个昵称
      final nameBottom = names.evaluate()
          .map((e) => tester.getBottomLeft(find.byElementPredicate((x) => x == e)).dy)
          .where((y) => y <= b)
          .reduce((a, c) => a > c ? a : c);
      return b - nameBottom;
    }

    expect(gap('punyaku'), closeTo(gap('punya orang'), 0.5),
        reason: '本人评论的昵称行不得被删除钮撑高');
    final del = tester.getSize(find.byKey(const ValueKey('placeCommentDelete-10')));
    expect(del.width, greaterThanOrEqualTo(44));
    expect(del.height, greaterThanOrEqualTo(44));
    expect(find.byKey(const ValueKey('placeCommentDelete-11')), findsNothing, reason: '他人评论没有删除钮');
  });
}
