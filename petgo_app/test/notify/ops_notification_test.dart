import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：通知中心显示运营通知并按配置跳转（V1.3.4 Story 6.3 · FR-138 · AD-17）。
///
/// 跳转规则本身由 Story 5.2 的 `test/ops/open_ops_target_test.dart` 覆盖；
/// 这里只验「通知中心把 `item.opsTarget` 交给了 `openOpsTarget`」与运营通知的渲染 / 埋点例外。
class _FakeNotificationRepo extends NotificationRepository {
  _FakeNotificationRepo(this.items) : super(dio: Dio());

  final List<NotificationItem> items;
  final List<String> marked = <String>[];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async => marked.add(token);
}

const _title = 'Promo akhir pekan 🎉';
const _body = 'Diskon 20% untuk makanan kucing pilihan, cuma sampai Minggu.';

NotificationItem _ops({
  String? title = _title,
  String? body = _body,
  OpsTarget? target,
  String? targetRef = 'camp1',
  String token = 'tok-ops',
  bool read = false,
}) =>
    NotificationItem(
      type: 'OPS_ANNOUNCEMENT',
      title: title,
      body: body,
      deepLinkType: 'OPS_ANNOUNCEMENT',
      deepLinkToken: token,
      targetRef: targetRef,
      read: read,
      createdAt: DateTime.now(),
      opsTarget: target,
    );

Future<_FakeNotificationRepo> _pump(WidgetTester tester, List<NotificationItem> items,
    {Locale locale = const Locale('id')}) async {
  final repo = _FakeNotificationRepo(items);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      notificationRepositoryProvider.overrideWithValue(repo),
    ],
    child: MaterialApp.router(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      // 🔴 openOpsTarget 用 rootNavigatorKey 导航（同 5.2 测试）。
      routerConfig: GoRouter(
        navigatorKey: rootNavigatorKey,
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(
            path: '/shop/products/:token',
            builder: (_, s) => Scaffold(body: Text('product:${s.pathParameters['token']}')),
          ),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

Finder _tile(String token) => find.byKey(ValueKey('notification_$token'));

Material _tileMaterial(WidgetTester tester, String token) => tester.widget<Material>(
    find.ancestor(of: _tile(token), matching: find.byType(Material)).first);

/// 未读紫点：行内 8×8 的 mint 圆。
Finder _unreadDot(String token) => find.descendant(
      of: _tile(token),
      matching: find.byWidgetPredicate((w) =>
          w is Container &&
          w.decoration is BoxDecoration &&
          (w.decoration as BoxDecoration).shape == BoxShape.circle &&
          (w.decoration as BoxDecoration).color == AppColors.mint),
    );

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC1 · NotificationItem.fromJson 解析 opsTarget（交给 5.2 的 OpsTarget.fromJson）', () {
    test('带 PRODUCT 目标 → type/value 解析正确', () {
      final item = NotificationItem.fromJson({
        'type': 'OPS_ANNOUNCEMENT',
        'read': false,
        'opsTarget': {'type': 'PRODUCT', 'value': 'abc'},
      });
      expect(item.opsTarget, isNotNull);
      expect(item.opsTarget!.type, OpsTargetType.product);
      expect(item.opsTarget!.value, 'abc');
    });

    test('无 opsTarget 键（非运营通知）→ null', () {
      final item = NotificationItem.fromJson({'type': 'CONTENT_LIKED', 'read': true});
      expect(item.opsTarget, isNull);
    });

    test('opsTarget 为空 Map / 未知 type → OpsTarget.none，不抛', () {
      final empty = NotificationItem.fromJson({'type': 'OPS_ANNOUNCEMENT', 'opsTarget': {}});
      expect(empty.opsTarget!.type, OpsTarget.none.type);
      final unknown = NotificationItem.fromJson({
        'type': 'OPS_ANNOUNCEMENT',
        'opsTarget': {'type': 'SOMETHING_NEW', 'value': 'x'},
      });
      expect(unknown.opsTarget, OpsTarget.none);
    });

    test('opsTarget 非 Map → null', () {
      final item = NotificationItem.fromJson({'type': 'OPS_ANNOUNCEMENT', 'opsTarget': 'PRODUCT'});
      expect(item.opsTarget, isNull);
    });

    test('NONE 只有 type → OpsTarget.none', () {
      final item = NotificationItem.fromJson({
        'type': 'OPS_ANNOUNCEMENT',
        'opsTarget': {'type': 'NONE'},
      });
      expect(item.opsTarget, OpsTarget.none);
    });
  });

  group('AC2 · 渲染运营原文', () {
    for (final locale in const [Locale('en'), Locale('id')]) {
      testWidgets('${locale.languageCode} 下原样显示后端 title / body（不随 App 语言换）', (tester) async {
        await _pump(tester, [_ops()], locale: locale);
        expect(find.text(_title), findsOneWidget);
        expect(find.text(_body), findsOneWidget);
        final l10n = lookupAppLocalizations(locale);
        expect(find.text(l10n.notifyTypeSystem), findsNothing);
        expect(find.text(l10n.notifyBodySystem), findsNothing);
      });
    }

    testWidgets('图标：campaign_rounded，图标色 mint、图标块底 cream2', (tester) async {
      await _pump(tester, [_ops()]);
      final iconFinder = find.byIcon(Icons.campaign_rounded);
      expect(iconFinder, findsOneWidget);
      expect(tester.widget<Icon>(iconFinder).color, AppColors.mint);
      final block = tester.widget<Container>(
          find.ancestor(of: iconFinder, matching: find.byType(Container)).first);
      expect((block.decoration as BoxDecoration).color, AppColors.cream2);
    });

    testWidgets('未读：cream2 底 + 紫点；已读：card 白底、无紫点', (tester) async {
      await _pump(tester, [
        _ops(token: 'tok-unread'),
        _ops(token: 'tok-read', read: true, title: 'Sudah dibaca', body: 'Isi lama'),
      ]);
      expect(_tileMaterial(tester, 'tok-unread').color, AppColors.cream2);
      expect(_unreadDot('tok-unread'), findsOneWidget);
      expect(_tileMaterial(tester, 'tok-read').color, AppColors.card);
      expect(_unreadDot('tok-read'), findsNothing);
    });

    testWidgets('兜底：title null / body 全空白 → 中性「系统通知」文案，不显示空白', (tester) async {
      await _pump(tester, [_ops(title: null, body: '  ')]);
      final l10n = lookupAppLocalizations(const Locale('id'));
      expect(find.text(l10n.notifyTypeSystem), findsOneWidget);
      expect(find.text(l10n.notifyBodySystem), findsOneWidget);
    });

    testWidgets('回归：同列表里的 CONTENT_LIKED 仍按本地化显示，不渲染后端串', (tester) async {
      await _pump(tester, [
        _ops(),
        NotificationItem(
          type: 'CONTENT_LIKED',
          title: '收到点赞',
          body: '有人赞了你',
          deepLinkType: 'CONTENT_LIKED',
          deepLinkToken: 'tok-like',
          targetRef: '42',
          read: false,
          createdAt: DateTime.now(),
        ),
      ]);
      final l10n = lookupAppLocalizations(const Locale('id'));
      expect(find.text(_title), findsOneWidget);
      expect(find.text(l10n.notifyTypeContentLiked), findsOneWidget);
      expect(find.text('收到点赞'), findsNothing);
      expect(find.text('有人赞了你'), findsNothing);
      expect(find.byIcon(Icons.favorite_rounded), findsOneWidget);
    });
  });

  group('AC3 · 在通知中心点击运营通知', () {
    testWidgets('opsTarget = PRODUCT → 标已读并经 openOpsTarget 到达商品页', (tester) async {
      final repo = await _pump(tester, [_ops(target: const OpsTarget(OpsTargetType.product, 'P_tok-1'))]);
      await tester.tap(_tile('tok-ops'));
      await tester.pumpAndSettle();
      expect(repo.marked, ['tok-ops']);
      expect(find.text('product:P_tok-1'), findsOneWidget);
      expect(find.byType(NotificationCenterPage), findsNothing);
    });

    for (final entry in <String, OpsTarget?>{'NONE': OpsTarget.none, 'null': null}.entries) {
      testWidgets('opsTarget = ${entry.key} → 只标已读、停在通知中心、行变已读样式', (tester) async {
        final repo = await _pump(tester, [_ops(target: entry.value)]);
        await tester.tap(_tile('tok-ops'));
        await tester.pumpAndSettle();
        expect(repo.marked, ['tok-ops']);
        expect(find.byType(NotificationCenterPage), findsOneWidget);
        expect(_tileMaterial(tester, 'tok-ops').color, AppColors.card);
        expect(_unreadDot('tok-ops'), findsNothing);
      });
    }

    testWidgets('埋点：ops_notification_opened{campaign_id, source: notification_center} + 既有 item_tapped',
        (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      await _pump(tester, [_ops(target: OpsTarget.none)]);
      await tester.tap(_tile('tok-ops'));
      await tester.pumpAndSettle();
      final opened = events.where((e) => e.$1 == 'ops_notification_opened').toList();
      expect(opened, hasLength(1));
      expect(opened.single.$2, {'campaign_id': 'camp1', 'source': 'notification_center'});
      final tapped = events.where((e) => e.$1 == 'app_notification_item_tapped').toList();
      expect(tapped, hasLength(1));
      expect(tapped.single.$2, {'notif_type': 'ops_announcement'});
    });

    testWidgets('targetRef 为空 → 不报 ops_notification_opened（仍报 item_tapped）', (tester) async {
      final events = <String>[];
      Analytics.debugCaptureSink = (e, _) => events.add(e);
      await _pump(tester, [_ops(targetRef: null)]);
      await tester.tap(_tile('tok-ops'));
      await tester.pumpAndSettle();
      expect(events, isNot(contains('ops_notification_opened')));
      expect(events, contains('app_notification_item_tapped'));
    });
  });

  group('AC4 · 从系统推送打开', () {
    test('OPS_ANNOUNCEMENT 推送 → ops_notification_opened{campaign_id, source: push}', () {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      reportOpsNotificationOpenedFromPush(
          const PushPayload(type: 'OPS_ANNOUNCEMENT', token: 't', targetRef: 'camp1'));
      expect(events, hasLength(1));
      expect(events.single.$1, 'ops_notification_opened');
      expect(events.single.$2, {'campaign_id': 'camp1', 'source': 'push'});
    });

    test('其它类型 / targetRef 缺失 → 不报', () {
      final events = <String>[];
      Analytics.debugCaptureSink = (e, _) => events.add(e);
      reportOpsNotificationOpenedFromPush(
          const PushPayload(type: 'CONTENT_LIKED', token: 't', targetRef: 'camp1'));
      reportOpsNotificationOpenedFromPush(const PushPayload(type: 'OPS_ANNOUNCEMENT', token: 't'));
      reportOpsNotificationOpenedFromPush(
          const PushPayload(type: 'OPS_ANNOUNCEMENT', token: 't', targetRef: ''));
      expect(events, isEmpty);
    });

    test('落点：推送不直达，OPS_ANNOUNCEMENT 落通知中心（2026-08-07 规则）', () {
      final deepLink = DeepLinkRoutes.pushPayloadToLocation('OPS_ANNOUNCEMENT', 'camp1');
      expect(deepLink, DeepLinkRoutes.notificationsCenter);
      expect(pushClickLocation(hasToken: true, deepLink: deepLink), DeepLinkRoutes.notificationsCenter);
    });
  });
}
