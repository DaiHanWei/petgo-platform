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
  PlaceComment comment(int id, {DateTime? createdAt}) => PlaceComment(
        id: id,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Budi',
        body: 'Tempatnya nyaman',
        mine: false,
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
}
