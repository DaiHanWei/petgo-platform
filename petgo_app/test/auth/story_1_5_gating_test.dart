import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/app.dart';
import 'package:petgo/features/auth/domain/auth_guard.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_guide_controller.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/content/data/feed_repository.dart';
import 'package:petgo/features/content/presentation/feed_tab_row.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/empty_state.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';

import '../support/fake_feed_repository.dart';

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
      child: const PetGoApp(),
    ));
    await tester.pumpAndSettle();

    // Story 3.2：首页 Feed 已就位，游客可见分类 Tab + 空状态（可下拉滚动容器）。
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.byType(SingleChildScrollView), findsWidgets); // 可滚动容器
  });

  testWidgets('AC2: 游客点受控 Tab（问诊）→ 弹强弹窗 + 不切换目的地', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
      child: const PetGoApp(),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Consult')); // 问诊 Tab（inactive 标签）
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsOneWidget);
    // 未切换：首页 Feed 仍在
    expect(find.byType(FeedTabRow), findsOneWidget);
  });

  testWidgets('AC2: 已登录点受控 Tab → 直接进入，不弹窗', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_old());

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const PetGoApp(),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Consult'));
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsNothing);
    // 切到问诊 hub（换肤后页面标题为 Momo 头「Konsultasi Kilat」，确认已落地该页）。
    expect(find.text('Konsultasi Kilat'), findsOneWidget);
  });
}
