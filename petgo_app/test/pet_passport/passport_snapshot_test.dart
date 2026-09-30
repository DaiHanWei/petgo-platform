import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/passport_snapshot.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/pet_passport/presentation/passport_versions_page.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_page.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 3.4 · L0：B2b 吸底购买 / 已买禁用态、B7「Nanti」不发请求、水印按版本态、已购版本入口与回看。
void main() {
  PassportStamp stamp(int i) => PassportStamp(
        placeToken: 't$i'.padRight(32, '0'),
        placeName: 'Tempat $i',
        placeType: PlaceType.values[i % PlaceType.values.length],
        available: true,
        visitCount: 1,
        firstVisitDate: DateTime(2026, 9, 1 + i),
      );

  PetPassport passport({bool unlocked = false, int purchased = 0, int n = 2}) => PetPassport(
      petName: 'Momo',
      passportNo: 'TT02P2600128',
      stamps: [for (var i = 0; i < n; i++) stamp(i)],
      currentVersionUnlocked: unlocked,
      purchasedVersionCount: purchased);

  Future<_Repo> pump(WidgetTester tester, _Repo repo) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(400, 1000);
    addTearDown(tester.view.reset);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const PetPassportPage()),
      GoRoute(path: PetInsightsRoutes.passportVersions, builder: (c, s) => const PassportVersionsPage()),
      GoRoute(
          path: PetInsightsRoutes.passportVersion,
          builder: (c, s) => PassportVersionPage(token: s.pathParameters['token']!)),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        petPassportRepositoryProvider.overrideWithValue(repo),
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

  Future<void> toGrid(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('passportToggleGrid')));
    await tester.pumpAndSettle();
  }

  test('wire：版本字段缺键 fail-closed', () {
    final p = PetPassport.fromJson({'petName': 'M', 'passportNo': 'X', 'stamps': []});
    expect(p.currentVersionUnlocked, isFalse);
    expect(p.purchasedVersionCount, 0);
    final q = PetPassport.fromJson(
        {'petName': 'M', 'passportNo': 'X', 'stamps': [], 'currentVersionUnlocked': true, 'purchasedVersionCount': 2});
    expect(q.currentVersionUnlocked, isTrue);
    expect(q.purchasedVersionCount, 2);
  });

  testWidgets('未买：B2 单章页无付费、内页带水印；B2b 吸底「Buka versi ini · Rp2.000」', (tester) async {
    await pump(tester, _Repo(passport()));
    expect(find.textContaining('Rp'), findsNothing);
    expect(find.byType(CardWatermark), findsOneWidget);
    await toGrid(tester);
    expect(find.text('Buka versi ini · Rp2.000'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('passportSnapshotCta'))).onPressed, isNotNull);
  });

  testWidgets('已买：吸底禁用态「Versi ini sudah kebuka」、无水印、AppBar 出已购版本入口', (tester) async {
    await pump(tester, _Repo(passport(unlocked: true, purchased: 1)));
    expect(find.byType(CardWatermark), findsNothing);
    expect(find.byKey(const ValueKey('passportPurchasedVersions')), findsOneWidget);
    expect(find.byIcon(Icons.more_horiz), findsNothing);
    await toGrid(tester);
    final owned = tester.widget<FilledButton>(find.byKey(const ValueKey('passportSnapshotOwned')));
    expect(owned.onPressed, isNull);
    expect(find.text('Versi ini sudah kebuka'), findsOneWidget);
    expect(find.textContaining('Rp'), findsNothing);
  });

  testWidgets('没有已买版本 → 不出入口', (tester) async {
    await pump(tester, _Repo(passport()));
    expect(find.byKey(const ValueKey('passportPurchasedVersions')), findsNothing);
  });

  testWidgets('B7「Nanti」只关抽屉、不发请求；「Buka」→ 选渠道 → PawCoin 成功切无水印 + toast', (tester) async {
    final repo = await pump(tester, _Repo(passport()));
    await toGrid(tester);
    await tester.tap(find.byKey(const ValueKey('passportSnapshotCta')));
    await tester.pumpAndSettle();
    expect(find.text('Buka paspor versi ini'), findsOneWidget);
    expect(find.text('2 cap sekarang · Rp2.000'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('passportSnapshotLater')));
    await tester.pumpAndSettle();
    expect(repo.starts, isEmpty);
    expect(find.byKey(const ValueKey('passportSnapshotSheet')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('passportSnapshotCta')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('passportSnapshotBuy')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Paspor tanpa watermark'), findsOneWidget);
    expect(find.text('TT02P2600128'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('passportSnapshotPayConfirm')));
    await tester.pumpAndSettle();
    expect(repo.starts, [HdPayChannel.pawcoin]);
    expect(find.text('Versi ini sudah kebuka!'), findsOneWidget);
    expect(find.byType(CardWatermark), findsNothing);
  });

  testWidgets('复审：QRIS 付款窗内又盖新章 → 已买版本数 +1 即认到账（当前版本仍锁、仍带水印）', (tester) async {
    final repo = _Repo(passport())..qris = true;
    await pump(tester, repo);
    await toGrid(tester);
    await tester.tap(find.byKey(const ValueKey('passportSnapshotCta')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('passportSnapshotBuy')));
    await tester.pumpAndSettle();
    // 余额 50000 足 → 默认 PawCoin；改选 QRIS。
    await tester.tap(find.text('QRIS'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('passportSnapshotPayConfirm')));
    await tester.pump(const Duration(milliseconds: 500));
    repo.paidWithNewStamp();
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(find.text('Versi ini sudah kebuka!'), findsOneWidget);
    expect(find.byType(CardWatermark), findsOneWidget, reason: '当前 3 章版本未买');
  });

  testWidgets('已购版本列表 → 回看：冻结数据、无水印、无购买、不调实时护照接口', (tester) async {
    final repo = await pump(tester, _Repo(passport(unlocked: false, purchased: 1)));
    final fetchesBefore = repo.fetches;
    await tester.tap(find.byKey(const ValueKey('passportPurchasedVersions')));
    await tester.pumpAndSettle();
    expect(find.text('Versi yang dibeli'), findsOneWidget);
    expect(find.textContaining('· 1 cap'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('passportVersion_snap1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('passportVersionBlock')), findsOneWidget);
    expect(find.text('Kopi Lama'), findsOneWidget);
    expect(find.text('Cap 1 / 1'), findsOneWidget);
    expect(find.byType(CardWatermark), findsNothing);
    expect(find.textContaining('Rp'), findsNothing);
    expect(repo.fetches, fetchesBefore, reason: '回看不读实时护照');
  });
}

class _Repo implements PetPassportRepository {
  _Repo(this.current);

  PetPassport current;
  final List<HdPayChannel> starts = [];
  int fetches = 0;
  bool qris = false;

  /// 模拟：冻结的 2 章版本已到账，但期间又盖了第 3 章 → 当前版本仍锁、已买数 +1。
  void paidWithNewStamp() {
    current = PetPassport(
        petName: current.petName,
        passportNo: current.passportNo,
        stamps: [...current.stamps, current.stamps.first],
        currentVersionUnlocked: false,
        purchasedVersionCount: current.purchasedVersionCount + 1);
  }

  @override
  Future<PetPassport> fetch() async {
    fetches++;
    return current;
  }

  @override
  Future<KeepsakePurchaseResult> startSnapshot(HdPayChannel channel) async {
    starts.add(channel);
    if (qris) {
      return const KeepsakePurchaseResult(unlocked: false, paymentToken: 'pi', payload: '00020101021226', purchaseToken: 'kp');
    }
    current = PetPassport(
        petName: current.petName,
        passportNo: current.passportNo,
        stamps: current.stamps,
        currentVersionUnlocked: true,
        purchasedVersionCount: current.purchasedVersionCount + 1);
    return const KeepsakePurchaseResult(unlocked: true, purchaseToken: 'kp');
  }

  @override
  Future<List<PassportSnapshotItem>> snapshots() async =>
      [PassportSnapshotItem(snapshotToken: 'snap1', paidAt: DateTime.utc(2026, 9, 20), stampCount: 1)];

  @override
  Future<PassportSnapshotDetail> snapshot(String token) async => PassportSnapshotDetail(
        snapshotToken: token,
        paidAt: DateTime.utc(2026, 9, 20),
        petName: 'Momo',
        passportNo: 'TT02P2600128',
        stamps: const [
          PassportStamp(placeToken: 'old', placeName: 'Kopi Lama', available: false, visitCount: 2, placeType: PlaceType.cafe),
        ],
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}
