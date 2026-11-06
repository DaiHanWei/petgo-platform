import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/coupon/domain/user_coupon.dart';
import 'package:tailtopia/features/shop/coupon/presentation/my_coupons_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'my_coupons_fakes.dart';

/// L0 widget：我的券（v1.4.2 shop Story 9.3 · AC2 / AC3 / AC7.1）。不写 golden，视觉留 L2。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, FakeMyCouponRepository repo) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [couponRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/me/coupons',
          routes: [
            GoRoute(path: '/me/coupons', builder: (_, _) => const MyCouponsPage()),
            GoRoute(
              path: '/shop/orders/:token',
              builder: (_, s) => Scaffold(body: Text('order:${s.pathParameters['token']}')),
            ),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  /// 同 [pump]，但不等列表首屏（首屏被 gate 卡住时用）。
  Future<void> pumpNoSettle(WidgetTester tester, FakeMyCouponRepository repo) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [couponRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/me/coupons',
          routes: [GoRoute(path: '/me/coupons', builder: (_, _) => const MyCouponsPage())],
        ),
      ),
    ));
    await tester.pump();
  }

  /// 弹出的 toast 有计时器：让它走完，避免测试结束时残留 Timer。
  Future<void> drainToast(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  }

  MyCouponPage page(List<UserCoupon> items, {int? count, String? next}) =>
      MyCouponPage(items: items, availableCount: count ?? items.length, nextCursor: next);

  Finder input() => find.byKey(const ValueKey('couponRedeemInput'));
  Finder button() => find.byKey(const ValueKey('couponRedeemButton'));
  bool buttonEnabled(WidgetTester t) => t.widget<FilledButton>(button()).onPressed != null;

  final sample = [
    coupon(token: 'u1', nameId: 'Kupon pengguna baru', amount: 20000, minSpend: 100000),
    coupon(
        token: 'u2',
        nameId: 'Diskon makanan anjing',
        amount: 20000,
        minSpend: 300000,
        scopeType: 'CATEGORY',
        scopeCategories: ['MAKANAN'],
        expiringSoon: true),
    coupon(
        token: 'u3',
        kind: 'FREE_SHIPPING',
        nameId: 'Gratis ongkir',
        amount: 15000,
        minSpend: 0,
        scopeType: 'PRODUCT'),
  ];

  group('G1 可用列表', () {
    testWidgets('满减 / 免运 / 即将过期 / 三种范围 + 页签计数取列表响应', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.available: [page(sample, count: 3)],
      });
      await pump(t, repo);
      expect(find.text('Kupon Saya'), findsOneWidget);
      expect(find.text('Bisa dipakai (3)'), findsOneWidget);
      expect(find.text('Sudah dipakai'), findsOneWidget);
      expect(find.text('Kupon pengguna baru'), findsOneWidget);
      expect(find.text('min. belanja Rp 100.000'), findsOneWidget);
      expect(find.text('min. belanja Rp 300.000'), findsOneWidget);
      // 免运券面额位
      expect(find.text('Gratis ongkir'), findsWidgets);
      expect(find.text('Rp 15.000'), findsOneWidget);
      // 范围
      expect(find.text('Semua produk'), findsOneWidget);
      expect(find.text('Kategori Makanan'), findsOneWidget);
      expect(find.text('Produk tertentu'), findsOneWidget);
      // 即将过期：Berakhir {date, time}（WIB：16:59Z → 23:59）+ Segera berakhir
      expect(find.text('Berakhir 31 Des 2026, 23:59'), findsOneWidget);
      expect(find.byKey(const ValueKey('couponExpiringSoon')), findsOneWidget);
      expect(find.text('s/d 31 Des 2026'), findsNWidgets(2));
    });

    testWidgets('C-3：占用券原样渲染（App 不按 status 过滤）、置灰标「Dipakai di pesanan…」、不可点', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.available: [
          page([coupon(token: 'u1'), coupon(token: 'lk', status: 'LOCKED', locked: true)], count: 1)
        ],
      });
      await pump(t, repo);
      expect(find.byKey(const ValueKey('myCoupon_lk')), findsOneWidget);
      expect(find.text('Dipakai di pesanan menunggu bayar'), findsOneWidget);
      // 页签计数取服务端 availableCount（不含 LOCKED），不本地数
      expect(find.text('Bisa dipakai (1)'), findsOneWidget);
    });

    testWidgets('列表首屏未返回 / 失败：页签用不带数的「Bisa dipakai」', (t) async {
      final repo = FakeMyCouponRepository(listError: problem(500, null));
      await pump(t, repo);
      expect(find.text('Bisa dipakai'), findsOneWidget);
    });
  });

  group('G2 已使用', () {
    testWidgets('降权 0.55 + 「Dipakai {date} · {no}」可点进订单；无 token 不可点；三字段缺失不显示', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.used: [
          page([
            coupon(
                token: 'd1',
                status: 'USED',
                expiringSoon: true,
                usedAt: '2026-11-01T18:00:00Z',
                usedOrderNo: 'TOKO-001',
                usedOrderToken: 'ord1'),
            coupon(
                token: 'd2',
                status: 'USED',
                usedAt: '2026-10-02T03:00:00Z',
                usedOrderNo: 'TOKO-002'),
            coupon(token: 'd3', status: 'USED'),
          ], count: 0)
        ],
      });
      await pump(t, repo);
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      final op = t.widget<Opacity>(find.descendant(
          of: find.byKey(const ValueKey('myCoupon_d1')),
          matching: find.byKey(const ValueKey('couponDimmed'))));
      expect(op.opacity, 0.55);
      // 已使用不画 Segera berakhir、不用「Berakhir」写法
      expect(find.byKey(const ValueKey('couponExpiringSoon')), findsNothing);
      // WIB：18:00Z → 次日 01:00
      expect(find.text('Dipakai 2 Nov 2026 · TOKO-001'), findsOneWidget);
      expect(find.text('Dipakai 2 Okt 2026 · TOKO-002'), findsOneWidget);
      expect(find.byKey(const ValueKey('couponUsedLine')), findsOneWidget);
      expect(find.textContaining('Dipakai '), findsNWidgets(2));
      await t.tap(find.byKey(const ValueKey('couponUsedOrder_ord1')));
      await t.pumpAndSettle();
      expect(find.text('order:ord1'), findsOneWidget);
    });

    testWidgets('已使用为空：只有标题、无副句', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.available: [page(sample)],
        CouponTab.used: [page(const [], count: 3)],
      });
      await pump(t, repo);
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      expect(find.text('Belum ada kupon'), findsOneWidget);
      expect(find.text('Tukar kode di atas, atau klaim kupon di halaman produk.'), findsNothing);
    });

    testWidgets('来回切页签不重复请求（各自缓存）；下拉强刷', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.available: [page(sample)],
        CouponTab.used: [page([coupon(token: 'd1', status: 'USED')], count: 3)],
      });
      await pump(t, repo);
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('couponTab_available')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      expect(repo.listCalls, [(CouponTab.available, null), (CouponTab.used, null)]);
      await t.fling(find.byKey(const ValueKey('myCoupon_d1')), const Offset(0, 400), 1000);
      await t.pumpAndSettle();
      expect(repo.listCalls.last, (CouponTab.used, null));
      expect(repo.listCalls.length, 3);
    });
  });

  testWidgets('游标分页：滚到底自动拉下一页并追加', (t) async {
    final first = List.generate(20, (i) => coupon(token: 'p$i', nameId: 'Kupon $i'));
    final repo = FakeMyCouponRepository(pages: {
      CouponTab.available: [
        page(first, count: 21, next: 'c1'),
        page([coupon(token: 'last', nameId: 'Kupon terakhir')], count: 21),
      ],
    });
    await pump(t, repo);
    await t.scrollUntilVisible(find.text('Kupon terakhir'), 300,
        scrollable: find.byType(Scrollable).last);
    expect(repo.listCalls, [(CouponTab.available, null), (CouponTab.available, 'c1')]);
  });

  testWidgets('AC2.7：加载更多失败 → 底部「Coba lagi」，点后重拉成功追加', (t) async {
    final first = List.generate(20, (i) => coupon(token: 'p$i', nameId: 'Kupon $i'));
    final repo = FakeMyCouponRepository(pages: {
      CouponTab.available: [
        page(first, count: 21, next: 'c1'),
        page([coupon(token: 'last', nameId: 'Kupon terakhir')], count: 21),
      ],
    });
    repo.moreError = problem(500, null);
    await pump(t, repo);
    final retry = find.byKey(const ValueKey('couponLoadMoreRetry'));
    await t.scrollUntilVisible(retry, 300, scrollable: find.byType(Scrollable).last);
    await t.pumpAndSettle();
    expect(retry, findsOneWidget);
    expect(find.text('Kupon terakhir'), findsNothing);
    repo.moreError = null;
    await t.tap(retry);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(find.text('Kupon terakhir'), 300,
        scrollable: find.byType(Scrollable).last);
    expect(find.text('Kupon terakhir'), findsOneWidget);
    expect(retry, findsNothing);
  });

  testWidgets('G4 可用为空：标题 + 副句', (t) async {
    final repo = FakeMyCouponRepository(pages: {
      CouponTab.available: [page(const [], count: 0)],
    });
    await pump(t, repo);
    expect(find.text('Bisa dipakai (0)'), findsOneWidget);
    expect(find.text('Belum ada kupon'), findsOneWidget);
    expect(find.text('Tukar kode di atas, atau klaim kupon di halaman produk.'), findsOneWidget);
    expect(find.byIcon(Icons.confirmation_number_outlined), findsOneWidget);
  });

  testWidgets('G5 加载失败：cloud_off + 重试重拉；兑换区仍可用', (t) async {
    final repo = FakeMyCouponRepository(listError: problem(500, null));
    repo.redeemResult = coupon(token: 'new', couponToken: 'tplNew');
    await pump(t, repo);
    expect(find.byIcon(Icons.cloud_off), findsOneWidget);
    expect(find.text('Gagal memuat kupon.'), findsOneWidget);
    // 兑换区可用
    await t.enterText(input(), 'KUCING2026');
    await t.pump();
    expect(buttonEnabled(t), isTrue);
    // 重试
    repo.listError = null;
    repo.pages[CouponTab.available] = [page(sample)];
    await t.tap(find.text('Coba lagi'));
    await t.pumpAndSettle();
    expect(find.text('Kupon pengguna baru'), findsOneWidget);
  });

  group('兑换', () {
    testWidgets('输入为空禁用；输入自动大写并过滤非字母数字，提交规范化码', (t) async {
      final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
      repo.redeemResult = coupon(token: 'new', couponToken: 'tplNew');
      await pump(t, repo);
      expect(buttonEnabled(t), isFalse);
      await t.enterText(input(), '   ');
      await t.pump();
      expect(buttonEnabled(t), isFalse);
      await t.enterText(input(), ' kucing2026 ');
      await t.pump();
      expect(t.widget<TextField>(input()).controller!.text, 'KUCING2026');
      await t.tap(button());
      await t.pumpAndSettle();
      expect(repo.redeemCalls, ['KUCING2026']);
      await drainToast(t);
    });

    testWidgets('复审 #3：过滤后不足 4 位不可提交（不发请求）；超长截到 32', (t) async {
      final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
      await pump(t, repo);
      await t.enterText(input(), 'a-b c');
      await t.pump();
      expect(t.widget<TextField>(input()).controller!.text, 'ABC');
      expect(buttonEnabled(t), isFalse);
      await t.tap(button(), warnIfMissed: false);
      await t.testTextInput.receiveAction(TextInputAction.done);
      await t.pump();
      expect(repo.redeemCalls, isEmpty);
      await t.enterText(input(), 'x' * 40);
      await t.pump();
      expect(t.widget<TextField>(input()).controller!.text, 'X' * 32);
      expect(buttonEnabled(t), isTrue);
    });

    testWidgets('复审 #2：首屏未返回时兑换成功 → 首屏完成后新券仍在顶部且带 Baru', (t) async {
      final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
      repo.firstGate = Completer<void>();
      repo.redeemResult = coupon(token: 'new', couponToken: 'tplNew', nameId: 'Kupon kode');
      await t.pumpWidget(const SizedBox());
      await pumpNoSettle(t, repo);
      await t.enterText(input(), 'KUCING2026');
      await t.pump();
      await t.tap(button());
      await t.pump();
      await t.pump();
      expect(repo.redeemCalls, ['KUCING2026']);
      // 首屏返回（不含新券）后才插顶；后台刷新失败时保留本地插入 —— 新券只能来自等首屏后的插顶。
      repo.refreshError = problem(500, null);
      repo.firstGate!.complete();
      await drainToast(t);
      expect(repo.listCalls.where((c) => c.$2 == null), hasLength(2)); // 首屏 + 后台刷新
      final tile = find.byKey(const ValueKey('myCoupon_new'));
      expect(tile, findsOneWidget);
      expect(t.getTopLeft(tile).dy,
          lessThan(t.getTopLeft(find.byKey(const ValueKey('myCoupon_u1'))).dy));
      expect(find.descendant(of: tile, matching: find.text('Baru')), findsOneWidget);
    });

    testWidgets('G6 成功：转圈防重复 → 顶部插入 + Baru + 清空 + toast + 埋点一次（模板 token）', (t) async {
      final repo = FakeMyCouponRepository(pages: {
        CouponTab.available: [page(sample)],
        CouponTab.used: [page(const [], count: 3)],
      });
      repo.redeemGate = Completer<void>();
      repo.redeemResult = coupon(token: 'new', couponToken: 'tplNew', nameId: 'Kupon kode', isNew: true);
      await pump(t, repo);
      // 从「已使用」页签兑换：成功后切回可用
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      await t.enterText(input(), 'KUCING2026');
      await t.pump();
      await t.tap(button());
      await t.pump();
      expect(find.byKey(const ValueKey('couponRedeemSpinner')), findsOneWidget);
      expect(buttonEnabled(t), isFalse);
      expect(t.widget<TextField>(input()).readOnly, isTrue);
      await t.tap(button(), warnIfMissed: false);
      await t.pump();
      expect(repo.redeemCalls, hasLength(1));
      // 后台刷新返回的列表里没带 isNew：Baru 仍靠本页 Set 保留
      repo.pages[CouponTab.available] = [
        page([coupon(token: 'new', couponToken: 'tplNew', nameId: 'Kupon kode'), ...sample], count: 4)
      ];
      repo.redeemGate!.complete();
      await t.pump();
      await t.pump();
      expect(find.text('Kupon berhasil ditukar'), findsOneWidget);
      await drainToast(t);
      expect(find.text('Bisa dipakai (4)'), findsOneWidget);
      expect(t.widget<TextField>(input()).controller!.text, isEmpty);
      final firstTile = find.byKey(const ValueKey('myCoupon_new'));
      expect(firstTile, findsOneWidget);
      expect(t.getTopLeft(firstTile).dy,
          lessThan(t.getTopLeft(find.byKey(const ValueKey('myCoupon_u1'))).dy));
      expect(find.descendant(of: firstTile, matching: find.text('Baru')), findsOneWidget);
      final claimed = events.where((e) => e.$1 == 'toko_coupon_claimed').toList();
      expect(claimed, hasLength(1));
      expect(claimed.single.$2, {'coupon_id': 'tplNew', 'source': 'code'});
      // 兑换码明文不进埋点
      expect(events.any((e) => e.$2?.values.contains('KUCING2026') ?? false), isFalse);
    });

    final inline = <String, String>{
      'coupon-not-found': 'Kode kupon tidak valid',
      'coupon-code-used': 'Kode ini sudah dipakai',
      'coupon-not-in-period': 'Kode ini tidak dalam masa berlaku',
      'coupon-already-claimed': 'Kamu sudah klaim kupon ini',
      'coupon-sold-out': 'Kupon sudah habis',
    };
    for (final e in inline.entries) {
      testWidgets('G7 ${e.key} → 框下「${e.value}」（不 toast、不埋点）；改输入清除', (t) async {
        final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
        repo.redeemError = problem(e.key == 'coupon-not-found' ? 404 : 409, e.key);
        await pump(t, repo);
        await t.enterText(input(), 'KUCING2026');
        await t.pump();
        await t.tap(button());
        await t.pumpAndSettle();
        expect(find.byKey(const ValueKey('couponRedeemError')), findsOneWidget);
        expect(find.text(e.value), findsOneWidget);
        expect(events.where((x) => x.$1 == 'toko_coupon_claimed'), isEmpty);
        expect(buttonEnabled(t), isTrue);
        await t.enterText(input(), 'KUCING2027');
        await t.pump();
        expect(find.byKey(const ValueKey('couponRedeemError')), findsNothing);
      });
    }

    testWidgets('G7 其他错误 → toast「Terjadi kesalahan…」，框不标红', (t) async {
      final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
      repo.redeemError = problem(500, null);
      await pump(t, repo);
      await t.enterText(input(), 'KUCING2026');
      await t.pump();
      await t.tap(button());
      await t.pump();
      await t.pump();
      expect(find.text('Terjadi kesalahan, coba lagi'), findsOneWidget);
      expect(find.byKey(const ValueKey('couponRedeemError')), findsNothing);
      await drainToast(t);
    });

    testWidgets('G8 429：输入框与按钮禁用 + 提示，本页存续期间保持', (t) async {
      final repo = FakeMyCouponRepository(pages: {CouponTab.available: [page(sample)]});
      repo.redeemError = problem(429, 'coupon-redeem-locked');
      await pump(t, repo);
      await t.enterText(input(), 'KUCING2026');
      await t.pump();
      await t.tap(button());
      await t.pumpAndSettle();
      expect(find.text('Terlalu banyak percobaan. Coba lagi dalam 1 jam.'), findsOneWidget);
      expect(buttonEnabled(t), isFalse);
      expect(t.widget<TextField>(input()).enabled, isFalse);
      await t.tap(find.byKey(const ValueKey('couponTab_used')));
      await t.pumpAndSettle();
      expect(buttonEnabled(t), isFalse);
      expect(find.text('Terlalu banyak percobaan. Coba lagi dalam 1 jam.'), findsOneWidget);
    });
  });
}
