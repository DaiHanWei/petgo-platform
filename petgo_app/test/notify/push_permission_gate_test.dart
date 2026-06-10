import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_gate.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Story 6.4 J4：推送权限双时机门控（首启不触发 / 首次问诊完成或建档完成且未问过才触发 / 已问过不再弹）+ 持久化。
/// 🔄 PRD V1.0.0 修订（F7 · 2026-06-08）：单时机→双时机取最早，建档时机加 neverConsulted 限定。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('纯门控：首次问诊完成且未问过 → 触发；否则不触发', () {
    expect(PushPermissionGate.shouldRequest(firstConsultDone: true, alreadyAsked: false), isTrue);
    // App 首启（无任一时机）不触发
    expect(PushPermissionGate.shouldRequest(alreadyAsked: false), isFalse);
    // 已问过不再弹
    expect(PushPermissionGate.shouldRequest(firstConsultDone: true, alreadyAsked: true), isFalse);
  });

  test('🔄 双时机：建档完成且从未问诊 → 触发；已问诊用户建档不重复触发', () {
    // 建档完成且从未问诊 → 触发（生日/纪念日提醒时机）
    expect(
        PushPermissionGate.shouldRequest(
            profileCreated: true, neverConsulted: true, alreadyAsked: false),
        isTrue);
    // 建档但已问诊（neverConsulted=false）→ 不由建档时机触发（问诊时机已负责）
    expect(
        PushPermissionGate.shouldRequest(
            profileCreated: true, neverConsulted: false, alreadyAsked: false),
        isFalse);
    // 建档触发但已问过权限 → 不再弹
    expect(
        PushPermissionGate.shouldRequest(
            profileCreated: true, neverConsulted: true, alreadyAsked: true),
        isFalse);
  });

  test('🔄 建档完成（从未问诊）→ 请求一次并持久化；再次（含后续问诊）被门控跳过（仅弹一次）', () async {
    final prefs = await AppPrefs.create();
    var requestCount = 0;
    final gate = PushPermissionGate(
      prefs: prefs,
      requestSystemPermission: () async {
        requestCount++;
        return true;
      },
    );
    // 建档先触发
    expect(await gate.maybeRequestAfterProfileCreated(neverConsulted: true), isTrue);
    expect(requestCount, 1);
    expect(prefs.pushPermissionAsked, isTrue);
    // 之后即使完成首次问诊也不再弹（取最早、仅一次）
    expect(await gate.maybeRequestAfterFirstConsult(firstConsultDone: true), isFalse);
    expect(requestCount, 1);
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
