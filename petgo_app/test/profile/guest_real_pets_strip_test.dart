import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/guest_real_pets_strip.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 onboarding-recall Story 1.3 · L0：游客展示页「真实宠物」区 B1 / B2 / B3、点图进帖、
/// 追更 / 点赞弹硬登录（entry_source = guest_real_pets、不自动补做）、埋点、游客页其余部分不变。

class _Public implements PublicPetRecommendationRepository {
  _Public(this.pets, {this.fail = false, this.gate});
  final List<RecommendedPet> pets;
  final bool fail;
  final Completer<void>? gate;
  int? lastLimit;

  @override
  Future<List<RecommendedPet>> fetchPublic({int limit = kGuestRealPetsLimit}) async {
    lastLimit = limit;
    if (gate != null) await gate!.future;
    if (fail) throw Exception('offline');
    return pets;
  }
}

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  final List<String> entrySources = [];
  RouteIntent? lastPending;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    entrySources.add(entrySource);
    lastPending = pendingAction;
  }
}

RecommendedPet _pet(int id, {int? postId}) => RecommendedPet(
      petId: id,
      name: 'Kopi$id',
      avatarUrl: '',
      petType: 'DOG',
      companionDays: 300,
      breed: 'Corgi',
      coverPostId: postId,
      coverLikeCount: 67,
    );

late _Guide _guide;

Future<void> _pump(WidgetTester tester, _Public repo, {Widget? home}) async {
  _guide = _Guide();
  final router = GoRouter(
    initialLocation: '/guest',
    routes: [
      GoRoute(
          path: '/guest',
          builder: (c, s) => home ?? const Scaffold(body: SingleChildScrollView(child: GuestRealPetsStrip()))),
      GoRoute(path: '/content/:id', builder: (c, s) => Scaffold(body: Text('POST ${s.pathParameters['id']}'))),
    ],
  );
  tester.view.physicalSize = const Size(1080, 4000);
  tester.view.devicePixelRatio = 2.75;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      publicPetRecommendationRepositoryProvider.overrideWithValue(repo),
      authControllerProvider.overrideWith(_Guest.new),
      loginGuideControllerProvider.overrideWithValue(_guide),
    ],
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
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Iterable<Map<String, Object>?> props(String name) => events.where((e) => e.$1 == name).map((e) => e.$2);

  testWidgets('B2 加载中骨架 → B1 有数据：标题 + 卡（宽 140）+ 曝光一次', (tester) async {
    final gate = Completer<void>();
    final repo = _Public([_pet(1, postId: 11), _pet(2, postId: 12), _pet(3)], gate: gate);
    await _pump(tester, repo);
    await tester.pump();
    expect(find.byKey(const ValueKey('guestRealPetsSkeleton')), findsOneWidget);

    gate.complete();
    await tester.pumpAndSettle();
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(repo.lastLimit, 6);
    expect(find.text(l10n.petRecommendSectionTitle), findsOneWidget);
    expect(find.byKey(const ValueKey('recommendedPet_1')), findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('recommendedPet_1'))).width, 140);
    expect(find.text('Corgi · Bersama 300 hari'), findsNWidgets(3));
    expect(find.byKey(const ValueKey('recommendLikePill_11')), findsOneWidget);
    expect(find.byKey(const ValueKey('petFollowList_3')), findsOneWidget);
    expect(props('guest_real_pets_viewed').toList(), [
      {'card_count': 3}
    ]);
  });

  testWidgets('B3 失败 → 整块（含标题）不渲染、无报错条', (tester) async {
    await _pump(tester, _Public(const [], fail: true));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('guestRealPetsHidden')), findsOneWidget);
    expect(find.byKey(const ValueKey('guestRealPetsStrip')), findsNothing);
    expect(events.where((e) => e.$1 == 'guest_real_pets_viewed'), isEmpty);
  });

  testWidgets('B3 为空 → 整块不渲染', (tester) async {
    await _pump(tester, _Public(const []));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('guestRealPetsStrip')), findsNothing);
  });

  testWidgets('AC2 点大图 → /content/{coverPostId}，报 open_post', (tester) async {
    await _pump(tester, _Public([_pet(1, postId: 11), _pet(2, postId: 12)]));
    await tester.pumpAndSettle();
    await tester.tapAt(tester.getTopLeft(find.byKey(const ValueKey('recommendedPetCoverTap_2'))) + const Offset(20, 60));
    await tester.pumpAndSettle();
    expect(find.text('POST 12'), findsOneWidget);
    expect(props('guest_real_pets_tapped').last, {'action': 'open_post', 'pet_id': 2, 'position': 1});
  });

  testWidgets('AC2 无封面帖点图无动作', (tester) async {
    await _pump(tester, _Public([_pet(1)]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('recommendedPetCoverTap_1')));
    await tester.pumpAndSettle();
    expect(find.textContaining('POST'), findsNothing);
    expect(_guide.entrySources, isEmpty);
  });

  testWidgets('AC3 游客点追更 → 硬登录 entry_source=guest_real_pets、不带待办（登录后不自动补做）', (tester) async {
    await _pump(tester, _Public([_pet(1, postId: 11)]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('petFollowList_1')));
    await tester.pumpAndSettle();
    expect(_guide.entrySources, ['guest_real_pets']);
    expect(_guide.lastPending, isNull);
    expect(props('guest_real_pets_tapped').last, {'action': 'pet_follow', 'pet_id': 1, 'position': 0});
  });

  testWidgets('AC3 游客点点赞胶囊 → 硬登录 entry_source=guest_real_pets', (tester) async {
    await _pump(tester, _Public([_pet(1, postId: 11)]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('likeButton_11')));
    await tester.pumpAndSettle();
    expect(_guide.entrySources, ['guest_real_pets']);
    expect(_guide.lastPending, isNull);
    expect(props('guest_real_pets_tapped').last, {'action': 'post_like', 'pet_id': 1, 'position': 0});
  });

  testWidgets('游客页其余部分不变：吸底主按钮仍在，真实宠物区在情感文案之后', (tester) async {
    await _pump(tester, _Public([_pet(1, postId: 11)]), home: const DiaryGuestPage());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('diaryGuestPrimaryCta')), findsOneWidget);
    expect(find.byKey(const ValueKey('diaryDemoStrip')), findsOneWidget);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('guestRealPetsStrip')), 300,
        scrollable: find.byType(Scrollable).first);
    final pitch = tester.getTopLeft(find.byKey(const ValueKey('diaryGuestPitch')));
    final strip = tester.getTopLeft(find.byKey(const ValueKey('guestRealPetsStrip')));
    expect(strip.dy, greaterThan(pitch.dy));
  });
}
