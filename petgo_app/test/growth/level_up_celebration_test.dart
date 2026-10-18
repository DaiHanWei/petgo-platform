import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/domain/growth_popups.dart';
import 'package:tailtopia/features/growth/presentation/level_up_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'growth_fixtures.dart';

/// V1.4.0 ① Story 2.3：升级庆祝（UI 稿 D1–D7，L0 结构断言，不做 golden）。
Future<List<GrowthPopupOutcome?>> _pump(WidgetTester tester, GrowthCelebration c, {bool still = false}) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final results = <GrowthPopupOutcome?>[];
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('id'),
    builder: (context, child) => still
        ? MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: true), child: child!)
        : child!,
    home: Builder(
      builder: (context) => Center(
        child: TextButton(
          onPressed: () async => results.add(await showLevelUpCelebration(context,
              celebration: c, nickname: 'Mochi', levels: prdLevels())),
          child: const Text('OPEN'),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('OPEN'));
  await tester.pumpAndSettle();
  return results;
}

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('段位内 → 卡片：Lv6 → Lv7、Kamu naik level!、下一级提示；首帧上报 shown', (tester) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    final out = await _pump(
        tester, const GrowthCelebration(fromLevel: 6, toLevel: 7, tierUp: false, toTier: GrowthTier.buddy, lifetimeTotal: 1420));

    expect(find.byKey(const ValueKey('growthCelebrationCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthCelebrationFull')), findsNothing);
    expect(find.text('Lv6 → Lv7'), findsOneWidget);
    expect(find.text('Kamu naik level!'), findsOneWidget);
    expect(find.text('Terus aktif bareng anabul untuk naik ke Lv8.'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthCelebrationBadge')), findsOneWidget);
    expect(seen.where((e) => e.$1 == 'growth_celebration_shown').single.$2, {'to_level': 7});

    await tester.tap(find.byKey(const ValueKey('growthCelebrationPrimary')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.viewLevel]);
  });

  testWidgets('一次跳多级 → Naik 3 level sekaligus!；次按钮 = 只关掉', (tester) async {
    final out = await _pump(tester, const GrowthCelebration(fromLevel: 5, toLevel: 8, tierUp: false, toTier: GrowthTier.buddy));
    expect(find.text('Naik 3 level sekaligus!'), findsOneWidget);
    expect(find.text('Lv5 → Lv8'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthCelebrationSecondary')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.close]);
  });

  testWidgets('跨段位 + OFFER_NEW_FRAME → 全屏，主按钮 Pakai Bingkai Baru', (tester) async {
    final out = await _pump(
        tester,
        const GrowthCelebration(
          fromLevel: 4,
          toLevel: 5,
          tierUp: true,
          toTier: GrowthTier.buddy,
          unlockedBenefits: ['PET_SLOT_PLUS_ONE', 'FRAME_BASIC'],
          frameAction: GrowthFrameAction.offerNewFrame,
        ));
    expect(find.byKey(const ValueKey('growthCelebrationFull')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthCelebrationCard')), findsNothing);
    expect(find.text('Selamat, kamu jadi Sahabat!'), findsOneWidget);
    expect(find.text('Pakai Bingkai Baru'), findsOneWidget);
    expect(find.text('Bingkai baru sudah terbuka. Mau langsung dipakai?'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthConfetti')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthCelebrationPrimary')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.viewLevel]);
  });

  testWidgets('跨段位 AUTO + 星级 → 星级文案；✕ 关闭', (tester) async {
    final out = await _pump(
        tester,
        const GrowthCelebration(
          fromLevel: 14,
          toLevel: 15,
          tierUp: true,
          toTier: GrowthTier.star,
          frameAction: GrowthFrameAction.auto,
        ));
    expect(find.text('Kamu termasuk pecinta hewan paling aktif di TailTopia.'), findsOneWidget);
    expect(find.text('Lihat Level Saya'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthCelebrationClose')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.close]);
  });

  testWidgets('disableAnimations → 首帧即终态（徽章已是新等级，无需推进时间）', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: MediaQuery(
        data: const MediaQueryData(disableAnimations: true, size: Size(360, 800)),
        child: Scaffold(
          body: LevelUpCelebrationView(
            celebration:
                const GrowthCelebration(fromLevel: 6, toLevel: 7, tierUp: false, toTier: GrowthTier.buddy),
            levels: prdLevels(),
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(
        find.descendant(of: find.byKey(const ValueKey('growthCelebrationBadge')), matching: find.text('Lv7')),
        findsOneWidget);
    expect(tester.binding.hasScheduledFrame, isFalse, reason: '不跑动画');
  });
}
