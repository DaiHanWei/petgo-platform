import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/me/domain/phone_soft_prompt.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';

/// Story 7.2 · 软引导的编排（判定 → 展示 → 置位 → 埋点）。
///
/// 时机的**算法**本身在 `phone_prompt_timing_test.dart` 里钉；
/// 本文件钉的是**围绕它的那圈护栏**：只问一次、标记与推送权限四键隔离、
/// 展示即用掉、以及与推送权限的先后顺序（AD-14 Rule 7）。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() {
    Analytics.debugCaptureSink = null;
    PushPermissionPrompt.phonePromptHook = null;
  });

  Future<AppPrefs> prefs() => AppPrefs.create();

  List<(String, Map<String, Object>?)> record() {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    return seen;
  }

  DateTime wib(int y, int m, int d, [int h = 12]) =>
      DateTime.utc(y, m, d, h).subtract(const Duration(hours: 7));

  group('只问一次', () {
    test('第 3 天到了 → 展示一次并置位；再调不展示', () async {
      final p = await prefs();
      var shown = 0;

      Future<void> run() => PhoneSoftPrompt.maybeShow(
            prefs: p,
            registeredAt: wib(2026, 8, 1),
            hasPhone: false,
            now: wib(2026, 8, 5),
            showSheet: () async {
              shown++;
              return false; // 用户跳过
            },
          );

      await run();
      await run();

      expect(shown, 1);
    });

    /// 🛡 **展示即用掉**（不等用户作答）：用户划走浮层后，下次打开不该再弹 ——
    /// 否则就变成「反复索要个人信息」，而 PRD 明确要避免这一点。
    test('用户跳过（未保存）也算用掉这次机会', () async {
      final p = await prefs();
      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: () async => false,
      );

      expect(p.getBool(AppPrefs.kPhonePromptShown), isTrue);
    });
  });

  group('🛡 四键物理隔离（AD-14 Rule 2）', () {
    test('手机号的键与推送权限三个键互不相同', () {
      final pushKeys = {
        AppPrefs.kPushPromptFirstConsult,
        AppPrefs.kPushPromptProfileCreated,
        AppPrefs.kPushPromptNotificationCenter,
      };
      expect(pushKeys.contains(AppPrefs.kPhonePromptShown), isFalse);
      expect(pushKeys, hasLength(3));
    });

    test('用掉手机号这次机会，不影响推送权限三个触发点', () async {
      final p = await prefs();
      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: () async => false,
      );

      for (final point in PushTriggerPoint.values) {
        expect(
          await PushPermissionPrompt.shouldPrompt(point,
              prefs: p, isGranted: () async => false),
          isTrue,
          reason: '$point 不该被手机号软引导用掉',
        );
      }
    });

    /// 🛡 AD-14 Rule 3：既有 `push_permission_asked` 归第二代，本 story 只读不写。
    test('不写既有的 petgo.push_permission_asked', () async {
      final p = await prefs();
      await p.setPushPermissionAsked(true);
      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: () async => false,
      );
      expect(p.pushPermissionAsked, isTrue);
    });
  });

  group('埋点', () {
    test('用户填了 → phone_prompt_responded(action=submitted)', () async {
      final p = await prefs();
      final seen = record();

      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: () async => true, // 保存成功
      );

      final ev = seen.where((e) => e.$1 == 'phone_prompt_responded').toList();
      expect(ev, hasLength(1));
      expect(ev.first.$2?['action'], 'submitted');
    });

    test('用户跳过 → action=skipped', () async {
      final p = await prefs();
      final seen = record();

      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: () async => false,
      );

      final ev = seen.where((e) => e.$1 == 'phone_prompt_responded').toList();
      expect(ev, hasLength(1));
      expect(ev.first.$2?['action'], 'skipped');
    });

    test('时机未到 → 一个事件都不报', () async {
      final p = await prefs();
      final seen = record();

      await PhoneSoftPrompt.maybeShow(
        prefs: p,
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 2), // 第 2 天
        showSheet: () async => true,
      );

      expect(seen, isEmpty);
    });
  });

  group('AD-14 Rule 7 · 与推送权限串行', () {
    /// 🛡 注册到 8-2 建好的那个排队入口即可 —— **不要另写一套判定**。
    /// 两处各判一次的话，「同时命中」的先后就取决于两段代码的偶然调用顺序。
    test('注册后，推送权限跑完才轮到手机号', () async {
      final p = await prefs();
      final order = <String>[];

      PhoneSoftPrompt.register(
        prefs: () async => p,
        registeredAt: () async => wib(2026, 8, 1),
        hasPhone: () async => false,
        now: () => wib(2026, 8, 5),
        showSheet: () async {
          order.add('phone');
          return false;
        },
      );

      await PushPermissionPrompt.runForTrigger(
        PushTriggerPoint.firstConsult,
        prefs: p,
        isGranted: () async => false,
        showGuide: () async {
          order.add('push');
          return PushPromptResult.dismissed;
        },
      );

      expect(order, ['push', 'phone']);
    });
  });

  group('绝不拖垮调用方', () {
    test('浮层抛异常 → 不向上抛', () async {
      final p = await prefs();
      await expectLater(
        PhoneSoftPrompt.maybeShow(
          prefs: p,
          registeredAt: wib(2026, 8, 1),
          hasPhone: false,
          now: wib(2026, 8, 5),
          showSheet: () async => throw StateError('boom'),
        ),
        completes,
      );
    });
  });
}
