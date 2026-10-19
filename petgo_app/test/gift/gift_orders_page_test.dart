import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_order_history.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/gift/presentation/gift_orders_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：我的送礼记录页（V1.4.0 gift Story 2.3 · AC4 · UI 稿 F2 ~ F4）。fake 仓储，结构断言，不写 golden。
class _FakeRepo extends GiftRepository {
  _FakeRepo() : super(dio: Dio());

  /// 按调用顺序消费：[GiftOrderHistoryPage] 或要抛的异常；用完重复最后一个。
  final List<Object> results = [];
  final List<String?> cursors = [];

  @override
  Future<GiftOrderHistoryPage> getMyGiftOrders({String? cursor}) async {
    cursors.add(cursor);
    final r = results.length > 1 ? results.removeAt(0) : results.first;
    if (r is GiftOrderHistoryPage) return r;
    throw r;
  }
}

DioException _netError() => DioException(requestOptions: RequestOptions(path: '/x'));

GiftOrderRecord _order(String token, GiftOrderStatus status,
        {GiftOrderPet? pet = const GiftOrderPet(petId: 30, name: 'Mochi'),
        String channel = 'QRIS',
        int quantity = 2,
        int total = 20000,
        DateTime? at}) =>
    GiftOrderRecord(
      orderToken: token,
      status: status,
      channel: channel,
      quantity: quantity,
      total: total,
      createdAt: at ?? DateTime.utc(2026, 10, 18, 10),
      nameEn: 'Bone',
      nameId: 'Tulang',
      unitPrice: total ~/ quantity,
      pet: pet,
    );

Future<void> _pump(WidgetTester tester, _FakeRepo repo, {Locale locale = const Locale('id')}) async {
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const GiftOrdersPage()),
    GoRoute(path: '/pets/:petId', builder: (c, s) => Scaffold(body: Text('pet-${s.pathParameters['petId']}'))),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [giftRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
    ),
  ));
  await tester.pumpAndSettle();
}

Finder _pill(String token) => find.byKey(ValueKey('giftOrderStatus-$token'));

void main() {
  testWidgets('三种主状态 + 退回中：胶囊文案；按接口顺序渲染（客户端不重排）；行内无按钮（不能续付）', (tester) async {
    final repo = _FakeRepo()
      ..results.add(GiftOrderHistoryPage(items: [
        // 故意让时间与顺序不一致：页面必须照接口顺序。
        _order('p', GiftOrderStatus.pending, at: DateTime.utc(2026, 10, 1)),
        _order('d', GiftOrderStatus.delivered, channel: 'PAWCOIN', at: DateTime.utc(2026, 10, 18)),
        _order('r', GiftOrderStatus.undeliveredRefunded),
        _order('rp', GiftOrderStatus.undeliveredRefundPending),
        _order('rm', GiftOrderStatus.refundManual),
        _order('u', GiftOrderStatus.unknown),
      ]));
    await _pump(tester, repo);

    expect(find.descendant(of: _pill('p'), matching: find.text('Menunggu pembayaran')), findsOneWidget);
    expect(find.descendant(of: _pill('d'), matching: find.text('Terkirim')), findsOneWidget);
    expect(find.descendant(of: _pill('r'), matching: find.text('Dikembalikan ke PawCoin')), findsOneWidget);
    expect(find.descendant(of: _pill('rp'), matching: find.text('Pengembalian diproses')), findsOneWidget);
    expect(find.descendant(of: _pill('rm'), matching: find.text('Pengembalian diproses')), findsOneWidget);
    expect(_pill('u'), findsNothing, reason: '未知代码不显示胶囊、不渲染原始代码');

    final ys = ['p', 'd', 'r', 'rp', 'rm']
        .map((t) => tester.getTopLeft(find.byKey(ValueKey('giftOrderRow-$t'))).dy)
        .toList();
    for (var i = 1; i < ys.length; i++) {
      expect(ys[i], greaterThan(ys[i - 1]));
    }

    // 行内容：礼物按语言（id → Tulang）× 数量、金额、渠道品牌名。
    expect(find.text('Tulang × 2'), findsNWidgets(6));
    expect(find.text('Rp20.000'), findsNWidgets(6));
    expect(find.text('PawCoin'), findsOneWidget);
    expect(find.text('QRIS'), findsNWidgets(5));
    // Q4：等待付款不能续付 —— 行内没有任何按钮。
    final pendingRow = find.byKey(const ValueKey('giftOrderRow-p'));
    expect(find.descendant(of: pendingRow, matching: find.byType(ButtonStyleButton)), findsNothing);
    expect(find.descendant(of: pendingRow, matching: find.byType(IconButton)), findsNothing);
  });

  testWidgets('英文：礼物名取 nameEn', (tester) async {
    final repo = _FakeRepo()
      ..results.add(GiftOrderHistoryPage(items: [_order('d', GiftOrderStatus.delivered)]));
    await _pump(tester, repo, locale: const Locale('en'));
    expect(find.text('Bone × 2'), findsOneWidget);
    expect(find.text('Delivered'), findsOneWidget);
    expect(find.text('Gift history'), findsOneWidget);
  });

  testWidgets('宠物已删：「Hewan ini sudah tidak ada」、头像不可跳转；其他行点头像 / 名字 push /pets/{id}', (tester) async {
    final repo = _FakeRepo()
      ..results.add(GiftOrderHistoryPage(items: [
        _order('gone', GiftOrderStatus.undeliveredRefunded, pet: null),
        _order('ok', GiftOrderStatus.delivered, pet: const GiftOrderPet(petId: 77, name: 'Kopi')),
      ]));
    await _pump(tester, repo);

    expect(find.byKey(const ValueKey('giftOrderPetGone-gone')), findsOneWidget);
    expect(find.text('Hewan ini sudah tidak ada'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('giftOrderRow-gone')), matching: find.byIcon(Icons.pets)),
        findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('giftOrderAvatar-gone')));
    await tester.pumpAndSettle();
    expect(find.byType(GiftOrdersPage), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('giftOrderRow-gone')));
    await tester.pumpAndSettle();
    expect(find.textContaining('pet-'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('giftOrderAvatar-ok')));
    await tester.pumpAndSettle();
    expect(find.text('pet-77'), findsOneWidget);

    // 返回后点名字同样进访客 Diary Tab。
    final ctx = tester.element(find.text('pet-77'));
    GoRouter.of(ctx).pop();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('giftOrderPetName-ok')));
    await tester.pumpAndSettle();
    expect(find.text('pet-77'), findsOneWidget);
  });

  testWidgets('F3 空态', (tester) async {
    final repo = _FakeRepo()..results.add(const GiftOrderHistoryPage(items: []));
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('giftOrdersEmpty')), findsOneWidget);
    expect(find.text('Belum ada riwayat hadiah'), findsOneWidget);
    expect(find.text('Hadiah yang kamu kirim akan muncul di sini.'), findsOneWidget);
    expect(find.byIcon(Icons.redeem_rounded), findsOneWidget);
  });

  testWidgets('F4 首屏失败 → 重试成功', (tester) async {
    final repo = _FakeRepo()
      ..results.addAll([
        _netError(),
        GiftOrderHistoryPage(items: [_order('d', GiftOrderStatus.delivered)]),
      ]);
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('giftOrdersError')), findsOneWidget);
    expect(find.text('Gagal memuat, silakan coba lagi'), findsOneWidget);
    expect(find.byIcon(Icons.cloud_off), findsOneWidget);
    expect(find.byKey(const ValueKey('giftOrdersEmpty')), findsNothing, reason: '失败不画成空态');

    await tester.tap(find.byKey(const ValueKey('giftOrdersRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftOrdersError')), findsNothing);
    expect(find.byKey(const ValueKey('giftOrderRow-d')), findsOneWidget);
  });

  testWidgets('加载更多：游标原样回传；失败底部行内重试、已加载行保留', (tester) async {
    final repo = _FakeRepo()
      ..results.addAll([
        GiftOrderHistoryPage(items: [_order('a', GiftOrderStatus.delivered)], nextCursor: 'c1'),
        _netError(),
        GiftOrderHistoryPage(items: [_order('b', GiftOrderStatus.pending)]),
      ]);
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('giftOrdersLoadMore')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftOrdersLoadMoreRetry')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftOrderRow-a')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftOrdersError')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('giftOrdersLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftOrderRow-b')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftOrdersLoadMore')), findsNothing);
    expect(find.byKey(const ValueKey('giftOrdersLoadMoreRetry')), findsNothing);
    expect(repo.cursors, [null, 'c1', 'c1']);
  });

  testWidgets('下拉刷新重新拉首页：付款后 PENDING 变 Terkirim（AC10b）', (tester) async {
    final repo = _FakeRepo()
      ..results.addAll([
        GiftOrderHistoryPage(items: [_order('q', GiftOrderStatus.pending)]),
        GiftOrderHistoryPage(items: [_order('q', GiftOrderStatus.delivered)]),
      ]);
    await _pump(tester, repo);
    expect(find.text('Menunggu pembayaran'), findsOneWidget);

    await tester.fling(find.byKey(const ValueKey('giftOrdersList')), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(find.text('Terkirim'), findsOneWidget);
    expect(find.text('Menunggu pembayaran'), findsNothing);
    expect(repo.cursors, [null, null]);
  });

  test('线格式：宠物缺省 / petGone → pet = null；数字容错；未知状态 → unknown', () {
    final page = GiftOrderHistoryPage.fromJson({
      'items': [
        {
          'orderToken': 't1',
          'createdAt': '2026-10-18T10:00:00Z',
          'status': 'DELIVERED',
          'channel': 'PAWCOIN',
          'quantity': 3,
          'total': 30000,
          'gift': {'nameEn': 'Bone', 'nameId': 'Tulang', 'iconUrl': 'https://cdn/b.png', 'unitPrice': 10000},
          'pet': {'petId': 30, 'name': 'Mochi', 'avatarUrl': 'https://cdn/p.jpg'},
          'petGone': false,
        },
        {
          'orderToken': 't2',
          'status': 'UNDELIVERED_REFUNDED',
          'channel': 'QRIS',
          'quantity': 1,
          'total': 5000,
          'gift': {'nameEn': 'Cake', 'nameId': 'Kue'},
          'petGone': true,
        },
        {'orderToken': 't3', 'status': 'SOMETHING_NEW', 'pet': {'petId': 1, 'name': 'X'}, 'petGone': true},
      ],
      'nextCursor': 'abc',
    });
    expect(page.nextCursor, 'abc');
    expect(page.items, hasLength(3));
    final a = page.items[0];
    expect(a.status, GiftOrderStatus.delivered);
    expect(a.pet!.petId, 30);
    expect(a.pet!.avatarUrl, 'https://cdn/p.jpg');
    expect(a.petGone, isFalse);
    expect(a.unitPrice, 10000);
    expect(a.createdAt, DateTime.utc(2026, 10, 18, 10));
    expect(a.giftName('id'), 'Tulang');
    expect(a.giftName('en'), 'Bone');
    expect(page.items[1].pet, isNull);
    expect(page.items[1].petGone, isTrue);
    expect(page.items[2].status, GiftOrderStatus.unknown);
    expect(page.items[2].petGone, isTrue, reason: 'petGone=true 时即使带 pet 也不可跳转');
    expect(GiftOrderHistoryPage.fromJson(const {}).items, isEmpty);
    expect(GiftOrderHistoryPage.fromJson(const {}).nextCursor, isNull);
  });

  testWidgets('每次进入页面都重新拉取（autoDispose，不吃上次缓存）', (tester) async {
    final repo = _FakeRepo()..results.add(const GiftOrderHistoryPage(items: []));
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('home'))),
      GoRoute(path: GiftOrdersPage.routePath, builder: (c, s) => const GiftOrdersPage()),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    for (var i = 1; i <= 2; i++) {
      unawaited(router.push(GiftOrdersPage.routePath));
      await tester.pumpAndSettle();
      expect(find.byType(GiftOrdersPage), findsOneWidget);
      expect(repo.cursors, hasLength(i));
      router.pop();
      await tester.pumpAndSettle();
    }
  });
}
