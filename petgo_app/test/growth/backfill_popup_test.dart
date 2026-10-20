import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/domain/growth_popups.dart';
import 'package:tailtopia/features/growth/presentation/backfill_popup.dart';
import 'package:tailtopia/features/growth/presentation/level_up_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ① Story 2.3：存量汇总弹窗（UI 稿 E1，L0 结构断言）。
Future<List<GrowthPopupOutcome?>> _pump(WidgetTester tester, GrowthBackfill b) async {
  final results = <GrowthPopupOutcome?>[];
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('id'),
    home: Builder(
      builder: (context) => Center(
        child: TextButton(
          onPressed: () async =>
              results.add(await showBackfillPopup(context, BackfillPopup(backfill: b, nickname: 'Mochi'))),
          child: const Text('OPEN'),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('OPEN'));
  await tester.pumpAndSettle();
  return results;
}

String _rowText(WidgetTester tester, String key) => tester
    .widgetList<Text>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Text)))
    .map((t) => t.data)
    .join(' | ');

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('账单三行：60 天 → 180 EXP、13 个节点 95 EXP、合计 275 EXP；首帧上报 shown', (tester) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    await _pump(
        tester,
        const GrowthBackfill(
            level: 6, tier: GrowthTier.buddy, activeDays: 60, milestoneCount: 13, milestoneExp: 95, total: 275));

    expect(find.byKey(const ValueKey('growthBackfillPopup')), findsOneWidget);
    expect(find.text('Lv6'), findsOneWidget);
    expect(find.text('Level kamu sudah dihitung!'), findsOneWidget);
    expect(_rowText(tester, 'growthBackfillDays'), allOf(contains('60 hari aktif × 3'), contains('180')));
    expect(_rowText(tester, 'growthBackfillMilestones'), allOf(contains('13 tonggak tercapai'), contains('95')));
    expect(_rowText(tester, 'growthBackfillTotal'), allOf(contains('Total'), contains('275')));
    expect(seen.where((e) => e.$1 == 'growth_backfill_popup_shown').single.$2, {'level': 6});
  });

  testWidgets('Lihat Level Saya → viewLevel', (tester) async {
    final out = await _pump(tester, const GrowthBackfill(level: 2, tier: GrowthTier.newbie, total: 70));
    expect(find.text('Lihat Level Saya'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthBackfillButton')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.viewLevel]);
  });

  testWidgets('✕ → close；0 天也正常渲染', (tester) async {
    final out = await _pump(tester, const GrowthBackfill(level: 1, tier: GrowthTier.newbie));
    expect(_rowText(tester, 'growthBackfillDays'), contains('0 hari aktif × 3'));
    await tester.tap(find.byKey(const ValueKey('growthBackfillClose')));
    await tester.pumpAndSettle();
    expect(out, [GrowthPopupOutcome.close]);
  });
}
