import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/onboarding/presentation/onboarding_recommend_page.dart';
import 'package:tailtopia/features/profile/data/onboarding_mark_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/social/data/pet_follow_repository.dart';
import 'package:tailtopia/features/social/domain/pet_follow_state.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 onboarding-recall Story 1.2 · L0：首日推荐页 A1 ~ A5 状态、只出现一次、失败 / 空直接去落点、埋点。
/// 视觉还原（≥ 95%）是 L2，在 4-1 本地对拆页打分；这里只做结构断言。

class _Recs implements PetRecommendationRepository {
  _Recs(this.pets, {this.fail = false, this.gate});
  final List<RecommendedPet> pets;
  final bool fail;
  final Completer<void>? gate;
  int? lastLimit;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    lastLimit = limit;
    if (gate != null) await gate!.future;
    if (fail) throw Exception('boom');
    return RecommendedPetPage(items: pets, hasMore: false);
  }
}

class _Marks extends OnboardingMarkRepository {
  _Marks(super.ref, {this.initial = const <String>{}, this.fetchFails = false});
  final Set<String> initial;
  final bool fetchFails;
  final List<String> marked = [];

  @override
  Future<Set<String>> fetchMarks() async {
    if (fetchFails) throw Exception('offline');
    return initial;
  }

  @override
  Future<void> mark(String key) async => marked.add(key);
}

class _Follows implements PetFollowRepository {
  _Follows({this.fail = false});
  final bool fail;
  @override
  Future<PetFollowResult> follow(int petId) async {
    if (fail) {
      throw DioException(
          requestOptions: RequestOptions(path: '/x'),
          response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 500));
    }
    return const PetFollowResult(iFollow: true, followerCount: 1);
  }

  @override
  Future<PetFollowResult> unfollow(int petId) async =>
      const PetFollowResult(iFollow: false, followerCount: 0);
}

class _Likes implements LikeRepository {
  @override
  Future<LikeResult> like(int postId) async => const LikeResult(liked: true, likeCount: 9);
  @override
  Future<LikeResult> unlike(int postId) async => const LikeResult(liked: false, likeCount: 8);
}

RecommendedPet _pet(int id, {int? postId, String? breed, String? match = 'SAME_BREED'}) => RecommendedPet(
      petId: id,
      name: 'Mochi$id',
      avatarUrl: '',
      petType: 'CAT',
      companionDays: 10,
      breed: breed,
      coverPostId: postId,
      coverLikeCount: 8,
      match: match,
    );

LoginResponse _user() => const LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: true,
      onboardingCompleted: true,
      profile: UserProfile(id: 1, onboardingCompleted: true),
    );

late _Marks _marks;

Future<void> _pump(
  WidgetTester tester, {
  required _Recs recs,
  Set<String> seen = const <String>{},
  bool marksFail = false,
  List<PetListItem> pets = const [],
  _Follows? follows,
  String entry = 'after_post',
  String landing = 'profile',
}) async {
  final router = GoRouter(
    initialLocation: onboardingRecommendLocation(entry: entry, landing: landing),
    routes: [
      GoRoute(
        path: kOnboardingRecommendRoute,
        builder: (c, s) => OnboardingRecommendPage(
          entry: s.uri.queryParameters['entry']!,
          landing: s.uri.queryParameters['landing']!,
        ),
      ),
      GoRoute(path: '/profile', builder: (c, s) => const Scaffold(body: Text('LANDING_PROFILE'))),
      GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('LANDING_HOME'))),
    ],
  );
  final container = ProviderContainer(overrides: [
    petRecommendationRepositoryProvider.overrideWithValue(recs),
    onboardingMarkRepositoryProvider.overrideWith((ref) {
      _marks = _Marks(ref, initial: seen, fetchFails: marksFail);
      return _marks;
    }),
    petListProvider.overrideWith((ref) async => pets),
    petFollowRepositoryProvider.overrideWithValue(follows ?? _Follows()),
    likeRepositoryProvider.overrideWithValue(_Likes()),
  ]);
  addTearDown(container.dispose);
  container.read(authControllerProvider.notifier).applyLogin(_user());
  // 竖屏手机尺寸且足够高：6 张卡（3 行）全部在视口内，懒加载网格不漏建。
  tester.view.physicalSize = const Size(1080, 4000);
  tester.view.devicePixelRatio = 2.75;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      locale: const Locale('id'),
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('id'));
  });
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Map<String, Object>? propsOf(String name) => events.where((e) => e.$1 == name).lastOrNull?.$2;

  testWidgets('A4 加载中骨架，A1 初始：6 张卡 + 底部 Lewati 描边 + 右上 Lewati + 写标记 + 曝光埋点', (tester) async {
    final gate = Completer<void>();
    final recs = _Recs([for (var i = 1; i <= 6; i++) _pet(i, postId: 100 + i)], gate: gate);
    await _pump(tester, recs: recs, pets: const [PetListItem(id: 9, name: 'Orenji')]);
    await tester.pump();
    expect(find.byKey(const ValueKey('onboardingRecommendSkeleton')), findsOneWidget);

    gate.complete();
    await tester.pumpAndSettle();
    expect(recs.lastLimit, 6);
    expect(find.byKey(const ValueKey('onboardingRecommendGrid')), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingRecommendSkipTop')), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingRecommendSkipBottom')), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingRecommendDone')), findsNothing);
    expect(find.text(l10n.petRecommendSectionTitle), findsOneWidget);
    expect(find.text(l10n.onboardingRecommendSubtitlePet('Orenji')), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back), findsNothing);
    expect(_marks.marked, [kOnboardingMarkFirstDayRecommend]);
    expect(propsOf('onboarding_recommend_viewed'), {'entry': 'after_post', 'card_count': 6});
  });

  testWidgets('A3 不足 6 只按实际渲染；没宠物用通用副标题；无封面帖不显示点赞胶囊', (tester) async {
    await _pump(tester,
        recs: _Recs([_pet(1, postId: 11), _pet(2), _pet(3, postId: 13)]), entry: 'no_pet', landing: 'home');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('recommendedPet_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('recommendedPet_3')), findsOneWidget);
    expect(find.byKey(const ValueKey('recommendedPet_4')), findsNothing);
    expect(find.text(l10n.onboardingRecommendSubtitleNoPet), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingLikePill_11')), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingLikePill_13')), findsOneWidget);
    expect(find.byKey(const ValueKey('petFollowList_2')), findsOneWidget);
  });

  testWidgets('A2 追更成功后底部变 Selesai，点它去落点并报 exited / action', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1, postId: 11), _pet(2, postId: 12, match: 'GLOBAL')]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('petFollowList_2')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('onboardingRecommendDone')), findsOneWidget);
    expect(find.byKey(const ValueKey('onboardingRecommendSkipTop')), findsOneWidget);
    expect(propsOf('onboarding_recommend_action'),
        {'action': 'pet_follow', 'pet_id': 2, 'position': 1, 'match': 'global'});

    await tester.tap(find.byKey(const ValueKey('likeButton_11')));
    await tester.pumpAndSettle();
    expect(propsOf('onboarding_recommend_action'),
        {'action': 'post_like', 'pet_id': 1, 'position': 0, 'match': 'same_breed'});

    await tester.tap(find.byKey(const ValueKey('onboardingRecommendDone')));
    await tester.pumpAndSettle();
    expect(find.text('LANDING_PROFILE'), findsOneWidget);
    expect(propsOf('onboarding_recommend_exited'), {'button': 'done', 'follow_count': 1, 'like_count': 1});
  });

  testWidgets('A5 追更失败：按钮回滚、底部仍是 Lewati', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1)]), follows: _Follows(fail: true));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('petFollowList_1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('onboardingRecommendDone')), findsNothing);
    expect(find.byKey(const ValueKey('onboardingRecommendSkipBottom')), findsOneWidget);
    expect(find.text(l10n.followFailed), findsOneWidget);
    expect(events.where((e) => e.$1 == 'onboarding_recommend_action'), isEmpty);
    await tester.pump(const Duration(seconds: 5));
  });

  testWidgets('右上 Lewati → 落点 home 并报 skip_top', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1)]), entry: 'skip_profile', landing: 'home');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('onboardingRecommendSkipTop')));
    await tester.pumpAndSettle();
    expect(find.text('LANDING_HOME'), findsOneWidget);
    expect(propsOf('onboarding_recommend_exited'), {'button': 'skip_top', 'follow_count': 0, 'like_count': 0});
  });

  testWidgets('底部 Lewati → skip_bottom', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1)]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('onboardingRecommendSkipBottom')));
    await tester.pumpAndSettle();
    expect(find.text('LANDING_PROFILE'), findsOneWidget);
    expect(propsOf('onboarding_recommend_exited')?['button'], 'skip_bottom');
  });

  testWidgets('AC6 接口为空 → 不出页直接去落点，照样写标记', (tester) async {
    await _pump(tester, recs: _Recs(const []));
    await tester.pumpAndSettle();
    expect(find.text('LANDING_PROFILE'), findsOneWidget);
    expect(_marks.marked, [kOnboardingMarkFirstDayRecommend]);
    expect(events.where((e) => e.$1 == 'onboarding_recommend_viewed'), isEmpty);
  });

  testWidgets('AC6 接口失败 → 直接去落点', (tester) async {
    await _pump(tester, recs: _Recs(const [], fail: true), landing: 'home');
    await tester.pumpAndSettle();
    expect(find.text('LANDING_HOME'), findsOneWidget);
  });

  testWidgets('AC5 已看过 → 不展示直接去落点、不请求推荐、不再写标记', (tester) async {
    final recs = _Recs([_pet(1)]);
    await _pump(tester, recs: recs, seen: {kOnboardingMarkFirstDayRecommend});
    await tester.pumpAndSettle();
    expect(find.text('LANDING_PROFILE'), findsOneWidget);
    expect(recs.lastLimit, isNull);
    expect(_marks.marked, isEmpty);
  });

  testWidgets('AC5 读标记失败 → 视为未看过照常展示', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1)]), marksFail: true);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('onboardingRecommendGrid')), findsOneWidget);
  });

  testWidgets('卡片头像 / 名字点击不跳转', (tester) async {
    await _pump(tester, recs: _Recs([_pet(1)]));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mochi1'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('onboardingRecommendGrid')), findsOneWidget);
  });

  test('落点映射：profile → /profile，其余 → /home', () {
    expect(OnboardingRecommendPage.landingPath('profile'), '/profile');
    expect(OnboardingRecommendPage.landingPath('home'), '/home');
    expect(OnboardingRecommendPage.landingPath('weird'), '/home');
    expect(onboardingRecommendLocation(entry: 'no_pet', landing: 'home'),
        '/onboarding/recommend?entry=no_pet&landing=home');
  });

  test('建档庆祝页出口：只有首日引导来的进推荐页 / 带 from=onboarding，其他入口保持原去向', () {
    expect(celebrationPublishLocation(fromOnboarding: true),
        '/publish?preset=growth-calendar&from=onboarding');
    expect(celebrationViewProfileLocation(fromOnboarding: true),
        '/onboarding/recommend?entry=skip_post&landing=profile');
    expect(celebrationPublishLocation(fromOnboarding: false), '/publish');
    expect(celebrationViewProfileLocation(fromOnboarding: false), '/profile');
  });
}
