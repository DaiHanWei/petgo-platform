import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/auth/domain/auth_guard.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/empty_state.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

import '../support/fake_feed_repository.dart';

/// 底栏标签精确定位：页面正文里也可能出现同名文字（如 Diary 游客态页头统计列的 "Diary"）。
Finder _tabButton(String label) =>
    find.descendant(of: find.byType(BottomTabBar), matching: find.text(label));

LoginResponse _old() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

void main() {
  testWidgets('AC2: 游客调 requireLogin → 弹强弹窗，onAllowed 不执行', (tester) async {
    var allowed = false;
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: ElevatedButton(
              key: const ValueKey('guarded'),
              onPressed: () => requireLogin(ref, context, onAllowed: () => allowed = true),
              child: const Text('do'),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('guarded')));
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(allowed, isFalse);
  });

  testWidgets('AC2: 已登录调 requireLogin → 直接执行 onAllowed，不弹窗', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_old());
    var allowed = false;

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: ElevatedButton(
              key: const ValueKey('guarded'),
              onPressed: () => requireLogin(ref, context, onAllowed: () => allowed = true),
              child: const Text('do'),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('guarded')));
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsNothing);
    expect(allowed, isTrue);
  });

  testWidgets('AC2: 并发触发强弹窗 → 单例（不叠多窗）', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: ElevatedButton(
              key: const ValueKey('dbl'),
              onPressed: () {
                final guide = ref.read(loginGuideControllerProvider);
                guide.showHardDialog(context);
                guide.showHardDialog(context); // 并发第二次应被单例守卫吞掉
              },
              child: const Text('x'),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('dbl')));
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC1: 游客首页可滚动只读容器 + Feed 空状态占位', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
      child: const TailTopiaApp(),
    ));
    await tester.pumpAndSettle();
    // V1.1.2 Story 2.4：游客冷启动落地页已由 Discovery 改为 Diary（落地矩阵 AD-8）。
    // 本条测的是 Discovery 的游客只读体验，先切过去。
    await tester.tap(_tabButton('Social'));
    await tester.pumpAndSettle();

    // Story 3.2：首页 Feed 已就位，游客可见分类 Tab + 空状态（可下拉滚动容器）。
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.byType(SingleChildScrollView), findsWidgets); // 可滚动容器
  });

  // ⚠️ 2026-08-21 DEP-1 闭合：第 2 位由 Health(受控) 换成 Toko(对游客开放)，
  //    本组的「受控 Tab」样本随之从 Health 改为 Me —— 换的是样本，不是断言强度。
  //    Toko 开放那一侧由 test/shared/diary_gating_and_landing_test.dart 正向守门。
  testWidgets('AC2: 游客点受控 Tab（我的）→ 弹强弹窗 + 不切换目的地', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
      child: const TailTopiaApp(),
    ));
    await tester.pumpAndSettle();

    await tester.tap(_tabButton('Me')); // 「我的」对游客恒受控
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsOneWidget);
    // 未切换目的地：仍停在冷启动落地页（V1.1.2 起游客落 Diary 游客引导态，不再是 Discovery）。
    expect(find.byType(DiaryGuestPage), findsOneWidget);
  });

  testWidgets('AC2: 已登录点受控 Tab → 直接进入，不弹窗', (tester) async {
    // V1.1.2 Story 2.4：已登录用户冷启动落地页改为 Diary（落地矩阵 AD-8），
    // 该页会拉宠物档案 —— 测试环境无后端，故把档案 provider 打桩为「无档案」，
    // 否则首帧的加载转圈让 pumpAndSettle 收不敛（与本条要测的 Tab 门控无关）。
    final container = ProviderContainer(overrides: [
      petProfileProvider.overrideWith((ref) async => null),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_old());

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const TailTopiaApp(),
    ));
    await tester.pumpAndSettle();

    await tester.tap(_tabButton('Me'));
    // 固定帧推进而非 pumpAndSettle：200ms 覆盖底部 Tab 按压释放的 120ms 动画 timer，
    // 避免 tearDown 报 pending timer。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.byType(LoginHardDialog), findsNothing);
    // 切到「我的」（底栏标签与页内标题同为 Me，故 findsWidgets）。
    expect(find.text('Me'), findsWidgets);

    // 卸载 widget 树：问诊 hub 常驻脉冲动画等在测试结束仍活跃会触发 !timersPending；
    // 卸载令 AnimationController/Ticker/timer 随 dispose 取消，收敛后再结束。
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
}
