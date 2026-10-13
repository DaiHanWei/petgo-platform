import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_bootstrap.dart';

/// 首启通知权限申请（2026-08-07 产品决策，取代 F7 双时机）。
/// 关键语义：只申请一次（标记持久化）、拒绝也标记、异常不外抛。
void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    StartupPopupLedger.instance.resetForTest();
  });

  test('首启未申请过 → 申请一次并落标记', () async {
    final prefs = await AppPrefs.create();
    var calls = 0;
    final granted = await PushPermissionBootstrap.requestOnFirstLaunch(
      prefs: prefs,
      request: () async {
        calls++;
        return true;
      },
    );
    expect(granted, isTrue);
    expect(calls, 1);
    expect(prefs.pushPermissionAsked, isTrue);
  });

  test('已申请过 → 不再弹窗（第二次启动空转）', () async {
    final prefs = await AppPrefs.create();
    await prefs.setPushPermissionAsked(true);
    var calls = 0;
    await PushPermissionBootstrap.requestOnFirstLaunch(
      prefs: prefs,
      request: () async {
        calls++;
        return true;
      },
    );
    expect(calls, 0, reason: '拒绝后不再自动打扰，改由设置页开关兜底');
  });

  test('用户拒绝 → 同样落标记（不再自动弹）', () async {
    final prefs = await AppPrefs.create();
    final granted = await PushPermissionBootstrap.requestOnFirstLaunch(
      prefs: prefs,
      request: () async => false,
    );
    expect(granted, isFalse);
    expect(prefs.pushPermissionAsked, isTrue);
  });

  test('申请抛异常 → 吞掉返回 false（绝不拖垮启动）', () async {
    final prefs = await AppPrefs.create();
    final granted = await PushPermissionBootstrap.requestOnFirstLaunch(
      prefs: prefs,
      request: () async => throw StateError('plugin missing'),
    );
    expect(granted, isFalse);
  });

  group('V1.3.4 Story 5.3 · 会弹才登记本次冷启动功能弹窗', () {
    final ledger = StartupPopupLedger.instance;

    test('willShowSystemPrompt → true：登记 pushPermission，request 被调一次', () async {
      final prefs = await AppPrefs.create();
      var calls = 0;
      final granted = await PushPermissionBootstrap.requestOnFirstLaunch(
        prefs: prefs,
        willShowSystemPrompt: () async => true,
        request: () async {
          calls++;
          expect(ledger.recorded, contains(StartupPopupKind.pushPermission), reason: '请求之前就登记');
          return true;
        },
      );
      expect(granted, isTrue);
      expect(calls, 1);
      expect(ledger.recorded, {StartupPopupKind.pushPermission});
    });

    test('willShowSystemPrompt → false：不登记，request 仍被调', () async {
      final prefs = await AppPrefs.create();
      var calls = 0;
      await PushPermissionBootstrap.requestOnFirstLaunch(
        prefs: prefs,
        willShowSystemPrompt: () async => false,
        request: () async {
          calls++;
          return true;
        },
      );
      expect(calls, 1);
      expect(ledger.hasAny, isFalse);
      expect(prefs.pushPermissionAsked, isTrue);
    });

    test('willShowSystemPrompt 抛异常：不登记，request 仍被调，返回值语义不变', () async {
      final prefs = await AppPrefs.create();
      var calls = 0;
      final granted = await PushPermissionBootstrap.requestOnFirstLaunch(
        prefs: prefs,
        willShowSystemPrompt: () async => throw StateError('plugin missing'),
        request: () async {
          calls++;
          return true;
        },
      );
      expect(calls, 1, reason: '判定异常不得落进外层 catch 而跳过 request');
      expect(granted, isTrue);
      expect(prefs.pushPermissionAsked, isTrue);
      expect(ledger.hasAny, isFalse);
    });

    test('已问过：判定函数零调用、不登记', () async {
      final prefs = await AppPrefs.create();
      await prefs.setPushPermissionAsked(true);
      var judged = 0;
      await PushPermissionBootstrap.requestOnFirstLaunch(
        prefs: prefs,
        willShowSystemPrompt: () async {
          judged++;
          return true;
        },
        request: () async => true,
      );
      expect(judged, 0);
      expect(ledger.hasAny, isFalse);
    });
  });
}
