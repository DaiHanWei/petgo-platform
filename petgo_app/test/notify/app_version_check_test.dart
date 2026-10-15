import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/domain/app_version_check.dart';

/// Story 6.5 J4：语义版本比较 + 三态判定 + 平台 URL 选择（含相等/补丁/预发边界）。
void main() {
  test('compareSemver：大小/相等/缺位补0/预发后缀忽略', () {
    expect(AppVersionCheck.compareSemver('1.0.0', '1.0.1'), -1);
    expect(AppVersionCheck.compareSemver('1.2.0', '1.1.9'), 1);
    expect(AppVersionCheck.compareSemver('1.0', '1.0.0'), 0);
    expect(AppVersionCheck.compareSemver('2.0.0', '1.9.9'), 1);
    expect(AppVersionCheck.compareSemver('1.0.0-beta', '1.0.0'), 0); // 忽略预发后缀
  });

  test('decide：强制 / 推荐 / 无更新三态', () {
    // 当前 < minSupported → 强制
    expect(AppVersionCheck.decide(current: '1.0.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.forced);
    // minSupported ≤ 当前 < latest → 推荐
    expect(AppVersionCheck.decide(current: '1.6.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.recommended);
    // 当前 ≥ latest → 无更新
    expect(AppVersionCheck.decide(current: '2.0.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.none);
    expect(AppVersionCheck.decide(current: '2.1.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.none);
  });

  test('storeUrl：平台选择 + 空兜底', () {
    expect(
        AppVersionCheck.storeUrl(isIos: true, iosStoreUrl: 'ios://x', androidStoreUrl: 'play://y'),
        'ios://x');
    expect(
        AppVersionCheck.storeUrl(isIos: false, iosStoreUrl: 'ios://x', androidStoreUrl: 'play://y'),
        'play://y');
    expect(AppVersionCheck.storeUrl(isIos: true, iosStoreUrl: '', androidStoreUrl: 'play://y'),
        isNull);
  });

  group('cooldownActive（V1.3.4 Story 3.2 · AC5.2）', () {
    final skippedAt = DateTime.utc(2026, 10, 1, 12);

    bool active({String latest = '1.3.4', String? version = '1.3.4', DateTime? at, required DateTime now}) =>
        AppVersionCheck.cooldownActive(
            latest: latest, skippedVersion: version, skippedAt: at ?? skippedAt, now: now);

    test('71h59m → 冷却中；72h → 不冷却', () {
      expect(active(now: skippedAt.add(const Duration(hours: 71, minutes: 59))), isTrue);
      expect(active(now: skippedAt.add(const Duration(hours: 72))), isFalse);
      expect(kUpdateCooldown, const Duration(hours: 72));
    });

    test('冷却中服务端从 1.3.4 升到 1.3.5 → 不冷却', () {
      expect(active(latest: '1.3.5', now: skippedAt.add(const Duration(hours: 1))), isFalse);
    });

    test('无跳过记录 → 不冷却', () {
      final now = skippedAt.add(const Duration(hours: 1));
      expect(
          AppVersionCheck.cooldownActive(
              latest: '1.3.4', skippedVersion: null, skippedAt: skippedAt, now: now),
          isFalse);
      expect(
          AppVersionCheck.cooldownActive(
              latest: '1.3.4', skippedVersion: '1.3.4', skippedAt: null, now: now),
          isFalse);
    });

    test('跳过时刻晚于 now（时钟被往回拨）→ 不冷却', () {
      expect(active(now: skippedAt.subtract(const Duration(minutes: 1))), isFalse);
    });

    test('回前台检查间隔 6h', () {
      expect(kUpdateResumeCheckInterval, const Duration(hours: 6));
    });
  });

  test('AC1.2：lib/ 下不得再从编译期注入取 APP_VERSION', () {
    final hits = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (!f.path.endsWith('.dart')) continue;
      if (f.readAsStringSync().contains("fromEnvironment('APP_VERSION'")) hits.add(f.path);
    }
    expect(hits, isEmpty, reason: '当前版本必须取真机包版本（PackageInfo），见 AppUpdateEnv');
  });
}
