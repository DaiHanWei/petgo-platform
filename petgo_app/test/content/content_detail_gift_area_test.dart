import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/gift/domain/gift_showcase.dart';
import 'package:tailtopia/features/place/domain/checkin_place_ref.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：Diary 详情礼物区（V1.4.0 gift Story 2.2 · AC4 · UI 稿 A3 / A5 / A6）。
const _wall = [
  GiftWallItem(nameEn: 'Cake', nameId: 'Kue ultah', unitPrice: 50000, count: 1),
  GiftWallItem(nameEn: 'Ball', nameId: 'Bola tenis', unitPrice: 10000, count: 3),
  GiftWallItem(nameEn: 'Bone', nameId: 'Tulang', unitPrice: 1000, count: 20),
];

const _mochi = GiftPetSummary(
    petId: 42, name: 'Mochi', totalValue: 100000, giftCount: 24, kindCount: 5, wallPreview: _wall);
const _empty = GiftPetSummary(petId: 43, name: 'Kopi');

ContentDetail _detail({
  String type = 'GROWTH_MOMENT',
  bool isAuthor = false,
  List<GiftPetSummary> pets = const [_mochi],
  CheckinPlaceRef? place,
  int? collectCount,
}) =>
    ContentDetail(
      id: 5,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Rina',
      type: type,
      body: 'Mochi nemu kardus baru',
      likeCount: 0,
      commentCount: 0,
      liked: false,
      isAuthor: isAuthor,
      createdAt: DateTime.utc(2026, 10, 1),
      checkinPlace: place,
      giftPets: pets,
      collectCount: collectCount,
    );

Future<void> _pump(WidgetTester tester, ContentDetail d) async {
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (c, s) => const ContentDetailPage(postId: 5)),
    GoRoute(
        path: '/pets/:petId/gifts',
        builder: (c, s) => Scaffold(body: Text('gift:${s.pathParameters['petId']}'))),
  ]);
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    detailRepositoryProvider.overrideWithValue(_Repo(d)),
    authControllerProvider.overrideWith(_Auth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ),
  ));
  await tester.pumpAndSettle();
}

Finder _row(int petId) => find.byKey(ValueKey('giftPetRow_$petId'));
Finder _send() => find.byKey(const ValueKey('giftDetailSendButton'));

void main() {
  group('ContentDetail.giftPets 解析', () {
    Map<String, dynamic> base() => {
          'id': 5,
          'authorId': 7,
          'type': 'GROWTH_MOMENT',
          'createdAt': '2026-10-01T00:00:00Z',
        };

    test('缺键 → 空表', () {
      expect(ContentDetail.fromJson(base()).giftPets, isEmpty);
    });

    test('2.1 线格式 → 字段齐全；+N = kindCount − wallPreview.length', () {
      final d = ContentDetail.fromJson({
        ...base(),
        'giftPets': [
          {
            'petId': 42,
            'name': 'Mochi',
            'totalValue': 53000,
            'giftCount': 4,
            'kindCount': 5,
            'wallPreview': [
              {'nameEn': 'Cake', 'nameId': 'Kue ultah', 'iconUrl': 'https://x/c.png', 'unitPrice': 50000, 'count': 1},
            ],
          },
        ],
      });
      final p = d.giftPets.single;
      expect(p.petId, 42);
      expect(p.giftCount, 4);
      expect(p.totalValue, 53000);
      expect(p.wallPreview.single.nameFor('id'), 'Kue ultah');
      expect(p.wallPreview.single.nameFor('en'), 'Cake');
      expect(p.moreKinds, 4);
      // withFollowEpoch 不丢 giftPets。
      expect(d.withFollowEpoch(3).giftPets, hasLength(1));
    });
  });

  testWidgets('A3：礼物区在场所条之后、评论标题之前；头像 + 名字 +「24 hadiah」+ 3 个图标 +「+2」+ Kirim Hadiah',
      (tester) async {
    await _pump(
        tester, _detail(place: const CheckinPlaceRef(token: 'pppp', name: 'Kopi Kucing', available: true)));
    expect(find.text('HADIAH'), findsOneWidget);
    expect(_row(42), findsOneWidget);
    expect(find.text('Mochi'), findsWidgets);
    expect(find.text('24 hadiah'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPetRowIcons_42')), findsOneWidget);
    expect(find.text('+2'), findsOneWidget);
    expect(find.descendant(of: _row(42), matching: find.byIcon(Icons.chevron_right_rounded)), findsOneWidget);
    expect(_send(), findsOneWidget);

    final stripY = tester.getTopLeft(find.byKey(const ValueKey('detailCheckinPlaceStrip'))).dy;
    final rowY = tester.getTopLeft(_row(42)).dy;
    final sendY = tester.getTopLeft(_send()).dy;
    final commentsY = tester.getTopLeft(find.byKey(const ValueKey('detailCommentsTitle'))).dy;
    expect(rowY, greaterThan(stripY));
    expect(sendY, greaterThan(rowY), reason: '按钮在礼物区卡片底部');
    expect(commentsY, greaterThan(sendY));
  });

  testWidgets('点宠物行 → 该宠物礼物页', (tester) async {
    await _pump(tester, _detail());
    await tester.tap(_row(42));
    await tester.pumpAndSettle();
    expect(find.text('gift:42'), findsOneWidget);
  });

  testWidgets('A5：0 礼物 → 「Belum ada hadiah. Jadi yang pertama!」，无图标、无「›」，仍可点', (tester) async {
    await _pump(tester, _detail(pets: const [_empty]));
    expect(find.text('Belum ada hadiah. Jadi yang pertama!'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPetRowIcons_43')), findsNothing);
    expect(find.descendant(of: _row(43), matching: find.byIcon(Icons.chevron_right_rounded)), findsNothing);
    await tester.tap(_row(43));
    await tester.pumpAndSettle();
    expect(find.text('gift:43'), findsOneWidget);
  });

  testWidgets('+N：kindCount 等于预览数 → 不显示「+0」', (tester) async {
    await _pump(
        tester,
        _detail(pets: const [
          GiftPetSummary(petId: 42, name: 'Mochi', giftCount: 24, kindCount: 3, wallPreview: _wall),
        ]));
    expect(find.byKey(const ValueKey('giftPetRowMore_42')), findsNothing);
  });

  testWidgets('A6：自己的 Diary → 礼物区照常、点行进礼物页，无「Kirim Hadiah」', (tester) async {
    await _pump(tester, _detail(isAuthor: true));
    expect(_row(42), findsOneWidget);
    expect(_send(), findsNothing);
    await tester.tap(_row(42));
    await tester.pumpAndSettle();
    expect(find.text('gift:42'), findsOneWidget);
  });

  testWidgets('giftPets 为空（Moment / Tips / 无关联宠物）→ 不渲染礼物区', (tester) async {
    await _pump(tester, _detail(pets: const []));
    expect(find.text('HADIAH'), findsNothing);
    expect(_send(), findsNothing);
  });

  // ===== V1.4.0 push-collect Story 3.1 · AC5.1 / AC5.3 / AC5.6：③ 礼物区 × ④ 底栏收藏 =====
  testWidgets('3.1 AC5：底栏 点赞 → 分享卡 → 收藏，无送礼按钮；作者 Diary 礼物区（无 Kirim Hadiah）与收藏数 + 锁并存',
      (tester) async {
    await _pump(tester, _detail(isAuthor: true, collectCount: 4));
    final bar = find.byKey(const ValueKey('detailBottomActions'));
    double x(Finder f) => tester.getCenter(f).dx;
    final like = find.descendant(of: bar, matching: find.byKey(const ValueKey('likeButton_5')));
    final share = find.descendant(of: bar, matching: find.byKey(const ValueKey('detailShareCardIcon')));
    final collect = find.descendant(of: bar, matching: find.byKey(const ValueKey('detailCollectButton')));
    expect(like, findsOneWidget);
    expect(share, findsOneWidget);
    expect(collect, findsOneWidget);
    expect(x(like) < x(share) && x(share) < x(collect), isTrue);
    expect(find.descendant(of: bar, matching: find.byIcon(Icons.redeem)), findsNothing);
    expect(find.descendant(of: bar, matching: find.byIcon(Icons.redeem_rounded)), findsNothing);
    expect(find.descendant(of: bar, matching: find.byKey(const ValueKey('giftDetailSendButton'))), findsNothing);
    // 作者态：数量 + 锁（C4）；礼物区照常在正文区、无「Kirim Hadiah」（③ A6）。
    expect(find.descendant(of: bar, matching: find.byKey(const ValueKey('detailCollectCount'))), findsOneWidget);
    expect(find.descendant(of: bar, matching: find.byKey(const ValueKey('detailCollectLock'))), findsOneWidget);
    expect(_row(42), findsOneWidget);
    expect(_send(), findsNothing);
  });

  testWidgets('3.1 AC5：非作者 → 礼物区有 Kirim Hadiah，底栏收藏无数量', (tester) async {
    await _pump(tester, _detail(collectCount: 4));
    final bar = find.byKey(const ValueKey('detailBottomActions'));
    expect(find.descendant(of: bar, matching: find.byKey(const ValueKey('detailCollectButton'))), findsOneWidget);
    expect(find.descendant(of: bar, matching: find.byKey(const ValueKey('detailCollectCount'))), findsNothing);
    expect(_send(), findsOneWidget);
  });
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

class _Repo implements DetailRepository {
  _Repo(this.d);

  final ContentDetail d;

  @override
  Future<ContentDetail> getDetail(int id) async => d;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
