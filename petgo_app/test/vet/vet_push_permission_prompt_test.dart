import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_online_status.dart';
import 'package:tailtopia/features/vet/domain/vet_push_permission_prompt.dart';

/// Story 8.3：触发点 5（兽医切换为在线）。
///
/// 🔴 **它与用户侧三点（8-2）的模型完全不同**，抄过来就全错（AD-14 Rule 4）：
/// **不限次数 · 无标记位 · 绝不硬拦截**。
///
/// 为什么兽医值得每次都提醒：漏单是真金白银（错过问诊 = 没有收入），
/// 而"切换为在线"这个动作本身就说明他此刻想接单 —— 每次提醒是成比例的。
/// 用户侧反复提醒只会招致反感，所以那边严格各一次。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({this.serverAccepts = true})
      : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  /// 服务端权威态：模拟"请求上线但服务端拒了"的情形。
  final bool serverAccepts;
  int setOnlineCalls = 0;

  @override
  Future<bool> readOnlineStatus() async => false;

  @override
  Future<bool> setOnline(bool next) async {
    setOnlineCalls++;
    return next && serverAccepts;
  }
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() {
    Analytics.debugCaptureSink = null;
    VetPushPermissionPrompt.showGuideHook = null;
  });

  ({
    ProviderContainer container,
    List<(String, Map<String, Object>?)> events,
    List<String> guideCalls,
  }) setup({
    bool granted = false,
    bool serverAccepts = true,
    PushPromptResult hookResult = PushPromptResult.settingsOpened,
    bool registerHook = true,
  }) {
    final events = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));

    final guideCalls = <String>[];
    if (registerHook) {
      VetPushPermissionPrompt.showGuideHook = () async {
        guideCalls.add('shown');
        return hookResult;
      };
    }
    VetPushPermissionPrompt.isGrantedOverride = () async => granted;

    final container = ProviderContainer(overrides: [
      vetRepositoryProvider.overrideWithValue(_FakeVetRepository(serverAccepts: serverAccepts)),
    ]);
    addTearDown(() {
      container.dispose();
      VetPushPermissionPrompt.isGrantedOverride = null;
    });
    return (container: container, events: events, guideCalls: guideCalls);
  }

  group('AC1 · 每次切为在线都提醒（不限次数）', () {
    test('通知关闭 + 切为在线 → 提醒一次，埋点带 trigger_point=vet_online', () async {
      final s = setup(granted: false);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      expect(s.guideCalls, hasLength(1));
      final shown = s.events.where((e) => e.$1 == 'push_permission_prompt_shown').toList();
      expect(shown, hasLength(1));
      expect(shown.first.$2?['trigger_point'], 'vet_online');
      expect(shown.first.$2?['prompt_type'], 'in_app_guide');
    });

    /// 🔴 **本 story 与 8-2 最核心的差异**：连续两次切为在线 → 提醒两次。
    /// 用户侧那套"各一次"的标记位逻辑在这里是**违规**的。
    test('连续两次切为在线 → 提醒两次', () async {
      final s = setup(granted: false);
      final n = s.container.read(vetOnlineStatusProvider.notifier);

      await n.toggle(true);
      await Future<void>.delayed(Duration.zero);
      await n.toggle(false); // 先下线
      await Future<void>.delayed(Duration.zero);
      await n.toggle(true); // 再上线
      await Future<void>.delayed(Duration.zero);

      expect(s.guideCalls, hasLength(2), reason: '不限次数：每次切为在线都提醒');
    });

    test('切为离线 → 不提醒（只有切为在线才判）', () async {
      final s = setup(granted: false);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(false);
      await Future<void>.delayed(Duration.zero);

      expect(s.guideCalls, isEmpty);
    });
  });

  group('AC2 · 🛡 绝不硬拦截（营收红线）', () {
    /// 🔴 把「通知已开」做成上线前置条件，等于让一部分兽医**完全无法接单**。
    /// 提醒是提醒，拦截是拦截 —— 这里只做前者。
    test('通知关闭时切为在线：仍然成功', () async {
      final s = setup(granted: false);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      expect(s.container.read(vetOnlineStatusProvider), isTrue, reason: '切换必须照常成功');
    });

    /// 引导本身抛异常也不能影响在线态 —— 提示失败不该让兽医上不了线。
    test('引导抛异常：在线态不受影响', () async {
      final s = setup(granted: false, registerHook: false);
      VetPushPermissionPrompt.showGuideHook = () async => throw StateError('boom');

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      expect(s.container.read(vetOnlineStatusProvider), isTrue);
    });

    /// 服务端拒了上线请求 → 兽医其实没在线 → 不该提醒（他没有"想接单却收不到"的处境）。
    test('服务端权威态为离线 → 不提醒', () async {
      final s = setup(granted: false, serverAccepts: false);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      expect(s.container.read(vetOnlineStatusProvider), isFalse);
      expect(s.guideCalls, isEmpty);
    });
  });

  group('AC3 · 前置闸门', () {
    test('通知已开 → 不提醒、不上报', () async {
      final s = setup(granted: true);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      expect(s.guideCalls, isEmpty);
      expect(s.events.where((e) => e.$1.startsWith('push_permission_')), isEmpty);
    });
  });

  group('AC1 · 🛡 无标记位（AD-14 Rule 4）', () {
    /// 🔴 **本 story 结束后不应多出任何 prefs 键。**
    /// 「顺手加个键防止提醒太频繁」是极自然的冲动，而它正好违规 ——
    /// 兽医端要的就是每次都提醒。这条用例专门守这个。
    test('切换在线多次后，SharedPreferences 里没有新增任何键', () async {
      SharedPreferences.setMockInitialValues({'petgo.locale': 'id'});
      final before = (await SharedPreferences.getInstance()).getKeys().toSet();

      final s = setup(granted: false);
      final n = s.container.read(vetOnlineStatusProvider.notifier);
      for (var i = 0; i < 3; i++) {
        await n.toggle(true);
        await Future<void>.delayed(Duration.zero);
        await n.toggle(false);
        await Future<void>.delayed(Duration.zero);
      }

      final after = (await SharedPreferences.getInstance()).getKeys().toSet();
      expect(after.difference(before), isEmpty,
          reason: '兽医端触发点无标记位：不得写入任何 prefs 键');
    });
  });

  group('AC5 · 埋点', () {
    test('响应事件带 trigger_point=vet_online 与 result', () async {
      final s = setup(granted: false, hookResult: PushPromptResult.settingsOpened);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      final responded = s.events.where((e) => e.$1 == 'push_permission_responded').toList();
      expect(responded, hasLength(1));
      expect(responded.first.$2?['trigger_point'], 'vet_online');
      expect(responded.first.$2?['result'], 'settings_opened');
    });

    /// 🛡 AD-14 Rule 6：任何触发点都不得上报「原生弹窗」形态。
    test('绝不上报 native_dialog', () async {
      final s = setup(granted: false);

      await s.container.read(vetOnlineStatusProvider.notifier).toggle(true);
      await Future<void>.delayed(Duration.zero);

      final pushEvents = s.events.where((e) => e.$1.startsWith('push_permission_'));
      expect(pushEvents, isNotEmpty);
      for (final e in pushEvents) {
        expect(e.$2?['prompt_type'], 'in_app_guide');
      }
    });
  });
}
