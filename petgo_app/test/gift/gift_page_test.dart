import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_order_browse.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/gift/presentation/gift_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：礼物页逐笔列表（V1.5.0 gift-pack Story 2-4 · A4 ~ A6 / F2 ~ F4）。结构断言，不写 golden。
class _Repo extends GiftRepository {
  _Repo() : super(dio: Dio());

  /// key = 'filter|categoryId|cursor' → 页或要抛出的异常。
  final Map<String, Object> pages = {};
  final List<String> calls = [];

  @override
  Future<GiftOrderPage> fetchPetGiftOrders(int petId,
      {GiftOrderFilter filter = const GiftOrderFilter.all(), String? cursor}) async {
    final key = '${filter.unopened ? 'U' : 'A'}|${filter.categoryId ?? ''}|${cursor ?? ''}';
    calls.add(key);
    final p = pages[key] ?? const GiftOrderPage();
    if (p is GiftOrderPage) return p;
    throw p;
  }
}

DioException _status(int code) {
  final ro = RequestOptions(path: '/');
  return DioException(requestOptions: ro, response: Response<dynamic>(requestOptions: ro, statusCode: code));
}

const _pet = GiftShowcasePet(petId: 42, name: 'Mochi', petType: 'CAT');
const _cats = [GiftCategoryChip(categoryId: 3, nameEn: 'Toys', nameId: 'Mainan')];

GiftOrderCard _card(String t, {bool? opened, String nick = 'Ana'}) => GiftOrderCard(
    orderToken: t, giftNameEn: 'Bone $t', giftNameId: 'Tulang $t', sender: GiftOrderSender(nickname: nick), opened: opened);

void main() {
  late _Repo repo;
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    repo = _Repo();
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, {int petId = 42}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: GiftPage(petId: petId),
      ),
    ));
    await tester.pumpAndSettle();
  }

  test('routeFor / routePattern', () {
    expect(GiftPage.routePattern, '/pets/:petId/gifts');
    expect(GiftPage.routeFor(5), '/pets/5/gifts');
    expect(GiftPage.routeFor(5, openLatest: true), '/pets/5/gifts?open=latest');
  });

  testWidgets('F2 主人：未拆排前（礼盒 + Masih terbungkus + Baru）、Buka Semua、Belum dibuka 胶囊、无送礼按钮', (tester) async {
    repo.pages['A||'] = GiftOrderPage(
        items: [_card('a', opened: false), _card('b', opened: true)],
        unopenedCount: 1,
        categories: _cats,
        pet: _pet,
        viewerIsOwner: true);
    await pump(tester);
    expect(find.text('Hadiah Mochi'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageOpenAll')), findsOneWidget);
    expect(find.text('Buka Semua'), findsOneWidget);
    final a = find.byKey(const ValueKey('giftOrderTile-a'));
    expect(find.descendant(of: a, matching: find.text('Masih terbungkus')), findsOneWidget);
    expect(find.descendant(of: a, matching: find.byKey(const ValueKey('giftOrderTileNew'))), findsOneWidget);
    final b = find.byKey(const ValueKey('giftOrderTile-b'));
    expect(find.descendant(of: b, matching: find.text('Tulang b')), findsOneWidget);
    expect(find.descendant(of: b, matching: find.text('dari Ana')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageFilter-unopened')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageFilter-cat-3')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageSendButton')), findsNothing);
    expect(events.map((e) => e.$1), ['gift_wall_opened', 'gift_sender_list_opened']);
    expect(events.every((e) => e.$2?['is_owner'] == true), isTrue);
  });

  testWidgets('A4 访客：无 Belum dibuka / Buka Semua，全是礼物本身，吸底 Kirim Hadiah', (tester) async {
    repo.pages['A||'] = const GiftOrderPage(
        items: [GiftOrderCard(orderToken: 'a', giftNameEn: 'Bone', giftNameId: 'Tulang')],
        categories: _cats,
        pet: _pet,
        viewerIsOwner: false);
    await pump(tester);
    expect(find.byKey(const ValueKey('giftPageFilter-unopened')), findsNothing);
    expect(find.byKey(const ValueKey('giftPageOpenAll')), findsNothing);
    expect(find.text('Masih terbungkus'), findsNothing);
    expect(find.text('Tulang'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageSendButton')), findsOneWidget);
  });

  testWidgets('分类筛选重新请求；F4 未拆筛选为空 → 主人空态', (tester) async {
    repo.pages['A||'] = GiftOrderPage(
        items: [_card('a', opened: false)], unopenedCount: 1, categories: _cats, pet: _pet, viewerIsOwner: true);
    repo.pages['A|3|'] = GiftOrderPage(items: [_card('t', opened: true)], unopenedCount: 1);
    await pump(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('giftPageFilter-cat-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('giftPageFilter-cat-3')));
    await tester.pumpAndSettle();
    expect(repo.calls.last, 'A|3|', reason: '${repo.calls}');
    expect(find.byKey(const ValueKey('giftOrderTile-t')), findsOneWidget);
    await tester.ensureVisible(find.byKey(const ValueKey('giftPageFilter-unopened')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('giftPageFilter-unopened')));
    await tester.pumpAndSettle();
    expect(repo.calls.last, 'U||');
    expect(find.text('Hadiah dari teman akan muncul di sini.'), findsOneWidget);
  });

  testWidgets('A5 访客空态 + 送礼按钮；F3 主人空态无按钮', (tester) async {
    repo.pages['A||'] = const GiftOrderPage(pet: _pet, viewerIsOwner: false, categories: []);
    await pump(tester);
    expect(find.text('Mochi belum menerima hadiah'), findsOneWidget);
    expect(find.text('Jadi yang pertama kirim hadiah untuk Mochi.'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPageSendButton')), findsOneWidget);
  });

  testWidgets('A6 加载失败：Gagal memuat hadiah + Coba lagi，重试成功', (tester) async {
    repo.pages['A||'] = _status(404);
    await pump(tester);
    expect(find.text('Gagal memuat hadiah'), findsOneWidget);
    repo.pages['A||'] = GiftOrderPage(items: [_card('a')], pet: _pet, viewerIsOwner: false, categories: const []);
    await tester.tap(find.byKey(const ValueKey('giftPageRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftOrderTile-a')), findsOneWidget);
  });

  testWidgets('翻页：nextCursor 自动加载、按 orderToken 去重', (tester) async {
    repo.pages['A||'] = GiftOrderPage(
        items: [_card('a', opened: true)], nextCursor: 'c1', pet: _pet, viewerIsOwner: true, categories: const []);
    repo.pages['A||c1'] = GiftOrderPage(items: [_card('a', opened: true), _card('b', opened: true)]);
    await pump(tester);
    expect(repo.calls, ['A||', 'A||c1']);
    expect(find.byKey(const ValueKey('giftOrderTile-a')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftOrderTile-b')), findsOneWidget);
  });

  testWidgets('petId 非法 → 直接失败页，不发请求', (tester) async {
    await pump(tester, petId: 0);
    expect(find.byKey(const ValueKey('giftPageError')), findsOneWidget);
    expect(repo.calls, isEmpty);
  });
}
