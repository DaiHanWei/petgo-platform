import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/presentation/place_checkin_success_page.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/features/place/presentation/place_stamp_ceremony_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 3.3 · AC5：场所打卡选宠物（G1 / G2 / G4）。
void main() {
  const orenji = PetListItem(id: 1, name: 'Orenji', petType: 'CAT');
  const kopi = PetListItem(id: 2, name: 'Kopi', petType: 'DOG');
  const mochi = PetListItem(id: 3, name: 'Mochi');
  const locked = PetListItem(id: 4, name: 'Bubu', status: PetListItem.statusReadOnly);

  late PlaceCheckinSuccessArgs? landed;

  Future<_Repo> pump(
    WidgetTester tester, {
    required List<PetListItem> pets,
    List<int>? checkedInPetIds,
    bool? checkedInToday = false,
    String? failSlug,
    bool withPetStamps = true,
  }) async {
    landed = null;
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(400, 1400);
    addTearDown(tester.view.reset);
    final repo = _Repo(
      checkedInPetIds: checkedInPetIds,
      checkedInToday: checkedInToday,
      failSlug: failSlug,
      withPetStamps: withPetStamps,
    );
    Widget capture(GoRouterState s) {
      landed = s.extra! as PlaceCheckinSuccessArgs;
      return const Scaffold(body: Text('SUCCESS'));
    }

    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const PlaceDetailPage(token: 'tok')),
      GoRoute(path: PlaceStampCeremonyPage.routePattern, builder: (c, s) => capture(s)),
      GoRoute(path: PlaceCheckinSuccessPage.routePattern, builder: (c, s) => capture(s)),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        locationGatewayProvider.overrideWithValue(_Gateway()),
        placeRepositoryProvider.overrideWithValue(repo),
        authControllerProvider.overrideWith(_Auth.new),
        petListProvider.overrideWith((ref) async => pets),
        petPassportProvider.overrideWith((ref) => Future<PetPassport>.error(StateError('offline'))),
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
    return repo;
  }

  Future<void> tapCheckin(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
    await tester.pumpAndSettle();
  }

  ButtonStyleButton submit(WidgetTester tester) =>
      tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('petPickerSubmit')));

  testWidgets('G4：只 1 只 ACTIVE（另 1 只只读）→ 不弹选择器，直接提交它', (tester) async {
    final repo = await pump(tester, pets: const [orenji, locked]);
    await tapCheckin(tester);
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(repo.lastPetIds, [1]);
    expect(find.text('SUCCESS'), findsOneWidget);
    expect(landed!.pets.single.petId, 1);
    expect(landed!.petName, 'Orenji');
  });

  testWidgets('0 只 ACTIVE → 现状 Toast placeCheckinNoPet，不提交', (tester) async {
    final repo = await pump(tester, pets: const [locked]);
    await tapCheckin(tester);
    expect(repo.checkInCalls, 0);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.placeCheckinNoPet), findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 5));
  });

  testWidgets('G1 / G2：≥ 2 只弹抽屉（只读不出现）；默认全不选按钮禁用；已打卡置灰不可点；选 2 只 → Check-in (2) 提交两项',
      (tester) async {
    final repo = await pump(tester, pets: const [orenji, kopi, mochi, locked], checkedInPetIds: const [2]);
    await tapCheckin(tester);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));

    expect(find.text(l10n.petPickerCheckinTitle), findsOneWidget);
    expect(find.text(l10n.petPickerCheckinBody), findsOneWidget);
    for (final id in [1, 2, 3]) {
      expect(find.byKey(ValueKey('petPickerRow-$id')), findsOneWidget);
    }
    expect(find.byKey(const ValueKey('petPickerRow-4')), findsNothing);
    expect(submit(tester).onPressed, isNull);

    // 已打卡的 Kopi：副标题换 G-3、尾部 block、点了不选中。
    expect(find.descendant(of: find.byKey(const ValueKey('petPickerRow-2')), matching: find.text(l10n.petPickerCheckedInHere)),
        findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('petPickerRow-2')), matching: find.byIcon(Icons.block)),
        findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('petPickerRow-2')));
    await tester.pump();
    expect(submit(tester).onPressed, isNull);

    // 先点 Mochi 再点 Orenji：提交按创建顺序。
    await tester.tap(find.byKey(const ValueKey('petPickerRow-3')));
    await tester.pump();
    expect(find.text(l10n.petPickerCheckinSubmit(1)), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('petPickerRow-1')));
    await tester.pump();
    expect(find.text('Check-in (2)'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('petPickerSubmit')));
    await tester.pumpAndSettle();

    expect(repo.lastPetIds, [1, 3]);
    expect(landed!.pets.map((p) => p.petId), [1, 3]);
    expect(landed!.pets.first.name, 'Orenji');
    // 逐宠物的章取自响应 pets[]（Orenji 新章 / Mochi 再访 ×3）。
    expect(landed!.pets.first.isNewStamp, isTrue);
    expect(landed!.pets.last.isNewStamp, isFalse);
    expect(landed!.pets.last.visitCount, 3);
  });

  testWidgets('关闭抽屉 = 取消本次打卡：不提交、按钮复位可点、不 Toast', (tester) async {
    final repo = await pump(tester, pets: const [orenji, kopi]);
    await tapCheckin(tester);
    expect(find.byKey(const ValueKey('petPickerTitle')), findsOneWidget);
    await tester.tapAt(const Offset(200, 20)); // 点遮罩关闭
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerTitle')), findsNothing);
    expect(repo.checkInCalls, 0);
    final button = tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('placeCheckinButton')));
    expect(button.onPressed, isNotNull);
  });

  testWidgets('后端没下发逐宠物今日状态（checkedInPetIds 缺键）→ 降级：抽屉不置灰任何行', (tester) async {
    await pump(tester, pets: const [orenji, kopi], checkedInPetIds: null);
    await tapCheckin(tester);
    expect(find.byIcon(Icons.block), findsNothing);
  });

  testWidgets('多宠 checkin-already-today（409）→ 刷新场所详情 + 失败文案，不进成功页、按钮仍可点', (tester) async {
    final repo = await pump(tester, pets: const [orenji, kopi], failSlug: 'checkin-already-today');
    final fetchesBefore = repo.detailFetches;
    await tapCheckin(tester);
    await tester.tap(find.byKey(const ValueKey('petPickerRow-1')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('petPickerSubmit')));
    await tester.pumpAndSettle();
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.placeCheckinFailed), findsOneWidget);
    expect(find.text('SUCCESS'), findsNothing);
    expect(repo.detailFetches, greaterThan(fetchesBefore));
    final button = tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('placeCheckinButton')));
    expect(button.onPressed, isNotNull);
    await tester.pumpAndSettle(const Duration(seconds: 5));
  });

  testWidgets('后端无逐宠物章（pets 缺键）→ 每只都按顶层字段（降级）', (tester) async {
    await pump(tester, pets: const [orenji, kopi], withPetStamps: false);
    await tapCheckin(tester);
    await tester.tap(find.byKey(const ValueKey('petPickerRow-1')));
    await tester.tap(find.byKey(const ValueKey('petPickerRow-2')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('petPickerSubmit')));
    await tester.pumpAndSettle();
    expect(landed!.pets.every((p) => p.isNewStamp), isTrue);
  });

  testWidgets('场所详情「已打卡」态沿用后端 checkedInToday（全部正常宠物都打过才 true）', (tester) async {
    await pump(tester, pets: const [orenji, kopi], checkedInToday: true, checkedInPetIds: const [1, 2]);
    final button = tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('placeCheckinButton')));
    expect(button.onPressed, isNull);
  });
}

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

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Repo implements PlaceRepository {
  _Repo({this.checkedInPetIds, this.checkedInToday, this.failSlug, this.withPetStamps = true});

  final List<int>? checkedInPetIds;
  final bool? checkedInToday;
  final String? failSlug;
  final bool withPetStamps;
  int checkInCalls = 0;
  int detailFetches = 0;
  List<int>? lastPetIds;

  @override
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async {
    detailFetches++;
    return PlaceDetail.fromJson({
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
      'checkedInToday': ?checkedInToday,
      'checkedInPetIds': ?checkedInPetIds,
    });
  }

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async => PlaceCommentPage.empty;

  @override
  Future<PlaceCheckinResult> checkIn(String token,
      {required double latitude, required double longitude, required List<int> petIds}) async {
    checkInCalls++;
    lastPetIds = petIds;
    if (failSlug != null) {
      final ro = RequestOptions(path: '/api/v1/places/$token/checkins');
      throw DioException(
        requestOptions: ro,
        response: Response(requestOptions: ro, statusCode: 409, data: {
          'type': 'https://petgo/errors/$failSlug',
          'status': 409,
        }),
        type: DioExceptionType.badResponse,
      );
    }
    return PlaceCheckinResult.fromJson({
      'checkinToken': 'c' * 32,
      'placeToken': token,
      'placeName': 'Kopi Kucing',
      'placeType': 'CAFE',
      'visitDate': '2026-10-18',
      'isNewStamp': true,
      'visitCount': 1,
      if (withPetStamps)
        'pets': [
          for (final id in petIds)
            {'petId': id, 'isNewStamp': id == petIds.first, 'visitCount': id == petIds.first ? 1 : 3},
        ],
    });
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}
