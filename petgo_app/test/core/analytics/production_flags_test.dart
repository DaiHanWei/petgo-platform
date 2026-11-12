import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/production_flags.dart';

/// analytics-pipeline Story 2.5 AC1–AC3：安装来源判定、环境上下文、缓存。
void main() {
  group('classifyInstallSource 全组合（AC2）', () {
    const cases = <(String, String?, bool, InstallSource)>[
      ('ios', 'com.apple', true, InstallSource.store),
      ('ios', null, true, InstallSource.store),
      ('ios', 'com.apple.testflight', true, InstallSource.testflight),
      ('ios', 'com.apple.simulator', false, InstallSource.simulator),
      ('ios', 'com.apple.simulator', true, InstallSource.simulator),
      ('ios', 'com.apple', false, InstallSource.simulator),
      ('android', 'com.android.vending', true, InstallSource.store),
      ('android', null, true, InstallSource.sideload),
      ('android', 'com.google.android.packageinstaller', true, InstallSource.sideload),
      ('android', 'com.xiaomi.market', true, InstallSource.sideload),
      ('android', 'com.android.vending', false, InstallSource.simulator),
    ];
    for (final (platform, store, physical, want) in cases) {
      test('$platform / $store / physical=$physical → ${want.wire}', () {
        final got = classifyInstallSource(platform: platform, installerStore: store, isPhysicalDevice: physical);
        expect(got, want);
        expect(isProduction(got), want == InstallSource.store);
      });
    }

    test('线上值为小写字符串，与后端非生产集合一致', () {
      expect(InstallSource.values.map((s) => s.wire), ['store', 'testflight', 'simulator', 'sideload']);
    });
  });

  group('buildEnvironmentContext（AC1）', () {
    const pkg = PackageFacts(appVersion: '1.5.0', appBuild: '21', installerStore: 'com.android.vending');
    const device = DeviceFacts(isPhysicalDevice: true, osVersion: '14', deviceModel: 'Google Pixel 8');

    test('键集合恰为契约所列（不含任何设备名 / 标识）', () {
      final ctx = buildEnvironmentContext(platform: 'android', pkg: pkg, device: device);
      expect(ctx.keys.toSet(), {
        'appVersion', 'appBuild', 'platform', 'osVersion', 'deviceModel',
        'isPhysicalDevice', 'installSource', 'isProduction', 'installerStore',
      });
      expect(ctx.keys, isNot(contains('name')));
      expect(ctx.keys, isNot(contains('deviceName')));
      expect(ctx, {
        'appVersion': '1.5.0',
        'appBuild': '21',
        'platform': 'android',
        'osVersion': '14',
        'deviceModel': 'Google Pixel 8',
        'isPhysicalDevice': true,
        'installerStore': 'com.android.vending',
        'installSource': 'store',
        'isProduction': true,
      });
    });

    test('模拟器：installSource=simulator、非生产', () {
      final ctx = buildEnvironmentContext(
        platform: 'android',
        pkg: const PackageFacts(appVersion: '1.5.0-stag', appBuild: '21'),
        device: const DeviceFacts(isPhysicalDevice: false, osVersion: '14', deviceModel: 'Google sdk_gphone64_arm64'),
      );
      expect(ctx['appVersion'], '1.5.0-stag');
      expect(ctx['installSource'], 'simulator');
      expect(ctx['isProduction'], false);
      expect(ctx.containsKey('installerStore'), isFalse); // null 不放键
    });

    test('设备信息采集失败：installSource 缺省、isProduction=false，其余字段照放', () {
      final ctx = buildEnvironmentContext(platform: 'ios', pkg: pkg, device: null);
      expect(ctx.containsKey('installSource'), isFalse);
      expect(ctx.containsKey('isPhysicalDevice'), isFalse);
      expect(ctx['isProduction'], false);
      expect(ctx['appVersion'], '1.5.0');
    });

    test('包信息采集失败：installSource 缺省、isProduction=false', () {
      final ctx = buildEnvironmentContext(platform: 'ios', pkg: null, device: device);
      expect(ctx.containsKey('installSource'), isFalse);
      expect(ctx['isProduction'], false);
      expect(ctx['isPhysicalDevice'], true);
    });

    test('平台未知：isProduction=false', () {
      final ctx = buildEnvironmentContext(platform: null, pkg: pkg, device: null);
      expect(ctx.containsKey('platform'), isFalse);
      expect(ctx['isProduction'], false);
    });
  });

  group('AnalyticsEnvironment 采集与缓存（AC3）', () {
    tearDown(AnalyticsEnvironment.debugReset);

    test('只采集一次，之后返回缓存', () async {
      var pkgCalls = 0;
      var deviceCalls = 0;
      AnalyticsEnvironment.debugPlatform = () => 'ios';
      AnalyticsEnvironment.debugReadPackage = () async {
        pkgCalls++;
        return const PackageFacts(appVersion: '1.5.0', appBuild: '7', installerStore: 'com.apple.testflight');
      };
      AnalyticsEnvironment.debugReadDevice = (p) async {
        deviceCalls++;
        expect(p, 'ios');
        return const DeviceFacts(isPhysicalDevice: true, osVersion: '18.1', deviceModel: 'iPhone15,2');
      };
      expect(AnalyticsEnvironment.context, isNull);
      final results = await Future.wait([AnalyticsEnvironment.load(), AnalyticsEnvironment.load()]);
      await AnalyticsEnvironment.load();
      expect(pkgCalls, 1);
      expect(deviceCalls, 1);
      expect(identical(results[0], results[1]), isTrue);
      final ctx = AnalyticsEnvironment.context!;
      expect(ctx['installSource'], 'testflight');
      expect(ctx['isProduction'], false);
      expect(ctx['installerStore'], 'com.apple.testflight');
      expect(() => ctx['x'] = 1, throwsUnsupportedError);
    });

    test('插件异常：降级为非生产，不抛', () async {
      AnalyticsEnvironment.debugPlatform = () => 'android';
      AnalyticsEnvironment.debugReadPackage = () async => throw StateError('boom');
      AnalyticsEnvironment.debugReadDevice =
          (_) async => const DeviceFacts(isPhysicalDevice: true, osVersion: '14', deviceModel: 'Google Pixel 8');
      final ctx = await AnalyticsEnvironment.load();
      expect(ctx['isProduction'], false);
      expect(ctx.containsKey('installSource'), isFalse);
      expect(ctx['osVersion'], '14');
    });

    test('插件超时 3 秒：降级为非生产（真实等待约 3 秒）', () async {
      AnalyticsEnvironment.debugPlatform = () => 'android';
      AnalyticsEnvironment.debugReadPackage = () => Completer<PackageFacts>().future; // 永不返回
      AnalyticsEnvironment.debugReadDevice =
          (_) async => const DeviceFacts(isPhysicalDevice: true, osVersion: '14', deviceModel: 'X');
      final ctx = await AnalyticsEnvironment.load();
      expect(ctx['isProduction'], false);
      expect(ctx.containsKey('appVersion'), isFalse);
      expect(ctx['isPhysicalDevice'], true);
    }, timeout: const Timeout(Duration(seconds: 15)));
  });
}
