import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';

/// Story 8.2：用户侧三触发点。
///
/// 这里钉的**全是护栏**（AD-14），不是"功能能不能跑"：
/// 每条护栏错了都不会报错、不会崩，只会让 FR-85 静默地失去意义 ——
/// 四键混用 → 一个触发点用掉全部机会；存量迁移成"已触发" → 对老用户完全失效；
/// 前置闸门消耗标记 → 机会白扔；上报 native_dialog → 实现违背规格却无人知。
void main() {
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<AppPrefs> prefs() => AppPrefs.create();

  List<(String, Map<String, Object>?)> record() {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    return seen;
  }

  group('AC1 · 每个触发点各一次', () {
    test('未触发过 + 通知关闭 → 应提示', () async {
      final p = await prefs();
      for (final point in PushTriggerPoint.values) {
        expect(
          await PushPermissionPrompt.shouldPrompt(point,
              prefs: p, isGranted: () async => false),
          isTrue,
          reason: '$point 首次应提示',
        );
      }
    });

    test('已提示过 → 不再提示', () async {
      final p = await prefs();
      await PushPermissionPrompt.markPrompted(PushTriggerPoint.firstConsult, prefs: p);

      expect(
        await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.firstConsult,
            prefs: p, isGranted: () async => false),
        isFalse,
      );
    });
  });

  group('AC2 · 四键物理隔离（AD-14 Rule 2）', () {
    /// 🛡 混用一个键 = 一个触发点用掉全部机会，FR-85 直接退化成"只提醒一次"。
    test('用掉一个触发点不影响其余两个', () async {
      final p = await prefs();
      await PushPermissionPrompt.markPrompted(PushTriggerPoint.firstConsult, prefs: p);

      expect(
          await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.profileCreated,
              prefs: p, isGranted: () async => false),
          isTrue);
      expect(
          await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.notificationCenter,
              prefs: p, isGranted: () async => false),
          isTrue);
    });

    /// 🛡 三个键必须彼此不同、且都不等于既有的 push_permission_asked。
    test('三个键互不相同，且都不是既有的 petgo.push_permission_asked', () {
      final keys = PushTriggerPoint.values.map(PushPermissionPrompt.prefsKeyOf).toList();
      expect(keys.toSet(), hasLength(3), reason: '三个键必须互不相同');
      expect(keys, isNot(contains('petgo.push_permission_asked')));
    });

    /// 🛡 AD-14 Rule 3：既有键只读不写、不迁移不删。
    test('不写既有的 petgo.push_permission_asked', () async {
      final p = await prefs();
      await p.setPushPermissionAsked(true); // 模拟第二代首启已置位

      for (final point in PushTriggerPoint.values) {
        await PushPermissionPrompt.markPrompted(point, prefs: p);
      }

      // 值仍是我们自己设的那个，没被本 story 的逻辑动过
      expect(p.pushPermissionAsked, isTrue);
    });
  });

  group('AC2 · 存量迁移：一律从「未触发」开始（AD-14 Rule 3）', () {
    /// 🔴 这条是本 story 最要紧的护栏。
    /// 若把旧标记迁移成"三个点都已触发"，FR-85 对**存量用户完全失效** ——
    /// 而"大量关掉通知的存量用户再也不会被提醒"正是这条 FR 要解决的问题。
    test('存量用户（push_permission_asked=true）三个点仍全部可触发', () async {
      final p = await prefs();
      await p.setPushPermissionAsked(true); // 存量：第二代首启已问过

      for (final point in PushTriggerPoint.values) {
        expect(
          await PushPermissionPrompt.shouldPrompt(point,
              prefs: p, isGranted: () async => false),
          isTrue,
          reason: '$point 对存量用户必须仍有一次机会',
        );
      }
    });
  });

  group('AC3 · 前置闸门：通知已开则跳过，且不消耗标记（AD-14 Rule 5）', () {
    test('通知已开 → 不提示', () async {
      final p = await prefs();
      expect(
        await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.firstConsult,
            prefs: p, isGranted: () async => true),
        isFalse,
      );
    });

    /// 🔴 **不消耗标记**：用户今天开着、下个月关掉，这个触发点仍应有一次机会。
    /// 顺手置位就等于把机会白扔了，而本 FR 的全部目的就是多给几次机会。
    test('通知已开时跳过后，关掉通知仍能提示（标记未被消耗）', () async {
      final p = await prefs();

      // 第一次：通知开着 → 跳过
      expect(
          await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.firstConsult,
              prefs: p, isGranted: () async => true),
          isFalse);

      // 后来用户关掉了通知 → 该触发点仍应有机会
      expect(
          await PushPermissionPrompt.shouldPrompt(PushTriggerPoint.firstConsult,
              prefs: p, isGranted: () async => false),
          isTrue,
          reason: '前置闸门跳过不得消耗标记');
    });
  });

  group('AC7 · 埋点', () {
    test('曝光事件带 trigger_point 与 prompt_type', () async {
      final seen = record();
      await PushPermissionPrompt.reportShown(PushTriggerPoint.notificationCenter);

      expect(seen, hasLength(1));
      expect(seen.first.$1, 'push_permission_prompt_shown');
      expect(seen.first.$2?['trigger_point'], 'notification_center');
      expect(seen.first.$2?['prompt_type'], 'in_app_guide');
    });

    test('响应事件带 trigger_point / prompt_type / result', () async {
      final seen = record();
      await PushPermissionPrompt.reportResponded(
          PushTriggerPoint.firstConsult, PushPromptResult.settingsOpened);

      expect(seen, hasLength(1));
      expect(seen.first.$1, 'push_permission_responded');
      expect(seen.first.$2?['trigger_point'], 'first_consult');
      expect(seen.first.$2?['result'], 'settings_opened');
    });

    /// 🛡 AD-14 Rule 6 的可线上校验护栏：任何触发点都不得上报「原生弹窗」形态。
    /// 原生弹窗机会在首启已被第二代消耗 —— 那条分支是死的，出现即实现违背规格。
    test('prompt_type 恒为 in_app_guide，绝不出现 native_dialog', () async {
      final seen = record();
      for (final point in PushTriggerPoint.values) {
        await PushPermissionPrompt.reportShown(point);
        await PushPermissionPrompt.reportResponded(point, PushPromptResult.dismissed);
      }

      expect(seen, isNotEmpty);
      for (final e in seen) {
        expect(e.$2?['prompt_type'], 'in_app_guide');
        expect(e.$2?['prompt_type'], isNot('native_dialog'));
      }
    });

    /// trigger_point 的取值必须与埋点清单词表一致 —— 它是本 FR「砍哪个触发点」的唯一依据，
    /// 值写错等于那一档数据白收。
    test('trigger_point 取值与清单词表一致', () {
      expect(PushPermissionPrompt.analyticsValueOf(PushTriggerPoint.firstConsult),
          'first_consult');
      expect(PushPermissionPrompt.analyticsValueOf(PushTriggerPoint.profileCreated),
          'profile_created');
      expect(PushPermissionPrompt.analyticsValueOf(PushTriggerPoint.notificationCenter),
          'notification_center');
    });
  });

  group('AC5 · 与手机号软引导串行（AD-14 Rule 7）', () {
    /// 🛡 先推送权限、后手机号，且**不叠同屏**（两个索取型弹层同屏会双双掉转化）。
    /// 7-2 尚未开工 —— 本 story 建统一排队入口，7-2 只需注册钩子。
    test('命中推送权限时：先跑推送引导，再跑手机号钩子', () async {
      final p = await prefs();
      final order = <String>[];
      PushPermissionPrompt.phonePromptHook = () async => order.add('phone');

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
      PushPermissionPrompt.phonePromptHook = null;
    });

    /// 🔴 **建档完成这个点不问手机号**（产品 2026-08-26）。
    ///
    /// 用户刚填完一整张宠物档案表单，紧接着又被要一次个人手机号 —— 连续两次索取信息，
    /// 观感是「这 App 一直在要东西」。手机号的正经时机是「注册第 3 天首次打开」（X-21），
    /// 本来就不依赖建档这个点。
    ///
    /// ⚠️ 只关这一个触发点：其余触发点仍要跑（见下一条），否则等于把手机号采集整条关掉。
    test('建档完成这个触发点：推送引导照跑，手机号钩子不跑', () async {
      final p = await prefs();
      final order = <String>[];
      PushPermissionPrompt.phonePromptHook = () async => order.add('phone');

      await PushPermissionPrompt.runForTrigger(
        PushTriggerPoint.profileCreated,
        prefs: p,
        isGranted: () async => false,
        showGuide: () async {
          order.add('push');
          return PushPromptResult.dismissed;
        },
      );

      expect(order, ['push'],
          reason: '🔴 建档后又弹手机号 —— 两次索取叠在一起，正是产品要去掉的观感');
      PushPermissionPrompt.phonePromptHook = null;
    });

    /// 推送权限这一侧被跳过（通知已开）时，手机号钩子**仍要跑** ——
    /// 否则"通知已开的用户永远不会被问手机号"，那是无意的耦合。
    test('推送权限被跳过时手机号钩子仍会跑', () async {
      final p = await prefs();
      final order = <String>[];
      PushPermissionPrompt.phonePromptHook = () async => order.add('phone');

      await PushPermissionPrompt.runForTrigger(
        PushTriggerPoint.firstConsult,
        prefs: p,
        isGranted: () async => true, // 通知已开 → 跳过推送引导
        showGuide: () async {
          order.add('push');
          return PushPromptResult.dismissed;
        },
      );

      expect(order, ['phone']);
      PushPermissionPrompt.phonePromptHook = null;
    });
  });

  group('运行一次即置位（防重复弹）', () {
    test('runForTrigger 展示后置位标记，同一触发点不再展示', () async {
      final p = await prefs();
      var shown = 0;

      Future<void> run() => PushPermissionPrompt.runForTrigger(
            PushTriggerPoint.profileCreated,
            prefs: p,
            isGranted: () async => false,
            showGuide: () async {
              shown++;
              return PushPromptResult.dismissed;
            },
          );

      await run();
      await run();

      expect(shown, 1);
    });

    /// 引导抛异常时不向上抛 —— 它挂在建档庆祝页与问诊完成之后，
    /// 绝不能因为一个提示把主流程搞崩。
    test('引导抛异常 → 不向上抛', () async {
      final p = await prefs();
      await expectLater(
        PushPermissionPrompt.runForTrigger(
          PushTriggerPoint.firstConsult,
          prefs: p,
          isGranted: () async => false,
          showGuide: () async => throw StateError('boom'),
        ),
        completes,
      );
    });
  });
}
