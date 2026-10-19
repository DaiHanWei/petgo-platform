import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/domain/growth_popup_trigger.dart';
import 'package:tailtopia/features/growth/domain/growth_popups.dart';
import 'package:tailtopia/features/growth/presentation/growth_popup_coordinator.dart';
import 'package:tailtopia/features/growth/presentation/level_up_celebration.dart';

/// V1.4.0 ① Story 2.3 · AD-11：成长弹窗协调器（L0，依赖全部注入）。
const _user = AuthState(status: AuthStatus.authenticated, role: 'USER');
const _vet = AuthState(status: AuthStatus.authenticated, role: 'VET');

const _cel = GrowthCelebration(fromLevel: 6, toLevel: 7, tierUp: false, toTier: GrowthTier.buddy);
const _bf = GrowthBackfill(level: 6, tier: GrowthTier.buddy, activeDays: 60, milestoneCount: 13, milestoneExp: 95, total: 275);

class _Env {
  _Env({this.popups = const GrowthPopups(celebration: _cel, backfill: _bf), this.auth = _user});

  GrowthPopups? popups;
  AuthState auth;
  bool resumed = true;
  bool topIsPopup = false;
  String path = '/profile';
  int fetches = 0;
  final List<int> celebrationAcks = [];
  int backfillAcks = 0;
  final List<String> shown = [];
  final List<bool> ledgerHadAtShow = [];
  GrowthPopupOutcome? outcome = GrowthPopupOutcome.close;
  int levelPageOpens = 0;
  final events = <(String, Map<String, Object>?)>[];
  final navKey = GlobalKey<NavigatorState>();
  List<GrowthLevelDef>? levels;
  List<GrowthLevelDef>? levelsAtShow;

  GrowthPopupCoordinator build() => GrowthPopupCoordinator(
        fetch: () async {
          fetches++;
          return popups;
        },
        ackCelebration: (to) async => celebrationAcks.add(to),
        ackBackfill: () async => backfillAcks++,
        auth: () => auth,
        rootContext: () => navKey.currentContext,
        topIsPopup: () => topIsPopup,
        isResumed: () => resumed,
        currentPath: () => path,
        levels: levels == null ? null : () async => levels!,
        showCelebration: (ctx, c, a, lv) async {
          levelsAtShow = lv;
          ledgerHadAtShow.add(StartupPopupLedger.instance.recorded.contains(StartupPopupKind.growthCelebration));
          shown.add('celebration:${c.toLevel}');
          return outcome;
        },
        showBackfill: (ctx, b, a) async {
          ledgerHadAtShow.add(StartupPopupLedger.instance.recorded.contains(StartupPopupKind.growthBackfill));
          shown.add('backfill:${b.level}');
          return outcome;
        },
        openLevelPage: (_) => levelPageOpens++,
        actionDelay: const Duration(milliseconds: 1500),
      );
}

Future<void> _host(WidgetTester tester, _Env env) async {
  // 在 fake-async zone 内新建启动链 Completer：setUp 里建的属于真实 zone，pump 冲刷不到它的回调。
  StartupPopupLedger.instance.resetForTest();
  await tester.pumpWidget(MaterialApp(navigatorKey: env.navKey, home: const SizedBox()));
}

GoRouter _router() => GoRouter(routes: [GoRoute(path: '/', builder: (_, _) => const SizedBox())]);

void main() {
  setUp(() {
    StartupPopupLedger.instance.resetForTest();
    growthPopupTrigger.resetForTest();
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('庆祝优先、一次只弹一个；弹出前登记 ledger 并上报 growth_level_up；关闭后 ack', (tester) async {
    final env = _Env();
    Analytics.debugCaptureSink = (e, p) => env.events.add((e, p));
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);

    c.requestPull(GrowthPullReason.resumed);
    await tester.pumpAndSettle();

    expect(env.shown, ['celebration:7']);
    expect(env.ledgerHadAtShow, [true]);
    expect(env.celebrationAcks, [7]);
    expect(env.backfillAcks, 0);
    final up = env.events.where((e) => e.$1 == 'growth_level_up').toList();
    expect(up, hasLength(1));
    expect(up.single.$2, {'from_level': 6, 'to_level': 7, 'is_tier_up': false});

    // 下一个触发点：服务端已不再返回庆祝 → 汇总顺延到此刻。
    env.popups = const GrowthPopups(backfill: _bf);
    c.requestPull(GrowthPullReason.resumed);
    await tester.pumpAndSettle();
    expect(env.shown, ['celebration:7', 'backfill:6']);
    expect(env.backfillAcks, 1);
    expect(StartupPopupLedger.instance.recorded,
        containsAll([StartupPopupKind.growthCelebration, StartupPopupKind.growthBackfill]));
  });

  testWidgets('游客 / 兽医不拉', (tester) async {
    for (final auth in [const AuthState.guest(), _vet]) {
      final env = _Env(auth: auth);
      await _host(tester, env);
      final c = env.build();
      c.requestPull(GrowthPullReason.resumed);
      await tester.pumpAndSettle();
      expect(env.fetches, 0, reason: '$auth');
      c.dispose();
    }
  });

  testWidgets('非前台 / 顶层已是弹出层 / 在 splash → 不弹也不 ack（等下一个触发点）', (tester) async {
    for (final setup in <void Function(_Env)>[
      (e) => e.resumed = false,
      (e) => e.topIsPopup = true,
      (e) => e.path = '/splash',
    ]) {
      final env = _Env();
      setup(env);
      await _host(tester, env);
      final c = env.build();
      c.requestPull(GrowthPullReason.resumed);
      await tester.pumpAndSettle();
      expect(env.fetches, 1);
      expect(env.shown, isEmpty);
      expect(env.celebrationAcks, isEmpty);
      expect(StartupPopupLedger.instance.hasAny, isFalse);

      // 条件恢复后的下一个触发点照常弹。
      env
        ..resumed = true
        ..topIsPopup = false
        ..path = '/home';
      c.requestPull(GrowthPullReason.resumed);
      await tester.pumpAndSettle();
      expect(env.shown, ['celebration:7']);
      c.dispose();
      StartupPopupLedger.instance.resetForTest();
    }
  });

  testWidgets('动作后延迟 1.5s，窗口内多次动作只拉一次', (tester) async {
    final env = _Env(popups: const GrowthPopups());
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);

    c.requestPull(GrowthPullReason.afterAction);
    await tester.pump(const Duration(milliseconds: 500));
    c.requestPull(GrowthPullReason.afterAction);
    c.requestPull(GrowthPullReason.afterAction);
    expect(env.fetches, 0);
    await tester.pump(const Duration(milliseconds: 1100));
    expect(env.fetches, 1);
    await tester.pump(const Duration(seconds: 3));
    expect(env.fetches, 1);
  });

  testWidgets('本进程弹过的同一次升级不重弹（ack 失败兜底）', (tester) async {
    final env = _Env(popups: const GrowthPopups(celebration: _cel));
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);

    c.requestPull(GrowthPullReason.resumed);
    await tester.pumpAndSettle();
    c.requestPull(GrowthPullReason.pushClicked);
    await tester.pumpAndSettle();
    expect(env.shown, ['celebration:7']);
    expect(env.fetches, 2);
  });

  testWidgets('主按钮 → 进等级页并上报 source=celebration', (tester) async {
    final env = _Env()..outcome = GrowthPopupOutcome.viewLevel;
    Analytics.debugCaptureSink = (e, p) => env.events.add((e, p));
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);

    c.requestPull(GrowthPullReason.resumed);
    await tester.pumpAndSettle();
    expect(env.levelPageOpens, 1);
    final viewed = env.events.where((e) => e.$1 == 'growth_level_page_viewed').toList();
    expect(viewed.single.$2, {'source': 'celebration'});
  });

  testWidgets('冷启动：启动链结束且在落地页 → 拉一次；coldStartSettled 随之完成；绑定全局触发器', (tester) async {
    final env = _Env(popups: const GrowthPopups());
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);
    var settled = false;
    c.coldStartSettled.then((_) => settled = true);

    c.attach(_router());
    await tester.pumpAndSettle();
    expect(env.fetches, 0, reason: '启动链未结束不拉');

    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pumpAndSettle();
    expect(env.fetches, 1);
    expect(settled, isTrue);

    growthPopupTrigger.request(GrowthPullReason.resumed);
    await tester.pumpAndSettle();
    expect(env.fetches, 2);
  });

  testWidgets('冷启动：游客 → 不拉，coldStartSettled 立即完成（运营弹窗不被卡）', (tester) async {
    final env = _Env(auth: const AuthState.guest());
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);
    var settled = false;
    c.coldStartSettled.then((_) => settled = true);
    c.attach(_router());
    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pumpAndSettle();
    expect(env.fetches, 0);
    expect(settled, isTrue);
  });

  testWidgets('等级表随庆祝传入（满级判定 / 逐级色）；未注入 → 空表', (tester) async {
    final env = _Env()..levels = const [GrowthLevelDef(level: 7, threshold: 1250, tier: GrowthTier.buddy)];
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);
    c.requestPull(GrowthPullReason.resumed);
    await tester.pumpAndSettle();
    expect(env.levelsAtShow, hasLength(1));
  });

  testWidgets('attach 后、启动链落定前的 resumed / afterAction 一律丢弃（不抢在强制更新等之前弹）', (tester) async {
    final env = _Env();
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);
    c.attach(_router());
    c.requestPull(GrowthPullReason.resumed);
    c.requestPull(GrowthPullReason.afterAction);
    await tester.pump(const Duration(seconds: 3));
    expect(env.fetches, 0);
    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pumpAndSettle();
    expect(env.fetches, 1, reason: '只有冷启动那一次');
    expect(env.shown, ['celebration:7']);
  });

  testWidgets('被杀进程点升级推送冷启动（落在等级页）→ 落定后照拉；已在等级页时主按钮不再叠一层', (tester) async {
    final env = _Env()
      ..path = '/me/growth'
      ..outcome = GrowthPopupOutcome.viewLevel;
    Analytics.debugCaptureSink = (e, p) => env.events.add((e, p));
    await _host(tester, env);
    growthPopupTrigger.request(GrowthPullReason.pushClicked); // 早于 bind
    final c = env.build();
    addTearDown(c.dispose);
    c.attach(_router());
    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pumpAndSettle();
    expect(env.fetches, 1);
    expect(env.shown, ['celebration:7']);
    expect(env.levelPageOpens, 0);
    expect(env.events.where((e) => e.$1 == 'growth_level_page_viewed'), isEmpty);
  });

  testWidgets('拉取进行中来的请求不被吞：这次没弹东西 → 结束后再拉一次', (tester) async {
    final env = _Env(popups: const GrowthPopups());
    await _host(tester, env);
    final c = env.build();
    addTearDown(c.dispose);
    c.requestPull(GrowthPullReason.resumed);
    c.requestPull(GrowthPullReason.pushClicked); // 第一次还在 await fetch
    await tester.pumpAndSettle();
    expect(env.fetches, 2);
  });
}
