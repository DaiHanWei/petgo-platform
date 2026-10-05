import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_checkin_success_page.dart';
import 'package:tailtopia/features/pet_passport/domain/new_stamp_args.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 batch-a Story 1.1 · L0：打卡结果契约 + 详情页打卡按钮流程（AC4）+ 成功页两种形态（AC5）。
void main() {
  group('PlaceCheckinResult 线上契约（后端 PlaceCheckinResponse）', () {
    const wire = {
      'checkinToken': 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0',
      'placeToken': 'p1p1p1p1p1p1p1p1p1p1p1p1p1p1p1p1',
      'placeName': 'Kopi Kucing',
      'placeType': 'CAFE',
      'visitDate': '2026-09-30',
      'isNewStamp': true,
      'visitCount': 1,
    };

    test('字段名与后端一一对应', () {
      final r = PlaceCheckinResult.fromJson(Map<String, dynamic>.from(wire));
      expect(r.checkinToken, 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0');
      expect(r.placeToken, 'p1p1p1p1p1p1p1p1p1p1p1p1p1p1p1p1');
      expect(r.placeName, 'Kopi Kucing');
      expect(r.placeType, PlaceType.cafe);
      expect(r.visitDate, DateTime(2026, 9, 30));
      expect(r.isNewStamp, isTrue);
      expect(r.visitCount, 1);
    });

    test('Story 1.4：stampImageUrl 有值原样、缺键 → null', () {
      expect(PlaceCheckinResult.fromJson({...wire, 'stampImageUrl': 'https://cdn/s.png'}).stampImageUrl,
          'https://cdn/s.png');
      expect(PlaceCheckinResult.fromJson(Map<String, dynamic>.from(wire)).stampImageUrl, isNull);
    });

    test('Story 1.2：passportNo / stampCount；老后端缺键 → null', () {
      final r = PlaceCheckinResult.fromJson(
          {...wire, 'passportNo': 'TT02P2600128', 'stampCount': 4});
      expect(r.passportNo, 'TT02P2600128');
      expect(r.stampCount, 4);
      final old = PlaceCheckinResult.fromJson(Map<String, dynamic>.from(wire));
      expect(old.passportNo, isNull);
      expect(old.stampCount, isNull);
    });

    test('再访：isNewStamp=false + 次数', () {
      final r = PlaceCheckinResult.fromJson({...wire, 'isNewStamp': false, 'visitCount': 3});
      expect(r.isNewStamp, isFalse);
      expect(r.visitCount, 3);
    });

    test('🛡 请求只带原始坐标 + petIds（仓库不做三位小数归一）', () {
      final src = File('lib/features/place/data/place_repository.dart').readAsStringSync();
      final method = src.substring(src.indexOf('Future<PlaceCheckinResult> checkIn('));
      final body = method.substring(0, method.indexOf('\n  }\n'));
      expect(body.contains('_round('), isFalse);
      expect(body.contains('placeDetailQueryFor('), isFalse);
      expect(body.contains("'latitude': latitude"), isTrue);
    });
  });

  group('详情页打卡按钮（AC4）', () {
    testWidgets('已登录 + 已授权 → 原始坐标提交 → 进成功页（新章形态）', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.granted);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();

      expect(repo.checkInCalls, 1);
      expect(repo.lastLat, -6.2123456, reason: '🔴 必须原始精度，不得归一到 3 位小数');
      expect(repo.lastPetIds, [77]);
      expect(find.byType(PlaceCheckinSuccessPage), findsOneWidget);
      expect(find.text('Momo dapat cap baru'), findsOneWidget);
    });

    testWidgets('永久拒绝 → 弹「Izinkan lokasi」，「Buka Pengaturan」走 openSettings，不打卡', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.permanentlyDenied);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('placeCheckinLocationDialog')), findsOneWidget);
      expect(find.text('Izinkan lokasi'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('placeCheckinOpenSettings')));
      await tester.pumpAndSettle();
      expect(gw.settingsOpened, 1);
      expect(repo.checkInCalls, 0);
    });

    testWidgets('本次拒绝 → request 仍拒绝 → 不打卡、按钮仍可点', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.denied);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(gw.requested, 1);
      expect(repo.checkInCalls, 0);
      expect(_button(tester).onPressed, isNotNull);
    });

    // L2 验收 2026-10-05：打卡时经系统框刚授权，详情页的定位态（只在进页时读一次）没更新 → 距离行一直不出现。
    testWidgets('本次拒绝 → request 授权 → 打卡成功，流程结束后页面重读定位态', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.denied, afterRequest: LocationPermissionOutcome.granted);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(gw.requested, 1);
      expect(repo.checkInCalls, 1);
      // 打卡流程自己读 1 次；页面定位态失效重建再读 1 次。
      expect(gw.statusReads, 2);
    });

    testWidgets('已授权直接打卡 → 不额外重读定位态（只在本次刚授权时才失效）', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.granted);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(repo.checkInCalls, 1);
      expect(gw.statusReads, 1);
    });

    // 待确认 1.2（2026-10-02）：本次拒绝并勾「不再询问」→ 当场弹去设置，不等下一次点击。
    testWidgets('本次拒绝变成永久拒绝 → 当场弹「Izinkan lokasi」、不打卡', (tester) async {
      final repo = _Repo();
      final gw = _Gateway(LocationPermissionOutcome.denied,
          afterRequest: LocationPermissionOutcome.permanentlyDenied);
      await _pumpDetail(tester, repo: repo, gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(gw.requested, 1);
      expect(find.byKey(const ValueKey('placeCheckinLocationDialog')), findsOneWidget);
      expect(repo.checkInCalls, 0);
    });

    testWidgets('GPS 取不到 → 轻提示「Lokasi belum ketemu」', (tester) async {
      final gw = _Gateway(LocationPermissionOutcome.granted, coords: null);
      await _pumpDetail(tester, repo: _Repo(), gateway: gw);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('Lokasi belum ketemu, coba lagi'), findsOneWidget);
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    testWidgets('checkin-too-far → 提示「Kamu harus berada di lokasi」', (tester) async {
      final repo = _Repo(failSlug: 'checkin-too-far', failStatus: 422);
      await _pumpDetail(tester, repo: repo, gateway: _Gateway(LocationPermissionOutcome.granted));

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('Kamu harus berada di lokasi untuk check-in'), findsOneWidget);
      expect(_button(tester).onPressed, isNotNull);
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    testWidgets('checkin-already-today（409）→ 按钮切禁用态「Sudah check-in hari ini」', (tester) async {
      final repo = _Repo(failSlug: 'checkin-already-today', failStatus: 409);
      await _pumpDetail(tester, repo: repo, gateway: _Gateway(LocationPermissionOutcome.granted));

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(_button(tester).onPressed, isNull);
      expect(find.text('Sudah check-in hari ini'), findsOneWidget);
    });

    testWidgets('服务端下发 checkedInToday=true → 禁用态、不隐藏', (tester) async {
      await _pumpDetail(tester,
          repo: _Repo(checkedInToday: true), gateway: _Gateway(LocationPermissionOutcome.granted));

      expect(find.byKey(const ValueKey('placeCheckinButton')), findsOneWidget);
      expect(_button(tester).onPressed, isNull);
      expect(find.text('Sudah check-in hari ini'), findsOneWidget);
    });

    testWidgets('游客点击 → 走 requireLogin 强引导，不读定位', (tester) async {
      final guide = _Guide();
      final gw = _Gateway(LocationPermissionOutcome.granted);
      await _pumpDetail(tester, repo: _Repo(), gateway: gw, loggedIn: false, guide: guide);

      await tester.tap(find.byKey(const ValueKey('placeCheckinButton')));
      await tester.pumpAndSettle();
      expect(guide.hardDialogs, 1);
      expect(gw.statusReads, 0, reason: '先过登录闸门，定位查询留到登录之后');
    });

    test('复审：打卡成功后失效护照缓存（栈里的护照页能看到新章）', () {
      final src = File('lib/features/place/presentation/place_checkin_button.dart').readAsStringSync();
      expect(src.contains('container.invalidate(petPassportProvider)'), isTrue);
    });

    test('🔴 源码：按钮在距离行之后、描述之前；页面仍不挂 bottomNavigationBar', () {
      final src = File('lib/features/place/presentation/place_detail_page.dart').readAsStringSync();
      final distance = src.indexOf('formatPlaceDistance(l10n, p.distanceMeters!)');
      final button = src.indexOf('PlaceCheckinButton(token: token');
      final description = src.indexOf('if (p.description != null)');
      expect(distance, greaterThan(0));
      expect(button, greaterThan(distance));
      expect(description, greaterThan(button));
      expect(src.contains('bottomNavigationBar:'), isFalse);
    });
  });

  group('成功页（AC5）', () {
    testWidgets('2026-10-06：护照里有这枚章 → 正文为 1.3 章详情（护照本 + 地点信息），不出「See place」', (tester) async {
      final passport = PetPassport.fromJson({
        'petName': 'Momo',
        'passportNo': 'TT02P2600128',
        'currentVersionUnlocked': false,
        'stamps': [
          {
            'placeToken': 'p' * 32,
            'placeName': 'Kopi Kucing',
            'placeType': 'CAFE',
            'placeStatus': 'ACTIVE',
            'visitCount': 1,
            'firstVisitDate': '2026-09-30',
            'addressText': 'Jl. Kemang 1',
          },
        ],
      });
      await _pumpSuccess(tester, isNew: true, count: 1, passport: passport);
      expect(find.byKey(const ValueKey('placeCheckinSuccessTitle')), findsOneWidget);
      expect(find.byKey(const ValueKey('passportStampBook')), findsOneWidget);
      expect(find.byKey(const ValueKey('passportStampAddress')), findsOneWidget);
      expect(find.byKey(const ValueKey('passportSeePlace')), findsNothing, reason: '成功页本就从场所页进来');
      expect(find.byKey(const ValueKey('placeCheckinNewStamp')), findsNothing, reason: '简版章面只做兜底');
      expect(find.byKey(const ValueKey('placeCheckinViewPassport')), findsOneWidget);
      expect(find.byType(CardWatermark), findsNothing, reason: '打卡成功页不叠水印（即使当前版本未买）');
    });

    testWidgets('Story 1.2 / 1.3：点「Lihat Paspor」→ 先进 B4 整页落章（带新章参数）', (tester) async {
      NewStampArgs? got;
      final router = GoRouter(routes: [
        GoRoute(
          path: '/',
          builder: (c, s) => PlaceCheckinSuccessPage(
            args: PlaceCheckinSuccessArgs(
              petName: 'Momo',
              result: PlaceCheckinResult(
                checkinToken: 'c' * 32,
                placeToken: 'p' * 32,
                placeName: 'Kopi Kucing',
                placeType: PlaceType.cafe,
                visitDate: DateTime(2026, 9, 30),
                isNewStamp: true,
                visitCount: 1,
                stampCount: 5,
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/profile/pet-insights/passport/new-stamp',
          builder: (c, s) {
            got = s.extra as NewStampArgs?;
            return const Scaffold(body: Text('b4'));
          },
        ),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [petPassportProvider.overrideWith((ref) async => throw StateError('offline'))],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('placeCheckinViewPassport')));
      await tester.pumpAndSettle();
      expect(find.text('b4'), findsOneWidget);
      expect(got?.placeToken, 'p' * 32);
      expect(got?.stampCount, 5);
      expect(got?.placeType, PlaceType.cafe);
    });

    // L2 验收 2026-10-05：360dp 屏上英文「View Passport」被截成「View Pas…」。两个按钮的字都必须完整排出（放不下就缩小）。
    for (final locale in const [Locale('en'), Locale('id')]) {
      testWidgets('新章底部两按钮：360dp 宽下文字不截断（${locale.languageCode}）', (tester) async {
        tester.view.physicalSize = const Size(1080, 2400);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        await _pumpSuccess(tester, isNew: true, count: 1, locale: locale);
        for (final key in ['placeCheckinViewPassport', 'placeCheckinRecordMoment']) {
          final text = find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Text));
          final paragraph = tester.renderObject<RenderParagraph>(
              find.descendant(of: text, matching: find.byType(RichText)));
          expect(paragraph.didExceedMaxLines, isFalse, reason: '$key 文字被截断');
          final button = tester.getRect(find.byKey(ValueKey(key)));
          final label = tester.getRect(text);
          expect(button.contains(label.topLeft) && button.contains(label.bottomRight - const Offset(0.1, 0.1)), isTrue,
              reason: '$key 文字超出按钮');
        }
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('新章（C2）：章面 + 「{pet} dapat cap baru」+「Lihat Paspor」（Story 1.2）', (tester) async {
      await _pumpSuccess(tester, isNew: true, count: 1);

      expect(
          find.byWidgetPredicate((w) =>
              w is Image &&
              w.image is AssetImage &&
              (w.image as AssetImage).assetName == 'assets/place_stamp/cafe.png'),
          findsOneWidget,
          reason: '无专属章：按场所类型显示包内默认章');
      expect(find.text('Check-in berhasil!'), findsOneWidget);
      expect(find.text('Momo dapat cap baru'), findsOneWidget);
      expect(find.byKey(const ValueKey('placeCheckinVisitBadge')), findsNothing);
      // Story 1.2 · AC6：仅新章出「Lihat Paspor」；Story 1.5 起同一行并排主 CTA「Rekam Momen Ini」。
      expect(find.text('Lihat Paspor'), findsOneWidget);
      expect(find.text('Rekam Momen Ini'), findsOneWidget);
    });

    testWidgets('Story 1.5 · D-14：C2 两个按钮同一 Row、主 CTA 更宽；C2b 只有主 CTA 整宽', (tester) async {
      await _pumpSuccess(tester, isNew: true, count: 1);
      final row = find.byKey(const ValueKey('placeCheckinActions'));
      final ghost = find.byKey(const ValueKey('placeCheckinViewPassport'));
      final primary = find.byKey(const ValueKey('placeCheckinRecordMoment'));
      expect(find.descendant(of: row, matching: ghost), findsOneWidget);
      expect(find.descendant(of: row, matching: primary), findsOneWidget);
      expect(tester.getSize(primary).width, greaterThan(tester.getSize(ghost).width));
      expect(tester.getSize(primary).height, tester.getSize(ghost).height);
      expect(tester.getSize(primary).height, greaterThanOrEqualTo(44));
      expect(find.text('Rekam Momen Ini'), findsOneWidget);
      expect(find.byIcon(Icons.photo_camera_outlined), findsOneWidget);

      await _pumpSuccess(tester, isNew: false, count: 2);
      expect(find.byKey(const ValueKey('placeCheckinViewPassport')), findsNothing);
      final solo = tester.getSize(find.byKey(const ValueKey('placeCheckinRecordMoment')));
      expect(solo.width, greaterThan(tester.getSize(find.byKey(const ValueKey('placeCheckinActions'))).width - 2));
    });

    test('源码：主 CTA 打开发帖页 = Diary 预选 + 打卡 token', () {
      final src = File('lib/features/place/presentation/place_checkin_success_page.dart').readAsStringSync();
      final at = src.indexOf('PublishComposePage.open(');
      final block = src.substring(at, src.indexOf('Icons.photo_camera_outlined', at));
      expect(block, contains('preset: ContentType.growthMoment'));
      expect(block, contains('placeCheckinToken: r.checkinToken'));
    });

    testWidgets('再访（C2b）：角标 ×n +「Cap … sekarang n×」；无「Lihat Paspor」', (tester) async {
      await _pumpSuccess(tester, isNew: false, count: 3);

      expect(find.byKey(const ValueKey('placeCheckinVisitBadge')), findsOneWidget);
      expect(find.text('×3'), findsOneWidget);
      expect(find.text('Cap Kopi Kucing sekarang 3×'), findsOneWidget);
      expect(find.byKey(const ValueKey('placeCheckinNewStamp')), findsNothing,
          reason: 'C2b 不播落章');
      expect(find.text('Lihat Paspor'), findsNothing);
    });
  });
}

// ===== fakes & harness =====

class _Gateway implements LocationGateway {
  _Gateway(this.outcome,
      {this.coords = const DeviceCoordinates(latitude: -6.2123456, longitude: 106.8123456), this.afterRequest});

  final LocationPermissionOutcome outcome;

  /// `request()` 的返回（null = 与 [outcome] 相同）。
  final LocationPermissionOutcome? afterRequest;
  final DeviceCoordinates? coords;
  int requested = 0;
  int settingsOpened = 0;
  int statusReads = 0;

  @override
  Future<LocationPermissionOutcome> status() async {
    statusReads++;
    return outcome;
  }

  @override
  Future<LocationPermissionOutcome> request() async {
    requested++;
    return afterRequest ?? outcome;
  }

  @override
  Future<DeviceCoordinates?> currentCoordinates() async => coords;

  @override
  Future<bool> openSettings() async {
    settingsOpened++;
    return true;
  }
}

class _Repo implements PlaceRepository {
  _Repo({this.failSlug, this.failStatus, this.checkedInToday});

  final String? failSlug;
  final int? failStatus;
  final bool? checkedInToday;
  int checkInCalls = 0;
  double? lastLat;
  List<int>? lastPetIds;

  @override
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async =>
      PlaceDetail.fromJson({
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
      });

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async =>
      PlaceCommentPage.empty;

  @override
  Future<PlaceCheckinResult> checkIn(String token,
      {required double latitude, required double longitude, required List<int> petIds}) async {
    checkInCalls++;
    lastLat = latitude;
    lastPetIds = petIds;
    if (failSlug != null) {
      final ro = RequestOptions(path: '/api/v1/places/$token/checkins');
      throw DioException(
        requestOptions: ro,
        response: Response(requestOptions: ro, statusCode: failStatus, data: {
          'type': 'https://petgo/errors/$failSlug',
          'status': failStatus,
        }),
        type: DioExceptionType.badResponse,
      );
    }
    return PlaceCheckinResult(
      checkinToken: 'c' * 32,
      placeToken: token,
      placeName: 'Kopi Kucing',
      placeType: PlaceType.cafe,
      visitDate: DateTime(2026, 9, 30),
      isNewStamp: true,
      visitCount: 1,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  _Auth(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
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

ButtonStyleButton _button(WidgetTester tester) =>
    tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('placeCheckinButton')));

Future<void> _pumpDetail(
  WidgetTester tester, {
  required _Repo repo,
  required _Gateway gateway,
  bool loggedIn = true,
  _Guide? guide,
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = const Size(400, 1400);
  addTearDown(tester.view.reset);
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const PlaceDetailPage(token: 'tok')),
    GoRoute(
      path: PlaceCheckinSuccessPage.routePattern,
      builder: (c, s) => PlaceCheckinSuccessPage(args: s.extra! as PlaceCheckinSuccessArgs),
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      // 列表页 / 详情页的定位态闸门与打卡按钮共用同一个 gateway。
      locationGatewayProvider.overrideWithValue(gateway),
      placeRepositoryProvider.overrideWithValue(repo),
      authControllerProvider.overrideWith(() => _Auth(loggedIn
          ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
          : const AuthState.guest())),
      petProfileProvider.overrideWith(
          (ref) async => const PetProfile(id: 77, name: 'Momo', cardToken: 'card')),
      if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ),
  ));
  await tester.pumpAndSettle();
  // 定位闸门里的 status() 读取不算按钮流程的一次。
  gateway.statusReads = 0;
}

/// [passport] 为 null：护照取不到 → 成功页走简版章面兜底（C2 / C2b 原样）；非 null：正文为 1.3 章详情块。
Future<void> _pumpSuccess(WidgetTester tester,
    {required bool isNew, required int count, Locale locale = const Locale('id'), PetPassport? passport}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      petPassportProvider.overrideWith(
          (ref) async => passport ?? (throw StateError('offline'))),
    ],
    child: MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: locale,
    home: PlaceCheckinSuccessPage(
      args: PlaceCheckinSuccessArgs(
        petName: 'Momo',
        result: PlaceCheckinResult(
          checkinToken: 'c' * 32,
          placeToken: 'p' * 32,
          placeName: 'Kopi Kucing',
          placeType: PlaceType.cafe,
          visitDate: DateTime(2026, 9, 30),
          isNewStamp: isNew,
          visitCount: count,
        ),
      ),
    ),
    ),
  ));
  await tester.pumpAndSettle();
}
