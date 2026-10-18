import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/me/presentation/settings_page.dart';
import 'package:tailtopia/features/notify/presentation/push_settings_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakePush extends PushService {
  _FakePush(super.ref);

  @override
  Future<void> syncRegistration({required bool isVet}) async {}
}

/// Story 7.1 · F8：二级设置页含语言/退出/注销三项入口（PDP 数据主体权利可达）。
void main() {
  testWidgets('设置页含语言 + 退出登录 + 账号注销入口', (tester) async {
    // 设置页分四组较长，用高视口确保 ListView 全量构建（底部 ZONA BAHAYA 不超出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: SettingsPage(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('meLanguage')), findsOneWidget);
    expect(find.byKey(const ValueKey('meLogout')), findsOneWidget); // 退出登录入口（7.3）
    expect(find.byKey(const ValueKey('meDeleteAccount')), findsOneWidget); // 账号注销入口（PDP 可达）
  });

  // ===== V1.4.0 ④ Story 1.3：「Notifikasi」从开关改为通知设置页入口（UI 稿 A1 / A2）=====

  Future<GoRouter> pumpWithRouter(WidgetTester tester, {required bool granted}) async {
    SharedPreferences.setMockInitialValues({});
    await tester.binding.setSurfaceSize(const Size(440, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final router = GoRouter(initialLocation: '/me/settings', routes: [
      GoRoute(
          path: '/me/settings',
          builder: (c, s) => SettingsPage(isNotificationGrantedForTest: () async => granted)),
      GoRoute(
          path: PushSettingsPage.routePath,
          builder: (c, s) => const Scaffold(body: Text('push-settings-stub'))),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [pushServiceProvider.overrideWith(_FakePush.new)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    return router;
  }

  testWidgets('A1：系统权限开 → 入口行右值「Aktif」、无红点；只剩一个开关（Profil Hewan）', (tester) async {
    await pumpWithRouter(tester, granted: true);
    final entry = find.byKey(const ValueKey('meNotifEntry'));
    expect(find.descendant(of: entry, matching: find.text('Aktif')), findsOneWidget);
    expect(find.byKey(const ValueKey('meNotifOffDot')), findsNothing);
    expect(find.byKey(const ValueKey('meNotifToggle')), findsNothing);
    expect(find.byType(Switch), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetPublicToggle')), findsOneWidget);
  });

  testWidgets('A2：系统权限关 → 红字「Notifikasi sistem nonaktif」+ 红点', (tester) async {
    await pumpWithRouter(tester, granted: false);
    final text = tester.widget<Text>(find.text('Notifikasi sistem nonaktif'));
    expect(text.style?.color, AppColors.popRed);
    expect(find.byKey(const ValueKey('meNotifOffDot')), findsOneWidget);
  });

  testWidgets('点入口行 → 进 /me/settings/notifications，不再弹「去设置」对话框', (tester) async {
    final router = await pumpWithRouter(tester, granted: false);
    await tester.tap(find.byKey(const ValueKey('meNotifEntry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('notifOpenSettingsDialog')), findsNothing);
    expect(router.state.uri.path, PushSettingsPage.routePath);
    expect(find.text('push-settings-stub'), findsOneWidget);
  });

  testWidgets('其余 10 行入口全部还在', (tester) async {
    await pumpWithRouter(tester, granted: true);
    for (final k in [
      'meEditProfile', 'meLanguage', 'mePhoneEntry', 'mePetPublicToggle', 'mePrivacyPolicy',
      'meTermsOfService', 'meBlockedUsers', 'settingsCheckUpdate', 'meLogout', 'meDeleteAccount',
    ]) {
      expect(find.byKey(ValueKey(k)), findsOneWidget, reason: k);
    }
  });
}
