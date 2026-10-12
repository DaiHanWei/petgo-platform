import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';
import 'package:tailtopia/features/notify/domain/app_update_controller.dart';
import 'package:tailtopia/features/notify/domain/app_version_check.dart';
import 'package:tailtopia/features/notify/presentation/app_update_banner.dart';
import 'package:tailtopia/features/notify/presentation/app_update_presenter.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 Story 3.2 · AC9（D-25）：版本更新埋点 —— 事件名从 `lib/` 源码提取，
/// 属性用 [Analytics.debugCaptureSink] 看端上真正发出的形态。
///
/// Story 3.3 起补 `app_update_later_tapped` 与横幅（surface=banner）三事件。
/// Story 3.4 起补设置页手动检查的推荐态弹窗（surface=dialog, decision=recommend）三事件。

Set<String> _eventNamesInSource() {
  final re = RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'""");
  final out = <String>{};
  for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
    if (!f.path.endsWith('.dart')) continue;
    for (final m in re.allMatches(f.readAsStringSync())) {
      out.add(m.group(1)!);
    }
  }
  return out;
}

class _FakeRepo extends AppVersionRepository {
  _FakeRepo() : super(dio: Dio());

  int calls = 0;

  @override
  Future<AppVersionInfo?> fetch() async {
    calls++;
    return _info;
  }

  static const _info = AppVersionInfo(
        iosLatestVersion: '1.3.4',
        androidLatestVersion: '1.3.4',
        minSupportedVersion: '1.3.0',
        iosStoreUrl: 'https://apps.apple.com/app/id1',
        androidStoreUrl: 'https://play.google.com/store/apps/details?id=x',
      );
}

void main() {
  test('事件名以字面量出现在 lib/ 里', () {
    final inSource = _eventNamesInSource();
    expect(inSource, contains('app_update_prompt_shown'));
    expect(inSource, contains('app_update_go_store_tapped'));
    expect(inSource, contains('app_update_later_tapped'));
  });

  group('首页推荐横幅（Story 3.3）', () {
    late List<MapEntry<String, Map<String, Object>?>> events;
    late List<String> launched;
    late ProviderContainer c;

    setUp(() {
      SharedPreferences.setMockInitialValues({});
      events = [];
      launched = [];
      Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpBanner(WidgetTester tester) async {
      c = ProviderContainer(overrides: [
        appVersionRepositoryProvider.overrideWithValue(_FakeRepo()),
        appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
          now: DateTime.now,
          isIos: false,
          loadCurrentVersion: () async => '1.3.3',
          loadPrefs: AppPrefs.create,
        )),
        storeLauncherProvider.overrideWithValue((url) async => launched.add(url)),
      ]);
      addTearDown(c.dispose);
      final d = await tester.runAsync(
          () => c.read(appUpdateControllerProvider.notifier).check(AppUpdateTrigger.coldStart));
      expect(d, UpdateDecision.recommended);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const Scaffold(body: AppUpdateBanner()),
        ),
      ));
      await tester.pumpAndSettle();
    }

    const bannerProps = {'decision': 'recommend', 'surface': 'banner'};

    testWidgets('曝光 + 点 ✕：prompt_shown / later_tapped 属性正确', (tester) async {
      await pumpBanner(tester);
      expect(events.where((e) => e.key == 'app_update_prompt_shown').single.value, bannerProps);

      await tester.tap(find.byKey(const ValueKey('appUpdateBannerClose')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.key == 'app_update_later_tapped').single.value, bannerProps);
      expect(launched, isEmpty);
    });

    testWidgets('点「Perbarui sekarang」：go_store_tapped 属性正确', (tester) async {
      await pumpBanner(tester);
      await tester.tap(find.byKey(const ValueKey('appUpdateBannerGoStore')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.key == 'app_update_go_store_tapped').single.value, bannerProps);
      expect(launched, ['https://play.google.com/store/apps/details?id=x']);
    });
  });

  group('推荐态弹窗（Story 3.4 · 设置页手动检查）', () {
    late List<MapEntry<String, Map<String, Object>?>> events;
    late List<String> launched;
    late WidgetRef hostRef;
    late BuildContext hostContext;

    setUp(() {
      SharedPreferences.setMockInitialValues({});
      events = [];
      launched = [];
      Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpDialog(WidgetTester tester) async {
      final c = ProviderContainer(overrides: [
        appVersionRepositoryProvider.overrideWithValue(_FakeRepo()),
        appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
          now: DateTime.now,
          isIos: false,
          loadCurrentVersion: () async => '1.3.3',
          loadPrefs: AppPrefs.create,
        )),
        storeLauncherProvider.overrideWithValue((url) async => launched.add(url)),
      ]);
      addTearDown(c.dispose);
      final outcome = await tester.runAsync(() => c.read(appUpdateControllerProvider.notifier).checkManually());
      expect(outcome, ManualCheckOutcome.recommended);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Consumer(builder: (context, ref, _) {
            hostRef = ref;
            hostContext = context;
            return const Scaffold(body: SizedBox.shrink());
          }),
        ),
      ));
      unawaited(showRecommendedUpdateDialog(hostContext, hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateRecommended')), findsOneWidget);
    }

    const dialogProps = {'decision': 'recommend', 'surface': 'dialog'};

    testWidgets('弹出报 prompt_shown；点「Nanti」报 later_tapped', (tester) async {
      await pumpDialog(tester);
      expect(events.where((e) => e.key == 'app_update_prompt_shown').single.value, dialogProps);

      await tester.tap(find.byKey(const ValueKey('appUpdateLater')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.key == 'app_update_later_tapped').single.value, dialogProps);
      expect(events.where((e) => e.key == 'app_update_go_store_tapped'), isEmpty);
      expect(launched, isEmpty);
    });

    testWidgets('点「Perbarui sekarang」报 go_store_tapped 并跳商店', (tester) async {
      await pumpDialog(tester);
      await tester.tap(find.byKey(const ValueKey('appUpdateGoStore')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.key == 'app_update_go_store_tapped').single.value, dialogProps);
      expect(events.where((e) => e.key == 'app_update_later_tapped'), isEmpty);
      expect(launched, ['https://play.google.com/store/apps/details?id=x']);
    });
  });

  group('强制弹窗', () {
    late List<MapEntry<String, Map<String, Object>?>> events;
    late List<String> launched;
    late WidgetRef hostRef;
    late GoRouter router;
    late _FakeRepo repo;

    setUp(() {
      SharedPreferences.setMockInitialValues({});
      debugResetForcedUpdateDialog();
      events = [];
      launched = [];
      repo = _FakeRepo();
      Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpApp(WidgetTester tester, {String initial = '/home'}) async {
      Widget page(String name) => Scaffold(body: Text(name));
      router = GoRouter(
        navigatorKey: rootNavigatorKey,
        initialLocation: initial,
        routes: [
          GoRoute(path: '/splash', builder: (c, s) => page('splash')),
          GoRoute(path: '/home', builder: (c, s) => page('home')),
          GoRoute(path: '/other', builder: (c, s) => page('other')),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          routerProvider.overrideWithValue(router),
          appVersionRepositoryProvider.overrideWithValue(repo),
          appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
            now: DateTime.now,
            isIos: false,
            loadCurrentVersion: () async => '1.2.0',
            loadPrefs: AppPrefs.create,
          )),
          storeLauncherProvider.overrideWithValue((url) async => launched.add(url)),
        ],
        // ref 取自路由之上（同生产：app.dart 根 State 的 ref 与 App 同寿命，不随页面替换失效）。
        child: Consumer(builder: (context, ref, _) {
          hostRef = ref;
          return MaterialApp.router(
            routerConfig: router,
            locale: const Locale('id'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
          );
        }),
      ));
      await tester.pumpAndSettle();
    }

    Future<void> runCheck(WidgetTester tester) async {
      final d = await tester.runAsync(
          () => hostRef.read(appUpdateControllerProvider.notifier).check(AppUpdateTrigger.coldStart));
      expect(d, UpdateDecision.forced);
    }

    testWidgets('弹出报 app_update_prompt_shown，点按钮报 go_store_tapped 并跳商店', (tester) async {
      await pumpApp(tester);
      await runCheck(tester);

      unawaited(showForcedUpdateDialog(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
      expect(find.text('Versi 1.3.4'), findsOneWidget);
      final shown = events.where((e) => e.key == 'app_update_prompt_shown').toList();
      expect(shown, hasLength(1));
      expect(shown.single.value, {'decision': 'force', 'surface': 'dialog'});

      await tester.tap(find.byKey(const ValueKey('appUpdateGoStore')));
      await tester.pumpAndSettle();
      final tapped = events.where((e) => e.key == 'app_update_go_store_tapped').toList();
      expect(tapped, hasLength(1));
      expect(tapped.single.value, {'decision': 'force', 'surface': 'dialog'});
      expect(launched, ['https://play.google.com/store/apps/details?id=x']);
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget,
          reason: '跳商店后强制弹窗不关闭');

      // 再判定一次强制（如回前台）不叠第二个弹窗、不重复报曝光。
      unawaited(showForcedUpdateDialog(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
      expect(events.where((e) => e.key == 'app_update_prompt_shown'), hasLength(1));
    });

    testWidgets('仍在 /splash 时先等它离开再弹（否则 go 替换栈会把弹窗一起移除）', (tester) async {
      await pumpApp(tester, initial: '/splash');
      await runCheck(tester);

      unawaited(showForcedUpdateDialog(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsNothing);
      expect(events.where((e) => e.key == 'app_update_prompt_shown'), isEmpty);

      router.go('/home');
      await tester.pumpAndSettle();
      expect(find.text('home'), findsOneWidget);
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
      expect(events.where((e) => e.key == 'app_update_prompt_shown'), hasLength(1));
    });

    testWidgets('🔴 复审：离开 splash 后 go 替换页面栈（推送点击 / 深链）也带不走强制弹窗；返回键关不掉',
        (tester) async {
      await pumpApp(tester);
      await runCheck(tester);
      unawaited(showForcedUpdateDialog(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget, reason: '返回键不得关闭');

      router.go('/other');
      await tester.pumpAndSettle();
      expect(find.text('other'), findsOneWidget);
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget,
          reason: 'go 替换栈后必须重新弹出');
      expect(events.where((e) => e.key == 'app_update_prompt_shown'), hasLength(1),
          reason: '重弹是同一次曝光，不重复计数');

      router.go('/home');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
    });

    testWidgets('🔴 复审：已判定 forced 时回前台直接重弹，不受 6h 节流、不发请求', (tester) async {
      await pumpApp(tester);
      await runCheck(tester);
      expect(repo.calls, 1);
      expect(find.byKey(const ValueKey('appUpdateForced')), findsNothing);

      unawaited(onResumeUpdateCheck(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
      expect(repo.calls, 1, reason: '重弹不走网络');

      // 已在显示 → 再回前台空转，不叠第二个。
      unawaited(onResumeUpdateCheck(hostRef));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
    });
  });
}
