import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/social/data/pet_follow_repository.dart';
import 'package:tailtopia/features/social/domain/pet_follow_state.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/domain/visitor_profile.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/gone_icon.dart';

/// V1.1.6 Story 2.3 · L0/widget：访客只读态渲染（AD-2 Rule 5 · AD-3 · AD-4）。
///
/// 这组测试守的是**减法有没有真做**。
/// 「访客看不到编辑按钮」这种事，人工点一遍很容易漏 ——
/// 而漏掉的后果不是不好看：**身份证入口跳的是当前登录用户自己的卡**，
/// 访客点进去要么看到自己的宠物、要么直接出错。
///
/// ⚠️ 特别注意「不渲染」与「不可点」的区别：把回调传 null 只会让按钮变灰、图标仍在，
/// 那不算移除（AD-2 Rule 5 要求两张入口卡**整块移除**）。下面用 `findsNothing` 钉死。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

const String _token = 'SHARE-TOK';

const VisitorProfile _profile = VisitorProfile(
  name: 'Mochi',
  petType: 'CAT',
  breed: 'Kucing Domestik',
  intro: 'Suka tidur',
  ownerNickname: 'Rina',
);

TimelineItem _moment({required int postId, required bool openable, String? text}) => TimelineItem(
      kind: TimelineKind.happyMoment,
      date: DateTime(2026, 5, 12),
      eventDate: DateTime(2026, 5, 12),
      postId: postId,
      imageUrls: const [],
      text: text ?? 'catatan $postId',
      itemType: TimelineItemType.happyMoment,
      openable: openable,
    );

Widget _wrap({
  required AuthState auth,
  List<TimelineItem> items = const [],
  VisitorProfile profile = _profile,
}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      visitorProfileProvider(const ArchiveScope.visitor(_token)).overrideWith((ref) async => profile),
      visitorStatsProvider(const ArchiveScope.visitor(_token)).overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30,
          giftTotalValue: 188000)),
      visitorTimelineProvider(const ArchiveScope.visitor(_token)).overrideWith((ref) async => TimelinePage(items: items)),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(visitorToken: _token),
    ),
  );
}

/// 站内入口（V1.3.0 batch-b1 Story 2.3）：按 petId、仅登录可用、**不渲染来源横幅**。
Widget _wrapInApp({List<TimelineItem> items = const []}) {
  const scope = ArchiveScope.inAppVisitor(_petId);
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(
          const AuthState(status: AuthStatus.authenticated, role: 'USER'))),
      visitorProfileProvider(scope).overrideWith((ref) async => _profile),
      visitorStatsProvider(scope).overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30)),
      visitorTimelineProvider(scope).overrideWith((ref) async => TimelinePage(items: items)),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: VisitorArchiveView(scope: scope),
    ),
  );
}

const int _petId = 42;

void main() {
  /// V1.3.0 batch-b1 Story 2.3 · AC4/AC5：同一屏的两种来源。
  group('站内入口（AD-4 Rule 5）', () {
    /// 2026-09-21 stag 验收：空时间线曾沿用主人态「Record Mochi's first diary entry」——
    /// 访客看的是别人的宠物，不能邀请他去写。
    testWidgets('时间线为空 → 访客文案，不出现主人态「去写第一篇」', (tester) async {
      await tester.pumpWidget(_wrapInApp());
      await tester.pumpAndSettle();
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));

      expect(find.byKey(const ValueKey('visitorTimelineEmpty')), findsOneWidget);
      expect(find.text(l10n.growthArchiveVisitorTimelineEmpty('Mochi')), findsOneWidget);
      expect(find.text(l10n.growthArchiveTimelineEmpty('Mochi')), findsNothing);
    });

    /// 🔴 **站内进入不渲染来源横幅**：从别人主页点宠物卡进来时，
    /// 「谁分享的」这个前提根本不成立。从分享链接进来时照旧显示（上面那组用例守着）。
    testWidgets('站内进入 → 没有「由 XX 分享」横幅，其余一字不变', (tester) async {
      await tester.pumpWidget(_wrapInApp());
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsNothing);
      // 其余照旧：宠物信息卡 / 里程碑进度 / 统计数字都在。
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('archiveMilestoneBar')), findsOneWidget);
      expect(find.text('24'), findsOneWidget);
    });

    /// ⚠️ AC5：**没有日历、没有视图切换行**（2026-08-18 / 08-28 两次拍板不做）。
    /// UI 旧稿画着那两个按钮，已于 2026-09-11 修订。**别照旧稿补回去。**
    testWidgets('站内进入同样没有日历与视图切换行', (tester) async {
      await tester.pumpWidget(_wrapInApp());
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('visitorViewTimeline')), findsNothing);
      expect(find.byKey(const ValueKey('visitorViewCalendar')), findsNothing);
    });

    /// 作者专属入口在站内态同样一个都不能有（减法口径与分享态逐条一致）。
    testWidgets('站内进入也不渲染编辑 / 健康 / 身份证 / 分享', (tester) async {
      await tester.pumpWidget(_wrapInApp());
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('editProfileButton')), findsNothing);
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsNothing);
      expect(find.byKey(const ValueKey('diaryIdCardButton')), findsNothing);
      expect(find.byKey(const ValueKey('shareFab')), findsNothing);
    });
  });

  group('AC2 减法：作者专属入口一个都不能留', () {
    /// 🛡 本组最要紧的一条。
    ///
    /// 身份证入口在作者态跳 `/profile/id-card` —— 那是**当前登录用户自己的**身份证页。
    /// 访客点进去：已登录的看到自己的宠物（莫名其妙），未登录的撞上登录墙。
    /// 「留个锁」解决不了，必须整块不渲染。
    testWidgets('访客态不渲染：编辑铅笔 · 健康记录入口 · 身份证入口 · 分享按钮', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('editProfileButton')), findsNothing,
          reason: '编辑铅笔仍在 —— 注意传 null 只是变灰，要的是不渲染');
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsNothing,
          reason: '健康记录入口仍在 —— 健康数据整块不对访客开放');
      expect(find.byKey(const ValueKey('diaryIdCardButton')), findsNothing,
          reason: '身份证入口仍在 —— 它跳的是当前登录用户自己的卡，访客点进去必然出错');
      expect(find.byKey(const ValueKey('shareFab')), findsNothing,
          reason: '分享入口仍在 —— 访客不得二次转发');
    });

    testWidgets('该保留的都在：统计条三列 · 里程碑进度 · 来源横幅（无视图切换）', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('archiveMilestoneBar')), findsOneWidget);
      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      // ⚠️ 2026-08-18 产品决定：访客态先不做日历屏 → 没有视图切换行。
      // 若哪天接回来，这两条断言要改成 findsOneWidget。
      expect(find.byKey(const ValueKey('visitorViewTimeline')), findsNothing);
      expect(find.byKey(const ValueKey('visitorViewCalendar')), findsNothing);
      // 统计数字与作者态同源（AD-1 Rule 8）
      expect(find.text('24'), findsOneWidget);
      // V1.4.0 gift Story 2.2 · AC2.5：中间格由问诊数换成礼物总价值（缩写），不再出现 Consultations。
      expect(find.text('Rp188rb'), findsOneWidget);
      expect(find.text('3'), findsNothing);
      expect(find.text('Consultations'), findsNothing);
      // AC2.6：分享 token 态拿不到 petId → 礼物格只显示数值、不可点、无「›」。
      expect(find.text('Gifts'), findsOneWidget);
      expect(find.text('Gifts ›'), findsNothing);
      expect(
          find.descendant(of: find.byKey(const ValueKey('petInfoGiftStat')), matching: find.byType(InkWell)),
          findsNothing);
    });

    /// AD-4：Diary 页从来没有「陪伴天数」，那是 H5 名片独有的，访客态不得引入。
    testWidgets('🛡 不引入「陪伴天数」', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();
      expect(find.textContaining('hari bersama'), findsNothing);
      expect(find.textContaining('bersama'), findsNothing);
    });
  });

  group('AC3 条目点击：公开可点开，私密就地拦', () {
    /// 🛡 私密条目**不跳转**，给一句解释性提示 —— 私密内容因此始终不越出链接边界。
    testWidgets('私密条目点击不跳转，出现解释性提示', (tester) async {
      await tester.pumpWidget(_wrap(
        auth: const AuthState.guest(),
        items: [_moment(postId: 1, openable: false, text: 'rahasia')],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('rahasia'));
      await tester.pump(const Duration(milliseconds: 100));

      // 仍停在访客视图（没有跳去内容详情页）
      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      // ⚠️ 取 l10n 要用**页面内部**的节点：MaterialApp 本身在 Localizations 之上，拿不到。
      final l10n = AppLocalizations.of(
          tester.element(find.byKey(const ValueKey('visitorSharedBanner'))));
      expect(find.text(l10n.visitorPrivateItemNotice), findsOneWidget,
          reason: '私密条目点了应给一句解释，而不是默默无反应');
      // toast 自带定时器；不等它走完，测试结束时会报「仍有 Timer 未完成」。
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    /// 🛡 `openable` 缺失（后端漏发）时**按不可点处理**（fail-closed）。
    ///
    /// 默认可点会让私密内容悄悄变得可点开 —— 那是隐私事故；
    /// 默认不可点最多是「本该能点的点不开」，一眼就能看见。
    testWidgets('openable 缺失时按不可点处理', (tester) async {
      final item = TimelineItem(
        kind: TimelineKind.happyMoment,
        date: DateTime(2026, 5, 12),
        postId: 9,
        imageUrls: const [],
        text: 'tanpa-flag',
        itemType: TimelineItemType.happyMoment,
        // openable 不传 → null
      );
      await tester.pumpWidget(_wrap(auth: const AuthState.guest(), items: [item]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('tanpa-flag'));
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget,
          reason: 'openable 缺失时不该跳转 —— fail-closed');
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });
  });

  group('AD-2 Rule 2：已登录非作者与未登录访客看到同一套', () {
    testWidgets('登录与否，渲染出的减法结果一致', (tester) async {
      for (final auth in <AuthState>[
        const AuthState.guest(),
        const AuthState(status: AuthStatus.authenticated, role: 'USER'),
      ]) {
        await tester.pumpWidget(_wrap(auth: auth));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('editProfileButton')), findsNothing);
        expect(find.byKey(const ValueKey('diaryIdCardButton')), findsNothing);
        expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      }
    });
  });

  /// V1.4.0 gift Story 2.2 · AC2.4（A1 → E1）：站内入口礼物格可点，push 该宠物礼物页。
  group('礼物格（V1.4.0 gift 2.2）', () {
    testWidgets('站内态：显示 Rp188rb +「Gifts ›」，点击 push /pets/{id}/gifts', (tester) async {
      const scope = ArchiveScope.inAppVisitor(_petId);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => const VisitorArchiveView(scope: scope)),
        GoRoute(
            path: '/pets/:petId/gifts',
            builder: (c, s) => Text('GIFT PAGE ${s.pathParameters['petId']}')),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
              const AuthState(status: AuthStatus.authenticated, role: 'USER'))),
          visitorProfileProvider(scope).overrideWith((ref) async => _profile),
          visitorStatsProvider(scope).overrideWith((ref) async => const ArchiveStats(
              happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30,
              giftTotalValue: 188000)),
          visitorTimelineProvider(scope).overrideWith((ref) async => const TimelinePage(items: [])),
          petProfileProvider.overrideWith((ref) async => null),
          petListProvider.overrideWith((ref) async => const []),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Rp188rb'), findsOneWidget);
      expect(find.text('Gifts ›'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petInfoGiftStat')));
      await tester.pumpAndSettle();
      expect(find.text('GIFT PAGE $_petId'), findsOneWidget);
    });
  });

  /// V1.4.2 social-topic · Story 1.4：追更按钮（AC1）、第 4 格（AC2）、页头合并（AC3）、A14 / A15（AC4 / AC5）。
  group('V1.4.2 social-topic 1.4', () {
    final events = <(String, Map<String, Object>?)>[];
    late AppLocalizations en;

    setUpAll(() async {
      en = await AppLocalizations.delegate.load(const Locale('en'));
    });
    setUp(() {
      events.clear();
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    testWidgets('站内他人宠物：Follow 按钮 + 4 格 Pengikut 128；点 → Following / 129；再点 → Follow / 128、无确认',
        (tester) async {
      final repo = _PetRepo();
      await tester.pumpWidget(_wrapFollow(repo: repo));
      await tester.pumpAndSettle();

      expect(_followLabel(tester), en.followActionFollow);
      expect(find.byKey(const ValueKey('petInfoFollowerStat')), findsOneWidget);
      expect(_count(tester), '128');

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pump();
      expect(_followLabel(tester), en.followActionFollowing);
      expect(_count(tester), '129', reason: 'A2：乐观 +1');
      await tester.pumpAndSettle();
      expect(_count(tester), '129');
      expect(repo.calls, ['POST $_petId']);
      expect(events.single.$1, 'pet_follow');
      expect(events.single.$2, {
        'pet_id': _petId,
        'owner_id': 'OWNER-TOK',
        'source': 'pet_visitor',
        'is_following_owner': false,
      });

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pump();
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
      expect(_followLabel(tester), en.followActionFollow);
      expect(_count(tester), '128');
      await tester.pumpAndSettle();
      expect(repo.calls, ['POST $_petId', 'DELETE $_petId']);
    });

    testWidgets('追更失败 → 回滚（按钮 + 人数）+ 顶部 followFailed', (tester) async {
      final repo = _PetRepo(error: DioException(requestOptions: RequestOptions(path: '/x')));
      await tester.pumpWidget(_wrapFollow(repo: repo));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pumpAndSettle();
      expect(_followLabel(tester), en.followActionFollow);
      expect(_count(tester), '128');
      expect(find.text(en.followFailed), findsOneWidget);
      expect(tester.getCenter(find.text(en.followFailed)).dy, lessThan(300));
      expect(events, isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('取消失败 → 回到 Following + followUnfollowFailed', (tester) async {
      final repo = _PetRepo(error: _dio(500));
      await tester.pumpWidget(_wrapFollow(repo: repo, iFollow: true));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pumpAndSettle();
      expect(_followLabel(tester), en.followActionFollowing);
      expect(_count(tester), '128');
      expect(find.text(en.followUnfollowFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('429 → 底部 followRateLimited、按钮回滚', (tester) async {
      final repo = _PetRepo(error: _dio(429, 'rate-limited'));
      await tester.pumpWidget(_wrapFollow(repo: repo));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pumpAndSettle();
      expect(_followLabel(tester), en.followActionFollow);
      expect(find.text(en.followRateLimited), findsOneWidget);
      expect(tester.getCenter(find.text(en.followRateLimited)).dy, greaterThan(300));
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('在途重复点击只发一次请求', (tester) async {
      final repo = _PetRepo()..gate = Completer<void>();
      await tester.pumpWidget(_wrapFollow(repo: repo));
      await tester.pumpAndSettle();

      for (var i = 0; i < 3; i++) {
        await tester.tap(find.byKey(const ValueKey('petFollowButton')));
        await tester.pump();
      }
      repo.gate!.complete();
      await tester.pumpAndSettle();
      expect(repo.calls, hasLength(1));
    });

    testWidgets('游客点 Follow → 拉起登录、不发请求、不埋点', (tester) async {
      final repo = _PetRepo();
      final guide = _Guide();
      await tester.pumpWidget(_wrapFollow(repo: repo, auth: const AuthState.guest(), guide: guide));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pumpAndSettle();
      expect(guide.hardDialogs, 1);
      expect(repo.calls, isEmpty);
      expect(events, isEmpty);
    });

    testWidgets('自己的宠物：无追更按钮（Pengikut 格照常）', (tester) async {
      await tester.pumpWidget(_wrapFollow(
          repo: _PetRepo(), mine: const PetProfile(id: _petId, name: 'Mochi', cardToken: 'c')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
      expect(find.byKey(const ValueKey('petInfoFollowerStat')), findsOneWidget);
    });

    testWidgets('多宠用户看自己的另一只宠物（当前宠物不是它）：无追更按钮、无送礼胶囊', (tester) async {
      await tester.pumpWidget(_wrapFollow(
        repo: _PetRepo(),
        mine: const PetProfile(id: 7, name: 'Kopi', cardToken: 'c'),
        myPets: const [PetListItem(id: 7, name: 'Kopi'), PetListItem(id: _petId, name: 'Mochi')],
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });

    testWidgets('petListProvider 加载中：无追更按钮、无送礼胶囊', (tester) async {
      final never = Completer<List<PetListItem>>();
      await tester.pumpWidget(_wrapFollow(repo: _PetRepo(), myPetsFuture: never.future));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
      expect(find.byKey(const ValueKey('giftSendPill')), findsNothing);
    });

    testWidgets('复审：同会话再进宠物页，服务端新快照接管早先的覆盖（人数 134、他端已取消 → Follow）',
        (tester) async {
      final timeline = _FakeTimelineRepo(profile: _followProfile(false), followerCount: 128);
      final petRepo = _PetRepo();
      final container = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(const AuthState(
              status: AuthStatus.authenticated,
              role: 'USER',
              profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
            ))),
        petFollowRepositoryProvider.overrideWithValue(petRepo),
        timelineRepositoryProvider.overrideWithValue(timeline),
        petProfileProvider.overrideWith((ref) async => null),
        petListProvider.overrideWith((ref) async => const []),
      ]);
      addTearDown(container.dispose);
      Widget app(bool show) => UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              locale: const Locale('en'),
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              home: show ? const VisitorArchiveView(scope: ArchiveScope.inAppVisitor(_petId)) : const SizedBox(),
            ),
          );

      // 第一次进：追更 → 覆盖 (true, 129)。成功后 stats 重取（服务端此时 129）→ 仍 129。
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      timeline.followerCount = 129;
      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pumpAndSettle();
      expect(_followLabel(tester), en.followActionFollowing);
      expect(_count(tester), '129');
      expect(container.read(petFollowStateProvider)[_petId]!.followerCount, 129);

      // 离开（autoDispose 回收）。期间别人又追更了 5 次、本人在他端取消了追更。
      await tester.pumpWidget(app(false));
      await tester.pumpAndSettle();
      timeline
        ..followerCount = 134
        ..profile = _followProfile(false);

      // 再进：新快照版本号 ≥ 覆盖 → 服务端值接管。
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      expect(_count(tester), '134', reason: '覆盖人数 129 不得盖住新 stats 134');
      expect(_followLabel(tester), en.followActionFollow, reason: '覆盖 true 不得永久盖住新 profile iFollow=false');

      // 再点一次：以新快照为基准乐观 +1（请求挂起，只看乐观值）。
      petRepo.gate = Completer<void>();
      await tester.tap(find.byKey(const ValueKey('petFollowButton')));
      await tester.pump();
      expect(_count(tester), '135');
      petRepo.gate!.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('petProfileProvider 加载中：无追更按钮（防自己的宠物闪现）', (tester) async {
      final never = Completer<PetProfile?>();
      await tester.pumpWidget(_wrapFollow(repo: _PetRepo(), mineFuture: never.future));
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
    });

    testWidgets('分享 token 态：无追更按钮；有 followerCount 时显示第 4 格', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
      // _wrap 的 stats 未带 followerCount（旧后端形状）→ 3 格。
      expect(find.byKey(const ValueKey('petInfoFollowerStat')), findsNothing);
    });

    testWidgets('页头合并：返回键 / 标题 / 送礼胶囊同一行（同一 y 中心），整行 44 高', (tester) async {
      await tester.pumpWidget(_wrapFollow(repo: _PetRepo()));
      await tester.pumpAndSettle();

      final back = find.byKey(const ValueKey('visitorBackButton'));
      expect(back, findsOneWidget);
      final title = find.text(en.growthArchivePassportTitle('Mochi'));
      expect(title, findsOneWidget);
      final row = find.ancestor(of: back, matching: find.byType(Row)).first;
      expect(find.descendant(of: row, matching: title), findsOneWidget, reason: '返回键与标题在同一 Row');
      expect(tester.getCenter(back).dy, closeTo(tester.getCenter(title).dy, 0.5));
      final pill = find.byKey(const ValueKey('giftSendPill'));
      expect(find.descendant(of: row, matching: pill), findsOneWidget, reason: '送礼胶囊同一行');
      expect(tester.getCenter(pill).dy, closeTo(tester.getCenter(back).dy, 0.5));
      expect(tester.getSize(back), const Size(44, 44));
      expect(tester.getSize(row).height, 44);
      // 返回键在标题左侧
      expect(tester.getCenter(back).dx, lessThan(tester.getTopLeft(title).dx));
    });

    testWidgets('分享 token 态：来源横幅在合并行之下', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();
      final back = find.byKey(const ValueKey('visitorBackButton'));
      final banner = find.byKey(const ValueKey('visitorSharedBanner'));
      expect(back, findsOneWidget);
      expect(banner, findsOneWidget);
      expect(tester.getTopLeft(banner).dy, greaterThan(tester.getBottomLeft(back).dy));
      expect(tester.getTopLeft(find.byKey(const ValueKey('petInfoCard'))).dy,
          greaterThan(tester.getBottomLeft(banner).dy));
    });

    testWidgets('返回键：canPop → pop；不能 pop → go /home', (tester) async {
      final router = GoRouter(initialLocation: '/start', routes: [
        GoRoute(path: '/home', builder: (c, s) => const Text('HOME')),
        GoRoute(path: '/start', builder: (c, s) => TextButton(
            onPressed: () => c.push('/v'), child: const Text('OPEN'))),
        GoRoute(path: '/v', builder: (c, s) => const VisitorArchiveView(scope: ArchiveScope.inAppVisitor(_petId))),
        GoRoute(path: '/direct', builder: (c, s) => const VisitorArchiveView(scope: ArchiveScope.inAppVisitor(_petId))),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(_wrapFollow(repo: _PetRepo(), router: router));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OPEN'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('visitorBackButton')));
      await tester.pumpAndSettle();
      expect(find.text('OPEN'), findsOneWidget, reason: 'canPop → pop 回上一页');

      router.go('/direct');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('visitorBackButton')));
      await tester.pumpAndSettle();
      expect(find.text('HOME'), findsOneWidget, reason: '栈底 → go /home');
    });

    testWidgets('A14：gone → 返回箭头 + detailGoneTitle，无任何按钮', (tester) async {
      await tester.pumpWidget(_wrapFollow(
          repo: _PetRepo(), profileError: const VisitorLoadError(VisitorLoadErrorKind.gone)));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('visitorGone')), findsOneWidget);
      expect(find.byKey(const ValueKey('visitorBackButton')), findsOneWidget);
      expect(find.text(en.detailGoneTitle), findsOneWidget);
      expect(find.byType(GoneIcon), findsOneWidget);
      expect(find.byType(FilledButton), findsNothing);
      expect(find.byType(TextButton), findsNothing);
      expect(find.text(en.commonRetry), findsNothing);
    });

    testWidgets('A15：network → detailNetworkError + commonRetry；点击后重新请求并恢复', (tester) async {
      var calls = 0;
      await tester.pumpWidget(_wrapFollow(
        repo: _PetRepo(),
        profileLoader: () async {
          calls++;
          if (calls == 1) throw const VisitorLoadError(VisitorLoadErrorKind.network);
          return _followProfile(false);
        },
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('visitorLoadFailed')), findsOneWidget);
      expect(find.byKey(const ValueKey('visitorBackButton')), findsOneWidget);
      expect(find.text(en.detailNetworkError), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off_rounded), findsOneWidget);
      expect(calls, 1, reason: '失败停在失败态，不自动重试');

      await tester.tap(find.text(en.commonRetry));
      await tester.pumpAndSettle();
      expect(calls, 2);
      expect(find.byKey(const ValueKey('visitorLoadFailed')), findsNothing);
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
    });

    test('数据层错误分类：404 / 403 → gone；500 / 无响应 → network', () {
      expect(VisitorLoadError.classify(_dio(404)).kind, VisitorLoadErrorKind.gone);
      expect(VisitorLoadError.classify(_dio(403, 'blocked-user')).kind, VisitorLoadErrorKind.gone);
      expect(VisitorLoadError.classify(_dio(500)).kind, VisitorLoadErrorKind.network);
      expect(VisitorLoadError.classify(DioException(requestOptions: RequestOptions(path: '/x'))).kind,
          VisitorLoadErrorKind.network);
    });
  });
}

/// 只实现访客页用到的三个方法：走**真实** provider 闭包（验证快照贴 followEpoch 的接线）。
class _FakeTimelineRepo implements TimelineRepository {
  _FakeTimelineRepo({required this.profile, required this.followerCount});

  VisitorProfile profile;
  int followerCount;

  @override
  Future<VisitorProfile> getInAppVisitorProfile(int petId) async => profile;

  @override
  Future<ArchiveStats> getStats({ArchiveScope scope = const ArchiveScope.me(), int? petId}) async => ArchiveStats(
      happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30,
      giftTotalValue: 188000, followerCount: followerCount);

  @override
  Future<TimelinePage> getTimeline({
    String? cursor,
    int limit = 20,
    ArchiveScope scope = const ArchiveScope.me(),
    bool diaryOnly = false,
    int? petId,
  }) async =>
      const TimelinePage(items: []);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _PetRepo implements PetFollowRepository {
  _PetRepo({this.error});

  Object? error;
  final List<String> calls = [];
  Completer<void>? gate;

  Future<PetFollowResult> _answer(bool iFollow) async {
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    return PetFollowResult(iFollow: iFollow, followerCount: iFollow ? 129 : 128);
  }

  @override
  Future<PetFollowResult> follow(int petId) {
    calls.add('POST $petId');
    return _answer(true);
  }

  @override
  Future<PetFollowResult> unfollow(int petId) {
    calls.add('DELETE $petId');
    return _answer(false);
  }
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
  }
}

DioException _dio(int status, [String? slug]) {
  final req = RequestOptions(path: '/api/v1/x');
  return DioException(
    requestOptions: req,
    response: Response<Map<String, dynamic>>(
      requestOptions: req,
      statusCode: status,
      data: {if (slug != null) 'type': 'https://petgo/errors/$slug', 'status': status},
    ),
  );
}

VisitorProfile _followProfile(bool iFollow) => VisitorProfile(
      name: 'Mochi',
      petType: 'CAT',
      ownerNickname: 'Rina',
      iFollow: iFollow,
      iFollowOwner: false,
      ownerToken: 'OWNER-TOK',
    );

/// 站内他人宠物（新后端形状：profile 带三字段、stats 带 followerCount 128）。
Widget _wrapFollow({
  required _PetRepo repo,
  bool iFollow = false,
  AuthState auth = const AuthState(
    status: AuthStatus.authenticated,
    role: 'USER',
    profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
  ),
  _Guide? guide,
  PetProfile? mine,
  Future<PetProfile?>? mineFuture,
  List<PetListItem> myPets = const [],
  Future<List<PetListItem>>? myPetsFuture,
  Object? profileError,
  Future<VisitorProfile> Function()? profileLoader,
  GoRouter? router,
}) {
  const scope = ArchiveScope.inAppVisitor(_petId);
  final l10nDelegates = AppLocalizations.localizationsDelegates;
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
      petFollowRepositoryProvider.overrideWithValue(repo),
      visitorProfileProvider(scope).overrideWith((ref) async {
        if (profileError != null) throw profileError;
        if (profileLoader != null) return profileLoader();
        return _followProfile(iFollow);
      }),
      visitorStatsProvider(scope).overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30,
          giftTotalValue: 188000, followerCount: 128)),
      visitorTimelineProvider(scope).overrideWith((ref) async => const TimelinePage(items: [])),
      petProfileProvider.overrideWith((ref) => mineFuture ?? Future.value(mine)),
      petListProvider.overrideWith((ref) => myPetsFuture ?? Future.value(myPets)),
    ],
    child: router != null
        ? MaterialApp.router(
            routerConfig: router,
            locale: const Locale('en'),
            localizationsDelegates: l10nDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
          )
        : MaterialApp(
            locale: const Locale('en'),
            localizationsDelegates: l10nDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: const VisitorArchiveView(scope: scope),
          ),
  );
}

String _followLabel(WidgetTester tester) => tester
    .widget<Text>(find.descendant(of: find.byKey(const ValueKey('petFollowButton')), matching: find.byType(Text)))
    .data!;

String _count(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const ValueKey('petInfoFollowerCount'))).data!;
