import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/core/storage/prefs.dart';
import 'package:petgo/features/notify/domain/push_permission_gate.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Story 6.4 J4：推送权限时机门控（首启不触发 / 首次问诊完成且未问过才触发 / 已问过不再弹）+ 持久化。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('纯门控：首次问诊完成且未问过 → 触发；否则不触发', () {
    expect(PushPermissionGate.shouldRequest(firstConsultDone: true, alreadyAsked: false), isTrue);
    // App 首启（未完成问诊）不触发
    expect(PushPermissionGate.shouldRequest(firstConsultDone: false, alreadyAsked: false), isFalse);
    // 已问过不再弹
    expect(PushPermissionGate.shouldRequest(firstConsultDone: true, alreadyAsked: true), isFalse);
  });

  test('首次问诊完成 → 请求一次并持久化 asked；再次调用被门控跳过', () async {
    final prefs = await AppPrefs.create();
    var requestCount = 0;
    final gate = PushPermissionGate(
      prefs: prefs,
      requestSystemPermission: () async {
        requestCount++;
        return false; // 模拟用户拒绝
      },
    );

    final first = await gate.maybeRequestAfterFirstConsult(firstConsultDone: true);
    expect(first, isTrue);
    expect(requestCount, 1);
    expect(prefs.pushPermissionAsked, isTrue); // 拒绝也置已问过

    // 拒绝后不再主动弹
    final second = await gate.maybeRequestAfterFirstConsult(firstConsultDone: true);
    expect(second, isFalse);
    expect(requestCount, 1);
  });

  test('未完成首次问诊 → 不请求', () async {
    final prefs = await AppPrefs.create();
    var requestCount = 0;
    final gate = PushPermissionGate(
      prefs: prefs,
      requestSystemPermission: () async {
        requestCount++;
        return true;
      },
    );
    expect(await gate.maybeRequestAfterFirstConsult(firstConsultDone: false), isFalse);
    expect(requestCount, 0);
    expect(prefs.pushPermissionAsked, isFalse);
  });
}
