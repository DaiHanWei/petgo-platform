import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/rive/rive_runtime.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_checkin_success_page.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/features/place/presentation/place_stamp_ceremony_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 签到盖章动画 · Task 3 · L0：签到按钮按 `isNewStamp` 分流（新章 → 动画页；再访 → 直接成功页）。
int _ceremonyRouteBuilds = 0;

void main() {
  setUp(() {
    RiveRuntime.available = true; // 假动画组件用例默认 Rive 可用
    _ceremonyRouteBuilds = 0;
  });
  tearDown(() => RiveRuntime.available = false);

  testWidgets('newStampGoesThroughCeremony', (tester) async {
    final ceremonies = <StampCeremonyRequest>[];
    await _pumpDetail(tester, isNewStamp: true, ceremonies: ceremonies);

    await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(find.byType(PlaceStampCeremonyPage), findsOneWidget);
    expect(find.byType(PlaceCheckinSuccessPage), findsNothing);
    expect(ceremonies, hasLength(1));

    ceremonies.single.onComplete();
    await tester.pump(const Duration(milliseconds: 750)); // 停 400ms（墙钟计时），随后成功页 300ms 淡入
    await tester.pumpAndSettle();
    expect(find.byType(PlaceStampCeremonyPage), findsNothing);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(find.text('Momo dapat cap baru'), findsOneWidget);

    // 返回栈：成功页 → 场所详情。
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byType(PlaceDetailPage), findsOneWidget);
    expect(find.byType(PlaceStampCeremonyPage), findsNothing);
  });

  testWidgets('iOS：播过动画后的成功页保留边缘右滑返回（回到详情）', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    final ceremonies = <StampCeremonyRequest>[];
    await _pumpDetail(tester, isNewStamp: true, ceremonies: ceremonies);

    await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    ceremonies.single.onComplete();
    await tester.pump(const Duration(milliseconds: 750));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(tester.widget<PlaceCheckinSuccessPage>(find.byType(PlaceCheckinSuccessPage)).args.ceremonyPlayed,
        isTrue);
    final route = ModalRoute.of(tester.element(find.byType(PlaceCheckinSuccessPage)))! as PageRoute<void>;
    expect(route.popGestureEnabled, isTrue);

    await tester.dragFrom(const Offset(5, 300), const Offset(380, 0));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceCheckinSuccessPage), findsNothing);
    expect(find.byType(PlaceDetailPage), findsOneWidget);
    debugDefaultTargetPlatformOverride = null; // 须在用例结束前复位（测试框架校验）
  });

  testWidgets('revisitSkipsCeremony', (tester) async {
    final ceremonies = <StampCeremonyRequest>[];
    await _pumpDetail(tester, isNewStamp: false, ceremonies: ceremonies);

    await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceStampCeremonyPage), findsNothing);
    expect(ceremonies, isEmpty, reason: '再访绝不进入动画页');
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
  });

  testWidgets('Rive 原生库不可用（真组件）→ 新章签到照常进成功页、保留小落章', (tester) async {
    RiveRuntime.available = false;
    await _pumpDetail(tester, isNewStamp: true, ceremonies: null);

    await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
    await tester.pumpAndSettle();
    expect(_ceremonyRouteBuilds, 0, reason: 'Rive 不可用时绝不构建动画页路由');
    expect(find.byType(PlaceStampCeremonyPage), findsNothing);
    expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
    expect(tester.widget<PlaceCheckinSuccessPage>(find.byType(PlaceCheckinSuccessPage)).args.ceremonyPlayed,
        isFalse);
  });
}

// ===== fakes & harness =====

class _Gateway implements LocationGateway {
  @override
  Future<LocationPermissionOutcome> status() async => LocationPermissionOutcome.granted;

  @override
  Future<LocationPermissionOutcome> request() async => LocationPermissionOutcome.granted;

  @override
  Future<DeviceCoordinates?> currentCoordinates() async =>
      const DeviceCoordinates(latitude: -6.2123456, longitude: 106.8123456);

  @override
  Future<bool> openSettings() async => true;
}

class _Repo implements PlaceRepository {
  _Repo(this.isNewStamp);

  final bool isNewStamp;

  @override
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async => PlaceDetail.fromJson({
        'token': token,
        'name': 'Kopi Kucing',
        'type': 'CAFE',
        'tags': const [],
        'photos': const [],
        'addressText': 'Jl. X',
        'latitude': -6.2,
        'longitude': 106.8,
        'markedBy': const {'userId': 1},
        'commentCount': 0,
        'recommendCount': 0,
        'notRecommendCount': 0,
        'photoSlotsRemaining': 9,
      });

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async => PlaceCommentPage.empty;

  @override
  Future<PlaceCheckinResult> checkIn(String token,
          {required double latitude, required double longitude, required List<int> petIds}) async =>
      PlaceCheckinResult(
        checkinToken: 'c' * 32,
        placeToken: token,
        placeName: 'Kopi Kucing',
        placeType: PlaceType.cafe,
        visitDate: DateTime(2026, 9, 30),
        isNewStamp: isNewStamp,
        visitCount: isNewStamp ? 1 : 3,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

/// [ceremonies] 非 null → 注入假动画组件并收集请求；null → 用真 `StampCeremony`（测 Rive 不可用兜底）。
Future<void> _pumpDetail(WidgetTester tester,
    {required bool isNewStamp, required List<StampCeremonyRequest>? ceremonies}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = const Size(400, 1400);
  addTearDown(tester.view.reset);
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const PlaceDetailPage(token: 'tok')),
    GoRoute(
      path: PlaceStampCeremonyPage.routePattern,
      builder: (c, s) {
        _ceremonyRouteBuilds++;
        return PlaceStampCeremonyPage(
        args: s.extra! as PlaceCheckinSuccessArgs,
        faceLoader: ({String? url, PlaceType? type}) async => Uint8List.fromList([1, 2, 3]),
        ceremonyBuilder: ceremonies == null
            ? null
            : (context, request) {
                if (!ceremonies.contains(request)) {
                  ceremonies.add(request);
                }
                return const SizedBox.expand();
              },
      );
      },
    ),
    GoRoute(
      path: PlaceCheckinSuccessPage.routePattern,
      pageBuilder: (c, s) => PlaceCheckinSuccessPage.pageFor(s, s.extra! as PlaceCheckinSuccessArgs),
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      locationGatewayProvider.overrideWithValue(_Gateway()),
      placeRepositoryProvider.overrideWithValue(_Repo(isNewStamp)),
      authControllerProvider.overrideWith(_Auth.new),
      petProfileProvider.overrideWith((ref) async => const PetProfile(id: 77, name: 'Momo', cardToken: 'card')),
      petPassportProvider.overrideWith((ref) async => throw StateError('offline')),
      milestoneInstantPollDelaysProvider.overrideWithValue(const []),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ),
  ));
  await tester.pumpAndSettle();
}
