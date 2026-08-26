import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 8.2 触发点 4：打开通知中心时的引导条。
///
/// 这个触发点的位置有讲究 —— 用户**主动**打开通知中心，说明他此刻在意"有没有人找我"，
/// 而如果系统通知是关着的，这一页里的东西他从来不会被及时告知。
/// 所以这里是四个触发点里语境最贴合的一个。
class _FakeRepo extends NotificationRepository {
  _FakeRepo() : super(dio: Dio());

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      const NotificationPage(items: <NotificationItem>[], hasMore: false);

  @override
  Future<int> unreadCount() async => 0;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() {
    Analytics.debugCaptureSink = null;
    PushPermissionPrompt.phonePromptHook = null;
  });

  /// [openId] 相当于「第几次打开通知中心」。
  /// ⚠️ 必须给页面一个**不同的 key** —— 否则第二次 `pumpWidget` 结构相同，
  ///    Flutter 会**复用 State**、`initState` 不再跑，引导条还是上一次那个，
  ///    这条用例就变成"测同一个活着的页面"，压根没在测「第二次打开」。
  Future<List<(String, Map<String, Object>?)>> pump(
    WidgetTester tester, {
    required bool granted,
    AppPrefs? prefs,
    int openId = 1,
  }) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        notificationRepositoryProvider.overrideWithValue(_FakeRepo()),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: NotificationCenterPage(
          key: ValueKey('open-$openId'),
          prefsForTest: prefs,
          isNotificationGrantedForTest: () async => granted,
          openSettingsForTest: () async => true,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return seen;
  }

  testWidgets('通知关闭 → 顶部出现引导条，并上报曝光（trigger_point=notification_center）',
      (tester) async {
    final seen = await pump(tester, granted: false);

    expect(find.byKey(const ValueKey('pushCenterBanner')), findsOneWidget);
    final shown = seen.where((e) => e.$1 == 'push_permission_prompt_shown').toList();
    expect(shown, hasLength(1));
    expect(shown.first.$2?['trigger_point'], 'notification_center');
    expect(shown.first.$2?['prompt_type'], 'in_app_guide');
  });

  /// 🛡 前置闸门（AD-14 Rule 5）：已经开着通知的人不该被问。
  testWidgets('通知已开 → 不出现引导条、不上报', (tester) async {
    final seen = await pump(tester, granted: true);

    expect(find.byKey(const ValueKey('pushCenterBanner')), findsNothing);
    expect(seen.where((e) => e.$1 == 'push_permission_prompt_shown'), isEmpty);
  });

  /// 🛡 各一次：这个触发点用掉之后，再打开通知中心不再出现。
  testWidgets('第二次打开不再出现引导条', (tester) async {
    final prefs = await AppPrefs.create();

    await pump(tester, granted: false, prefs: prefs);
    expect(find.byKey(const ValueKey('pushCenterBanner')), findsOneWidget);

    // 重建一次页面 = 再次打开通知中心（换 key 强制新的 State）
    final seen = await pump(tester, granted: false, prefs: prefs, openId: 2);
    expect(find.byKey(const ValueKey('pushCenterBanner')), findsNothing);
    expect(seen.where((e) => e.$1 == 'push_permission_prompt_shown'), isEmpty);
  });

  testWidgets('点引导条 → 跳系统设置并上报 settings_opened', (tester) async {
    final seen = await pump(tester, granted: false);

    await tester.tap(find.byKey(const ValueKey('pushCenterBannerAction')));
    await tester.pumpAndSettle();

    final responded = seen.where((e) => e.$1 == 'push_permission_responded').toList();
    expect(responded, hasLength(1));
    expect(responded.first.$2?['trigger_point'], 'notification_center');
    expect(responded.first.$2?['result'], 'settings_opened');
  });

  /// 🛡 AD-14 Rule 6：任何触发点都不得上报「原生弹窗」形态。
  testWidgets('绝不上报 native_dialog', (tester) async {
    final seen = await pump(tester, granted: false);
    await tester.tap(find.byKey(const ValueKey('pushCenterBannerAction')));
    await tester.pumpAndSettle();

    for (final e in seen.where((e) => e.$1.startsWith('push_permission_'))) {
      expect(e.$2?['prompt_type'], 'in_app_guide');
    }
  });
}
