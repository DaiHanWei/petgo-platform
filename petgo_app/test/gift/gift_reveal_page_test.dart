import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_order_browse.dart';
import 'package:tailtopia/features/gift/domain/gift_order_detail.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/gift/presentation/gift_page.dart';
import 'package:tailtopia/features/gift/presentation/gift_reveal_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：拆礼物 G1 ~ G6（V1.5.0 gift-pack Story 2-5）。Rive 用可注入替身，不渲染真动画；不写 golden。
class _Repo extends GiftRepository {
  _Repo() : super(dio: Dio());

  final List<({List<String> tokens, bool all})> openCalls = [];
  Object openResult = const GiftOpenResult();
  final Map<String, GiftOrderPage> pages = {};
  final List<String> listCalls = [];

  @override
  Future<GiftOpenResult> openPetGiftOrders(int petId, {List<String> orderTokens = const [], bool all = false}) async {
    openCalls.add((tokens: orderTokens, all: all));
    final r = openResult;
    if (r is GiftOpenResult) return r;
    throw r;
  }

  @override
  Future<GiftOrderPage> fetchPetGiftOrders(int petId,
      {GiftOrderFilter filter = const GiftOrderFilter.all(), String? cursor}) async {
    final key = filter.unopened ? 'U' : 'A';
    listCalls.add(key);
    return pages[key] ?? const GiftOrderPage();
  }
}

GiftOrderDetail _detail(String t,
        {String? message, bool canReport = false, int exp = 20, int? userId = 7, bool deleted = false, int qty = 1}) =>
    GiftOrderDetail(
      orderToken: t,
      petId: 42,
      petName: 'Mochi',
      giftNameEn: 'Strawberry $t',
      giftNameId: 'Stroberi $t',
      quantity: qty,
      message: message,
      canReport: canReport,
      ownerExp: exp,
      sender: GiftDetailSender(userId: deleted ? null : userId, nickname: deleted ? null : 'Michelle', deleted: deleted),
    );

/// 替身动画区：记录 open 信号，暴露 onDone。
VoidCallback? _done;
int _openSignals = 0;

Widget _stub(BuildContext context, ValueListenable<bool> open, VoidCallback onDone, void Function(Object) onError) {
  _done = onDone;
  return ValueListenableBuilder<bool>(
    valueListenable: open,
    builder: (_, v, _) {
      if (v) _openSignals++;
      return SizedBox.expand(child: Center(child: Text(v ? 'stub-open' : 'stub-idle')));
    },
  );
}

void main() {
  late _Repo repo;
  setUp(() {
    repo = _Repo();
    _done = null;
    _openSignals = 0;
    giftRevealRiveBuilderOverride = _stub;
  });
  tearDown(() => giftRevealRiveBuilderOverride = null);

  Future<List<Object?>> pump(WidgetTester tester,
      {List<String> tokens = const ['t1'], bool all = false, bool reduceMotion = false}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final popped = <Object?>[];
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) =>
            MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion), child: child!),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                final r = await Navigator.of(context).push<bool>(MaterialPageRoute(
                    builder: (_) => GiftRevealPage(petId: 42, orderTokens: tokens, all: all)));
                popped.add(r);
              },
              child: const Text('go'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('go'));
    await tester.pumpAndSettle();
    return popped;
  }

  Future<void> openAndFinish(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('giftRevealStage')));
    await tester.pump();
    await tester.pump(giftRevealFallback);
    await tester.pumpAndSettle();
  }

  test('GiftOpenResult 解析：non_null 省略字段、注销送礼人、alreadyOpened', () {
    final r = GiftOpenResult.fromJson({
      'items': [
        {
          'orderToken': 'a', 'petId': 42, 'petName': 'Mochi', 'giftNameEn': 'Bone', 'giftNameId': 'Tulang',
          'quantity': 3, 'receivedAt': '2026-11-14T01:00:00Z', 'opened': true, 'alreadyOpened': false,
          'message': 'hai', 'messageHidden': false, 'ownerExp': 12, 'canReport': true,
          'sender': {'userId': 7, 'nickname': 'Ana', 'deleted': false, 'petNames': ['Kiki']},
        },
        {
          'orderToken': 'b', 'petId': 42, 'petName': 'Mochi', 'giftNameEn': 'Bone', 'giftNameId': 'Tulang',
          'quantity': 1, 'messageHidden': true, 'canReport': false,
          'sender': {'deleted': true, 'petNames': []},
        },
      ],
      'totalOwnerExp': 12,
    });
    expect(r.totalOwnerExp, 12);
    expect(r.items.first.sender.userId, 7);
    expect(r.items.first.sender.petNames, ['Kiki']);
    expect(r.items.first.alreadyOpened, isFalse);
    expect(r.items.first.giftNameFor(const Locale('id')), 'Tulang');
    expect(r.items.last.message, isNull);
    expect(r.items.last.messageHidden, isTrue);
    expect(r.items.last.ownerExp, isNull);
    expect(r.items.last.sender.deleted, isTrue);
    expect(r.items.last.sender.userId, isNull);
  });

  test('分页：1 / 9 / 10 份 → 1 / 1 / 2 页', () {
    expect(giftRevealPages(List.generate(1, (i) => i)).length, 1);
    expect(giftRevealPages(List.generate(9, (i) => i)).length, 1);
    final ten = giftRevealPages(List.generate(10, (i) => i));
    expect(ten.length, 2);
    expect(ten.last, [9]);
  });

  testWidgets('G1：待机文案 + 关闭；连点礼盒只发一次 open 请求、只触发一次 GiftVM.open', (tester) async {
    repo.openResult = GiftOpenResult(items: [_detail('t1')], totalOwnerExp: 20);
    await pump(tester);
    expect(find.text('Ketuk untuk membuka'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealClose')), findsOneWidget);
    for (var i = 0; i < 4; i++) {
      await tester.tap(find.byKey(const ValueKey('giftRevealStage')));
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(repo.openCalls.length, 1);
    expect(repo.openCalls.single.tokens, ['t1']);
    expect(_openSignals, 1);
    expect(find.text('Ketuk untuk membuka'), findsNothing);
    await tester.pump(giftRevealFallback);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftRevealTitle')), findsOneWidget);
  });

  testWidgets('关闭不拆：不调接口，pop false', (tester) async {
    final popped = await pump(tester);
    await tester.tap(find.byKey(const ValueKey('giftRevealClose')));
    await tester.pumpAndSettle();
    expect(repo.openCalls, isEmpty);
    expect(popped, [false]);
  });

  testWidgets('G2：标题 / 祝福语 / EXP / ⋯ / Lihat profil；revealDone 先于兜底进终态；Lanjut pop true', (tester) async {
    repo.openResult = GiftOpenResult(
        items: [_detail('t1', message: 'Happy birthday Mochi', canReport: true, exp: 200, qty: 10)],
        totalOwnerExp: 200);
    final popped = await pump(tester);
    await tester.tap(find.byKey(const ValueKey('giftRevealStage')));
    await tester.pump();
    _done!();
    await tester.pumpAndSettle();
    expect(find.text('Michelle mengirim 10 Stroberi t1 untuk Mochi'), findsOneWidget);
    expect(find.text('Happy birthday Mochi'), findsOneWidget);
    expect(find.text('Kamu dapat 200 EXP'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealMore')), findsOneWidget);
    expect(find.text('Lihat profil Michelle'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('giftRevealMore')));
    await tester.pumpAndSettle();
    expect(find.text('Laporkan ucapan'), findsOneWidget);
    await tester.tapAt(const Offset(5, 5));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Lanjut'));
    await tester.pumpAndSettle();
    expect(popped, [true]);
  });

  testWidgets('G3：无祝福语 → 无祝福语区、无 ⋯；EXP 0 不显示；送礼人注销 → 无 Lihat profil', (tester) async {
    repo.openResult = GiftOpenResult(items: [_detail('t1', exp: 0, deleted: true)]);
    await pump(tester);
    await openAndFinish(tester);
    expect(find.byKey(const ValueKey('giftRevealTitle')), findsOneWidget);
    expect(find.text('Pengguna terhapus mengirim 1 Stroberi t1 untuk Mochi'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealMessage')), findsNothing);
    expect(find.byKey(const ValueKey('giftRevealMore')), findsNothing);
    expect(find.byKey(const ValueKey('giftRevealExp')), findsNothing);
    expect(find.byKey(const ValueKey('giftRevealSenderProfile')), findsNothing);
  });

  testWidgets('祝福语被隐藏 → 显示隐藏文案', (tester) async {
    repo.openResult = const GiftOpenResult(items: [
      GiftOrderDetail(
          orderToken: 't1', petId: 42, petName: 'Mochi', giftNameEn: 'A', giftNameId: 'A', messageHidden: true),
    ]);
    await pump(tester);
    await openAndFinish(tester);
    expect(find.text('Ucapan ini disembunyikan karena melanggar pedoman komunitas.'), findsOneWidget);
  });

  testWidgets('减弱动态效果：不点礼盒直接终态，仍调 open 接口', (tester) async {
    repo.openResult = GiftOpenResult(items: [_detail('t1')]);
    await pump(tester, reduceMotion: true);
    expect(repo.openCalls.length, 1);
    expect(find.byKey(const ValueKey('giftRevealTitle')), findsOneWidget);
    expect(find.text('Ketuk untuk membuka'), findsNothing);
  });

  testWidgets('接口失败：动画照播，结束后 toast，停在 Lanjut；pop true（返回刷新）', (tester) async {
    repo.openResult = DioException(requestOptions: RequestOptions(path: '/'));
    final popped = await pump(tester);
    await openAndFinish(tester);
    expect(find.text('Terjadi kesalahan, coba lagi'), findsOneWidget);
    await tester.tap(find.text('Lanjut'));
    await tester.pumpAndSettle(const Duration(seconds: 3));
    expect(popped, [true]);
  });

  testWidgets('批量 10 份：汇总标题、2 页圆点、合计 EXP；都无祝福语 → Lanjut', (tester) async {
    repo.openResult = GiftOpenResult(items: [for (var i = 0; i < 10; i++) _detail('b$i')], totalOwnerExp: 820);
    await pump(tester, tokens: const [], all: true);
    await openAndFinish(tester);
    expect(repo.openCalls.single.all, isTrue);
    expect(find.text('Kamu menerima 10 hadiah'), findsOneWidget);
    expect(find.text('Kamu dapat 820 EXP'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealPageDot-1')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealPageDot-2')), findsNothing);
    expect(find.byKey(const ValueKey('giftRevealCell-b0')), findsOneWidget);
    expect(find.text('×1 Stroberi b0'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealCell-b9')), findsNothing);
    expect(find.text('Lanjut'), findsOneWidget);
  });

  testWidgets('批量 1 / 9 份：无分页圆点', (tester) async {
    repo.openResult = GiftOpenResult(items: [for (var i = 0; i < 9; i++) _detail('b$i')]);
    await pump(tester, tokens: const [], all: true);
    await openAndFinish(tester);
    expect(find.text('Kamu menerima 9 hadiah'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealPageDot-0')), findsNothing);
  });

  testWidgets('批量部分有祝福语：Berikutnya → G6 只轮播有祝福语的那几笔，末条 Lanjut', (tester) async {
    repo.openResult = GiftOpenResult(items: [
      _detail('b0', message: 'pesan satu'),
      _detail('b1'),
      _detail('b2', message: 'pesan dua'),
    ]);
    final popped = await pump(tester, tokens: const [], all: true);
    await openAndFinish(tester);
    expect(find.text('Berikutnya'), findsOneWidget);
    await tester.tap(find.text('Berikutnya'));
    await tester.pumpAndSettle();
    expect(find.text('pesan satu'), findsOneWidget);
    expect(find.text('Michelle mengirim 1 Stroberi b0 untuk Mochi'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftRevealProgressDot-1')), findsOneWidget);
    await tester.tap(find.text('Berikutnya'));
    await tester.pumpAndSettle();
    expect(find.text('pesan dua'), findsOneWidget);
    expect(find.text('Lanjut'), findsOneWidget);
    await tester.tap(find.text('Lanjut'));
    await tester.pumpAndSettle();
    expect(popped, [true]);
  });

  group('礼物页 open=latest（AC3.3）', () {
    const pet = GiftShowcasePet(petId: 42, name: 'Mochi', petType: 'CAT');
    GiftOrderPage page(List<GiftOrderCard> items) => GiftOrderPage(items: items, pet: pet, viewerIsOwner: true);

    Future<void> pumpGiftPage(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: [giftRepositoryProvider.overrideWithValue(repo)],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const GiftPage(petId: 42, openLatest: true),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('有未拆 → 直接进 G1 拆最新一笔未拆', (tester) async {
      repo.pages['A'] = page(const [GiftOrderCard(orderToken: 'u1', giftNameEn: 'G', giftNameId: 'G', opened: false), GiftOrderCard(orderToken: 'o1', giftNameEn: 'G', giftNameId: 'G')]);
      repo.pages['U'] = page(const [GiftOrderCard(orderToken: 'u1', giftNameEn: 'G', giftNameId: 'G', opened: false)]);
      await pumpGiftPage(tester);
      expect(find.byType(GiftRevealPage), findsOneWidget);
      expect(tester.widget<GiftRevealPage>(find.byType(GiftRevealPage)).orderTokens, ['u1']);
      // 关闭后回礼物页，不再自动弹
      await tester.tap(find.byKey(const ValueKey('giftRevealClose')));
      await tester.pumpAndSettle();
      expect(find.byType(GiftRevealPage), findsNothing);
      expect(repo.listCalls.where((c) => c == 'U').length, 1);
    });

    testWidgets('无未拆 → 不进拆礼物页（转详情）', (tester) async {
      repo.pages['A'] = page(const [GiftOrderCard(orderToken: 'o1', giftNameEn: 'G', giftNameId: 'G', opened: true)]);
      await pumpGiftPage(tester);
      expect(repo.listCalls, contains('U'));
      expect(find.byType(GiftRevealPage), findsNothing);
    });
  });
}
