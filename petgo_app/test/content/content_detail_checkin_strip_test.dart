import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/place/domain/checkin_place_ref.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 1.5 · L0：帖子详情的打卡场所条（AC5.4）+ 成功页按钮行（AC2）。
void main() {
  ContentDetail detail(CheckinPlaceRef? place) => ContentDetail(
        id: 5,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Alice',
        type: 'GROWTH_MOMENT',
        body: 'Ngopi bareng Momo',
        likeCount: 0,
        commentCount: 0,
        liked: false,
        isAuthor: true,
        createdAt: DateTime.utc(2026, 9, 30),
        checkinPlace: place,
      );

  Future<void> pump(WidgetTester tester, ContentDetail d) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const ContentDetailPage(postId: 5)),
      GoRoute(path: '/places/:token', builder: (c, s) => Scaffold(body: Text('place ${s.uri}'))),
    ]);
    addTearDown(router.dispose);
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(_Repo(d))]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('ACTIVE：场所条可点 → 场所详情 from=post', (tester) async {
    await pump(tester, detail(const CheckinPlaceRef(token: 'pppp', name: 'Kopi Kucing', available: true)));
    expect(find.byKey(const ValueKey('detailCheckinPlaceStrip')), findsOneWidget);
    expect(find.text('Kopi Kucing'), findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('detailCheckinPlaceStrip'))).height,
        greaterThanOrEqualTo(44));

    await tester.tap(find.byKey(const ValueKey('detailCheckinPlaceStrip')));
    await tester.pumpAndSettle();
    expect(find.text('place /places/pppp?from=post'), findsOneWidget);
  });

  testWidgets('UNAVAILABLE：点击只出「Tempat yang kamu pilih sudah dihapus」、不跳转', (tester) async {
    await pump(tester, detail(const CheckinPlaceRef(token: 'pppp', name: 'Kopi Kucing', available: false)));
    await tester.tap(find.byKey(const ValueKey('detailCheckinPlaceStrip')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('Tempat yang kamu pilih sudah dihapus'), findsOneWidget);
    expect(find.textContaining('place /places'), findsNothing);
    await tester.pumpAndSettle(const Duration(seconds: 5));
  });

  testWidgets('复审：读屏器双击也能触发（Semantics 带 tap 动作）', (tester) async {
    final handle = tester.ensureSemantics();
    await pump(tester, detail(const CheckinPlaceRef(token: 'pppp', name: 'Kopi Kucing', available: true)));
    final node = tester.getSemantics(find.byKey(const ValueKey('detailCheckinPlaceStrip')));
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    handle.dispose();
  });

  testWidgets('普通帖不渲染场所条', (tester) async {
    await pump(tester, detail(null));
    expect(find.byKey(const ValueKey('detailCheckinPlaceStrip')), findsNothing);
  });

  test('源码：场所条在正文之后、分隔线之前', () {
    final src = File('lib/features/content/presentation/content_detail_page.dart').readAsStringSync();
    final strip = src.indexOf('_DetailCheckinPlaceStrip(place: detail.checkinPlace!)');
    final body = src.indexOf('MentionText(');
    final divider = src.indexOf('const Divider(height: AppSpacing.xl, color: AppColors.divider)');
    expect(strip, greaterThan(body));
    expect(divider, greaterThan(strip));
  });
}

class _Repo implements DetailRepository {
  _Repo(this.d);

  final ContentDetail d;

  @override
  Future<ContentDetail> getDetail(int id) async => d;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
