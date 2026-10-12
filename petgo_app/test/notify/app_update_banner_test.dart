import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';
import 'package:tailtopia/features/notify/domain/app_update_controller.dart';
import 'package:tailtopia/features/notify/domain/app_version_check.dart';
import 'package:tailtopia/features/notify/presentation/app_update_banner.dart';
import 'package:tailtopia/features/notify/presentation/app_update_presenter.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 Story 3.3：首页顶部推荐更新横幅。
///
/// 经 fake repository + [AppUpdateEnv] 驱动真实控制器（不 override 控制器本身），
/// 冷却 / 曝光去重都走 3.2 的真实逻辑。

class _FakeRepo extends AppVersionRepository {
  _FakeRepo(this.result) : super(dio: Dio());

  AppVersionInfo? result;

  @override
  Future<AppVersionInfo?> fetch() async => result;
}

const _playUrl = 'https://play.google.com/store/apps/details?id=x';

AppVersionInfo _info({String android = '1.3.4', String min = '1.0.0', String? androidUrl = _playUrl}) =>
    AppVersionInfo(
      iosLatestVersion: '1.3.4',
      androidLatestVersion: android,
      minSupportedVersion: min,
      iosStoreUrl: 'https://apps.apple.com/app/id1',
      androidStoreUrl: androidUrl,
    );

final _banner = find.byKey(const ValueKey('appUpdateBanner'));

void main() {
  late DateTime now;
  late _FakeRepo repo;
  late List<MapEntry<String, Map<String, Object>?>> events;
  late List<String> launched;
  /// launcher 被调用那一刻 prefs 里的 skippedVersion（钉住「先写冷却、再跳商店」）。
  late List<String?> skippedAtLaunch;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    now = DateTime.utc(2026, 10, 12, 8);
    repo = _FakeRepo(_info());
    events = [];
    launched = [];
    skippedAtLaunch = [];
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  ProviderContainer host({String current = '1.3.3', bool isIos = false, bool prefsFails = false}) {
    final c = ProviderContainer(overrides: [
      appVersionRepositoryProvider.overrideWithValue(repo),
      appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
        now: () => now,
        isIos: isIos,
        loadCurrentVersion: () async => current,
        loadPrefs: prefsFails ? () async => throw StateError('prefs down') : AppPrefs.create,
      )),
      storeLauncherProvider.overrideWithValue((url) async {
        launched.add(url);
        skippedAtLaunch.add((await SharedPreferences.getInstance()).getString(AppPrefs.kUpdateSkippedVersion));
      }),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  Future<UpdateDecision?> runCheck(WidgetTester tester, ProviderContainer c) async {
    final d = await tester.runAsync(
        () => c.read(appUpdateControllerProvider.notifier).check(AppUpdateTrigger.coldStart));
    await tester.pumpAndSettle();
    return d;
  }

  Future<void> pumpBanner(WidgetTester tester, ProviderContainer c,
      {bool show = true, Locale locale = const Locale('id'), bool onStage = true}) async {
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: show
              ? TickerMode(
                  enabled: onStage,
                  child: const Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [AppUpdateBanner(), Text('below', key: ValueKey('below'))],
                  ),
                )
              : const Text('elsewhere'),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  List<MapEntry<String, Map<String, Object>?>> named(String e) => events.where((x) => x.key == e).toList();

  group('AC1 出现条件', () {
    testWidgets('推荐态（1.3.3 < 1.3.4）→ 横幅出现；检查前不出现、不占位', (tester) async {
      final c = host();
      await pumpBanner(tester, c);
      expect(_banner, findsNothing);
      expect(tester.getTopLeft(find.byKey(const ValueKey('below'))).dy, 0, reason: '不满足条件时不留占位');

      expect(await runCheck(tester, c), UpdateDecision.recommended);
      expect(_banner, findsOneWidget);
      expect(find.text('Versi baru tersedia'), findsOneWidget);
      expect(find.text('Perbarui sekarang'), findsOneWidget);
    });

    testWidgets('AC1.5 离开再回来，只要 bannerVisible 仍为 true 就还在', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      expect(_banner, findsOneWidget);
      await pumpBanner(tester, c, show: false);
      expect(_banner, findsNothing);
      await pumpBanner(tester, c);
      expect(_banner, findsOneWidget);
    });

    test('AC1.3 lib/ 中 `AppUpdateBanner(` 用法恰 1 处，且在 home_page.dart', () {
      // 排除构造函数声明 `AppUpdateBanner({super.key})`。
      final re = RegExp(r'\bAppUpdateBanner\((?!\{)');
      final hits = <String>[];
      for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
        if (!f.path.endsWith('.dart')) continue;
        final n = re.allMatches(f.readAsStringSync()).length;
        for (var i = 0; i < n; i++) {
          hits.add(f.path);
        }
      }
      expect(hits, hasLength(1));
      expect(hits.single, endsWith('content/presentation/home_page.dart'));
    });
  });

  group('AC2 外观（结构断言）', () {
    testWidgets('高 ≥ 52、浅紫底、标题不截断、✕ 热区 44×44、图标方块 36×36', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);

      expect(tester.getSize(_banner).height, greaterThanOrEqualTo(52));
      final m = tester.widget<Material>(_banner);
      expect(m.color, AppColors.mintTint);
      expect(m.borderRadius, BorderRadius.circular(14));

      final title = tester.widget<Text>(find.text('Versi baru tersedia'));
      expect(title.maxLines, isNull);
      expect(title.overflow, isNot(TextOverflow.ellipsis));
      expect(title.style?.fontSize, 13);
      expect(title.style?.fontWeight, FontWeight.w700);
      expect(title.style?.color, AppColors.ink);

      expect(tester.getSize(find.byKey(const ValueKey('appUpdateBannerClose'))), const Size(44, 44));
      expect(tester.getSize(find.byKey(const ValueKey('appUpdateBannerIcon'))), const Size(36, 36));
      expect(find.byKey(const ValueKey('appUpdateBannerGoStore')), findsOneWidget);
      expect(find.byIcon(Icons.close_rounded), findsOneWidget);

      // 外边距：左右 16。
      final screenW = tester.getSize(find.byType(Scaffold)).width;
      expect(tester.getTopLeft(_banner).dx, 16);
      expect(tester.getTopRight(_banner).dx, screenW - 16);
    });

    // ⚠️ 测试字体（FlutterTest）每个字形宽 1em，比真机字体宽得多：印尼语胶囊「Perbarui sekarang」
    //    在测试字体下就占 226px，360dp 屏里会把标题挤到 0 宽 —— 那是测试字体的假象，不代表真机。
    //    因此这里用英文文案验「折行不截断」机制；印尼语 360dp 实测归 L2（Story 3.3 T4）。
    testWidgets('360dp 窄屏：标题折行而非截断，横幅随之增高，无溢出', (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c, locale: const Locale('en'));

      expect(tester.takeException(), isNull);
      final title = find.text('A new version is available');
      final para = tester.renderObject<RenderParagraph>(title);
      expect(para.didExceedMaxLines, isFalse);
      expect(tester.getSize(title).height, greaterThan(13 * 1.5), reason: '窄屏折到多行');
      expect(tester.getSize(_banner).height, greaterThanOrEqualTo(52));
    });
  });

  group('AC3 / AC4 点击', () {
    testWidgets('AC3 点「Perbarui sekarang」：报埋点 → 横幅收起 + 写冷却 → 跳 Google Play', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);

      await tester.tap(find.byKey(const ValueKey('appUpdateBannerGoStore')));
      await tester.pumpAndSettle();

      expect(_banner, findsNothing);
      expect(launched, [_playUrl]);
      expect(skippedAtLaunch, ['1.3.4'], reason: '跳商店之前冷却已落盘');
      expect(named('app_update_go_store_tapped').single.value, {'decision': 'recommend', 'surface': 'banner'});
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString(AppPrefs.kUpdateSkippedVersion), '1.3.4');
      expect(prefs.getInt(AppPrefs.kUpdateSkippedAt), now.millisecondsSinceEpoch);
    });

    testWidgets('AC3 iOS：跳 App Store 链接', (tester) async {
      final c = host(isIos: true);
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      await tester.tap(find.byKey(const ValueKey('appUpdateBannerGoStore')));
      await tester.pumpAndSettle();
      expect(launched, ['https://apps.apple.com/app/id1']);
    });

    testWidgets('复审：冷却写失败（recordSkip 抛异常）→ 横幅照样收起、照样跳商店', (tester) async {
      final c = host(prefsFails: true);
      expect(await runCheck(tester, c), UpdateDecision.recommended);
      await pumpBanner(tester, c);
      await tester.tap(find.byKey(const ValueKey('appUpdateBannerGoStore')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_banner, findsNothing);
      expect(launched, [_playUrl]);
    });

    testWidgets('复审：冷却写失败时点 ✕ 也不抛、横幅收起', (tester) async {
      final c = host(prefsFails: true);
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      await tester.tap(find.byKey(const ValueKey('appUpdateBannerClose')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_banner, findsNothing);
      expect(launched, isEmpty);
    });

    testWidgets('AC4 点 ✕：报 later_tapped → 横幅收起 + 写冷却，不跳商店', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);

      await tester.tap(find.byKey(const ValueKey('appUpdateBannerClose')));
      await tester.pumpAndSettle();

      expect(_banner, findsNothing);
      expect(launched, isEmpty);
      expect(named('app_update_later_tapped').single.value, {'decision': 'recommend', 'surface': 'banner'});
      expect(named('app_update_go_store_tapped'), isEmpty);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString(AppPrefs.kUpdateSkippedVersion), '1.3.4');
    });
  });

  group('AC5 冷却生效与恢复', () {
    testWidgets('冷却记录 1.3.4 / 1 小时前：接口 1.3.4 → 无横幅；升到 1.3.5 → 下次检查立即恢复', (tester) async {
      SharedPreferences.setMockInitialValues({
        AppPrefs.kUpdateSkippedVersion: '1.3.4',
        AppPrefs.kUpdateSkippedAt: now.subtract(const Duration(hours: 1)).millisecondsSinceEpoch,
      });
      final c = host();
      await pumpBanner(tester, c);
      expect(await runCheck(tester, c), UpdateDecision.recommended);
      expect(_banner, findsNothing);

      repo.result = _info(android: '1.3.5');
      expect(await runCheck(tester, c), UpdateDecision.recommended);
      expect(_banner, findsOneWidget);
    });
  });

  group('AC5 冷却到期', () {
    testWidgets('冷却记录同版本但已满 72 小时 → 恢复显示', (tester) async {
      SharedPreferences.setMockInitialValues({
        AppPrefs.kUpdateSkippedVersion: '1.3.4',
        AppPrefs.kUpdateSkippedAt: now.subtract(const Duration(hours: 72)).millisecondsSinceEpoch,
      });
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      expect(_banner, findsOneWidget);
    });
  });

  group('AC6 曝光每次运行只报一次', () {
    testWidgets('复审：首页分支离台（TickerMode 关）时不报、不占掉去重位；回到台前才报 1 条', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c, onStage: false);
      expect(named('app_update_prompt_shown'), isEmpty);
      expect(c.read(appUpdateControllerProvider).bannerShownReported, isFalse);

      await pumpBanner(tester, c);
      expect(named('app_update_prompt_shown'), hasLength(1));
      expect(c.read(appUpdateControllerProvider).bannerShownReported, isTrue);
    });

    testWidgets('同一 ProviderContainer 内先后两次进首页 → 只报 1 条 prompt_shown', (tester) async {
      final c = host();
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      expect(named('app_update_prompt_shown'), hasLength(1));
      expect(named('app_update_prompt_shown').single.value, {'decision': 'recommend', 'surface': 'banner'});

      await pumpBanner(tester, c, show: false);
      await pumpBanner(tester, c);
      expect(_banner, findsOneWidget);
      expect(named('app_update_prompt_shown'), hasLength(1));
    });

    testWidgets('横幅不可见 → 不报曝光', (tester) async {
      final c = host(current: '1.3.4');
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      expect(named('app_update_prompt_shown'), isEmpty);
    });
  });

  group('AC7 不出现的情形', () {
    Future<void> expectNoBanner(WidgetTester tester, ProviderContainer c) async {
      await runCheck(tester, c);
      await pumpBanner(tester, c);
      expect(_banner, findsNothing);
      expect(tester.getTopLeft(find.byKey(const ValueKey('below'))).dy, 0);
      expect(named('app_update_prompt_shown'), isEmpty);
    }

    testWidgets('本平台商店链接为空', (tester) async {
      repo.result = _info(androidUrl: '');
      await expectNoBanner(tester, host());
    });

    testWidgets('接口异常（fetch 返回 null）', (tester) async {
      repo.result = null;
      await expectNoBanner(tester, host());
    });

    testWidgets('冷却期内', (tester) async {
      SharedPreferences.setMockInitialValues({
        AppPrefs.kUpdateSkippedVersion: '1.3.4',
        AppPrefs.kUpdateSkippedAt: now.subtract(const Duration(hours: 71)).millisecondsSinceEpoch,
      });
      await expectNoBanner(tester, host());
    });

    testWidgets('判定为 none（已是最新）', (tester) async {
      await expectNoBanner(tester, host(current: '1.3.4'));
    });

    testWidgets('判定为 forced（低于最低支持版本）', (tester) async {
      repo.result = _info(min: '1.3.4');
      await expectNoBanner(tester, host());
    });
  });
}
