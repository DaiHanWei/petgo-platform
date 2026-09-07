import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.6 Story 6.1：S/M 里程碑通知在通知中心的呈现与埋点。
///
/// <p>守的核心是一条：**必须看得出是哪一条里程碑**。
/// 只在后端加类型、App 不改的话，它会落到「未知类型兜底」渲染成中性的「系统通知」——
/// 那正是 AC 明令禁止的"看不出发生了什么"的记录。
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

NotificationItem _item({
  required String type,
  String? targetRef,
  String? deepLinkType,
}) =>
    NotificationItem(
      type: type,
      targetRef: targetRef,
      deepLinkType: deepLinkType ?? type,
      deepLinkToken: 'tok-$type-$targetRef',
      read: false,
      createdAt: DateTime.now(),
    );

Future<void> _pump(
  WidgetTester tester,
  List<NotificationItem> items, {
  PetProfile? pet,
}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items)),
      if (pet != null) petProfileProvider.overrideWith((ref) async => pet),
    ],
    // ⚠️ 点通知会走真实跳转，所以要给一个路由器 —— 否则埋点虽已上报，
    // 紧接着的导航会抛「No GoRouter found」把用例带崩。
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        initialLocation: '/notifications',
        routes: [
          GoRoute(
            path: '/notifications',
            builder: (_, _) => const NotificationCenterPage(),
          ),
          GoRoute(path: '/profile/milestones', builder: (_, _) => const SizedBox()),
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

  group('AC4 必须看得出是哪一条里程碑', () {
    /// 🔴 有宠物名 → 用**庆祝文案**（AC 要求复用它），`{name}` 被替换成真实昵称。
    testWidgets('显示该里程碑的庆祝文案，不是中性兜底', (tester) async {
      await _pump(
        tester,
        [_item(type: 'MILESTONE_SM_NODE', targetRef: 'C-S1', deepLinkType: 'MILESTONE_NODE')],
        pet: const PetProfile(id: 1, name: 'Mochi', cardToken: 'tok', petType: 'CAT'),
      );

      // C-S1 的印尼语庆祝标题是「Profil {name} sudah lengkap! 🎉」
      expect(find.textContaining('Mochi'), findsWidgets);
      expect(find.textContaining('Profil'), findsWidgets);
    });

    /// 🔴 拿不到宠物名 → 退化成里程碑的**简短标题**，仍然具体。
    ///
    /// 关键是**绝不能落到"系统通知"那个中性兜底** —— 那等于这条 AC 没做。
    testWidgets('没有宠物名时退化成简短标题，仍然具体', (tester) async {
      await _pump(tester,
          [_item(type: 'MILESTONE_SM_NODE', targetRef: 'C-S14', deepLinkType: 'MILESTONE_NODE')]);

      // C-S14 = 「第一次被评论」，印尼语简短标题 Komentar pertama
      expect(find.textContaining('Komentar pertama'), findsOneWidget);
    });

    /// 未知编码不崩、也不显示中文。
    testWidgets('未知编码兜底不崩且不显示中文', (tester) async {
      await _pump(tester,
          [_item(type: 'MILESTONE_SM_NODE', targetRef: 'X-Z9', deepLinkType: 'MILESTONE_NODE')]);

      expect(tester.takeException(), isNull);
      expect(find.textContaining('里程碑'), findsNothing);
    });
  });

  group('AC6 埋点', () {
    /// 🛡 **所有类型都报** —— 只报里程碑就没法横向对比点击率，
    /// 而"S/M 通知到底是留痕还是召回"这个判断恰恰要靠与点赞/评论类的对比得出。
    testWidgets('点击 S/M 里程碑通知上报类型与级别', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _pump(tester,
          [_item(type: 'MILESTONE_SM_NODE', targetRef: 'C-M8', deepLinkType: 'MILESTONE_NODE')]);
      await tester.tap(find.textContaining('Komentar').evaluate().isNotEmpty
          ? find.textContaining('Komentar')
          : find.byType(InkWell).first);
      await tester.pump();

      final tap = seen.where((e) => e.$1 == 'app_notification_item_tapped').toList();
      expect(tap, hasLength(1));
      expect(tap.first.$2?['notif_type'], 'milestone_sm');
      expect(tap.first.$2?['level'], 'M');
    });

    testWidgets('点击点赞类通知也上报（横向对比的分母）', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _pump(tester, [_item(type: 'CONTENT_LIKED', targetRef: '42')]);
      await tester.tap(find.byType(InkWell).first);
      await tester.pump();

      final tap = seen.where((e) => e.$1 == 'app_notification_item_tapped').toList();
      expect(tap, hasLength(1));
      expect(tap.first.$2?['notif_type'], 'like');
      expect(tap.first.$2?.containsKey('level'), isFalse, reason: '非里程碑不带级别');
    });
  });
}
