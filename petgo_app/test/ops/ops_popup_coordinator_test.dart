import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/ops/domain/ops_popup.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';
import 'package:tailtopia/features/ops/presentation/ops_popup_coordinator.dart';
import 'package:tailtopia/features/ops/presentation/ops_popup_dialog.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 5.4 · AC1 / AC3.4-3.5 / AC5 / AC6：判定协调器（全部依赖注入假实现，L0）。

const _catTarget = OpsTarget(OpsTargetType.category, 'MAKANAN');

OpsPopupCandidate _cand(
  String token, {
  OpsPopupFrequency f = OpsPopupFrequency.once,
  OpsTarget target = _catTarget,
}) =>
    OpsPopupCandidate(
      token: token,
      frequency: f,
      imageUrlId: 'https://x/$token-id.webp',
      imageUrlEn: 'https://x/$token-en.webp',
      titleId: 'Judul',
      titleEn: 'Title',
      buttonTextId: 'Lihat',
      buttonTextEn: 'See',
      target: target,
    );

const _user = AuthState(status: AuthStatus.authenticated, role: 'USER');
const _vet = AuthState(status: AuthStatus.authenticated, role: 'VET');

class _Env {
  List<OpsPopupCandidate> candidates = [_cand('popA')];
  Object? fetchError;
  Completer<void>? fetchGate;
  int fetchCalls = 0;

  final List<String> impressions = [];
  bool precacheResult = true;
  Completer<bool>? precacheGate;
  Completer<void>? authGate;
  int authReadyCalls = 0;
  final List<ImageProvider> precached = [];

  int showCalls = 0;
  OpsPopupDialog? lastDialog;
  Completer<OpsPopupOutcome?>? showResult;
  bool callOnShown = true;

  final List<OpsTarget> opened = [];
  bool showCompletedBeforeOpen = false;
  bool _showCompleted = false;

  bool canPop = false;
  bool resumed = true;
  AuthState auth = const AuthState.guest();
  DateTime now = DateTime.utc(2026, 10, 12, 3);
  late AppPrefs prefs;

  final events = <MapEntry<String, Map<String, Object>?>>[];
}

class _Harness {
  _Harness(this.env, this.router, this.coordinator, this.navKey);
  final _Env env;
  final GoRouter router;
  final OpsPopupCoordinator coordinator;
  final GlobalKey<NavigatorState> navKey;
}

Future<_Harness> _pump(WidgetTester tester, _Env env, {String initial = '/home', Locale? locale}) async {
  // ⚠️ 必须在测试体（fakeAsync zone）里重置：Completer 建在 setUp 的真实 zone 里，
  // 它的完成回调排进真实事件循环，`tester.pump()` 推不动。
  StartupPopupLedger.instance.resetForTest();
  SharedPreferences.setMockInitialValues({});
  env.prefs = AppPrefs(await SharedPreferences.getInstance());
  final navKey = GlobalKey<NavigatorState>();
  Widget page(String name) => Scaffold(body: Text('page:$name'));
  final router = GoRouter(
    navigatorKey: navKey,
    initialLocation: initial,
    routes: [
      GoRoute(path: '/home', builder: (_, _) => page('home')),
      GoRoute(path: '/profile', builder: (_, _) => page('profile')),
      GoRoute(path: '/notifications', builder: (_, _) => page('notifications')),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(MaterialApp.router(
    routerConfig: router,
    locale: locale ?? const Locale('id'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
  ));
  await tester.pumpAndSettle();

  final c = OpsPopupCoordinator(
    fetchCandidates: () async {
      env.fetchCalls++;
      if (env.fetchGate != null) await env.fetchGate!.future;
      if (env.fetchError != null) throw env.fetchError!;
      return env.candidates;
    },
    reportImpression: (t) async => env.impressions.add(t),
    prefs: () async => env.prefs,
    auth: () => env.auth,
    authReady: () async {
      env.authReadyCalls++;
      if (env.authGate != null) await env.authGate!.future;
    },
    now: () => env.now,
    precache: (ctx, image) async {
      env.precached.add(image);
      if (env.precacheGate != null) return env.precacheGate!.future;
      return env.precacheResult;
    },
    show: (ctx, dialog) {
      env.showCalls++;
      env.lastDialog = dialog;
      if (env.callOnShown) {
        dialog.onShown();
        dialog.onShown(); // 重复回调不得重复写入 / 重复上报
      }
      final r = env.showResult ?? (Completer<OpsPopupOutcome?>()..complete(OpsPopupOutcome.close));
      return r.future.whenComplete(() => env._showCompleted = true);
    },
    open: (ctx, target) async {
      env.showCompletedBeforeOpen = env._showCompleted;
      env.opened.add(target);
    },
    rootContext: () => navKey.currentContext,
    rootCanPop: () => env.canPop,
    isResumed: () => env.resumed,
  );
  addTearDown(c.dispose);
  c.attach(router);
  return _Harness(env, router, c, navKey);
}

/// 放行启动链并跑完「一帧 + 500ms」与后续异步。
Future<void> _release(WidgetTester tester) async {
  StartupPopupLedger.instance.markStartupChainDone();
  await _settleRun(tester);
}

Future<void> _settleRun(WidgetTester tester) async {
  await tester.pump(); // 启动链 future 的回调落地，endOfFrame 排帧
  await tester.pump(); // 帧结束 → 开始 500ms 计时
  await tester.pump(const Duration(milliseconds: 501));
  await tester.pump();
  await tester.pump();
}

Future<void> _go(WidgetTester tester, _Harness h, String location) async {
  h.router.go(location);
  await tester.pump();
  await _settleRun(tester);
}

List<String> _names(_Env env) => env.events.map((e) => e.key).toList();

void main() {
  late _Env env;

  setUp(() {
    env = _Env();
    Analytics.debugCaptureSink = (e, p) => env.events.add(MapEntry(e, p));
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    StartupPopupLedger.instance.resetForTest();
  });

  testWidgets('① startupChainDone 未完成 → 不取候选', (tester) async {
    final h = await _pump(tester, env);
    await tester.pump(const Duration(seconds: 10));
    expect(env.fetchCalls, 0);
    expect(h.coordinator.inFlight, isTrue, reason: '停在第 1 步等启动链');
    expect(h.coordinator.settled, isFalse);
  });

  testWidgets('② 放行后一帧 + 500ms 内不取候选、之后取', (tester) async {
    await _pump(tester, env);
    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pump();
    await tester.pump(); // 一帧结束
    await tester.pump(const Duration(milliseconds: 498));
    expect(env.fetchCalls, 0);
    await tester.pump(const Duration(milliseconds: 3));
    expect(env.fetchCalls, 1);
  });

  testWidgets('③ ledger 有记录 → 定局、不取候选', (tester) async {
    final h = await _pump(tester, env);
    StartupPopupLedger.instance.record(StartupPopupKind.att);
    await _release(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.settled, isTrue);
    expect(env.showCalls, 0);
  });

  testWidgets('④a 候选为空 → 定局、不 show', (tester) async {
    env.candidates = const [];
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isTrue);
  });

  testWidgets('④b 取候选抛异常 → 定局、不 show、不抛', (tester) async {
    env.fetchError = StateError('net');
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('④c 本地频次全满 → 定局、不 show', (tester) async {
    final h = await _pump(tester, env);
    await env.prefs.markOpsPopupShown('popA', 'once');
    await _release(tester);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isTrue);
  });

  testWidgets('⑤ 图片 precache 失败 → 不 show、prefs 无写入、定局；不退而取第二个', (tester) async {
    env.candidates = [_cand('popA'), _cand('popB')];
    env.precacheResult = false;
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.precached, hasLength(1), reason: '图片失败不取第二个候选（PRD）');
    expect(env.showCalls, 0);
    expect(env.prefs.opsPopupShown(), isEmpty, reason: '没显示就不计频次（D-21）');
    expect(env.impressions, isEmpty);
    expect(h.coordinator.settled, isTrue);
    expect(_names(env), isNot(contains('ops_popup_shown')));
  });

  testWidgets('⑥a 成功（ONCE · 登录用户）→ show 一次、onShown 后写 once、报曝光、ops_popup_shown 一次', (tester) async {
    env.auth = _user;
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 1);
    expect(h.coordinator.settled, isTrue);
    expect(env.prefs.opsPopupShown(), {'popA': 'once'});
    expect(env.impressions, ['popA']);
    final shown = env.events.where((e) => e.key == 'ops_popup_shown').toList();
    expect(shown, hasLength(1));
    expect(shown.single.value, {'popup_id': 'popA'}, reason: 'D-24：只带 public_token');
    // ID 语言：图片 / 文案取 *Id。
    final d = env.lastDialog!;
    expect((d.image as NetworkImage).url, 'https://x/popA-id.webp');
    expect(identical(env.precached.single, d.image), isTrue, reason: '预加载与展示同一个 provider');
    expect(d.title, 'Judul');
    expect(d.buttonText, 'Lihat');
    expect(d.imageTappable, isTrue);
  });

  testWidgets('⑥b DAILY · 游客 → 写当天 WIB 日期、不报曝光', (tester) async {
    env.candidates = [_cand('popD', f: OpsPopupFrequency.daily)];
    env.now = DateTime.utc(2026, 10, 12, 17, 30); // WIB 已是 13 号
    await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 1);
    expect(env.prefs.opsPopupShown(), {'popD': '2026-10-13'});
    expect(env.impressions, isEmpty, reason: '游客不调曝光接口');
    expect(_names(env).where((n) => n == 'ops_popup_shown'), hasLength(1));
  });

  testWidgets('⑥c 弹窗没真正显示（onShown 未回调）→ 不写频次、不报', (tester) async {
    env.callOnShown = false;
    env.auth = _user;
    await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 1);
    expect(env.prefs.opsPopupShown(), isEmpty);
    expect(env.impressions, isEmpty);
  });

  testWidgets('⑥d EN：图片 / 文案取 *En', (tester) async {
    await _pump(tester, env, locale: const Locale('en'));
    await _release(tester);
    final d = env.lastDialog!;
    expect((d.image as NetworkImage).url, 'https://x/popA-en.webp');
    expect(d.title, 'Title');
    expect(d.buttonText, 'See');
  });

  testWidgets('⑦ 取候选期间 ledger 被登记（模拟蒙层）→ 第二次检查拦下、不 show、定局', (tester) async {
    env.fetchGate = Completer<void>();
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.fetchCalls, 1);
    StartupPopupLedger.instance.record(StartupPopupKind.coachmark);
    env.fetchGate!.complete();
    await _settleRun(tester);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isTrue);
    expect(env.prefs.opsPopupShown(), isEmpty);
  });

  testWidgets('⑦b 图片预加载卡住期间 ledger 被登记 → 二次检查紧贴 show、不 show、定局', (tester) async {
    env.precacheGate = Completer<bool>();
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.precached, hasLength(1));
    expect(env.showCalls, 0);
    StartupPopupLedger.instance.record(StartupPopupKind.coachmark);
    env.precacheGate!.complete(true);
    await _settleRun(tester);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isTrue);
    expect(env.prefs.opsPopupShown(), isEmpty);
  });

  testWidgets('登录态恢复未落定 → 不判定；落定为 VET → 定局不取候选', (tester) async {
    env.authGate = Completer<void>();
    final h = await _pump(tester, env);
    await _release(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(env.authReadyCalls, 1);
    expect(env.fetchCalls, 0, reason: '恢复中不得按游客取候选');
    env.auth = _vet; // 恢复结果：兽医
    env.authGate!.complete();
    await _settleRun(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.settled, isTrue);
  });

  testWidgets('登录态恢复超过 5s → 按当时已知态继续判定', (tester) async {
    env.authGate = Completer<void>();
    await _pump(tester, env);
    StartupPopupLedger.instance.markStartupChainDone();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 4999));
    expect(env.fetchCalls, 0);
    await tester.pump(const Duration(milliseconds: 2));
    await _settleRun(tester);
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 1);
  });

  testWidgets('⑧ 初始路径 /notifications → 不判定；切到 /home → 判定', (tester) async {
    final h = await _pump(tester, env, initial: '/notifications');
    await _release(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.inFlight, isFalse);
    await _go(tester, h, '/home');
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 1);
  });

  testWidgets('⑧b 落地页上 push 了别的页面（currentConfiguration 仍是 /home）→ 不判定', (tester) async {
    final h = await _pump(tester, env, initial: '/notifications');
    await _release(tester);
    h.router.go('/home');
    unawaited(h.router.push('/notifications'));
    await tester.pump();
    await _settleRun(tester);
    expect(env.showCalls, 0);
  });

  testWidgets('⑨ 空闲检查失败（rootCanPop）→ 不定局；再次路由到 /profile 后重新判定并 show', (tester) async {
    env.canPop = true;
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.settled, isFalse);
    expect(h.coordinator.inFlight, isFalse);
    env.canPop = false;
    await _go(tester, h, '/profile');
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 1);
    expect(h.coordinator.settled, isTrue);
  });

  testWidgets('⑨b 非 resumed → 不定局', (tester) async {
    env.resumed = false;
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.settled, isFalse);
  });

  testWidgets('⑨c 第二次空闲检查失败（取候选期间压上对话框）→ 不 show、不定局', (tester) async {
    env.fetchGate = Completer<void>();
    final h = await _pump(tester, env);
    await _release(tester);
    env.canPop = true;
    env.fetchGate!.complete();
    await _settleRun(tester);
    expect(env.showCalls, 0);
    expect(h.coordinator.settled, isFalse);
    expect(env.prefs.opsPopupShown(), isEmpty);
  });

  testWidgets('⑩ 同一运行已 show 后再切 Tab → 不再判定', (tester) async {
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 1);
    await _go(tester, h, '/profile');
    await _go(tester, h, '/home');
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 1);
  });

  testWidgets('⑪ VET → 定局、不取候选', (tester) async {
    env.auth = _vet;
    final h = await _pump(tester, env);
    await _release(tester);
    expect(env.fetchCalls, 0);
    expect(h.coordinator.settled, isTrue);
  });

  testWidgets('⑫a tap + 有效目标 → show future 完成之后 open 一次、ops_popup_tapped', (tester) async {
    env.showResult = Completer<OpsPopupOutcome?>();
    await _pump(tester, env);
    await _release(tester);
    expect(env.showCalls, 1);
    expect(env.opened, isEmpty, reason: '弹窗未关闭前不得跳转');
    env.showResult!.complete(OpsPopupOutcome.tap);
    await tester.pump();
    await tester.pump();
    expect(env.opened, [_catTarget]);
    expect(env.showCompletedBeforeOpen, isTrue);
    final tapped = env.events.where((e) => e.key == 'ops_popup_tapped').toList();
    expect(tapped.single.value, {'popup_id': 'popA'});
    expect(_names(env), isNot(contains('ops_popup_closed')));
  });

  testWidgets('⑫b close → ops_popup_closed、不 open', (tester) async {
    env.showResult = Completer<OpsPopupOutcome?>()..complete(OpsPopupOutcome.close);
    await _pump(tester, env);
    await _release(tester);
    expect(env.opened, isEmpty);
    expect(env.events.where((e) => e.key == 'ops_popup_closed').single.value, {'popup_id': 'popA'});
    expect(_names(env), isNot(contains('ops_popup_tapped')));
  });

  testWidgets('⑫c 系统返回（null）→ 按 close 处理', (tester) async {
    env.showResult = Completer<OpsPopupOutcome?>()..complete(null);
    await _pump(tester, env);
    await _release(tester);
    expect(env.opened, isEmpty);
    expect(_names(env), contains('ops_popup_closed'));
  });

  testWidgets('⑫d NONE 目标点按钮 → 不 open、报 ops_popup_tapped；图片不可点', (tester) async {
    env.candidates = [_cand('popN', target: OpsTarget.none)];
    env.showResult = Completer<OpsPopupOutcome?>()..complete(OpsPopupOutcome.tap);
    await _pump(tester, env);
    await _release(tester);
    expect(env.lastDialog!.imageTappable, isFalse);
    expect(env.opened, isEmpty);
    expect(_names(env), contains('ops_popup_tapped'));
  });

  testWidgets('attach 幂等：重复 attach 不重复判定', (tester) async {
    final h = await _pump(tester, env);
    h.coordinator.attach(h.router);
    h.coordinator.attach(h.router);
    await _release(tester);
    expect(env.fetchCalls, 1);
    expect(env.showCalls, 1);
  });

  group('defaultPrecache（D-21：5 秒内未成功即失败）', () {
    Future<BuildContext> ctxOf(WidgetTester tester) async {
      late BuildContext ctx;
      await tester.pumpWidget(Builder(builder: (c) {
        ctx = c;
        return const SizedBox();
      }));
      return ctx;
    }

    testWidgets('超过 5s 未加载完 → false', (tester) async {
      final ctx = await ctxOf(tester);
      bool? r;
      unawaited(OpsPopupCoordinator.defaultPrecache(ctx, const _TestImage(_Mode.never)).then((v) => r = v));
      await tester.pump(const Duration(milliseconds: 4999));
      expect(r, isNull);
      await tester.pump(const Duration(milliseconds: 2));
      expect(r, isFalse);
    });

    testWidgets('加载成功 → true', (tester) async {
      final ctx = await ctxOf(tester);
      final img = await tester.runAsync(() => createTestImage(width: 4, height: 5));
      bool? r;
      unawaited(OpsPopupCoordinator.defaultPrecache(ctx, _ReadyImage(img!)).then((v) => r = v));
      await tester.pump();
      await tester.pump();
      expect(r, isTrue);
    });

    testWidgets('加载报错 → false', (tester) async {
      final ctx = await ctxOf(tester);
      bool? r;
      unawaited(OpsPopupCoordinator.defaultPrecache(ctx, const _TestImage(_Mode.error)).then((v) => r = v));
      await tester.pump();
      await tester.pump();
      expect(r, isFalse);
    });
  });
}

enum _Mode { never, error }

class _TestImage extends ImageProvider<_TestImage> {
  const _TestImage(this.mode);
  final _Mode mode;

  @override
  Future<_TestImage> obtainKey(ImageConfiguration configuration) => SynchronousFuture<_TestImage>(this);

  @override
  ImageStreamCompleter loadImage(_TestImage key, ImageDecoderCallback decode) =>
      OneFrameImageStreamCompleter(mode == _Mode.never
          ? Completer<ImageInfo>().future
          : Future<ImageInfo>.error(StateError('404')));

  @override
  bool operator ==(Object other) => other is _TestImage && other.mode == mode && identical(this, other);

  @override
  int get hashCode => identityHashCode(this);
}

class _ReadyImage extends ImageProvider<_ReadyImage> {
  const _ReadyImage(this.image);
  final ui.Image image;

  @override
  Future<_ReadyImage> obtainKey(ImageConfiguration configuration) => SynchronousFuture<_ReadyImage>(this);

  @override
  ImageStreamCompleter loadImage(_ReadyImage key, ImageDecoderCallback decode) =>
      OneFrameImageStreamCompleter(SynchronousFuture<ImageInfo>(ImageInfo(image: image.clone())));
}
