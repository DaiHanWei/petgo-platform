import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：被 @ 的通知在客户端这一半（V1.3.0 batch-b1 Story 3.4 · AC3/AC4/AC6）。
///
/// <h3>🔴 一个 type、两种落点、两套文案，全靠 targetRef 的 variant 前缀</h3>
/// 后端只下发 `type + targetRef`（`POST:{postId}` / `COMMENT:{postId}`），
/// 条目文案与深链落点**都由客户端按这个前缀选**。
/// 两处分叉的表现是「正文里说在评论里提到你，点进去却没锚到评论区」——
/// 所以下面两组用例用的是同一份 targetRef 口径。
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

NotificationItem _mention(String targetRef) => NotificationItem(
      type: 'CONTENT_MENTIONED',
      targetRef: targetRef,
      deepLinkType: 'CONTENT_MENTIONED',
      deepLinkToken: 'tok-$targetRef',
      read: false,
      createdAt: DateTime.now(),
    );

Future<void> _pump(WidgetTester tester, List<NotificationItem> items,
    {Locale locale = const Locale('id')}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items)),
    ],
    child: MaterialApp.router(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(path: '/content/:id', builder: (_, _) => const SizedBox()),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC4 深链落点', () {
    test('POST: → 内容详情页', () {
      expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_MENTIONED', 'POST:123'),
          '/content/123');
    });

    test('COMMENT: → 内容详情页 + 锚定评论区（复用既有 ?focus=comments）', () {
      expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_MENTIONED', 'COMMENT:123'),
          '/content/123?focus=comments');
    });

    test('🔴 落点不依赖 commentAnchor 参数（它只为 CONTENT_COMMENTED 而设）', () {
      // 通知中心那处按 `deepLinkType == 'CONTENT_COMMENTED'` 传 commentAnchor，
      // @ 提及不在其列 —— 锚点判定必须自己从 targetRef 里读出来，否则评论提及永远不锚。
      expect(
          DeepLinkRoutes.pushPayloadToLocation('CONTENT_MENTIONED', 'COMMENT:123',
              commentAnchor: false),
          '/content/123?focus=comments');
    });

    test('🛡 认不出前缀 / 缺 targetRef / id 不是数字 → 落通知中心，绝不拼非法路由', () {
      for (final ref in <String?>[null, '', '123', 'POST:', 'POST:abc', 'WHAT:1']) {
        expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_MENTIONED', ref),
            DeepLinkRoutes.notificationsCenter,
            reason: 'targetRef=$ref');
      }
    });
  });

  group('AC3 条目文案（App 按 type 渲染，不读后端下发的串）', () {
    testWidgets('帖子提及与评论提及是**两句不同的话**', (tester) async {
      await _pump(tester, [_mention('POST:1'), _mention('COMMENT:2')]);
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));

      expect(l10n.notifyBodyContentMentionedPost,
          isNot(l10n.notifyBodyContentMentionedComment));
      expect(find.text(l10n.notifyBodyContentMentionedPost), findsOneWidget);
      expect(find.text(l10n.notifyBodyContentMentionedComment), findsOneWidget);
      // 标题两种共用一句（AC3 只要求正文区分）。
      expect(find.text(l10n.notifyTypeContentMentioned), findsNWidgets(2));
    });

    testWidgets('🔴 没有落到「系统通知」那个中性兜底', (tester) async {
      // 只在后端加类型而 App 不改，条目会渲染成中性的「系统通知」——
      // 那等于 AC3 没做（同 MILESTONE_SM_NODE 当年踩的坑）。
      await _pump(tester, [_mention('POST:1')]);
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.text(l10n.notifyTypeSystem), findsNothing);
      expect(find.text(l10n.notifyBodySystem), findsNothing);
    });

    testWidgets('🔴 界面上一个中文字都没有（AC3）', (tester) async {
      await _pump(tester, [_mention('POST:1'), _mention('COMMENT:2')]);
      for (final w in tester.widgetList<Text>(find.byType(Text))) {
        final text = w.data ?? '';
        expect(text, isNot(matches(RegExp(r'[一-龥]'))), reason: text);
      }
    });

    testWidgets('en 与 id 两套都渲染得出来（双表，不靠后端下发）', (tester) async {
      for (final locale in const [Locale('en'), Locale('id')]) {
        await _pump(tester, [_mention('COMMENT:2')], locale: locale);
        final l10n = await AppLocalizations.delegate.load(locale);
        expect(find.text(l10n.notifyBodyContentMentionedComment), findsOneWidget,
            reason: locale.languageCode);
      }
    });

    testWidgets('targetRef 前缀认不出来时按帖子说，不崩不空白', (tester) async {
      await _pump(tester, [_mention('WHAT:9')]);
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(tester.takeException(), isNull);
      expect(find.text(l10n.notifyBodyContentMentionedPost), findsOneWidget);
    });
  });

  group('AC6 样式复用', () {
    testWidgets('图标是 @ 符号（复用既有图标块，不新画一套）', (tester) async {
      await _pump(tester, [_mention('POST:1')]);
      expect(find.byIcon(Icons.alternate_email_rounded), findsOneWidget);
    });
  });

  group('AC4 点击跳转', () {
    testWidgets('点评论提及 → 带评论锚点的详情页', (tester) async {
      await _pump(tester, [_mention('COMMENT:77')]);
      await tester.tap(find.text(
          (await AppLocalizations.delegate.load(const Locale('id')))
              .notifyBodyContentMentionedComment));
      await tester.pumpAndSettle();
      // 路由已切走：通知中心的条目不再在树上。
      expect(find.byType(NotificationCenterPage), findsNothing);
    });
  });
}
