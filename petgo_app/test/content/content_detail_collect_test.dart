import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ④ Story 2.3 · AC1 / AC2 / AC5：详情模型收藏字段 + 底栏收藏按钮位置 / 私密帖隐藏 / 游客可见。
class _Repo implements DetailRepository {
  _Repo(this.detail);

  final ContentDetail detail;

  @override
  Future<ContentDetail> getDetail(int id) async => detail;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

ContentDetail _detail({String visibility = 'PUBLIC', bool isAuthor = false, int? collectCount}) => ContentDetail(
      id: 5,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      visibility: visibility,
      body: 'A lovely pet day',
      likeCount: 3,
      commentCount: 0,
      liked: false,
      isAuthor: isAuthor,
      createdAt: DateTime.utc(2026, 6, 2),
      collected: true,
      collectCount: collectCount,
    );

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

void main() {
  group('模型', () {
    final base = <String, dynamic>{
      'id': 5,
      'authorId': 7,
      'type': 'DAILY',
      'createdAt': '2026-06-02T00:00:00Z',
    };

    test('fromJson 解析 collected / collectCount；缺键为 false / null', () {
      final d = ContentDetail.fromJson({...base, 'collected': true, 'collectCount': 3});
      expect(d.collected, isTrue);
      expect(d.collectCount, 3);
      final missing = ContentDetail.fromJson(base);
      expect(missing.collected, isFalse);
      expect(missing.collectCount, isNull);
    });

    test('🔴 withFollowEpoch 后两字段保留', () {
      final d = ContentDetail.fromJson({...base, 'collected': true, 'collectCount': 2}).withFollowEpoch(9);
      expect(d.collected, isTrue);
      expect(d.collectCount, 2);
      expect(d.followEpoch, 9);
    });
  });

  Future<void> pump(WidgetTester tester, ContentDetail detail, {bool loggedIn = true}) async {
    tester.view.physicalSize = const Size(1000, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(_Repo(detail))]);
    addTearDown(container.dispose);
    if (loggedIn) container.read(authControllerProvider.notifier).applyLogin(_user());
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: ContentDetailPage(postId: 5),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('公开帖：收藏按钮在分享卡之后', (tester) async {
    await pump(tester, _detail());
    final collect = find.byKey(const ValueKey('detailCollectButton'));
    expect(collect, findsOneWidget);
    expect(tester.getCenter(collect).dx,
        greaterThan(tester.getCenter(find.byKey(const ValueKey('detailShareCardIcon'))).dx));
  });

  testWidgets('私密帖：无收藏按钮，分享卡仍在', (tester) async {
    await pump(tester, _detail(visibility: 'PRIVATE'));
    expect(find.byKey(const ValueKey('detailCollectButton')), findsNothing);
    expect(find.byKey(const ValueKey('detailShareCardIcon')), findsOneWidget);
  });

  testWidgets('游客只读态也有收藏按钮', (tester) async {
    await pump(tester, _detail(), loggedIn: false);
    expect(find.byKey(const ValueKey('detailCollectButton')), findsOneWidget);
  });

  testWidgets('作者：数字 + 锁', (tester) async {
    await pump(tester, _detail(isAuthor: true, collectCount: 2));
    expect(find.byKey(const ValueKey('detailCollectCount')), findsOneWidget);
    expect(find.byKey(const ValueKey('detailCollectLock')), findsOneWidget);
  });

  testWidgets('非作者即便响应里有 collectCount 也不显示', (tester) async {
    await pump(tester, _detail(isAuthor: false, collectCount: 2));
    expect(find.byKey(const ValueKey('detailCollectCount')), findsNothing);
  });
}
