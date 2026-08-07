import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_bootstrap.dart';

/// 首启通知权限申请（2026-08-07 产品决策，取代 F7 双时机）。
/// 关键语义：只申请一次（标记持久化）、拒绝也标记、异常不外抛。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

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
}
