import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';
import 'package:tailtopia/features/notify/domain/app_update_bootstrap.dart';
import 'package:tailtopia/features/notify/domain/app_update_controller.dart';
import 'package:tailtopia/features/notify/domain/app_version_check.dart';

/// 假 repository：计数 + 可编排返回值 / 可挂起。
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

AppVersionInfo _info({
  String ios = '1.3.3',
  String android = '1.3.5',
  String min = '1.0.0',
  String? iosUrl = 'https://apps.apple.com/app/id1',
  String? androidUrl = 'https://play.google.com/x',
}) =>
    AppVersionInfo(
      iosLatestVersion: ios,
      androidLatestVersion: android,
      minSupportedVersion: min,
      iosStoreUrl: iosUrl,
      androidStoreUrl: androidUrl,
    );

/// V1.3.4 Story 3.2 · AC3/AC4/AC5/AC6.4/AC10。
void main() {
  late DateTime now;
  late _FakeRepo repo;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    now = DateTime.utc(2026, 10, 12, 8);
    repo = _FakeRepo(_info());
  });

  ProviderContainer host(
      {bool isIos = false, String current = '1.3.3', bool versionFails = false, bool prefsFails = false}) {
    final c = ProviderContainer(overrides: [
      appVersionRepositoryProvider.overrideWithValue(repo),
      appUpdateEnvProvider.overrideWithValue(AppUpdateEnv(
        now: () => now,
        isIos: isIos,
        loadCurrentVersion: () async {
          if (versionFails) throw StateError('no package info');
          return current;
        },
        loadPrefs: prefsFails ? () async => throw StateError('prefs down') : AppPrefs.create,
      )),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  AppUpdateController ctl(ProviderContainer c) => c.read(appUpdateControllerProvider.notifier);

  group('AC3 按本平台判定', () {
    test('iOS：ios=1.3.3 / android=1.3.5 / current=1.3.3 → none', () async {
      final c = host(isIos: true);
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.none);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isFalse);
    });

    test('Android 同数据 → recommended（只调高一端时另一端无提示）', () async {
      final c = host();
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.recommended);
      final s = c.read(appUpdateControllerProvider);
      expect(s.bannerVisible, isTrue);
      expect(s.currentVersion, '1.3.3');
      expect(ctl(c).latestForPlatform, '1.3.5');
      expect(ctl(c).storeUrlForPlatform, 'https://play.google.com/x');
    });

    test('current=0.9.0, min=1.0.0 → forced，横幅恒 false', () async {
      final c = host(current: '0.9.0');
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.forced);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isFalse);
    });

    test('stag 包 1.3.3-stag 按 1.3.3 判', () async {
      final c = host(isIos: true, current: '1.3.3-stag');
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.none);
    });

    test('本平台商店链接为空 → 推荐态也不出横幅', () async {
      repo.result = _info(androidUrl: '');
      final c = host();
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.recommended);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isFalse);
    });
  });

  group('AC10 失败放行', () {
    test('repository 返回 null → check 返回 null、decision none、横幅 false', () async {
      repo.result = null;
      final c = host();
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), isNull);
      final s = c.read(appUpdateControllerProvider);
      expect(s.decision, UpdateDecision.none);
      expect(s.bannerVisible, isFalse);
      expect(s.lastCheckedAt, now, reason: '失败也记发出请求的时刻');
    });

    test('取版本号失败 → null，不发接口请求', () async {
      final c = host(versionFails: true);
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), isNull);
      expect(repo.calls, 0);
    });

    test('复审：读冷却键失败 → 按无冷却记录继续判定，不丢强制判定', () async {
      expect(await ctl(host(current: '0.9.0', prefsFails: true)).check(AppUpdateTrigger.coldStart),
          UpdateDecision.forced);
      final c = host(prefsFails: true);
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.recommended);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isTrue);
    });

    test('失败时保持上一次成功值不变', () async {
      final c = host();
      await ctl(c).check(AppUpdateTrigger.coldStart);
      repo.result = null;
      now = now.add(const Duration(hours: 7));
      expect(await ctl(c).check(AppUpdateTrigger.resume), isNull);
      final s = c.read(appUpdateControllerProvider);
      expect(s.decision, UpdateDecision.recommended);
      expect(s.bannerVisible, isTrue);
      expect(s.lastCheckedAt, now);
    });
  });

  group('AC6.4 回前台节流', () {
    test('lastCheckedAt == null（冷启动检查尚未发出）→ 0 次请求', () async {
      final c = host();
      expect(await ctl(c).onResumed(), isNull);
      expect(repo.calls, 0);
    });

    test('5h59m → 0 次；6h → 1 次', () async {
      final c = host();
      await ctl(c).check(AppUpdateTrigger.coldStart);
      expect(repo.calls, 1);
      final start = now;

      now = start.add(const Duration(hours: 5, minutes: 59));
      expect(await ctl(c).onResumed(), isNull);
      expect(repo.calls, 1);

      now = start.add(const Duration(hours: 6));
      expect(await ctl(c).onResumed(), UpdateDecision.recommended);
      expect(repo.calls, 2);
    });
  });

  test('AC4.1 并发去重：在途时再调 check / onResumed / checkManually 复用同一请求', () async {
    final c = host();
    repo.gate = Completer<void>();
    final a = ctl(c).check(AppUpdateTrigger.coldStart);
    final b = ctl(c).check(AppUpdateTrigger.coldStart);
    final r = ctl(c).onResumed();
    final m = ctl(c).checkManually();
    repo.gate!.complete();
    expect(await a, UpdateDecision.recommended);
    expect(await b, UpdateDecision.recommended);
    expect(await r, UpdateDecision.recommended);
    expect(await m, ManualCheckOutcome.recommended);
    expect(repo.calls, 1);
  });

  group('AC4.4 checkManually', () {
    test('四种结果', () async {
      expect(await ctl(host(current: '0.9.0')).checkManually(), ManualCheckOutcome.forced);
      expect(await ctl(host()).checkManually(), ManualCheckOutcome.recommended);
      expect(await ctl(host(current: '1.3.5')).checkManually(), ManualCheckOutcome.upToDate);
      repo.result = null;
      expect(await ctl(host()).checkManually(), ManualCheckOutcome.failed);
    });

    test('冷却期内也返回 recommended，且无视 6h 间隔照常请求', () async {
      final c = host();
      await ctl(c).check(AppUpdateTrigger.coldStart);
      await ctl(c).recordSkip();
      now = now.add(const Duration(minutes: 1));
      expect(await ctl(c).checkManually(), ManualCheckOutcome.recommended);
      expect(repo.calls, 2);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isFalse);
    });
  });

  group('AC5 冷却', () {
    test('recordSkip() 后 bannerVisible 立即 false，prefs 两键写入', () async {
      final c = host();
      await ctl(c).check(AppUpdateTrigger.coldStart);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isTrue);

      await ctl(c).recordSkip();
      final s = c.read(appUpdateControllerProvider);
      expect(s.bannerVisible, isFalse);
      expect(s.skippedVersion, '1.3.5');
      expect(s.skippedAt, now);

      final sp = await SharedPreferences.getInstance();
      expect(sp.getString('petgo.update_skipped_version'), '1.3.5');
      expect(sp.getInt('petgo.update_skipped_at'), now.millisecondsSinceEpoch);
      final prefs = await AppPrefs.create();
      expect(prefs.updateSkippedAt, now);
      expect(prefs.updateSkippedAt!.isUtc, isTrue);
    });

    test('71h59m 仍冷却；72h 横幅重新出现', () async {
      final skippedAt = now;
      SharedPreferences.setMockInitialValues({
        'petgo.update_skipped_version': '1.3.5',
        'petgo.update_skipped_at': skippedAt.millisecondsSinceEpoch,
      });
      final c = host();
      now = skippedAt.add(const Duration(hours: 71, minutes: 59));
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.recommended);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isFalse);

      now = skippedAt.add(const Duration(hours: 72));
      await ctl(c).check(AppUpdateTrigger.resume);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isTrue);
    });

    test('冷却中服务端从 1.3.4 升到 1.3.5 → 不冷却', () async {
      SharedPreferences.setMockInitialValues({
        'petgo.update_skipped_version': '1.3.4',
        'petgo.update_skipped_at': now.millisecondsSinceEpoch,
      });
      final c = host();
      now = now.add(const Duration(hours: 1));
      await ctl(c).check(AppUpdateTrigger.coldStart);
      expect(c.read(appUpdateControllerProvider).bannerVisible, isTrue);
    });

    test('强制态不受冷却影响', () async {
      SharedPreferences.setMockInitialValues({
        'petgo.update_skipped_version': '1.3.5',
        'petgo.update_skipped_at': now.millisecondsSinceEpoch,
      });
      final c = host(current: '0.9.0');
      expect(await ctl(c).check(AppUpdateTrigger.coldStart), UpdateDecision.forced);
    });
  });

  test('markBannerShownReported：首次 true，之后恒 false', () {
    final c = host();
    expect(ctl(c).markBannerShownReported(), isTrue);
    expect(ctl(c).markBannerShownReported(), isFalse);
    expect(c.read(appUpdateControllerProvider).bannerShownReported, isTrue);
  });

  group('AppUpdateBootstrap', () {
    tearDown(AppUpdateBootstrap.debugReset);

    test('未注册 → 空转', () async {
      AppUpdateBootstrap.debugReset();
      await AppUpdateBootstrap.runColdStartCheck();
    });

    test('回调抛错被吞掉，不影响启动', () async {
      var ran = false;
      AppUpdateBootstrap.register(() async {
        ran = true;
        throw StateError('boom');
      });
      await AppUpdateBootstrap.runColdStartCheck();
      expect(ran, isTrue);
    });
  });
}
