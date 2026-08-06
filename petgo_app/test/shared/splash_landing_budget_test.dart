import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/onboarding/presentation/splash_page.dart';

import '../support/fake_feed_repository.dart';

/// 🔴 code-review 2026-08-04：**冷启动到落地的总耗时必须被一个 5s 预算封顶**。
///
/// 决策 D-2 的口径是「**从冷启动起算** 5s 兜底放行」，而改前是两段串联：
/// splash 自己的 `readyDeadline`(5s) 到点 force 调 `onComplete`，路由层**从那一刻起**又
/// `await restore.timeout(5s)` ⇒ 最坏 **10s** 停在紫屏上，慢网提示可读 7.7s（设计是 2.7s）。
///
/// 更隐蔽的连带后果（也是本用例存在的第二个理由）：恢复若在 5~10s 之间完成，
/// `timedOut` 会保持 false ⇒ 既不打 `restore_timeout` 标记（AC6 的兜底发生率不可观测），
/// 也不武装 FR-91 迟到纠正 ⇒ **7-4 的核心机制在最常见的慢网区间形同死代码**。
///
/// 因此这条测试锁的是「总预算」这个语义，而不是某个具体实现 —— 只要有人再把两段计时串起来，
/// 它就会红。
class _NeverRestoringAuth extends AuthController {
  _NeverRestoringAuth(this.never, {this.lateState});

  /// 永不完成（或到测试指定时刻才完成）的恢复 —— 模拟「弱网 / 后端挂住」。
  /// dio 侧 receiveTimeout 是 30s，真机上完全够挂住。
  final Completer<void> never;

  /// 恢复**晚到**时要写回的状态。为空表示恢复始终没结果（真游客兜底）。
  final AuthState? lateState;

  @override
  AuthState build() => const AuthState.guest();

  @override
  Future<void> ensureRestored() => never.future;

  /// 模拟「恢复在落地之后才回来」：写回状态 + 置位 `isRestored` 语义（由 future 完成表达）。
  void completeLate() {
    if (lateState != null) state = lateState!;
    if (!never.isCompleted) never.complete();
  }
}

void main() {
  testWidgets('会话恢复永不返回 → 仍在 5s 总预算内离开启动屏（不得退回两段串联的 10s）',
      (tester) async {
    final never = Completer<void>();
    addTearDown(() {
      if (!never.isCompleted) never.complete();
    });

    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(() => _NeverRestoringAuth(never)),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();

    String loc() =>
        container.read(routerProvider).routerDelegate.currentConfiguration.uri.path;

    expect(loc(), '/splash', reason: '冷启动应当先落启动屏');

    // 兜底之前：仍在启动屏（这一段本来就该等，慢网提示与进度线在此期间出现）
    await tester.pump(const Duration(milliseconds: 4500));
    expect(loc(), '/splash', reason: '5s 预算未用尽前不该提前放行');

    // 越过 5s 总预算：必须已经离开启动屏。
    // 改前此刻仍是 /splash（要再等一个完整 5s，直到 ~10s 才走）。
    //
    // ⚠️ **这里绝不能用 `pumpAndSettle`**：它会一直按给定步长推进时钟直到没有待处理帧，
    // 等于把「多等的那 5 秒」直接跨过去 —— 那样这条断言对两种实现都成立，就白写了
    // （2026-08-04 第一版就踩了这个坑）。只推进刚过预算的那一点点，再用零时长 pump
    // 冲掉微任务与零延时定时器（步长要非零：elapse(0) 不触发同刻到期的 Timer）。
    await tester.pump(const Duration(milliseconds: 600)); // 累计 5100ms
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }
    expect(loc(), isNot('/splash'),
        reason: '从冷启动起算 5s（readyDeadline=${SplashPage.readyDeadline.inMilliseconds}ms）'
            '就该兜底放行；若这里仍是 /splash，说明 splash 与路由层的两段超时又被串起来了');

    await tester.pumpWidget(const SizedBox());
  });

  // AC9 边界（原先在 late_landing_correction_test.dart 里靠源码 grep 断言，2026-08-04 改为行为级）：
  // 等待超时**只停止等待、不取消底层请求** —— 恢复晚到仍会写回 AuthState，
  // FR-91 的迟到纠正正建立在这一行为之上。若哪天把它改成可取消，这条会红。
  testWidgets('恢复晚到仍写回状态 → 迟到纠正照常把 A·未建档 从 Diary 纠正到 Social',
      (tester) async {
    final never = Completer<void>();
    addTearDown(() {
      if (!never.isCompleted) never.complete();
    });
    // 恢复晚到后的真实身份：已登录、但没有宠物档案（A·未建档）⇒ 落地应为 '/home'
    final auth = _NeverRestoringAuth(
      never,
      lateState: AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: false),
      ),
    );

    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(() => auth),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();

    String loc() =>
        container.read(routerProvider).routerDelegate.currentConfiguration.uri.path;

    // AC6 埋点口径也在这里一并锁住（原为 late_landing_correction_test.dart 的源码 grep）
    final captured = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);

    // 兜底落地：超时时按当时已知态（游客）落 Diary
    await tester.pump(const Duration(milliseconds: 5100));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }
    expect(loc(), '/profile', reason: '超时兜底一律落 Diary（游客种草页）');

    final fallback = captured.where((c) => c.$1 == 'app_launch_landed_on_tab').toList();
    expect(fallback, hasLength(1), reason: '兜底落地应上报一次 T-1（事件名不得新造）');
    expect(fallback.single.$2?['restore_timeout'], isTrue,
        reason: '兜底那次必须带 restore_timeout —— 否则与真游客混成一档，'
            '「兜底发生率」不可观测（PRD OQ-23）');

    // 恢复此刻才回来 —— 请求没被取消，状态照常写回
    auth.completeLate();
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 20));
    }

    expect(loc(), '/home',
        reason: '恢复晚到写回后，FR-91 应把用户从兜底的 Diary 纠正到真实落地页 Social；'
            '若这里仍是 /profile，说明等待被改成了可取消（恢复晚到不再写回）'
            '或迟到纠正没有被武装');

    final all = captured.where((c) => c.$1 == 'app_launch_landed_on_tab').toList();
    expect(all, hasLength(2), reason: '兜底落地 + 纠正后落地，恰好两次');
    final corrected = all.last.$2!;
    expect(corrected['tab'], 'social');
    // `corrected_from` 送**产品名**（与同一次上报的 `tab` 同一套词表），不是 `/profile` 路径原文
    expect(corrected['corrected_from'], 'diary',
        reason: '路径原文与 tab 对不上，看板无法交叉分析；7-4 护栏也禁止把路径原文塞进属性');
    expect(corrected.containsKey('restore_timeout'), isFalse,
        reason: '纠正那次不是兜底，不该带兜底标记');

    await tester.pumpWidget(const SizedBox());
  });

  // 🔴 AC4 / NFR-15 安全红线的**行为级**验收（code-review 2026-08-04 新增）：
  // 「把正在浏览的用户拽走」比「落错页」严重得多，两者冲突时以不打扰为准。
  // 改前只在恢复完成的那一刻比对一次路径，于是分不清「一直没走」与「走了又回来」。
  testWidgets('🔴 用户切走再切回兜底落地页 → 恢复完成后一律不纠正（走过就不回头）',
      (tester) async {
    final never = Completer<void>();
    addTearDown(() {
      if (!never.isCompleted) never.complete();
    });
    final auth = _NeverRestoringAuth(
      never,
      lateState: AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: false),
      ),
    );
    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(() => auth),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();
    final router = container.read(routerProvider);
    String loc() => router.routerDelegate.currentConfiguration.uri.path;

    // 兜底落地 → /profile
    await tester.pump(const Duration(milliseconds: 5100));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }
    expect(loc(), '/profile');

    // 用户自己切到 Social，又切回 Diary —— 结束时 path 与兜底目标**相同**
    router.go('/home');
    await tester.pump(const Duration(milliseconds: 50));
    expect(loc(), '/home');
    router.go('/profile');
    await tester.pump(const Duration(milliseconds: 50));
    expect(loc(), '/profile', reason: '前置条件：用户已回到与兜底目标相同的页面');

    // 此刻恢复才回来。真实身份是 A·未建档（本该纠正到 /home），但人是自己选的 Diary。
    auth.completeLate();
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 20));
    }

    expect(loc(), '/profile',
        reason: '用户离开过兜底落地页（即便又走回来）就必须放手 —— '
            '若这里变成 /home，说明判定只比对最终路径，把用户从他自己选的 Diary 拽走了');

    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('🔴 纠正时用户正开着弹层（根 Navigator 可 pop）→ 不纠正、不强关',
      (tester) async {
    final never = Completer<void>();
    addTearDown(() {
      if (!never.isCompleted) never.complete();
    });
    final auth = _NeverRestoringAuth(
      never,
      lateState: AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: false),
      ),
    );
    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(() => auth),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();
    String loc() =>
        container.read(routerProvider).routerDelegate.currentConfiguration.uri.path;

    await tester.pump(const Duration(milliseconds: 5100));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }
    expect(loc(), '/profile');

    // 用户打开一个对话框（`showDialog` 默认走根 Navigator —— 强登录弹窗就是这么弹的）。
    // 它**不改变路由 path**，所以单靠路径比对会判成「人没走」。
    showDialog<void>(
      context: rootNavigatorKey.currentContext!,
      builder: (_) => const AlertDialog(content: Text('用户正在看的东西')),
    );
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('用户正在看的东西'), findsOneWidget);
    expect(loc(), '/profile', reason: '前置条件：弹层不改变路由 path');

    auth.completeLate();
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 20));
    }

    expect(find.text('用户正在看的东西'), findsOneWidget,
        reason: '纠正不得把用户正在看的弹层无声关掉（AC4 优先于「纠正到正确页」）');
    expect(loc(), '/profile', reason: '弹层还开着就不该跳页');

    await tester.pumpWidget(const SizedBox());
  });
}
