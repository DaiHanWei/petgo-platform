import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/anonymous_id.dart';
import 'package:tailtopia/core/analytics/event_names.dart';
import 'package:tailtopia/core/analytics/lifecycle_events.dart';

/// analytics-pipeline Story 2.3：生命周期事件（Installed / Updated / Opened）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('decideInstallEvent（AC5 纯函数）', () {
    const v = '1.5.0';
    const b = '21';

    test('新装：无标记、无旧数据 → installed', () {
      final r = decideInstallEvent(const LifecycleSnapshot(hasLegacyData: false), version: v, build: b);
      expect(r, isA<InstallInstalled>());
      r as InstallInstalled;
      expect((r.version, r.build), (v, b));
    });

    test('升级：无标记、有旧数据 → updated 且无 previous（不报 installed）', () {
      final r = decideInstallEvent(const LifecycleSnapshot(hasLegacyData: true), version: v, build: b);
      expect(r, isA<InstallUpdated>());
      r as InstallUpdated;
      expect((r.version, r.build), (v, b));
      expect(r.previousVersion, isNull);
      expect(r.previousBuild, isNull);
    });

    test('同版本重启：标记 = 当前 → none', () {
      final r = decideInstallEvent(
        const LifecycleSnapshot(hasLegacyData: false, markerVersion: '1.5.0+21'),
        version: v,
        build: b,
      );
      expect(r, isA<InstallNone>());
    });

    test('有标记也有旧数据、同版本 → none', () {
      final r = decideInstallEvent(
        const LifecycleSnapshot(hasLegacyData: true, markerVersion: '1.5.0+21'),
        version: v,
        build: b,
      );
      expect(r, isA<InstallNone>());
    });

    test('版本再变：标记 ≠ 当前 → updated 带 previous', () {
      final r = decideInstallEvent(
        const LifecycleSnapshot(hasLegacyData: true, markerVersion: '1.5.0+20'),
        version: '1.5.1',
        build: '22',
      );
      expect(r, isA<InstallUpdated>());
      r as InstallUpdated;
      expect((r.version, r.build), ('1.5.1', '22'));
      expect((r.previousVersion, r.previousBuild), ('1.5.0', '20'));
    });

    test('只改 build 号也算版本变化；stag 后缀原样', () {
      final r = decideInstallEvent(
        const LifecycleSnapshot(hasLegacyData: false, markerVersion: '1.5.0-stag+21'),
        version: '1.5.0-stag',
        build: '22',
      );
      r as InstallUpdated;
      expect((r.previousVersion, r.previousBuild), ('1.5.0-stag', '21'));
      expect(versionKey('1.5.0-stag', '22'), '1.5.0-stag+22');
    });
  });

  test('isLegacyDataKey（AC3 判据）', () {
    expect(isLegacyDataKey('petgo.push_permission_asked'), isTrue);
    expect(isLegacyDataKey('petgo.splash_last_shown_date'), isTrue);
    expect(isLegacyDataKey('tailtopia.analytics.anonymous_id'), isFalse);
    expect(isLegacyDataKey('tailtopia.analytics.installed_version'), isFalse);
  });

  group('state.json 与快照', () {
    late Directory base;
    File stateFile() => File('${base.path}/analytics/state.json');

    setUp(() async {
      base = await Directory.systemTemp.createTemp('lifecycle_test');
      AnonymousId.debugReset();
      LifecycleEvents.debugReset();
    });

    tearDown(() async {
      AnonymousId.debugReset();
      LifecycleEvents.debugReset();
      if (base.existsSync()) await base.delete(recursive: true);
    });

    test('有 petgo.* 键 → hasLegacyData = true；读出安装标记', () async {
      SharedPreferences.setMockInitialValues({'petgo.push_permission_asked': true});
      await Directory('${base.path}/analytics').create(recursive: true);
      stateFile().writeAsStringSync(jsonEncode({installedVersionField: '1.4.2+9'}));
      final read = await AnonymousId.load(baseDir: base);
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      expect(LifecycleEvents.debugSnapshot!.hasLegacyData, isTrue);
      expect(LifecycleEvents.debugSnapshot!.markerVersion, '1.4.2+9');
    });

    test('空 prefs、无 state.json → hasLegacyData = false、无标记', () async {
      SharedPreferences.setMockInitialValues({});
      final read = await AnonymousId.load(baseDir: base);
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      expect(LifecycleEvents.debugSnapshot!.hasLegacyData, isFalse);
      expect(LifecycleEvents.debugSnapshot!.markerVersion, isNull);
    });

    test('只有 tailtopia.analytics.* 键 → 不算旧版数据', () async {
      SharedPreferences.setMockInitialValues({'tailtopia.analytics.installed_version': 'x'});
      final read = await AnonymousId.load(baseDir: base);
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      expect(LifecycleEvents.debugSnapshot!.hasLegacyData, isFalse);
    });

    test('2.1 复审 R4：state.json 存在但读 IO 失败 → 不写回、快照未知、跳过 Installed', () async {
      // state.json 位置放一个目录 → typeSync 判为存在，readAsString 抛 FileSystemException。
      await Directory('${base.path}/analytics/state.json').create(recursive: true);
      final read = await AnonymousId.load(baseDir: base);
      expect(read.readable, isFalse);
      expect(FileSystemEntity.isDirectorySync(stateFile().path), isTrue, reason: '不得覆盖写回');
      expect(File('${stateFile().path}.tmp').existsSync(), isFalse);
      expect(await updateAnalyticsState({'x': 1}, baseDir: base), isFalse);

      SharedPreferences.setMockInitialValues({});
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      expect(LifecycleEvents.debugSnapshot, isNull);

      final events = <String>[];
      Analytics.debugCaptureSink = (e, _) => events.add(e);
      addTearDown(() => Analytics.debugCaptureSink = null);
      await LifecycleEvents.start(
        packageInfo: () async => _info('1.5.0', '21'),
        currentState: () => AppLifecycleState.resumed,
        baseDir: base,
        registerObserver: false,
      );
      expect(events, [AnalyticsEvents.applicationOpened], reason: '不报 Installed / Updated');
    });

    test('updateAnalyticsState 保留其他字段（anonymousId 不被抹掉）', () async {
      await AnonymousId.load(baseDir: base);
      final id = AnonymousId.value;
      expect(await updateAnalyticsState({installedVersionField: '1.5.0+21'}, baseDir: base), isTrue);
      final saved = jsonDecode(stateFile().readAsStringSync()) as Map<String, dynamic>;
      expect(saved['anonymousId'], id);
      expect(saved[installedVersionField], '1.5.0+21');
    });
  });

  group('LifecycleEvents.start（AC1 / AC2）', () {
    late Directory base;
    late List<MapEntry<String, Map<String, Object>?>> events;

    setUp(() async {
      base = await Directory.systemTemp.createTemp('lifecycle_start_test');
      AnonymousId.debugReset();
      LifecycleEvents.debugReset();
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
    });

    tearDown(() async {
      Analytics.debugCaptureSink = null;
      AnonymousId.debugReset();
      LifecycleEvents.debugReset();
      if (base.existsSync()) await base.delete(recursive: true);
    });

    Future<void> launch(Map<String, Object> prefs, String version, String build) async {
      LifecycleEvents.debugReset();
      SharedPreferences.setMockInitialValues(prefs);
      final read = await AnonymousId.load(baseDir: base);
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      await LifecycleEvents.start(
        packageInfo: () async => _info(version, build),
        currentState: () => AppLifecycleState.resumed,
        baseDir: base,
        registerObserver: false,
      );
    }

    Map<String, dynamic> state() =>
        jsonDecode(File('${base.path}/analytics/state.json').readAsStringSync()) as Map<String, dynamic>;

    test('新装 → Installed（先于 Opened）并写标记；同版本重启 → 只有 Opened；版本变 → Updated 带 previous', () async {
      await launch({}, '1.5.0', '21');
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationInstalled, AnalyticsEvents.applicationOpened]);
      expect(events.first.value, {'version': '1.5.0', 'build': '21'});
      expect(events.last.value, {'from_background': false});
      expect(state()[installedVersionField], '1.5.0+21');
      expect(state()['anonymousId'], AnonymousId.value);

      events.clear();
      await launch({'petgo.push_permission_asked': true}, '1.5.0', '21');
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationOpened]);

      events.clear();
      await launch({'petgo.push_permission_asked': true}, '1.5.1', '22');
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationUpdated, AnalyticsEvents.applicationOpened]);
      expect(events.first.value, {
        'version': '1.5.1',
        'build': '22',
        'previous_version': '1.5.0',
        'previous_build': '21',
      });
      expect(state()[installedVersionField], '1.5.1+22');
    });

    test('旧版升级：有 petgo.* 无标记 → 只 Updated（无 previous），不报 Installed', () async {
      await launch({'petgo.push_permission_asked': true}, '1.5.0', '21');
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationUpdated, AnalyticsEvents.applicationOpened]);
      expect(events.first.value, {'version': '1.5.0', 'build': '21'});
      expect(state()[installedVersionField], '1.5.0+21');
    });

    test('未取快照（未知）→ 不判定、不读 PackageInfo、不写标记', () async {
      var called = false;
      await LifecycleEvents.start(
        packageInfo: () async {
          called = true;
          return _info('1.5.0', '21');
        },
        currentState: () => AppLifecycleState.resumed,
        baseDir: base,
        registerObserver: false,
      );
      expect(called, isFalse);
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationOpened]);
      expect(File('${base.path}/analytics/state.json').existsSync(), isFalse);
    });

    test('PackageInfo 失败 → 吞掉，仍跟踪 Opened', () async {
      SharedPreferences.setMockInitialValues({});
      final read = await AnonymousId.load(baseDir: base);
      LifecycleEvents.takeSnapshot(await SharedPreferences.getInstance(), read);
      await LifecycleEvents.start(
        packageInfo: () async => throw StateError('boom'),
        currentState: () => AppLifecycleState.resumed,
        baseDir: base,
        registerObserver: false,
      );
      expect(events.map((e) => e.key), [AnalyticsEvents.applicationOpened]);
    });
  });

  group('Application Opened 状态机（AC4）', () {
    late List<Map<String, Object>?> opened;

    setUp(() {
      LifecycleEvents.debugReset();
      opened = [];
      Analytics.debugCaptureSink = (e, p) {
        if (e == AnalyticsEvents.applicationOpened) opened.add(p);
      };
    });

    tearDown(() {
      Analytics.debugCaptureSink = null;
      LifecycleEvents.debugReset();
    });

    Future<AppOpenTracker> startWith(AppLifecycleState? s) async {
      await LifecycleEvents.start(currentState: () => s, registerObserver: false);
      return LifecycleEvents.debugOpenTracker!;
    }

    test('冷启动 resumed → 1 条 from_background=false', () async {
      await startWith(AppLifecycleState.resumed);
      expect(opened, [
        {'from_background': false},
      ]);
    });

    test('inactive → resumed（系统弹窗 / 下拉通知栏）→ 不新增', () async {
      final t = await startWith(AppLifecycleState.resumed);
      opened.clear();
      t.didChangeAppLifecycleState(AppLifecycleState.inactive);
      t.didChangeAppLifecycleState(AppLifecycleState.resumed);
      expect(opened, isEmpty);
    });

    test('inactive → hidden → paused → resumed → 1 条 from_background=true', () async {
      final t = await startWith(AppLifecycleState.resumed);
      opened.clear();
      t.didChangeAppLifecycleState(AppLifecycleState.inactive);
      t.didChangeAppLifecycleState(AppLifecycleState.hidden);
      t.didChangeAppLifecycleState(AppLifecycleState.paused);
      t.didChangeAppLifecycleState(AppLifecycleState.hidden);
      t.didChangeAppLifecycleState(AppLifecycleState.inactive);
      t.didChangeAppLifecycleState(AppLifecycleState.resumed);
      expect(opened, [
        {'from_background': true},
      ]);
      // 再来一轮仍各报一次。
      t.didChangeAppLifecycleState(AppLifecycleState.paused);
      t.didChangeAppLifecycleState(AppLifecycleState.resumed);
      expect(opened.length, 2);
    });

    test('冷启动时非 resumed（后台拉起）、之后 resumed → 1 条 false、不重复', () async {
      final t = await startWith(null);
      expect(opened, isEmpty);
      t.didChangeAppLifecycleState(AppLifecycleState.hidden);
      t.didChangeAppLifecycleState(AppLifecycleState.paused);
      t.didChangeAppLifecycleState(AppLifecycleState.resumed);
      expect(opened, [
        {'from_background': false},
      ]);
      t.didChangeAppLifecycleState(AppLifecycleState.resumed);
      expect(opened.length, 1);
    });

    test('后台拉起后从未到前台（静默推送）→ 一条不报', () async {
      final t = await startWith(AppLifecycleState.paused);
      t.didChangeAppLifecycleState(AppLifecycleState.paused);
      t.didChangeAppLifecycleState(AppLifecycleState.detached);
      expect(opened, isEmpty);
    });

    test('start 只生效一次', () async {
      await startWith(AppLifecycleState.resumed);
      await LifecycleEvents.start(currentState: () => AppLifecycleState.resumed, registerObserver: false);
      expect(opened.length, 1);
    });
  });
}

PackageInfo _info(String version, String build) => PackageInfo(
      appName: 'TailTopia',
      packageName: 'com.tailtopia.app',
      version: version,
      buildNumber: build,
    );
