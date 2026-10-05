import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/boarding_pass/data/boarding_pass_repository.dart';
import 'package:tailtopia/features/boarding_pass/domain/boarding_pass.dart';
import 'package:tailtopia/features/boarding_pass/presentation/boarding_pass_detail_page.dart';
import 'package:tailtopia/features/boarding_pass/presentation/boarding_pass_list_page.dart';
import 'package:tailtopia/features/boarding_pass/presentation/widgets/boarding_pass_card.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 3.5 · L0：wire 契约、列表两态 + 次数 + 空态、详情（PASSPORT 独占一行 / 水印 / 吸底 / 下架 / 占位图）、B7b 与购买。
void main() {
  Map<String, dynamic> detailJson({bool unlocked = false, String status = 'ACTIVE', String? image}) => {
        'placeToken': 'p' * 32,
        'passenger': 'Momo',
        'breed': 'Anggora',
        'petType': 'CAT',
        'placeName': 'Taman Menteng',
        'passportNo': 'TT02P2600128',
        'lastVisitDate': '2026-09-28',
        'firstVisitDate': '2026-09-01',
        'visitCount': 3,
        'seat': '02A',
        'placeType': 'PARK',
        'placeStatus': status,
        'placeImageUrl': ?image,
        if (status == 'ACTIVE') 'addressText': 'Jl. Menteng 1',
        if (status == 'ACTIVE') 'city': 'Jakarta',
        'unlocked': unlocked,
      };

  group('wire', () {
    test('列表：缺 unlocked / placeStatus fail-closed；次数 ≥1', () {
      final l = BoardingPassList.fromJson({
        'petName': 'Momo',
        'passportNo': 'TT02P2600128',
        'items': [
          {'placeToken': 'a', 'placeName': 'A', 'placeType': 'CAFE', 'placeStatus': 'ACTIVE', 'visitCount': 2,
            'unlocked': true, 'lastVisitDate': '2026-09-20'},
          {'placeToken': 'b', 'placeName': 'B'},
          {'placeName': 'no token'},
        ],
      });
      expect(l.items, hasLength(2));
      expect(l.items[0].unlocked, isTrue);
      expect(l.items[0].available, isTrue);
      expect(l.items[1].unlocked, isFalse);
      expect(l.items[1].available, isFalse);
      expect(l.items[1].visitCount, 1);
    });

    test('详情：全字段；UNAVAILABLE 无地址', () {
      final d = BoardingPassDetail.fromJson(detailJson(status: 'UNAVAILABLE'));
      expect(d.passportNo, 'TT02P2600128');
      expect(d.seat, '02A');
      expect(d.available, isFalse);
      expect(d.addressText, isNull);
      expect(d.placeImageUrl, isNull);
      expect(d.unlockToken, isNull);
    });
  });

  Future<_Repo> pump(WidgetTester tester, _Repo repo, {required String initial, double width = 400}) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = Size(width, 1200);
    addTearDown(tester.view.reset);
    final router = GoRouter(initialLocation: initial, routes: [
      GoRoute(path: PetInsightsRoutes.boardingPass, builder: (c, s) => const BoardingPassListPage()),
      GoRoute(
          path: PetInsightsRoutes.boardingPassDetail,
          builder: (c, s) => BoardingPassDetailPage(placeToken: s.pathParameters['placeToken']!)),
      GoRoute(path: '/places', builder: (c, s) => const Scaffold(body: Text('places-list'))),
      GoRoute(path: '/places/:token', builder: (c, s) => Scaffold(body: Text('place:${s.pathParameters['token']}'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        boardingPassRepositoryProvider.overrideWithValue(repo),
        keepsakePricingProvider.overrideWith((ref) async => const KeepsakePricing(
            ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000)),
        pawCoinProvider.overrideWith(_Paw.new),
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

  group('列表 B3（AC8）', () {
    testWidgets('已解锁「Terbuka」/ 未解锁「Rp1.000」+ 次数角标；点卡进详情', (tester) async {
      final repo = _Repo(BoardingPassDetail.fromJson(detailJson()));
      repo.listResult = BoardingPassList.fromJson({
        'petName': 'Momo',
        'passportNo': 'TT02P2600128',
        'items': [
          {'placeToken': 'a', 'placeName': 'Kopi A', 'placeType': 'CAFE', 'placeStatus': 'ACTIVE', 'visitCount': 3,
            'unlocked': true, 'lastVisitDate': '2026-09-20'},
          {'placeToken': 'b', 'placeName': 'Taman B', 'placeType': 'PARK', 'placeStatus': 'UNAVAILABLE',
            'visitCount': 1, 'unlocked': false, 'lastVisitDate': '2026-09-10'},
        ],
      });
      await pump(tester, repo, initial: PetInsightsRoutes.boardingPass);
      expect(find.text('Boarding Pass'), findsOneWidget);
      expect(find.byKey(const ValueKey('boardingPassState_a')), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassState_a'))).data, 'Terbuka');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassState_b'))).data, 'Rp1.000');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassVisits_a'))).data, '3×');
      expect(find.text('MOMO · TT02P2600128'), findsNWidgets(2));
      await tester.tap(find.byKey(const ValueKey('boardingPassRow_b')));
      await tester.pumpAndSettle();
      expect(find.byType(BoardingPassDetailPage), findsOneWidget);
    });

    testWidgets('空态 → Cari Tempat 去场所列表', (tester) async {
      final repo = _Repo(BoardingPassDetail.fromJson(detailJson()))
        ..listResult = const BoardingPassList(petName: 'Momo', passportNo: null, items: []);
      await pump(tester, repo, initial: PetInsightsRoutes.boardingPass);
      expect(find.text('Belum ada boarding pass'), findsOneWidget);
      expect(find.text('Check-in di tempat pet-friendly buat dapat kartu pertama'), findsOneWidget);
      await tester.tap(find.text('Cari Tempat'));
      await tester.pumpAndSettle();
      expect(find.text('places-list'), findsOneWidget);
    });
  });

  group('详情 B3b / B3c（AC9）', () {
    String route() => PetInsightsRoutes.boardingPassFor('p' * 32);

    testWidgets('窄屏 360：按 2026-10-06 设计稿的横版票面，详情页顺时针转 90° 竖放；字段取值正确；未解锁有水印 + 吸底「Buka Rp1.000」；无图走占位', (tester) async {
      await pump(tester, _Repo(BoardingPassDetail.fromJson(detailJson())), initial: route(), width: 360);
      expect(tester.widget<RotatedBox>(find.byKey(const ValueKey('boardingPassRotated'))).quarterTurns, 1);
      final card = tester.getRect(find.byKey(const ValueKey('boardingPassRotated')));
      expect(card.width, lessThanOrEqualTo(360), reason: '竖放后宽度不超出屏幕');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassPassportNo'))).data, 'TT02P2600128');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassPassenger'))).data, 'Momo');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassTo'))).data, 'Taman Menteng');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassDate'))).data, '28 SEP 2026',
          reason: 'Date = 最近一次打卡日，大写月');
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassVisits'))).data, endsWith('x'));
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassBreed'))).data, 'Kucing Anggora',
          reason: 'Breed 栏 = 物种（按界面语言）+ 品种');
      expect(find.byType(CardWatermark), findsOneWidget);
      expect(find.text('Buka Rp1.000'), findsOneWidget);
      expect(find.byKey(const ValueKey('boardingPassImagePlaceholder')), findsOneWidget);
      expect(find.text(BoardingPassLabels.header), findsOneWidget);
      expect(find.text('Pertama 1 Sep 2026 · Terakhir 28 Sep 2026'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('没有护照号 → 卡面 Passport 显示「——」', (tester) async {
      final j = detailJson()..remove('passportNo');
      await pump(tester, _Repo(BoardingPassDetail.fromJson(j)), initial: route());
      expect(tester.widget<Text>(find.byKey(const ValueKey('boardingPassPassportNo'))).data, '——');
    });

    testWidgets('已解锁：无水印、无吸底；ACTIVE 地址条可点进场所详情', (tester) async {
      await pump(tester, _Repo(BoardingPassDetail.fromJson(detailJson(unlocked: true))), initial: route());
      expect(find.byType(CardWatermark), findsNothing);
      expect(find.byKey(const ValueKey('boardingPassUnlockCta')), findsNothing);
      expect(find.textContaining('Jl. Menteng 1, Jakarta'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('boardingPassAddress')));
      await tester.pumpAndSettle();
      expect(find.text('place:${'p' * 32}'), findsOneWidget);
    });

    testWidgets('下架：卡照常、地址条换成「Tempat tidak ditemukan」', (tester) async {
      await pump(tester, _Repo(BoardingPassDetail.fromJson(detailJson(status: 'UNAVAILABLE'))), initial: route());
      expect(find.byKey(const ValueKey('boardingPassCard')), findsOneWidget);
      expect(find.text('Tempat tidak ditemukan'), findsOneWidget);
      expect(find.text('Tempat ini sudah tidak terdaftar'), findsOneWidget);
      expect(find.byKey(const ValueKey('boardingPassAddress')), findsNothing);
    });

    testWidgets('B7b「Nanti」不发请求；「Buka」→ PawCoin → 无水印 + toast', (tester) async {
      final repo = await pump(tester, _Repo(BoardingPassDetail.fromJson(detailJson())), initial: route());
      await tester.tap(find.byKey(const ValueKey('boardingPassUnlockCta')));
      await tester.pumpAndSettle();
      expect(find.text('Buka boarding pass ini'), findsOneWidget);
      expect(find.text('Taman Menteng · Rp1.000'), findsOneWidget);
      expect(find.text('Cuma kartu tempat ini. Kartu tempat lain dibuka terpisah.'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('boardingPassLater')));
      await tester.pumpAndSettle();
      expect(repo.unlocks, isEmpty);

      await tester.tap(find.byKey(const ValueKey('boardingPassUnlockCta')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('boardingPassBuy')));
      await tester.pumpAndSettle();
      expect(find.textContaining('tanpa watermark'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('boardingPassPayConfirm')));
      await tester.pumpAndSettle();
      expect(repo.unlocks, [HdPayChannel.pawcoin]);
      expect(find.text('Boarding pass kebuka!'), findsOneWidget);
      expect(find.byType(CardWatermark), findsNothing);
      expect(find.byKey(const ValueKey('boardingPassUnlockCta')), findsNothing);
    });
  });

  test('默认场所图映射：素材未到货前一律 null（走占位）', () {
    for (final t in PlaceType.values) {
      expect(boardingPassPlaceImage(t), isNull);
    }
  });
}

class _Repo implements BoardingPassRepository {
  _Repo(this.current);

  BoardingPassDetail current;
  BoardingPassList listResult = const BoardingPassList(petName: 'Momo', passportNo: null, items: []);
  final List<HdPayChannel> unlocks = [];

  @override
  Future<BoardingPassList> list() async => listResult;

  @override
  Future<BoardingPassDetail> detail(String placeToken) async => current;

  @override
  Future<KeepsakePurchaseResult> unlock(String placeToken, HdPayChannel channel) async {
    unlocks.add(channel);
    current = BoardingPassDetail.fromJson({
      'placeToken': current.placeToken,
      'passenger': current.passenger,
      'placeName': current.placeName,
      'passportNo': current.passportNo,
      'seat': current.seat,
      'visitCount': current.visitCount,
      'placeStatus': current.available ? 'ACTIVE' : 'UNAVAILABLE',
      'unlocked': true,
    });
    return const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'kp');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}
