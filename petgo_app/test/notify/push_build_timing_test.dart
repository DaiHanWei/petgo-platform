import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';
import 'package:tailtopia/features/notify/presentation/push_permission_guide_flow.dart';
import 'package:tailtopia/features/profile/presentation/profile_created_celebration_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';

/// 触发点 2「建档后」的**位置锚点**：庆祝页主 CTA「开始探索」回调之后、**进首页之前**。
///
/// 🔴 2026-08-20（V1.1.6 Story 8.2）本文件整体改写。原版锁的是 Story 6.4 的
/// `PushPermissionGate.maybeRequestAfterProfileCreated`，而那条路早已是死代码 ——
/// 它的门 `!alreadyAsked` 被第二代「首启即申请」在首次冷启动就置位了。
///
/// ⚠️ 更要紧的是：原版**自己搭了一个与 app_router "同构"的闭包**，所以真实路由改了之后
/// 它照样绿 —— 守的是一个已经不存在的结构。改写后仍沿用"同构"这个做法（真实路由难以在
/// widget test 里整体加载），但**锁的是新模型**，且断言换成"引导被调用过 + 最终落到首页"。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() {
    Analytics.debugCaptureSink = null;
    PushPermissionPrompt.phonePromptHook = null;
  });

  Future<({int shown, List<String> order})> pumpAndStart(
    WidgetTester tester, {
    bool notificationsGranted = false,
  }) async {
    // ⚠️ 视口要够高：说明抽屉有三条好处说明，默认 800×600 下「不，谢谢」在屏幕外，
    //    点不到 → 抽屉不关 → 回调永远挂着（表现是 order 为空，极易误判成"逻辑没跑"）。
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final prefs = await AppPrefs.create();
    var shown = 0;
    final order = <String>[];

    final router = GoRouter(
      initialLocation: '/c',
      routes: [
        GoRoute(
          path: '/c',
          builder: (c, s) => ProfileCreatedCelebrationPage(
            petName: 'Momo',
            avatarUrl: null,
            onStartExplore: () async {
              // 与 app_router /profile/created 同构：建档时机锚点 = CTA 后、go home 前。
              await maybeShowPushPermissionGuide(
                c,
                PushTriggerPoint.profileCreated,
                prefs: prefs,
                isGranted: () async => notificationsGranted,
                openSettings: () async {
                  order.add('settings');
                  return true;
                },
              );
              order.add('go-home');
              if (c.mounted) c.go('/home');
            },
          ),
        ),
        GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('HOME'))),
      ],
    );

    Analytics.debugCaptureSink = (e, p) {
      if (e == 'push_permission_prompt_shown') shown++;
    };

    await tester.pumpWidget(ProviderScope(
      overrides: [
        shareServiceProvider.overrideWithValue((_, {sharePositionOrigin}) async {}),
      ],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('celebrationStartExplore')));
    await tester.pumpAndSettle();

    // 说明抽屉弹出时点「不，谢谢」关掉（本测试只锁时序，不测抽屉内容）
    final dismiss = find.byKey(const ValueKey('pushPromptDismiss'));
    if (dismiss.evaluate().isNotEmpty) {
      await tester.tap(dismiss);
      await tester.pumpAndSettle();
    }
    // 抽屉关闭后回调里还有几段 await（上报 → 手机号钩子 → go home），
    // 一次 pumpAndSettle 不保证全部排完；有界等到 go-home 出现为止。
    for (var i = 0; i < 20 && !order.contains('go-home'); i++) {
      await tester.pumpAndSettle();
    }
    return (shown: shown, order: order);
  }

  testWidgets('通知关闭：「开始探索」→ 引导曝光 → 才进首页（引导在 go home 之前）', (tester) async {
    final r = await pumpAndStart(tester);

    expect(r.shown, 1, reason: '建档触发点应曝光一次');
    expect(r.order.last, 'go-home', reason: 'await 保证引导跑完才进首页');
    expect(find.text('HOME'), findsOneWidget);
  });

  /// 🛡 前置闸门（AD-14 Rule 5）：通知已开 → 不打扰，且仍正常进首页。
  testWidgets('通知已开：不曝光引导，仍正常进首页', (tester) async {
    final r = await pumpAndStart(tester, notificationsGranted: true);

    expect(r.shown, 0);
    expect(find.text('HOME'), findsOneWidget);
  });

  /// 🛡 存量用户（第二代已置位 `push_permission_asked`）**仍应拿到这一次机会** ——
  /// 迁移成「已触发」会让 FR-85 对存量用户完全失效，而那正是这条 FR 要解决的问题。
  testWidgets('存量用户（push_permission_asked=true）仍会曝光引导', (tester) async {
    SharedPreferences.setMockInitialValues({'petgo.push_permission_asked': true});
    final r = await pumpAndStart(tester);

    expect(r.shown, 1);
    expect(find.text('HOME'), findsOneWidget);
  });
}
