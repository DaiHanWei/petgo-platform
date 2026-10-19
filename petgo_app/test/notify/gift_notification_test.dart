import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：通知中心 G1 ~ G3（V1.4.0 gift Story 2.2 · AC5 / AC6.3）。
class _FakeNotificationRepo extends NotificationRepository {
  _FakeNotificationRepo(this.items) : super(dio: Dio());

  final List<NotificationItem> items;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async {}
}

NotificationItem _received({String? actorName = 'Andi', String? title, String token = 'tok-g'}) =>
    NotificationItem(
      type: 'PET_GIFT_RECEIVED',
      title: title ?? 'Andi dan 2 lainnya mengirim hadiah untuk Mochi',
      deepLinkType: 'PET_GIFT_RECEIVED',
      deepLinkToken: token,
      targetRef: '12',
      read: false,
      createdAt: DateTime.now(),
      actorName: actorName,
      actorCount: actorName == null ? 0 : 3,
    );

NotificationItem _refund({String? title, String? body, String token = 'tok-r'}) => NotificationItem(
      type: 'GIFT_UNDELIVERED_REFUND',
      title: title,
      body: body,
      deepLinkType: 'GIFT_UNDELIVERED_REFUND',
      deepLinkToken: token,
      targetRef: 'ordTok',
      read: false,
      createdAt: DateTime.now(),
    );

Future<void> _pump(WidgetTester tester, List<NotificationItem> items, {List<PetListItem>? myPets}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items)),
      if (myPets != null) petListProvider.overrideWith((ref) async => myPets),
    ],
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        navigatorKey: rootNavigatorKey,
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('DIARY TAB'))),
          GoRoute(
            path: '/pets/:petId/gifts',
            builder: (_, s) => Scaffold(
                appBar: AppBar(),
                body: Text('gift:${s.pathParameters['petId']}:${s.uri.queryParameters['wall']}')),
          ),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

Finder _tile(String token) => find.byKey(ValueKey('notification_$token'));

Icon _icon(WidgetTester tester, String token) =>
    tester.widget<Icon>(find.descendant(of: _tile(token), matching: find.byType(Icon)).first);

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('PET_GIFT_RECEIVED 是「后端文案条目」类型（有 actorName 才读后端串）', () {
    expect(NotificationItem.serverCopyTypes, contains('PET_GIFT_RECEIVED'));
    expect(_received().hasServerCopy, isTrue);
    expect(_received(actorName: null).hasServerCopy, isFalse);
  });

  testWidgets('G1 收礼：金色礼物图标；后端整句标题、只有人名加粗', (tester) async {
    await _pump(tester, [_received()]);
    final icon = _icon(tester, 'tok-g');
    expect(icon.icon, Icons.redeem_rounded);
    expect(icon.color, AppColors.gold);

    final title = tester.widget<Text>(find.byKey(const ValueKey('notificationTitle_tok-g')));
    final spans = (title.textSpan! as TextSpan).children!.cast<TextSpan>();
    expect(spans.map((s) => s.text).join(), 'Andi dan 2 lainnya mengirim hadiah untuk Mochi');
    final bold = spans.where((s) => s.style?.fontWeight == FontWeight.w700).map((s) => s.text).toList();
    expect(bold, ['Andi']);
  });

  testWidgets('G1 无 actorName（送礼人已注销 / 封号）→ 本地兜底文案', (tester) async {
    await _pump(tester, [_received(actorName: null, title: '有人给你的宠物送了礼物')]);
    expect(find.text('Hadiah baru'), findsOneWidget);
    expect(find.text('Ada yang mengirim hadiah untuk hewanmu.'), findsOneWidget);
    expect(find.text('有人给你的宠物送了礼物'), findsNothing);
  });

  testWidgets('G3 退回：紫色钱包图标（不用红色）；后端 title / body 优先', (tester) async {
    await _pump(tester, [
      _refund(
          title: 'Hadiah tidak terkirim',
          body: 'Hadiahmu untuk Mochi tidak terkirim (hewan ini sudah tidak ada). '
              '25.000 PawCoin sudah dikembalikan ke saldomu.'),
    ]);
    final icon = _icon(tester, 'tok-r');
    expect(icon.icon, Icons.account_balance_wallet_rounded);
    expect(icon.color, AppColors.mint);
    expect(icon.color, isNot(AppColors.coral));
    expect(find.text('Hadiah tidak terkirim'), findsOneWidget);
    expect(find.textContaining('25.000 PawCoin sudah dikembalikan'), findsOneWidget);
  });

  testWidgets('G3 后端 title / body 为空 → 分别回落本地兜底', (tester) async {
    await _pump(tester, [_refund(title: '  ', body: null)]);
    expect(find.text('Hadiah tidak terkirim'), findsOneWidget);
    expect(find.text('Hadiahmu tidak terkirim. PawCoin sudah dikembalikan ke saldomu.'), findsOneWidget);
  });

  testWidgets('点收礼条目：先落 Diary Tab（/profile），下一帧叠礼物页且墙展开；返回回到 Diary Tab', (tester) async {
    await _pump(tester, [_received()]);
    await tester.tap(_tile('tok-g'));
    await tester.pumpAndSettle();
    expect(find.text('gift:12:expanded'), findsOneWidget);

    final ctx = tester.element(find.text('gift:12:expanded'));
    GoRouter.of(ctx).pop();
    await tester.pumpAndSettle();
    expect(find.text('DIARY TAB'), findsOneWidget);
  });

  // gift Story 4.1 · AC6.4：多宠物主人点「B 收到礼物」→ 先把 B 切为当前宠物，再进 Diary Tab 叠 B 的礼物页。
  testWidgets('多宠物：被送礼的是自己的宠物 → 先切为当前宠物再进 Diary Tab', (tester) async {
    await _pump(tester, [_received()], myPets: const [
      PetListItem(id: 11, name: 'Aaa', isDefault: true),
      PetListItem(id: 12, name: 'Mochi'),
    ]);
    final c = ProviderScope.containerOf(tester.element(find.byType(NotificationCenterPage)));
    expect(c.read(currentPetIdProvider), isNull);
    await tester.tap(_tile('tok-g'));
    await tester.pumpAndSettle();
    expect(c.read(currentPetIdProvider), 12);
    expect(find.text('gift:12:expanded'), findsOneWidget);
  });

  testWidgets('多宠物：宠物不在自己的列表（已删 / 已转手）→ 不切，照常进礼物页', (tester) async {
    await _pump(tester, [_received()], myPets: const [PetListItem(id: 11, name: 'Aaa', isDefault: true)]);
    final c = ProviderScope.containerOf(tester.element(find.byType(NotificationCenterPage)));
    await tester.tap(_tile('tok-g'));
    await tester.pumpAndSettle();
    expect(c.read(currentPetIdProvider), isNull);
    expect(find.text('gift:12:expanded'), findsOneWidget);
  });
}
