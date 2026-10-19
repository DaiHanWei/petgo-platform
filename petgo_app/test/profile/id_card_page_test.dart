import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/hd_paywall_sheet.dart';
import 'package:tailtopia/features/profile/presentation/id_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：listCards 返当前态（可失败一次）；purchaseHdForCard 把卡翻成已解锁。
class _FakeIdCardRepo implements IdCardRepository {
  _FakeIdCardRepo(List<IdCard> cards, {this.failFirstList = false}) : _cards = [...cards];

  List<IdCard> _cards;
  bool failFirstList;
  int listCalls = 0;
  final purchased = <int>[];

  @override
  Future<int> reportShareForReward(int cardId, {int? petId}) async => 0;
  @override
  Future<int> advertisableShareReward({int? petId}) async => 0;

  @override
  Future<List<IdCard>> listCards() async {
    listCalls++;
    if (failFirstList) {
      failFirstList = false;
      throw Exception('boom');
    }
    return _cards;
  }

  @override
  Future<IdCard> getCard(int cardId) async => _cards.firstWhere((c) => c.id == cardId);

  @override
  Future<IdCard> createCard(CreateIdCardRequest req) async => IdCard(id: 999, serialId: 999, name: req.name);

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async {
    purchased.add(cardId);
    _cards = [
      for (final c in _cards)
        c.id == cardId
            ? IdCard(
                id: c.id,
                serialId: c.serialId,
                cardNo: c.cardNo,
                name: c.name,
                cardType: c.cardType,
                cardVariant: c.cardVariant,
                createdAt: c.createdAt,
                hdUnlocked: true)
            : c
    ];
    return const HdPurchaseResult(unlocked: true);
  }

  @override
  Future<int> hdPrice() async => 5000; // 同步返回不碰 dio（避免 timer 残留）

  @override
  Future<IdCardData?> getMyIdCard({int? petId}) async => null;
  @override
  Future<IdCardData> generate({int? petId}) async => const IdCardData(generated: true);
  @override
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel, {int? petId}) async => const HdPurchaseResult(unlocked: true);
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

Future<void> _pump(WidgetTester tester, _FakeIdCardRepo repo, {bool openMyCards = true, bool noRetry = false}) async {
  tester.view.physicalSize = const Size(420, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const IdCardPage()),
    GoRoute(path: '/profile/id-cards/:id', builder: (c, s) => Scaffold(body: Text('detail:${s.pathParameters['id']}'))),
  ]);
  await tester.pumpWidget(ProviderScope(
    // 失败用例关掉 Riverpod 3 的自动重试，让失败态稳定可见（重试只由「Coba lagi」触发）。
    retry: noRetry ? (_, _) => null : null,
    overrides: [
      idCardRepositoryProvider.overrideWithValue(repo),
      pawCoinProvider.overrideWith(_Paw.new),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  await tester.pumpAndSettle();
  if (openMyCards) {
    // V1.3.4 ktp Story 1.5：默认停在 Pilih Kartu，卡列表在 Kartuku Tab。
    await tester.tap(find.byKey(const ValueKey('idCardTab_created')));
    await tester.pumpAndSettle();
  }
}

/// Pilih Kartu 是否为当前可见 Tab（TabBarView 里不可见的页不可命中）。
bool _onStyleTab(WidgetTester tester) =>
    find.byKey(const ValueKey('idCardStyleCell_ktp')).hitTestable().evaluate().isNotEmpty;

void main() {
  testWidgets('空态（B2）：圆形证件图标 + 标题 + 吸底「Create New Card」→ 切到 Pilih Kartu', (tester) async {
    await _pump(tester, _FakeIdCardRepo(const []));
    expect(find.text('No ID cards yet'), findsOneWidget);
    expect(find.byIcon(Icons.badge_outlined), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardCreateEntry')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('idCardCreateNewCta')));
    await tester.pumpAndSettle();
    expect(_onStyleTab(tester), isTrue);
  });

  testWidgets('有卡（B3）：说明句、注册表卡种名、card_no 原样、旧卡补零、Buka HD 只挂未解锁行', (tester) async {
    await _pump(tester, _FakeIdCardRepo([
      IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true, createdAt: DateTime.utc(2026, 7, 22)),
      IdCard(id: 2, serialId: 7, name: 'Coco', cardType: 'PASSPORT', cardNo: 'TT600324020001'),
      IdCard(id: 3, serialId: 8, name: 'Oyen', cardType: 'SIM', cardNo: 'TT230326020110'),
      IdCard(id: 4, serialId: 9, name: 'Luna', cardType: 'STUDENT', cardVariant: 'MALAM', cardNo: 'TT230326020111'),
    ]));
    expect(find.textContaining('Each card is a saved snapshot'), findsOneWidget);
    expect(find.text('KTP · No. 0012'), findsOneWidget);
    expect(find.text('Passport · No. TT600324020001'), findsOneWidget);
    // SIM 在列表里原样显示 card_no，不做 TTDDMM-YYYY-SPXXXX 格式化。
    expect(find.text('Driving License · No. TT230326020110'), findsOneWidget);
    expect(find.text('Student Night · No. TT230326020111'), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardBukaHd_1')), findsNothing, reason: '已解锁不挂标签');
    for (final id in [2, 3, 4]) {
      expect(find.byKey(ValueKey('idCardBukaHd_$id')), findsOneWidget);
    }
    expect(find.text('Unlock HD'), findsNWidgets(3));
    expect(find.text('HD'), findsNothing, reason: '旧「✓ HD」徽标已删除');
    expect(find.byKey(const ValueKey('idCardCreateEntry')), findsNothing, reason: '顶部入口行已删除');
    expect(find.byKey(const ValueKey('idCardCreateNewCta')), findsOneWidget);
    // 标签热区 ≥ 44×44。
    final tag = tester.getSize(find.byKey(const ValueKey('idCardBukaHd_2')));
    expect(tag.width, greaterThanOrEqualTo(44));
    expect(tag.height, greaterThanOrEqualTo(44));
  });

  testWidgets('吸底按钮切回 Pilih Kartu', (tester) async {
    await _pump(tester, _FakeIdCardRepo([IdCard(id: 1, serialId: 12, name: 'Mochi')]));
    await tester.tap(find.byKey(const ValueKey('idCardCreateNewCta')));
    await tester.pumpAndSettle();
    expect(_onStyleTab(tester), isTrue);
  });

  testWidgets('点标签弹付费抽屉、不进详情；下滑关闭仍在列表', (tester) async {
    final repo = _FakeIdCardRepo([IdCard(id: 2, serialId: 7, name: 'Coco')]);
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('idCardBukaHd_2')));
    await tester.pumpAndSettle();
    expect(find.byType(HdPaywallSheet), findsOneWidget);
    expect(find.textContaining('detail:'), findsNothing);
    await tester.tapAt(const Offset(10, 10)); // 点遮罩关闭
    await tester.pumpAndSettle();
    expect(find.byType(HdPaywallSheet), findsNothing);
    expect(find.byKey(const ValueKey('idCardTile_2')), findsOneWidget);
    expect(repo.purchased, isEmpty);
  });

  testWidgets('PawCoin 当场成功：留在列表、刷新后标签消失 + toast', (tester) async {
    final repo = _FakeIdCardRepo([IdCard(id: 2, serialId: 7, name: 'Coco')]);
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('idCardBukaHd_2')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(repo.purchased, [2]);
    expect(find.byKey(const ValueKey('idCardTile_2')), findsOneWidget, reason: '留在列表');
    expect(find.byKey(const ValueKey('idCardBukaHd_2')), findsNothing, reason: '刷新后标签消失');
    expect(find.text('HD download unlocked!'), findsOneWidget);
    expect(find.textContaining('detail:'), findsNothing);
  });

  testWidgets('点行其余位置 → 进卡详情', (tester) async {
    await _pump(tester, _FakeIdCardRepo([IdCard(id: 2, serialId: 7, name: 'Coco')]));
    await tester.tap(find.text('Coco'));
    await tester.pumpAndSettle();
    expect(find.text('detail:2'), findsOneWidget);
  });

  testWidgets('失败态（B5）：文案 + Coba lagi；点击重拉、成功后渲染列表', (tester) async {
    final repo = _FakeIdCardRepo([IdCard(id: 2, serialId: 7, name: 'Coco')], failFirstList: true);
    await _pump(tester, repo, noRetry: true);
    expect(find.text('Failed to load ID card. Please try again.'), findsOneWidget);
    expect(find.byIcon(Icons.cloud_off_outlined), findsOneWidget);
    expect(repo.listCalls, 1);
    await tester.tap(find.byKey(const ValueKey('idCardListRetry')));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget, reason: '重拉期间转圈（B4）');
    await tester.pumpAndSettle();
    expect(repo.listCalls, 2);
    expect(find.byKey(const ValueKey('idCardTile_2')), findsOneWidget);
  });
}
