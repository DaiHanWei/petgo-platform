import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/data/pet_follow_repository.dart';
import 'package:tailtopia/features/social/domain/pet_follow_state.dart';
import 'package:tailtopia/features/social/presentation/widgets/pet_follow_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic · Story 1.4 · AC1 / AC6：追更按钮两态外观、乐观更新 / 回滚、跨实例同步、门控、埋点。
const int _kPet = 42;
const String _kOwnerToken = 'ownerTokOwnerTokOwnerTok';

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;
  RouteIntent? lastPending;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
    lastPending = pendingAction;
  }
}

class _Repo implements PetFollowRepository {
  _Repo({this.error, this.count = 129});

  Object? error;
  int count;
  final List<String> calls = [];
  Completer<void>? gate;

  Future<PetFollowResult> _answer(bool iFollow) async {
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    return PetFollowResult(iFollow: iFollow, followerCount: count);
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

DioException _http(int status, [String? slug]) {
  final req = RequestOptions(path: '/api/v1/me/pet-follows/$_kPet');
  return DioException(
    requestOptions: req,
    response: Response<Map<String, dynamic>>(
      requestOptions: req,
      statusCode: status,
      data: {if (slug != null) 'type': 'https://petgo/errors/$slug', 'status': status},
    ),
  );
}

late AppLocalizations l10n;

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  required _Repo repo,
  bool initialFollow = false,
  int? serverCount = 128,
  String? ownerToken = _kOwnerToken,
  bool? iFollowOwner = true,
  bool loggedIn = true,
  _Guide? guide,
  bool twoInstances = false,
  VoidCallback? onNotFound,
}) async {
  final container = ProviderContainer(overrides: [
    petFollowRepositoryProvider.overrideWithValue(repo),
    authControllerProvider.overrideWith(loggedIn ? _LoggedIn.new : _Guest.new),
    if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
  ]);
  addTearDown(container.dispose);
  Widget button(PetFollowButtonStyle style) => PetFollowButton(
        petId: _kPet,
        initialFollow: initialFollow,
        serverFollowerCount: serverCount,
        ownerToken: ownerToken,
        iFollowOwner: iFollowOwner,
        style: style,
        source: 'pet_visitor',
        onNotFound: onNotFound,
      );
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 300,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                button(PetFollowButtonStyle.profileFull),
                if (twoInstances) button(PetFollowButtonStyle.listCompact),
              ],
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

String _label(WidgetTester tester, [Key key = const ValueKey('petFollowButton')]) => tester
    .widget<Text>(find.descendant(of: find.byKey(key), matching: find.byType(Text)))
    .data!;

Future<void> _tap(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('petFollowButton')));
  await tester.pump();
}

void main() {
  final events = <(String, Map<String, Object>?)>[];

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  group('两态外观（复用 FollowButton._profileFull）', () {
    testWidgets('未追更 →「Follow」：mint 实心、整宽 × 40、圆角 12、14 / w700', (tester) async {
      await _pump(tester, repo: _Repo());
      expect(_label(tester), l10n.followActionFollow);
      expect(tester.getSize(find.byKey(const ValueKey('petFollowButton'))), const Size(300, 40));
      final b = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(b.style!.backgroundColor!.resolve({}), AppColors.mint);
      expect(b.style!.foregroundColor!.resolve({}), Colors.white);
      final shape = b.style!.shape!.resolve({})! as RoundedRectangleBorder;
      expect(shape.borderRadius, BorderRadius.circular(12));
      final text = tester.widget<Text>(find.text(l10n.followActionFollow));
      expect(text.style!.fontSize, 14);
      expect(text.style!.fontWeight, FontWeight.w700);
    });

    testWidgets('已追更 →「Following」：surface 底 + 1.5 line 灰框 + ink 字，尺寸不变', (tester) async {
      await _pump(tester, repo: _Repo(), initialFollow: true);
      expect(_label(tester), l10n.followActionFollowing);
      final b = tester.widget<OutlinedButton>(find.byType(OutlinedButton));
      expect(b.style!.backgroundColor!.resolve({}), AppColors.surface);
      expect(b.style!.foregroundColor!.resolve({}), AppColors.ink);
      final side = b.style!.side!.resolve({})!;
      expect(side.color, AppColors.line);
      expect(side.width, 1.5);
      expect(tester.getSize(find.byKey(const ValueKey('petFollowButton'))), const Size(300, 40));
    });
  });

  group('点击', () {
    testWidgets('追更成功：立即切 Following、人数乐观 +1、发 POST、以响应写回、报 pet_follow', (tester) async {
      final repo = _Repo(count: 130)..gate = Completer<void>();
      final c = await _pump(tester, repo: repo);

      await _tap(tester);
      expect(_label(tester), l10n.followActionFollowing);
      expect(c.read(petFollowStateProvider)[_kPet],
          const PetFollowOverride(iFollow: true, followerCount: 129), reason: '乐观 128 → 129');
      repo.gate!.complete();
      await tester.pumpAndSettle();

      expect(repo.calls, ['POST $_kPet']);
      expect(c.read(petFollowStateProvider)[_kPet], const PetFollowOverride(iFollow: true, followerCount: 130),
          reason: '成功后以响应 followerCount 为准');
      expect(events.map((e) => e.$1), ['pet_follow']);
      expect(events.single.$2, {
        'pet_id': _kPet,
        'owner_id': _kOwnerToken,
        'source': 'pet_visitor',
        'is_following_owner': true,
      });
    });

    testWidgets('取消：无二次确认、立即回 Follow、人数 −1、发 DELETE、报 pet_unfollow', (tester) async {
      final repo = _Repo(count: 127);
      final c = await _pump(tester, repo: repo, initialFollow: true);

      await _tap(tester);
      expect(_label(tester), l10n.followActionFollow);
      expect(c.read(petFollowStateProvider)[_kPet]!.followerCount, 127);
      await tester.pumpAndSettle();

      expect(find.byType(BottomSheet), findsNothing);
      expect(find.byType(Dialog), findsNothing);
      expect(repo.calls, ['DELETE $_kPet']);
      expect(events.single.$1, 'pet_unfollow');
    });

    testWidgets('R-2：缺 ownerToken / iFollowOwner（旧后端）→ 埋点省略该属性，不报 null', (tester) async {
      await _pump(tester, repo: _Repo(), ownerToken: null, iFollowOwner: null);
      await _tap(tester);
      await tester.pumpAndSettle();
      expect(events.single.$2, {'pet_id': _kPet, 'source': 'pet_visitor'});
    });

    testWidgets('人数未知（stats 未到）→ 乐观不改人数，成功后写回响应值', (tester) async {
      final repo = _Repo(count: 5)..gate = Completer<void>();
      final c = await _pump(tester, repo: repo, serverCount: null);
      await _tap(tester);
      expect(c.read(petFollowStateProvider)[_kPet]!.followerCount, isNull);
      repo.gate!.complete();
      await tester.pumpAndSettle();
      expect(c.read(petFollowStateProvider)[_kPet]!.followerCount, 5);
    });

    testWidgets('游客：弹登录、不带 pendingAction、仓储零调用、不埋点', (tester) async {
      final repo = _Repo();
      final guide = _Guide();
      await _pump(tester, repo: repo, loggedIn: false, guide: guide);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(guide.hardDialogs, 1);
      expect(guide.lastPending, isNull, reason: '登录后不自动追更');
      expect(repo.calls, isEmpty);
      expect(events, isEmpty);
      expect(_label(tester), l10n.followActionFollow);
    });

    testWidgets('请求在途时再点直接忽略，只发一次请求', (tester) async {
      final repo = _Repo()..gate = Completer<void>();
      await _pump(tester, repo: repo);

      await _tap(tester);
      await _tap(tester);
      await _tap(tester);
      repo.gate!.complete();
      await tester.pumpAndSettle();

      expect(repo.calls, hasLength(1));
      expect(_label(tester), l10n.followActionFollowing);
    });

    testWidgets('petFollowStateProvider 跨实例同步：一处点了，另一处同时变 Following', (tester) async {
      await _pump(tester, repo: _Repo(), twoInstances: true);
      const listKey = ValueKey('petFollowList_$_kPet');
      expect(_label(tester, listKey), l10n.followActionFollow);

      await _tap(tester);
      expect(_label(tester, listKey), l10n.followActionFollowing);
      await tester.pumpAndSettle();
      expect(_label(tester, listKey), l10n.followActionFollowing);
    });
  });

  group('失败回滚（A3）', () {
    double toastY(WidgetTester tester, String text) => tester.getCenter(find.text(text)).dy;

    testWidgets('追更失败：按钮与人数回滚 + 顶部 toast followFailed，不埋点', (tester) async {
      final repo = _Repo(error: DioException(requestOptions: RequestOptions(path: '/x')));
      final c = await _pump(tester, repo: repo);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollow);
      expect(c.read(petFollowStateProvider), isEmpty, reason: '点击前没有覆盖 → 删除');
      expect(find.text(l10n.followFailed), findsOneWidget);
      expect(toastY(tester, l10n.followFailed), lessThan(300), reason: '顶部 toast');
      expect(events, isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('取消失败：回到 Following + 顶部 followUnfollowFailed', (tester) async {
      final repo = _Repo(error: _http(500));
      await _pump(tester, repo: repo, initialFollow: true);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollowing);
      expect(find.text(l10n.followUnfollowFailed), findsOneWidget);
      expect(events, isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('429 rate-limited：回滚 + **底部** toast followRateLimited', (tester) async {
      final repo = _Repo(error: _http(429, 'rate-limited'));
      await _pump(tester, repo: repo);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollow);
      expect(find.text(l10n.followRateLimited), findsOneWidget);
      expect(find.text(l10n.followFailed), findsNothing);
      expect(toastY(tester, l10n.followRateLimited), greaterThan(300), reason: '底部 toast');
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('403 blocked-user 按普通失败处理（顶部 followFailed）', (tester) async {
      await _pump(tester, repo: _Repo(error: _http(403, 'blocked-user')));
      await _tap(tester);
      await tester.pumpAndSettle();
      expect(find.text(l10n.followFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('404：顶部 toast + 回调 onNotFound（页面据此重取 → A14）', (tester) async {
      var notFound = 0;
      await _pump(tester, repo: _Repo(error: _http(404, 'not-found')), onNotFound: () => notFound++);
      await _tap(tester);
      await tester.pumpAndSettle();
      expect(find.text(l10n.followFailed), findsOneWidget);
      expect(notFound, 1);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('失败时回滚到点击前已有的覆盖值（不是服务端初值）', (tester) async {
      final repo = _Repo(error: _http(500));
      final c = await _pump(tester, repo: repo);
      const prior = PetFollowOverride(iFollow: true, followerCount: 200);
      c.read(petFollowStateProvider.notifier).set(_kPet, prior);
      await tester.pump();
      expect(_label(tester), l10n.followActionFollowing);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(c.read(petFollowStateProvider)[_kPet], prior);
      expect(_label(tester), l10n.followActionFollowing);
      await tester.pump(const Duration(seconds: 3));
    });
  });

  testWidgets('换账号：覆盖表重建为空（B 看不到 A 的追更态）', (tester) async {
    final c = await _pump(tester, repo: _Repo());
    await _tap(tester);
    await tester.pumpAndSettle();
    expect(c.read(petFollowStateProvider), isNotEmpty);
    c.read(authControllerProvider.notifier).state = const AuthState(
      status: AuthStatus.authenticated,
      role: 'USER',
      profile: UserProfile(id: 2, nickname: 'B', onboardingCompleted: true),
    );
    await tester.pump();
    expect(c.read(petFollowStateProvider), isEmpty);
  });

  /// 1.4 复审：覆盖只在比服务端快照新时生效（仿 FollowStateNotifier 的 epoch）。
  group('版本号（快照接管）', () {
    test('覆盖 epoch > 快照 → 用覆盖；快照 ≥ 覆盖 → 用服务端', () {
      const o = PetFollowOverride(iFollow: true, followerCount: 129, epoch: 5);
      expect(resolvePetFollow(o, false, snapshotEpoch: 4), isTrue);
      expect(resolvePetFollowerCount(o, 134, snapshotEpoch: 4), 129);
      expect(resolvePetFollow(o, false, snapshotEpoch: 5), isFalse);
      expect(resolvePetFollowerCount(o, 134, snapshotEpoch: 5), 134);
      expect(resolvePetFollowerCount(const PetFollowOverride(iFollow: true, epoch: 9), 7), 7,
          reason: '覆盖无人数 → 回落服务端');
    });

    testWidgets('按钮：快照版本号 ≥ 覆盖时显示服务端 iFollow', (tester) async {
      final c = await _pump(tester, repo: _Repo());
      final n = c.read(petFollowStateProvider.notifier)
        ..set(_kPet, const PetFollowOverride(iFollow: true, followerCount: 129));
      await tester.pump();
      expect(_label(tester), l10n.followActionFollowing, reason: '快照 epoch 0 < 覆盖');
      // 新快照（发请求前读到 currentEpoch）→ 服务端值接管。
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 300,
                child: PetFollowButton(
                  petId: _kPet,
                  initialFollow: false,
                  followSnapshotEpoch: n.currentEpoch,
                  style: PetFollowButtonStyle.profileFull,
                  source: 'pet_visitor',
                ),
              ),
            ),
          ),
        ),
      ));
      await tester.pump();
      expect(_label(tester), l10n.followActionFollow);
    });
  });
}
