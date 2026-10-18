import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/growth/data/growth_repository.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/presentation/level_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'growth_fixtures.dart';

/// V1.4.0 ① Story 1.5 · AC2 ~ AC4（L0，结构断言，不做 golden）。
Future<void> _pump(WidgetTester tester, FakeGrowthRepository repo, {bool still = false}) async {
  tester.view.physicalSize = const Size(1200, 4000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final router = GoRouter(initialLocation: LevelPage.location, routes: [
    GoRoute(path: LevelPage.location, builder: (c, s) => const LevelPage()),
    GoRoute(path: '/me/growth/history', builder: (c, s) => const Text('HISTORY_PAGE')),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [growthRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      // 头像框一排里有动效框预览（8 秒一圈）：含它的用例在「移除动画」下测，否则 pumpAndSettle 永不结束。
      builder: still
          ? (context, child) =>
              MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: true), child: child!)
          : null,
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('B1：等级卡文案（千分位点号）、进度、段位区间', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));

    expect(find.text('Lv7'), findsWidgets);
    expect(find.text('Sahabat · Lv5–9'), findsWidgets);
    expect(find.text('1.420 / 1.800 EXP · Kurang 380 EXP ke Lv8'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthProgressFill')), findsOneWidget);
    expect(find.text('Lv8'), findsOneWidget); // 右端虚线胶囊
  });

  testWidgets('B3：满级显示 Level maksimal 与累计 EXP', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [maxSummary()]));

    expect(find.byKey(const ValueKey('growthLevelMax')), findsOneWidget);
    expect(find.text('Level maksimal'), findsOneWidget);
    expect(find.text('45.210 EXP'), findsOneWidget);
    expect(find.text('Bintang · Lv15+'), findsWidgets);
  });

  testWidgets('权益：已解锁 Terbuka / 未解锁 Terbuka di Lv10；未知代码（优惠券）不显示', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));

    expect(find.text('Terbuka'), findsNWidgets(2)); // Pemula + Sahabat
    expect(find.text('Terbuka di Lv10'), findsOneWidget);
    expect(find.text('Terbuka di Lv15'), findsOneWidget);
    expect(find.textContaining('COUPON'), findsNothing);
    expect(find.textContaining('Kupon'), findsNothing);
    expect(find.text('Postingan lebih sering tampil'), findsOneWidget);
  });

  testWidgets('B2：规则行今日进度 / 满额 / 被动合成行 / 无上限行', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));

    final login = find.byKey(const ValueKey('growthRule_DAILY_LOGIN'));
    expect(find.descendant(of: login, matching: find.text('Penuh hari ini')), findsOneWidget);
    final post = find.byKey(const ValueKey('growthRule_POST_PUBLISHED'));
    expect(find.descendant(of: post, matching: find.text('Hari ini 1/3')), findsOneWidget);
    final passive = find.byKey(const ValueKey('growthRule_PASSIVE'));
    expect(find.descendant(of: passive, matching: find.text('Hari ini 18/50')), findsOneWidget);
    expect(find.descendant(of: passive, matching: find.text('+1 · +3 · +2 EXP')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthRule_LIKE_RECEIVED')), findsNothing); // 被动三项只有一行
    final milestone = find.byKey(const ValueKey('growthRule_MILESTONE'));
    expect(find.descendant(of: milestone, matching: find.text('+5 · +15 · +25 EXP')), findsOneWidget);
    final shop = find.byKey(const ValueKey('growthRule_SPEND_SHOP'));
    expect(find.descendant(of: shop, matching: find.text('+5 EXP / Rp10.000')), findsOneWidget);
    expect(find.descendant(of: shop, matching: find.text('Tanpa batas harian')), findsOneWidget);
  });

  testWidgets('最近 3 条 + Lihat semua 跳明细页', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));

    expect(find.text('Posting Diary'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthSeeAllLedger')));
    await tester.pumpAndSettle();
    expect(find.text('HISTORY_PAGE'), findsOneWidget);
  });

  testWidgets('无流水：空态一行、不显示 Lihat semua', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary(recent: const [])]));

    expect(find.byKey(const ValueKey('growthRecentEmpty')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthSeeAllLedger')), findsNothing);
  });

  testWidgets('不渲染头像框一排（Story 3.3 接入）', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));

    expect(find.textContaining('Bingkai avatar Dasar'), findsOneWidget); // 只作为权益文案出现
    expect(find.byKey(const ValueKey('growthFramePicker')), findsNothing);
  });

  testWidgets('B4：首帧加载中居中转圈（不做骨架）', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [growthRepositoryProvider.overrideWithValue(FakeGrowthRepository(pages: [midSummary()]))],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('id'),
        home: LevelPage(),
      ),
    ));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.pumpAndSettle();
  });

  testWidgets('B4 / B5：加载中转圈；失败点 Coba lagi 重新请求', (tester) async {
    final repo = FakeGrowthRepository(pages: [StateError('net'), midSummary()]);
    tester.view.physicalSize = const Size(1200, 4000);
    await _pump(tester, repo);

    expect(find.byKey(const ValueKey('growthLevelError')), findsOneWidget);
    expect(find.text('Gagal memuat level'), findsOneWidget);
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(repo.pageCalls, 2);
    expect(find.byKey(const ValueKey('growthLevelCard')), findsOneWidget);
  });

  testWidgets('V1.4.0 ① 3.3（B1 / C）：有头像框四档 → 等级卡之下显示一排，头部头像戴生效框', (tester) async {
    const frames = GrowthFrames(effective: 'BASIC', options: [
      GrowthFrameOption(frame: 'NONE', unlocked: true, unlockLevel: 1),
      GrowthFrameOption(frame: 'BASIC', unlocked: true, unlockLevel: 5),
      GrowthFrameOption(frame: 'UPGRADED', unlocked: false, unlockLevel: 10),
      GrowthFrameOption(frame: 'ANIMATED', unlocked: false, unlockLevel: 15),
    ]);
    await _pump(tester, FakeGrowthRepository(pages: [midSummary(frames: frames)]), still: true);

    expect(find.text('BINGKAI AVATAR'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFramePicker')), findsOneWidget);
    final avatar = find.byKey(const ValueKey('growthLevelAvatar'));
    expect(find.descendant(of: avatar, matching: find.byKey(const ValueKey('growthFrame_basic'))), findsOneWidget);
    expect(find.descendant(of: avatar, matching: find.text('7')), findsOneWidget);
    // B1 顺序：一排在「Keuntungan level」之上。
    expect(tester.getTopLeft(find.byKey(const ValueKey('growthFramePicker'))).dy,
        lessThan(tester.getTopLeft(find.text('Terbuka').first).dy));
  });

  testWidgets('无头像框四档（旧后端）→ 不显示一排', (tester) async {
    await _pump(tester, FakeGrowthRepository(pages: [midSummary()]));
    expect(find.byKey(const ValueKey('growthFramePicker')), findsNothing);
  });
}
