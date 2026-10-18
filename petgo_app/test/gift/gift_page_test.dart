import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/gift/presentation/gift_page.dart';
import 'package:tailtopia/features/gift/presentation/gift_supporters_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：礼物页 E1 ~ E7（V1.4.0 gift Story 2.2 · AC3 / AC6.4 / AC7）。结构断言，不写 golden（视觉留 L2）。
class _Repo extends GiftRepository {
  _Repo({required this.showcase, Map<String?, Object>? pages}) : pages = pages ?? {}, super(dio: Dio());

  /// [PetGiftShowcase] 或要抛出的异常。
  Object showcase;

  /// 游标 → 页（[GiftSupporterPage]）或要抛出的异常。
  final Map<String?, Object> pages;
  Completer<void>? showcaseGate;

  /// 非 null 时首页（cursor == null）请求挂起，直到 complete。
  Completer<void>? firstPageGate;
  final List<String?> cursors = [];

  @override
  Future<PetGiftShowcase> getPetGifts(int petId) async {
    if (showcaseGate != null) await showcaseGate!.future;
    final s = showcase;
    if (s is PetGiftShowcase) return s;
    throw s;
  }

  @override
  Future<GiftSupporterPage> getSupporters(int petId, {String? cursor}) async {
    cursors.add(cursor);
    if (cursor == null && firstPageGate != null) await firstPageGate!.future;
    final p = pages[cursor] ?? const GiftSupporterPage();
    if (p is GiftSupporterPage) return p;
    throw p;
  }
}

DioException _status(int code) {
  final ro = RequestOptions(path: '/');
  return DioException(
      requestOptions: ro, response: Response<dynamic>(requestOptions: ro, statusCode: code));
}

const _pet = GiftShowcasePet(petId: 42, name: 'Mochi', petType: 'CAT');

List<GiftWallItem> _wall(int n) => [
      for (var i = 0; i < n; i++)
        GiftWallItem(nameEn: 'Gift$i', nameId: 'Hadiah$i', unitPrice: 50000 - i * 1000, count: i + 1),
    ];

PetGiftShowcase _showcase({bool owner = false, int giftCount = 21, int wallSize = 12}) => PetGiftShowcase(
      pet: _pet,
      viewerIsOwner: owner,
      totalValue: giftCount == 0 ? 0 : 188000,
      giftCount: giftCount,
      supporterCount: giftCount == 0 ? 0 : 9,
      wall: giftCount == 0 ? const [] : _wall(wallSize),
    );

GiftSupporter _row(int rank, {bool deleted = false, int amount = 1000}) => GiftSupporter(
      rank: rank,
      nickname: deleted ? null : 'User$rank',
      deleted: deleted,
      totalAmount: amount,
      giftCount: 1,
      gifts: const [GiftSupporterKind(nameEn: 'Bone', nameId: 'Tulang', count: 3)],
    );

final _firstPage = GiftSupporterPage(items: [
  _row(1, amount: 100000),
  _row(2, amount: 40000),
  _row(3, deleted: true, amount: 30000),
  _row(4, amount: 13000),
]);

Future<_Repo> _pump(WidgetTester tester, _Repo repo, {bool expanded = false, bool settle = true}) async {
  tester.view.physicalSize = const Size(800, 3000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [giftRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GiftPage(petId: 42, wallInitiallyExpanded: expanded),
    ),
  ));
  if (settle) await tester.pumpAndSettle();
  return repo;
}

Finder _send() => find.byKey(const ValueKey('giftPageSendButton'));
Finder _cell(int i) => find.byKey(ValueKey('giftWallCell_$i'));

BoxDecoration _badge(WidgetTester tester, int rank) => tester
    .widget<Container>(find.byKey(ValueKey('giftRankBadge_$rank')))
    .decoration! as BoxDecoration;

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('routeFor / routePattern', () {
    expect(GiftPage.routePattern, '/pets/:petId/gifts');
    expect(GiftPage.routeFor(5), '/pets/5/gifts');
    expect(GiftPage.routeFor(5, wallExpanded: true), '/pets/5/gifts?wall=expanded');
  });

  testWidgets('E1 访客：标题、汇总卡完整金额、吸底 Kirim Hadiah', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(), pages: {null: _firstPage}));
    expect(find.text('Hadiah Mochi'), findsOneWidget);
    expect(find.text('Rp188.000'), findsOneWidget);
    expect(find.text('21 hadiah dari 9 pendukung'), findsOneWidget);
    expect(find.text('Koleksi hadiah'), findsOneWidget);
    expect(find.text('· 12 jenis'), findsOneWidget);
    expect(find.text('Pendukung'), findsOneWidget);
    expect(find.text('· 9 orang'), findsOneWidget);
    expect(_send(), findsOneWidget);
    expect(find.text('Kirim Hadiah'), findsOneWidget);
  });

  testWidgets('E2 主人：无吸底按钮', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(owner: true), pages: {null: _firstPage}));
    expect(find.text('Rp188.000'), findsOneWidget);
    expect(_send(), findsNothing);
  });

  testWidgets('墙 > 8 种：默认只露 8 格 +「Lihat semua」，点后全部、按钮消失；名字按语言取 nameId', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(), pages: {null: _firstPage}));
    expect(_cell(7), findsOneWidget);
    expect(_cell(8), findsNothing);
    expect(find.text('Hadiah0'), findsOneWidget);
    expect(find.text('Gift0'), findsNothing);
    expect(find.text('×1'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('giftWallShowAll')));
    await tester.pumpAndSettle();
    expect(_cell(11), findsOneWidget);
    expect(find.byKey(const ValueKey('giftWallShowAll')), findsNothing);
  });

  testWidgets('墙 ≤ 8 种：无「Lihat semua」', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(wallSize: 8), pages: {null: _firstPage}));
    expect(_cell(7), findsOneWidget);
    expect(find.byKey(const ValueKey('giftWallShowAll')), findsNothing);
  });

  testWidgets('?wall=expanded：墙初始即展开全部', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(), pages: {null: _firstPage}), expanded: true);
    expect(_cell(11), findsOneWidget);
    expect(find.byKey(const ValueKey('giftWallShowAll')), findsNothing);
  });

  testWidgets('粉丝榜：前三名实色金 / 银 / 铜圆 + 白字，第 4 名灰；注销者「Pengguna terhapus」；累计金额完整', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(), pages: {null: _firstPage}));
    expect(_badge(tester, 1).color, AppColors.gold);
    expect(_badge(tester, 2).color, AppColors.rankSilver);
    expect(_badge(tester, 3).color, AppColors.rankBronze);
    expect(_badge(tester, 4).color, AppColors.line2);
    Text rankText(int r) => tester.widget<Text>(
        find.descendant(of: find.byKey(ValueKey('giftRankBadge_$r')), matching: find.byType(Text)));
    expect(rankText(1).style!.color, Colors.white);
    expect(rankText(4).style!.color, AppColors.muted);
    expect(find.textContaining('🥇'), findsNothing);

    expect(find.text('Pengguna terhapus'), findsOneWidget);
    expect(find.text('Rp100.000'), findsOneWidget);
    final amount = tester.widget<Text>(find.byKey(const ValueKey('giftSupporterAmount_1')));
    expect(amount.style!.fontSize, 14);
    expect(amount.style!.fontFeatures, contains(const FontFeature.tabularFigures()));
    expect(find.text('×3'), findsWidgets);
  });

  testWidgets('E3 访客无礼物：空态文案 + 吸底按钮照常；不请求粉丝榜区块', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(giftCount: 0)));
    expect(find.byKey(const ValueKey('giftPageEmpty')), findsOneWidget);
    expect(find.text('Mochi belum menerima hadiah'), findsOneWidget);
    expect(find.text('Jadi yang pertama kirim hadiah untuk Mochi.'), findsOneWidget);
    expect(find.byIcon(Icons.redeem_rounded), findsOneWidget);
    expect(_send(), findsOneWidget);
  });

  testWidgets('E4 主人无礼物：主人副文案、无吸底按钮', (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(owner: true, giftCount: 0)));
    expect(find.text('Mochi belum menerima hadiah'), findsOneWidget);
    expect(find.text('Hadiah dari teman akan muncul di sini.'), findsOneWidget);
    expect(_send(), findsNothing);
  });

  testWidgets('E5 加载中：居中转圈、无按钮', (tester) async {
    final repo = _Repo(showcase: _showcase(), pages: {null: _firstPage})..showcaseGate = Completer<void>();
    await _pump(tester, repo, settle: false);
    await tester.pump();
    expect(find.byKey(const ValueKey('giftPageLoading')), findsOneWidget);
    expect(_send(), findsNothing);
    repo.showcaseGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Rp188.000'), findsOneWidget);
  });

  for (final code in [403, 404, 500]) {
    testWidgets('E6 加载失败（$code 同形态）：cloud_off + 文案 + Coba lagi；重试后成功', (tester) async {
      final repo = _Repo(showcase: _status(code), pages: {null: _firstPage});
      await _pump(tester, repo);
      expect(find.byKey(const ValueKey('giftPageError')), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off), findsOneWidget);
      expect(find.text('Gagal memuat, silakan coba lagi'), findsOneWidget);
      expect(find.text('Coba lagi'), findsOneWidget);
      expect(_send(), findsNothing);
      repo.showcase = _showcase();
      await tester.tap(find.byKey(const ValueKey('giftPageRetry')));
      await tester.pumpAndSettle();
      expect(find.text('Rp188.000'), findsOneWidget);
    });
  }

  testWidgets('E7 下一页失败：行内「Gagal memuat · Coba lagi」，已加载行保留；点击只重试下一页', (tester) async {
    final repo = _Repo(showcase: _showcase(), pages: {
      null: GiftSupporterPage(items: _firstPage.items, nextCursor: 'c2'),
      'c2': _status(500),
    });
    await _pump(tester, repo);
    // 首页不足一屏 → 自动加载下一页 → 失败。
    expect(repo.cursors, [null, 'c2']);
    expect(find.byKey(const ValueKey('giftSupportersLoadMoreRetry')), findsOneWidget);
    expect(find.text('Gagal memuat · Coba lagi'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftSupporterRow_4')), findsOneWidget);

    repo.pages['c2'] = GiftSupporterPage(items: [_row(5, amount: 1000)]);
    await tester.tap(find.byKey(const ValueKey('giftSupportersLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(repo.cursors, [null, 'c2', 'c2']);
    expect(find.byKey(const ValueKey('giftSupporterRow_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftSupporterRow_5')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftSupportersLoadMoreRetry')), findsNothing);
  });

  test('刷新（送达后 invalidate）进行中不续页：不拿旧游标与新首页并发（code-review）', () async {
    final repo = _Repo(showcase: _showcase(), pages: {
      null: GiftSupporterPage(items: _firstPage.items, nextCursor: 'c2'),
      'c2': GiftSupporterPage(items: [_row(5)]),
    });
    final container = ProviderContainer(overrides: [giftRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    final p = giftSupportersProvider(42);
    final sub = container.listen(p, (_, _) {});
    addTearDown(sub.close);
    await container.read(p.future);

    repo.firstPageGate = Completer<void>();
    container.invalidate(p);
    expect(container.read(p).isLoading, isTrue);
    await container.read(p.notifier).loadMore();
    expect(repo.cursors, [null, null], reason: '刷新中不得用旧游标 c2 续页');

    repo.firstPageGate!.complete();
    final fresh = await container.read(p.future);
    expect(fresh.items.map((e) => e.rank), [1, 2, 3, 4]);
    expect(fresh.loadingMore, isFalse);
  });

  testWidgets('榜首页失败：榜区块内行内重试，墙照常', (tester) async {
    final repo = _Repo(showcase: _showcase(), pages: {null: _status(500)});
    await _pump(tester, repo);
    expect(find.text('Rp188.000'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftSupportersRetry')), findsOneWidget);
    repo.pages[null] = _firstPage;
    await tester.tap(find.byKey(const ValueKey('giftSupportersRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftSupporterRow_1')), findsOneWidget);
  });

  testWidgets('埋点：gift_wall_opened 报一次带 is_owner；粉丝榜首屏可见 → gift_sender_list_opened 只报一次',
      (tester) async {
    await _pump(tester, _Repo(showcase: _showcase(owner: true), pages: {null: _firstPage}));
    // 再触发几次重建（展开墙）不应重复上报。
    await tester.tap(find.byKey(const ValueKey('giftWallShowAll')));
    await tester.pumpAndSettle();
    final wall = events.where((e) => e.$1 == 'gift_wall_opened').toList();
    expect(wall, hasLength(1));
    expect(wall.single.$2, {'is_owner': true});
    final list = events.where((e) => e.$1 == 'gift_sender_list_opened').toList();
    expect(list, hasLength(1));
    expect(list.single.$2, {'is_owner': true});
  });

  testWidgets('埋点：粉丝榜在首屏之下 → 滚入视口才报 gift_sender_list_opened', (tester) async {
    tester.view.physicalSize = const Size(400, 500);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        giftRepositoryProvider.overrideWithValue(_Repo(showcase: _showcase(), pages: {null: _firstPage})),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const GiftPage(petId: 42, wallInitiallyExpanded: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'gift_wall_opened'), hasLength(1));
    expect(events.where((e) => e.$1 == 'gift_sender_list_opened'), isEmpty);
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -600));
    await tester.pumpAndSettle();
    final list = events.where((e) => e.$1 == 'gift_sender_list_opened').toList();
    expect(list, hasLength(1));
    expect(list.single.$2, {'is_owner': false});
  });

  testWidgets('petId 非法（路由解析失败 → 0）：直接 E6，不发请求', (tester) async {
    final repo = _Repo(showcase: _showcase());
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const GiftPage(petId: 0),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftPageError')), findsOneWidget);
    expect(repo.cursors, isEmpty);
  });
}
