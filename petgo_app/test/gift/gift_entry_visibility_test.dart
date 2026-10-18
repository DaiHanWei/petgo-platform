import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/domain/visitor_profile.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 gift Story 1.3 · AC1（L0）：送礼入口显示 / 不显示（A1 / A3 / A6 / 分享链接）。
void main() {
  group('A1 访客视图「Kirim Hadiah」胶囊', () {
    const visitor = VisitorProfile(name: 'Mochi', petType: 'CAT', ownerNickname: 'Rina');

    Future<void> pump(WidgetTester tester, ArchiveScope scope,
        {required FutureOr<PetProfile?> Function() mine}) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _Auth()),
          visitorProfileProvider(scope).overrideWith((ref) async => visitor),
          visitorStatsProvider(scope).overrideWith((ref) async => const ArchiveStats(
              happyMomentCount: 1, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
          visitorTimelineProvider(scope).overrideWith((ref) async => const TimelinePage(items: [])),
          petProfileProvider.overrideWith((ref) => mine()),
        ],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: VisitorArchiveView(scope: scope),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
    }

    PetProfile pet(int id) => PetProfile(id: id, name: 'Mine', cardToken: 'ct');

    testWidgets('他人的宠物 → 显示（标题行右侧、文案 Kirim Hadiah）', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => pet(7));
      expect(find.byKey(const ValueKey('giftSendPill')), findsOneWidget);
      expect(find.text('Kirim Hadiah'), findsOneWidget);
      expect(tester.getSize(find.byKey(const ValueKey('giftSendPill'))).height,
          greaterThanOrEqualTo(44), reason: '点击热区补到 44');
    });

    testWidgets('还没建宠物（petProfile = null）→ 显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => null);
      expect(find.byKey(const ValueKey('giftSendPill')), findsOneWidget);
    });

    testWidgets('自己的宠物 → 不显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => pet(42));
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });

    testWidgets('petProfileProvider 加载中 → 不显示（防自己的宠物闪现）', (tester) async {
      final never = Completer<PetProfile?>();
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () => never.future);
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });

    testWidgets('petProfileProvider 失败 → 不显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42),
          mine: () => Future<PetProfile?>.error(Exception('x')));
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });

    testWidgets('分享链接进入（petId == null）→ 本分支不显示', (tester) async {
      await pump(tester, const ArchiveScope.visitor('SHARE'), mine: () async => pet(7));
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });
  });

  group('A3 / A6 详情页礼物区', () {
    ContentDetail detail({String type = 'GROWTH_MOMENT', bool isAuthor = false, bool deleted = false}) =>
        ContentDetail(
          id: 5,
          authorId: 7,
          authorDeleted: deleted,
          authorNickname: deleted ? null : 'Alice',
          type: type,
          body: 'Momo hari ini',
          likeCount: 0,
          commentCount: 0,
          liked: false,
          isAuthor: isAuthor,
          createdAt: DateTime.utc(2026, 10, 1),
        );

    Future<void> pump(WidgetTester tester, ContentDetail d) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => const ContentDetailPage(postId: 5)),
      ]);
      addTearDown(router.dispose);
      // 照 content_detail_checkin_strip_test：容器在 widget 树之后释放（详情页的会话级 provider 在 dispose 里还要写状态）。
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

    Finder zone() => find.byKey(const ValueKey('giftDetailSendButton'));

    testWidgets('他人 Diary（GROWTH_MOMENT、非作者）→ 显示礼物区：HADIAH + Kirim Hadiah，在分隔线之前', (tester) async {
      await pump(tester, detail());
      expect(zone(), findsOneWidget);
      expect(find.text('HADIAH'), findsOneWidget);
      expect(find.text('Kirim Hadiah'), findsOneWidget);
      expect(tester.getSize(zone()).height, 44);
      final zoneY = tester.getTopLeft(zone()).dy;
      final commentsY = tester.getTopLeft(find.byKey(const ValueKey('detailCommentsTitle'))).dy;
      expect(zoneY, lessThan(commentsY));
    });

    testWidgets('自己的 Diary → 不显示', (tester) async {
      await pump(tester, detail(isAuthor: true));
      expect(zone(), findsNothing);
    });

    testWidgets('作者已注销 → 不显示', (tester) async {
      await pump(tester, detail(deleted: true));
      expect(zone(), findsNothing);
    });

    for (final type in ['DAILY', 'KNOWLEDGE']) {
      testWidgets('Moment / Tips（$type）→ 不显示', (tester) async {
        await pump(tester, detail(type: type));
        expect(zone(), findsNothing);
      });
    }

    testWidgets('底栏 _actions() 仍只有点赞 + 分享（不加送礼按钮）', (tester) async {
      await pump(tester, detail());
      final row = tester.widget<Row>(find.byKey(const ValueKey('detailBottomActions')));
      // 点赞热区 + 间距 + 分享热区。
      expect(row.children, hasLength(3));
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('detailBottomActions')), matching: find.byIcon(Icons.redeem)),
          findsNothing);
    });

    test('源码：礼物区插在打卡场所条之后、分隔线之前', () {
      final src = File('lib/features/content/presentation/content_detail_page.dart').readAsStringSync();
      final strip = src.indexOf('_DetailCheckinPlaceStrip(place: detail.checkinPlace!)');
      final gift = src.indexOf('GiftDetailZone(postId: detail.id)');
      final divider = src.indexOf('const Divider(height: AppSpacing.xl, color: AppColors.divider)');
      expect(gift, greaterThan(strip));
      expect(divider, greaterThan(gift));
    });
  });
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
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
