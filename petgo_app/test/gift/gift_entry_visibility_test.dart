import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_order_browse.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
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
  group('A1 访客视图「Kirim Hadiah」按钮（V1.5.0 起与 Ikuti 并排）', () {
    const visitor = VisitorProfile(name: 'Mochi', petType: 'CAT', ownerNickname: 'Rina');

    Future<void> pump(WidgetTester tester, ArchiveScope scope,
        {required FutureOr<PetProfile?> Function() mine, Future<List<PetListItem>> Function()? myPets}) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _Auth()),
          visitorProfileProvider(scope).overrideWith((ref) async => visitor),
          visitorStatsProvider(scope).overrideWith((ref) async => const ArchiveStats(
              happyMomentCount: 1, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
          visitorTimelineProvider(scope).overrideWith((ref) async => const TimelinePage(items: [])),
          petProfileProvider.overrideWith((ref) => mine()),
          // V1.4.2 social-topic 1.4 复审：「自己的宠物」同时比对我名下全部宠物（多宠）。
          petListProvider.overrideWith((ref) => myPets?.call() ?? Future.value(const <PetListItem>[])),
          giftPreviewProvider(42).overrideWith((ref) async => const GiftPreview()),
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
      // petListProvider 在档案就绪的分支里才首次被 watch → 再给一帧让它落值。
      await tester.pump();
    }

    PetProfile pet(int id) => PetProfile(id: id, name: 'Mine', cardToken: 'ct');

    testWidgets('他人的宠物 → 信息卡内与 Ikuti 并排（各占一半、高 40）', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => pet(7));
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsOneWidget);
      expect(find.text('Kirim Hadiah'), findsOneWidget);
      expect(tester.getSize(find.byKey(const ValueKey('giftDiarySendButton'))).height, 40);
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing, reason: '标题行胶囊去掉');
      final send = tester.getRect(find.byKey(const ValueKey('giftDiarySendButton')));
      final follow = tester.getRect(find.byKey(const ValueKey('petFollowButton')));
      expect(send.width, closeTo(follow.width, 1));
      expect(send.left - follow.right, closeTo(8, 0.5));
      expect(find.byKey(const ValueKey('giftDiaryRow')), findsOneWidget, reason: 'A1 礼物行');
    });

    testWidgets('还没建宠物（petProfile = null）→ 显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => null);
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsOneWidget);
    });

    testWidgets('自己的宠物 → 不显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => pet(42));
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
    });

    testWidgets('petProfileProvider 加载中 → 不显示（防自己的宠物闪现）', (tester) async {
      final never = Completer<PetProfile?>();
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () => never.future);
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
    });

    testWidgets('多宠：我名下另一只宠物（当前宠物不是它）→ 不显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42),
          mine: () async => pet(7),
          myPets: () async => const [PetListItem(id: 7, name: 'A'), PetListItem(id: 42, name: 'B')]);
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
    });

    testWidgets('petListProvider 加载中 → 不显示', (tester) async {
      final never = Completer<List<PetListItem>>();
      await pump(tester, const ArchiveScope.inAppVisitor(42), mine: () async => pet(7), myPets: () => never.future);
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
    });

    testWidgets('petProfileProvider 失败 → 不显示', (tester) async {
      await pump(tester, const ArchiveScope.inAppVisitor(42),
          mine: () => Future<PetProfile?>.error(Exception('x')));
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
    });

    testWidgets('分享链接进入（petId == null）→ 本分支不显示', (tester) async {
      await pump(tester, const ArchiveScope.visitor('SHARE'), mine: () async => pet(7));
      expect(find.byKey(const ValueKey('giftDiarySendButton')), findsNothing);
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
          // V1.4.0 gift Story 2.2：礼物区按 giftPets 渲染（Story 2.1 只对有关联宠物的 Diary 下发）。
          giftPets: type == 'GROWTH_MOMENT' ? const [GiftPetSummary(petId: 42, name: 'Mochi')] : const [],
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

    testWidgets('底栏 _actions() 不加送礼按钮（四线合并后 = 点赞 · 分享 · 收藏）', (tester) async {
      await pump(tester, detail());
      final row = tester.widget<Row>(find.byKey(const ValueKey('detailBottomActions')));
      // 四线合并记账（push-collect 3.1 · AC5.1）：点赞热区 + 间距 + 分享热区 + 间距 + ④ 收藏热区 = 5
      // （③ 合入时为 3；公开帖才有收藏，本例 detail() 为公开帖）。送礼仍不进操作栏。
      expect(row.children, hasLength(5));
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('detailBottomActions')), matching: find.byIcon(Icons.redeem)),
          findsNothing);
    });

    test('源码：礼物区插在打卡场所条之后、分隔线之前', () {
      final src = File('lib/features/content/presentation/content_detail_page.dart').readAsStringSync();
      final strip = src.indexOf('_DetailCheckinPlaceStrip(place: detail.checkinPlace!)');
      final gift = src.indexOf('GiftDetailZone(');
      final divider = src.indexOf('const Divider(height: AppSpacing.xl, color: AppColors.divider)');
      expect(gift, greaterThan(strip));
      expect(divider, greaterThan(gift));
      // gift Story 4.1 · AC5.1：与 ② 关联宠物胶囊并存 —— 首图胶囊（F6）与无图胶囊（F6b）都在礼物区之前。
      final f6 = src.indexOf('ContentLinkedPetsCapsule(pets: widget.linkedPets, dark: true)');
      final f6b = src.indexOf('ContentLinkedPetsCapsule(pets: linkedPets, dark: false)');
      final carousel = src.indexOf('_ImageCarousel(');
      expect(f6, greaterThan(0));
      expect(carousel, lessThan(f6b));
      expect(f6b, lessThan(strip));
      // 礼物区拿到全部 giftPets 与 ② 的 pets（A7 物种），不再按 postId 送礼。
      expect(src.substring(gift, divider), contains('pets: detail.giftPets'));
      expect(src.substring(gift, divider), contains('linkedPets: detail.pets'));
      expect(File('lib/features/gift/presentation/widgets/gift_entry.dart').readAsStringSync(),
          isNot(contains('GiftRecipientRef.post')));
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
