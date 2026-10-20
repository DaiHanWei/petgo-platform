import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/pet_slot/presentation/add_pet_flow.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 2.4 · AC6（K1）：名额到期两类通知读后端 title / body（同 OPS_ANNOUNCEMENT，
/// 不按 App 语言再选）、空串回退中性兜底、点击直达名额管理页、埋点 notif_type。
class _Repo extends NotificationRepository {
  _Repo(this.items) : super(dio: Dio());

  final List<NotificationItem> items;
  final List<String> marked = [];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async => marked.add(token);
}

NotificationItem _item(String type, {String? title, String? body, String token = 't1'}) => NotificationItem(
      type: type,
      title: title,
      body: body,
      deepLinkType: type,
      deepLinkToken: token,
      read: false,
      createdAt: DateTime.now(),
    );

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('深链：两类型固定落名额管理页（不依赖 targetRef，在「无 targetRef → 通知中心」短路之前）', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('PET_SLOT_EXPIRED', null), '/me/pet-slots');
    expect(DeepLinkRoutes.pushPayloadToLocation('PET_SLOT_EXPIRING', null), '/me/pet-slots');
    expect(DeepLinkRoutes.pushPayloadToLocation('PET_SLOT_EXPIRING', ''), '/me/pet-slots');
    expect(DeepLinkRoutes.petSlots, kPetSlotsRoute);
  });

  Future<_Repo> pump(WidgetTester tester, List<NotificationItem> items, {Locale locale = const Locale('en')}) async {
    final repo = _Repo(items);
    await tester.pumpWidget(ProviderScope(
      overrides: [notificationRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          navigatorKey: rootNavigatorKey,
          initialLocation: '/notifications',
          routes: [
            GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
            GoRoute(path: kPetSlotsRoute, builder: (_, _) => const Scaffold(body: Text('slots-page'))),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return repo;
  }

  testWidgets('显示后端 title / body（印尼语账号文案在英文 App 下也原样显示），图标沙漏 / 锁钟', (tester) async {
    await pump(tester, [
      _item('PET_SLOT_EXPIRING',
          title: 'Slot sewa segera berakhir', body: 'Slot sewa kamu berakhir 16 Okt.', token: 'a'),
      _item('PET_SLOT_EXPIRED',
          title: 'Slot sewa sudah berakhir', body: 'Boba sekarang hanya-baca.', token: 'b'),
    ]);
    expect(find.text('Slot sewa segera berakhir'), findsOneWidget);
    expect(find.text('Slot sewa kamu berakhir 16 Okt.'), findsOneWidget);
    expect(find.text('Slot sewa sudah berakhir'), findsOneWidget);
    expect(find.text('Boba sekarang hanya-baca.'), findsOneWidget);
    expect(find.byIcon(Icons.hourglass_bottom_rounded), findsOneWidget);
    expect(find.byIcon(Icons.lock_clock_rounded), findsOneWidget);
  });

  testWidgets('后端 title / body 为空 → 中性兜底（不显示空白）', (tester) async {
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    await pump(tester, [_item('PET_SLOT_EXPIRED', title: '  ', body: null)]);
    expect(find.text(l10n.notifyTypeSystem), findsOneWidget);
    expect(find.text(l10n.notifyBodySystem), findsOneWidget);
  });

  testWidgets('点击 → 标已读 + 进名额管理页 + app_notification_item_tapped{notif_type}', (tester) async {
    final repo = await pump(tester, [_item('PET_SLOT_EXPIRING', title: 'T', body: 'B', token: 'x1')]);
    await tester.tap(find.text('T'));
    await tester.pumpAndSettle();
    expect(find.text('slots-page'), findsOneWidget);
    expect(repo.marked, ['x1']);
    expect(events.where((e) => e.$1 == 'app_notification_item_tapped').single.$2,
        {'notif_type': 'pet_slot_expiring'});
  });
}
