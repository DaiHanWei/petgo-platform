import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/user_state.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/profile/presentation/diary_demo_detail_page.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

import '../support/fake_feed_repository.dart';

/// 底栏标签精确定位：页面正文里也可能出现同名文字（如游客态页头的统计列 "Diary"），
/// 直接 `find.text('Diary')` 会命中多个。
Finder _tabButton(String label) =>
    find.descendant(of: find.byType(BottomTabBar), matching: find.text(label));

/// Story 2.4 · L0：登录门控解除（双向）+ 落地 Tab 矩阵。
///
/// ⚠️ **安全攸关**。门控采用「默认受控 + 精确例外」：
/// **新增任何 `/profile/*` 子页默认受控**，要放行必须显式写进 `_controlledExactExceptions`
/// 的完整路径字面量。反向做法（列举受控子页）会把安全默认反转 —— 新增子页忘登记就对游客敞开。
///
/// 本文件的断言是这条规则的守门人：拦截方向与放行方向都锁死，任一侧被改坏都会红。
Future<ProviderContainer> _pumpGuestApp(WidgetTester tester) async {
  final container = ProviderContainer(
    overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
  );
  await tester.pumpAndSettle();
  return container;
}

void main() {
  group('AC1/AC3 深链门控：默认受控 + 精确例外（拦截方向）', () {
    // 只列 Diary 子页；这些必须**继续**被拦。新增 /profile/* 子页无需登记即自动受控。
    const controlledDiarySubPages = <String>[
      '/profile/create',
      '/profile/edit',
      '/profile/health',
      '/profile/id-card',
      '/profile/milestones',
      '/profile/day?date=2026-05-20',
    ];

    for (final loc in controlledDiarySubPages) {
      testWidgets('游客深链 $loc → 被拦回 /home', (tester) async {
        final container = await _pumpGuestApp(tester);
        final router = container.read(routerProvider);

        router.go(loc);
        await tester.pumpAndSettle();

        expect(router.state.matchedLocation, '/home',
            reason: '$loc 属 Diary 子页，必须继续受控（放行需显式加入精确例外集）');
        expect(find.byType(FeedTabRow), findsOneWidget);
      });
    }

    testWidgets('其它受控前缀（/me、/triage、/consult、/notifications、/publish）不受本次放行影响',
        (tester) async {
      final container = await _pumpGuestApp(tester);
      final router = container.read(routerProvider);

      for (final loc in const ['/me', '/triage', '/consult', '/notifications', '/publish']) {
        router.go(loc);
        await tester.pumpAndSettle();
        expect(router.state.matchedLocation, '/home', reason: '$loc 应仍被门控拦截');
      }
    });
  });

  group('AC1/AC3 深链门控（放行方向）', () {
    testWidgets('游客深链 /profile → 放行，渲染游客引导态', (tester) async {
      final container = await _pumpGuestApp(tester);
      final router = container.read(routerProvider);

      router.go('/profile');
      await tester.pumpAndSettle();

      expect(router.state.matchedLocation, '/profile');
      expect(find.byType(DiaryGuestPage), findsOneWidget);
      expect(find.byType(LoginHardDialog), findsNothing);
    });

    testWidgets('名片分享深链 tailtopia://card/<token> 的落点 /profile 对游客可达（FR-14 连带调整②）',
        (tester) async {
      // 深链映射是纯函数（app.dart），此处验「映射目标 + 门控」这对组合对游客成立。
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/abc123')), '/profile');

      final container = await _pumpGuestApp(tester);
      final router = container.read(routerProvider);
      router.go(deepLinkToLocation(Uri.parse('tailtopia://card/abc123'))!);
      await tester.pumpAndSettle();

      expect(router.state.matchedLocation, '/profile',
          reason: '游客点名片深链应落 Diary 游客态，不再被弹回 Discovery');
      expect(find.byType(DiaryGuestPage), findsOneWidget);
    });
  });

  group('AC2 Tab 点击门控（第二处，PRD 未覆盖）', () {
    test('免门控白名单 = {Discovery, Diary}；Health / Me 维持受控', () {
      expect(kUngatedTabs, <AppTab>{AppTab.home, AppTab.profile});
      expect(kUngatedTabs.contains(AppTab.triage), isFalse, reason: 'Health 对游客维持受控');
      expect(kUngatedTabs.contains(AppTab.me), isFalse, reason: 'Me 对游客维持受控');
    });

    testWidgets('游客点 Diary 标签 → 直接进入，不弹登录框', (tester) async {
      await _pumpGuestApp(tester);
      // 冷启动后游客已落 Diary（AC4）；先切到 Discovery 再点回 Diary，才是真正在测这次点击。
      await tester.tap(_tabButton('Social'));
      await tester.pumpAndSettle();
      expect(find.byType(FeedTabRow), findsOneWidget);

      await tester.tap(_tabButton('Diary'));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsNothing);
      expect(find.byType(DiaryGuestPage), findsOneWidget);
    });

    testWidgets('游客点 Health → 仍弹强登录窗且不切换目的地', (tester) async {
      await _pumpGuestApp(tester);

      await tester.tap(_tabButton('Health'));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsOneWidget);
      // 不切换目的地：仍停在冷启动落地的 Diary 游客态
      expect(find.byType(DiaryGuestPage), findsOneWidget);
    });

    testWidgets('游客点 Me → 仍弹强登录窗', (tester) async {
      await _pumpGuestApp(tester);

      await tester.tap(_tabButton('Me'));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsOneWidget);
    });
  });

  group('AC7 游客完整链路：进 Diary → 点带图示例 → 见详情 → 返回，全程无门控介入', () {
    testWidgets('页内推入的示例详情对游客可达，且返回后仍在 /profile', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final container = await _pumpGuestApp(tester);
      final router = container.read(routerProvider);

      // 冷启动后游客已落 Diary 游客引导态（AC4 落地矩阵，端到端形态）。
      expect(find.byType(DiaryGuestPage), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HAPPY_MOMENT_MILESTONE')));
      await tester.pumpAndSettle();

      expect(find.byType(DiaryDemoDetailPage), findsOneWidget);
      expect(find.byType(LoginHardDialog), findsNothing);
      // 示例详情是**页内推入**，不新增路由 → 路由位置仍是放行的 /profile，
      // 因此不可能被任何受控前缀命中（AC3 放行方向 + Story 2.2 AC8 的路由陷阱约束）。
      expect(router.state.matchedLocation, '/profile');

      await tester.pageBack();
      await tester.pumpAndSettle();

      expect(find.byType(DiaryDemoDetailPage), findsNothing);
      expect(find.byType(DiaryGuestPage), findsOneWidget);
      expect(find.byType(LoginHardDialog), findsNothing);
    });
  });

  group('游客态的外壳与 CTA 行为（真路径，防「没底栏 / 直进建档表单」的误判复现）', () {
    testWidgets('游客态渲染在 App 外壳内 —— **底部 Tab Bar 必须在**', (tester) async {
      await _pumpGuestApp(tester);

      expect(find.byType(DiaryGuestPage), findsOneWidget);
      // ⚠️ 这条断言是为了锁住一类真实事故：曾用 shell 外的调试路由验收，看起来「没有底栏」。
      // 真路径下游客态是 Tab 分支根页，底栏必须在（A1 稿亦有底栏）。
      expect(find.byType(BottomTabBar), findsOneWidget);
      expect(_tabButton('Diary'), findsOneWidget);
      expect(_tabButton('Health'), findsOneWidget);
      expect(_tabButton('Social'), findsOneWidget);
      expect(_tabButton('Me'), findsOneWidget);
    });

    testWidgets('游客点主 CTA → **弹登录窗**，不是直接进建档表单', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final container = await _pumpGuestApp(tester);

      await tester.tap(find.byKey(const ValueKey('diaryGuestPrimaryCta')));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsOneWidget);
      // 关键：**没有**跳到建档表单；登录成功后才由 pendingAction 续到 /profile/create。
      expect(container.read(routerProvider).state.matchedLocation, '/profile');
    });
  });

  group('AC4 落地矩阵（端到端形态）', () {
    testWidgets('未登录冷启动 → 落 Diary 游客引导态，不落 Discovery', (tester) async {
      final container = await _pumpGuestApp(tester);

      expect(container.read(routerProvider).state.matchedLocation, '/profile');
      expect(find.byType(DiaryGuestPage), findsOneWidget);
      expect(find.byType(FeedTabRow), findsNothing);
      expect(find.byType(LoginHardDialog), findsNothing);
    });
  });

  group('AC4 落地 Tab 矩阵（实时计算，不持久化）', () {
    AppUserState resolve({
      bool isLoggedIn = true,
      bool isVet = false,
      String? petStatus,
      bool hasPetProfile = false,
    }) =>
        resolveAppUserState(
            isLoggedIn: isLoggedIn,
            isVet: isVet,
            petStatus: petStatus,
            hasPetProfile: hasPetProfile);

    test('六态判定互斥且穷尽', () {
      expect(resolve(isLoggedIn: false), AppUserState.guest);
      expect(resolve(isVet: true), AppUserState.vet);
      expect(resolve(petStatus: 'HAS_PET', hasPetProfile: true), AppUserState.ownerWithProfile);
      expect(resolve(petStatus: 'HAS_PET'), AppUserState.ownerWithoutProfile);
      expect(resolve(petStatus: 'PLANNING'), AppUserState.planning);
      expect(resolve(petStatus: 'ENTHUSIAST'), AppUserState.enthusiast);
      // petStatus 未回填 → 按状态 A 处理（与 Diary 页判定同口径）
      expect(resolve(petStatus: null, hasPetProfile: true), AppUserState.ownerWithProfile);
      expect(AppUserState.values.length, 6);
    });

    test('落地矩阵：游客 / A 两态 → Diary；B / C → Discovery；兽医 → 工作台', () {
      expect(AppUserState.guest.landingLocation, '/profile');
      expect(AppUserState.ownerWithProfile.landingLocation, '/profile');
      expect(AppUserState.ownerWithoutProfile.landingLocation, '/profile');
      expect(AppUserState.planning.landingLocation, '/home');
      expect(AppUserState.enthusiast.landingLocation, '/home');
      expect(AppUserState.vet.landingLocation, '/vet/workbench');
    });

    test('埋点口径：wire 值稳定（Story 6.1 的 user_state 属性取它）', () {
      expect(AppUserState.values.map((s) => s.wire).toList(), <String>[
        'guest',
        'vet',
        'owner_with_profile',
        'owner_without_profile',
        'planning',
        'enthusiast',
      ]);
    });

    test('落地目标恒为门控可达的路径（游客落点必须在精确例外集里，否则开屏即被弹回）', () {
      // 游客落 /profile：若哪天有人把例外集改小，本断言 + 上面的放行方向 widget test 会一起红。
      expect(AppUserState.guest.landingLocation, '/profile');
    });
  });

  group('AC4 状态改变后落地实时跟随（不持久化「上次落在哪」）', () {
    test('同一用户 B → A 后落地目标随之改变', () {
      const before = UserProfile(petStatus: 'PLANNING');
      const after = UserProfile(petStatus: 'HAS_PET', hasPetProfile: true);
      String landing(UserProfile p) => resolveAppUserState(
            isLoggedIn: true,
            isVet: false,
            petStatus: p.petStatus,
            hasPetProfile: p.hasPetProfile,
          ).landingLocation;

      expect(landing(before), '/home');
      expect(landing(after), '/profile');
    });
  });
}
