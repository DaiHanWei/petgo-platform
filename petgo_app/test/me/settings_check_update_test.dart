import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/me/presentation/settings_page.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';
import 'package:tailtopia/features/notify/domain/app_update_bootstrap.dart';
import 'package:tailtopia/features/notify/domain/app_update_controller.dart';
import 'package:tailtopia/features/notify/presentation/app_update_presenter.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repository：计数 + 可编排返回值 + 可挂起（Completer 卡住在途请求）。
class _FakeRepo extends AppVersionRepository {
  _FakeRepo(this.result) : super(dio: Dio());

  AppVersionInfo? result;
  int calls = 0;
  Completer<void>? gate;

  @override
  Future<AppVersionInfo?> fetch() async {
    calls++;
    final g = gate;
    if (g != null) await g.future;
    return result;
  }
}

AppVersionInfo _info({String latest = '1.3.5', String min = '1.0.0'}) => AppVersionInfo(
      iosLatestVersion: latest,
      androidLatestVersion: latest,
      minSupportedVersion: min,
      iosStoreUrl: 'https://apps.apple.com/app/id1',
      androidStoreUrl: 'https://play.google.com/store/apps/details?id=x',
    );

/// V1.3.4 Story 3.4：设置页「TENTANG APLIKASI · Periksa pembaruan」手动检查更新。
void main() {
  late _FakeRepo repo;
  late List<String> launched;
  late List<MapEntry<String, Map<String, Object>?>> events;
  late DateTime now;
  late ProviderContainer container;
  late GoRouter router;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    PackageInfo.setMockInitialValues(
        appName: 'TailTopia',
        packageName: 'com.tailtopia.app',
        version: '1.3.4',
        buildNumber: '34',
        buildSignature: '');
    debugResetForcedUpdateDialog();
    AppUpdateBootstrap.debugReset();
    repo = _FakeRepo(_info());
    launched = [];
    events = [];
    now = DateTime.utc(2026, 10, 12, 8);
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    AppUpdateBootstrap.debugReset();
  });

  Future<void> pumpSettings(WidgetTester tester,
      {String locale = 'id', bool versionFails = false, double width = 440}) async {
    await tester.binding.setSurfaceSize(Size(width, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    // 强制态弹窗走 rootNavigatorKey + routerProvider，所以用真实 GoRouter 承载设置页。
    router = GoRouter(
      navigatorKey: rootNavigatorKey,
      initialLocation: '/me/settings',
      routes: [
        GoRoute(path: '/me/settings', builder: (c, s) => const SettingsPage()),
        GoRoute(path: '/other', builder: (c, s) => const Scaffold(body: Text('other'))),
      ],
    );
    addTearDown(router.dispose);
    container = ProviderContainer(overrides: [
      routerProvider.overrideWithValue(router),
      appVersionRepositoryProvider.overrideWithValue(repo),
      appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
        now: () => now,
        isIos: false,
        loadCurrentVersion: () async {
          if (versionFails) throw StateError('no package info');
          return '1.3.4';
        },
        loadPrefs: AppPrefs.create,
      )),
      storeLauncherProvider.overrideWithValue((url) async => launched.add(url)),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      // 同生产 app.dart：根 State 的常驻 ref 注册强制弹窗入口（不随页面替换卸载）。
      child: Consumer(builder: (context, ref, _) {
        AppUpdateBootstrap.registerForcedDialog(() => showForcedUpdateDialog(ref));
        return MaterialApp.router(
          routerConfig: router,
          locale: Locale(locale),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        );
      }),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> tapCheck(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('settingsCheckUpdate')));
    await tester.pumpAndSettle();
  }

  /// 等 toast 自动消失，避免测试结束时还挂着 Timer。
  Future<void> drainToast(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  }

  group('AC1 分组与入口行', () {
    testWidgets('TENTANG APLIKASI 在 PRIVASI & KEAMANAN 之下、ZONA BAHAYA 之上；右值 v1.3.4 + 箭头',
        (tester) async {
      // 测试字体（Ahem）每个字形是方块，440 宽下长文案会折行；放宽视口只为比较单行行高。
      await pumpSettings(tester, width: 600);
      final privacy = tester.getTopLeft(find.text('PRIVASI & KEAMANAN')).dy;
      final about = tester.getTopLeft(find.text('TENTANG APLIKASI')).dy;
      final danger = tester.getTopLeft(find.text('ZONA BAHAYA')).dy;
      expect(privacy < about, isTrue);
      expect(about < danger, isTrue);

      final row = find.byKey(const ValueKey('settingsCheckUpdate'));
      expect(find.descendant(of: row, matching: find.text('Periksa pembaruan')), findsOneWidget);
      final version = tester.widget<Text>(find.byKey(const ValueKey('settingsCheckUpdateVersion')));
      expect(version.data, 'v1.3.4');
      expect(version.style?.fontSize, 14);
      expect(version.style?.fontFeatures, contains(const FontFeature.tabularFigures()));
      expect(find.descendant(of: row, matching: find.byIcon(Icons.chevron_right)), findsOneWidget);

      // 底部小字保持不变；入口行高度与既有单行 _navRow（隐私政策行）一致。
      expect(find.text('TailTopia v1.3.4 · Build 34'), findsOneWidget);
      expect(tester.getSize(row).height,
          tester.getSize(find.byKey(const ValueKey('mePrivacyPolicy'))).height);
      expect(repo.calls, 0, reason: '进页面不发检查请求');
    });

    testWidgets('EN：ABOUT THE APP / Check for updates', (tester) async {
      await pumpSettings(tester, locale: 'en');
      expect(find.text('ABOUT THE APP'), findsOneWidget);
      expect(find.text('Check for updates'), findsOneWidget);
    });
  });

  testWidgets('AC2 检查中：转圈替换右值、行不可点，连点只发一次请求；结束后恢复', (tester) async {
    repo.result = _info(latest: '1.3.4'); // upToDate
    repo.gate = Completer<void>();
    await pumpSettings(tester);

    await tester.tap(find.byKey(const ValueKey('settingsCheckUpdate')));
    await tester.pump();
    expect(find.byKey(const ValueKey('settingsCheckUpdateSpinner')), findsOneWidget);
    expect(find.byKey(const ValueKey('settingsCheckUpdateVersion')), findsNothing);
    final spinner = tester.widget<CircularProgressIndicator>(find.descendant(
        of: find.byKey(const ValueKey('settingsCheckUpdateSpinner')),
        matching: find.byType(CircularProgressIndicator)));
    expect(spinner.strokeWidth, 2.5);
    expect(tester.getSize(find.byKey(const ValueKey('settingsCheckUpdateSpinner'))), const Size(18, 18));
    final ink = tester.widget<InkWell>(find.byKey(const ValueKey('settingsCheckUpdate')));
    expect(ink.onTap, isNull);

    await tester.tap(find.byKey(const ValueKey('settingsCheckUpdate')));
    await tester.pump();
    expect(repo.calls, 1);

    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('settingsCheckUpdateSpinner')), findsNothing);
    expect(find.byKey(const ValueKey('settingsCheckUpdateVersion')), findsOneWidget);
    expect(repo.calls, 1);
    await drainToast(tester);
  });

  testWidgets('AC5 已是最新 → toast「Kamu sudah memakai versi terbaru」', (tester) async {
    repo.result = _info(latest: '1.3.4');
    await pumpSettings(tester);
    await tapCheck(tester);
    expect(find.text('Kamu sudah memakai versi terbaru'), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateRecommended')), findsNothing);
    await drainToast(tester);
  });

  testWidgets('AC6 接口失败 → toast「Gagal memeriksa pembaruan. Coba lagi nanti.」', (tester) async {
    repo.result = null;
    await pumpSettings(tester);
    await tapCheck(tester);
    expect(find.text('Gagal memeriksa pembaruan. Coba lagi nanti.'), findsOneWidget);
    await drainToast(tester);
  });

  testWidgets('AC6 取不到包版本 → 失败 toast（EN）', (tester) async {
    await pumpSettings(tester, locale: 'en', versionFails: true);
    await tapCheck(tester);
    expect(find.text("Couldn't check for updates. Try again later."), findsOneWidget);
    await drainToast(tester);
  });

  testWidgets('AC5 EN：You\'re on the latest version', (tester) async {
    repo.result = _info(latest: '1.3.4');
    await pumpSettings(tester, locale: 'en');
    await tapCheck(tester);
    expect(find.text("You're on the latest version"), findsOneWidget);
    await drainToast(tester);
  });

  testWidgets('AC4 判定强制 → 同一强制态弹窗（无「Nanti」）', (tester) async {
    repo.result = _info(latest: '1.4.0', min: '1.4.0');
    await pumpSettings(tester);
    await tapCheck(tester);
    expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateLater')), findsNothing);
    expect(events.where((e) => e.key == 'app_update_prompt_shown').single.value,
        {'decision': 'force', 'surface': 'dialog'});
  });

  testWidgets('🔴 复审：手动检查判定强制后 go() 替换栈卸载设置页，强制弹窗仍重弹且无异常', (tester) async {
    repo.result = _info(latest: '1.4.0', min: '1.4.0');
    await pumpSettings(tester);
    await tapCheck(tester);
    expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);

    router.go('/other');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byType(SettingsPage), findsNothing, reason: '设置页已被替换卸载');
    expect(find.text('other'), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget, reason: '强制弹窗必须重弹');
    expect(events.where((e) => e.key == 'app_update_prompt_shown'), hasLength(1));

    router.go('/me/settings');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
  });

  testWidgets('检查中离开设置页：放行后无 toast / 无弹窗 / 无异常', (tester) async {
    repo.gate = Completer<void>();
    await pumpSettings(tester); // 默认 recommended
    await tester.tap(find.byKey(const ValueKey('settingsCheckUpdate')));
    await tester.pump();
    expect(find.byKey(const ValueKey('settingsCheckUpdateSpinner')), findsOneWidget);

    router.go('/other');
    await tester.pumpAndSettle();
    expect(find.byType(SettingsPage), findsNothing);

    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('appUpdateRecommended')), findsNothing);
    expect(find.text('Kamu sudah memakai versi terbaru'), findsNothing);
    expect(find.text('Gagal memeriksa pembaruan. Coba lagi nanti.'), findsNothing);
    expect(events.where((e) => e.key == 'app_update_prompt_shown'), isEmpty);
    expect(repo.calls, 1);
  });

  group('AC3 推荐态弹窗', () {
    testWidgets('弹出推荐弹窗（Versi 1.3.5 + 主按钮 + Nanti）；点「Nanti」写 72h 冷却', (tester) async {
      await pumpSettings(tester);
      await tapCheck(tester);
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsOneWidget);
      expect(find.text('Versi 1.3.5'), findsOneWidget);
      expect(find.byKey(const ValueKey('appUpdateGoStore')), findsOneWidget);
      expect(find.byKey(const ValueKey('appUpdateLater')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('appUpdateLater')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsNothing);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString(AppPrefs.kUpdateSkippedVersion), '1.3.5');
      expect(prefs.getInt(AppPrefs.kUpdateSkippedAt), now.millisecondsSinceEpoch);
      expect(container.read(appUpdateControllerProvider).bannerVisible, isFalse);
      expect(launched, isEmpty);
    });

    testWidgets('点「Perbarui sekarang」→ 关弹窗、写冷却、跳本平台商店', (tester) async {
      await pumpSettings(tester);
      await tapCheck(tester);
      await tester.tap(find.byKey(const ValueKey('appUpdateGoStore')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsNothing);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString(AppPrefs.kUpdateSkippedVersion), '1.3.5');
      expect(launched, ['https://play.google.com/store/apps/details?id=x']);
    });

    testWidgets('点遮罩关闭：不记冷却、不报 later / go_store 埋点', (tester) async {
      await pumpSettings(tester);
      await tapCheck(tester);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsNothing);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString(AppPrefs.kUpdateSkippedVersion), isNull);
      expect(events.where((e) => e.key == 'app_update_later_tapped'), isEmpty);
      expect(events.where((e) => e.key == 'app_update_go_store_tapped'), isEmpty);
    });

    testWidgets('冷却期内手动检查仍弹推荐弹窗（手动无视冷却）', (tester) async {
      SharedPreferences.setMockInitialValues({
        AppPrefs.kUpdateSkippedVersion: '1.3.5',
        AppPrefs.kUpdateSkippedAt: now.subtract(const Duration(hours: 1)).millisecondsSinceEpoch,
      });
      await pumpSettings(tester);
      await tapCheck(tester);
      expect(container.read(appUpdateControllerProvider).bannerVisible, isFalse,
          reason: '冷却期内横幅不出');
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsOneWidget);
    });
  });
}
