import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/data/growth_repository.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/presentation/exp_ledger_page.dart';
import 'package:tailtopia/features/growth/presentation/growth_labels.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'growth_fixtures.dart';

/// V1.4.0 ① Story 1.5 · AC5（L0）：WIB 分组、名称映射、加减号、分页、空态、加载更多失败。
/// 「现在」固定为 2026-10-18 10:00 WIB（= 03:00 UTC）。
final _now = DateTime.utc(2026, 10, 18, 3);

Future<void> _pump(WidgetTester tester, FakeGrowthRepository repo, {Locale locale = const Locale('id')}) async {
  tester.view.physicalSize = const Size(1200, 3000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(ProviderScope(
    overrides: [growthRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: ExpLedgerPage(now: () => _now),
    ),
  ));
  await tester.pumpAndSettle();
}

GrowthLedgerEntry _e(String rule, int delta, {String? reason, String? note, String? detail, DateTime? at}) =>
    GrowthLedgerEntry(ruleCode: rule, delta: delta, reason: reason, note: note, detailCode: detail, createdAt: at ?? _now);

void main() {
  testWidgets('WIB 分组：UTC 16:30 / 17:30 分属不同 WIB 自然日', (tester) async {
    // 10-17 17:30 UTC = 10-18 00:30 WIB（今天）；10-17 16:30 UTC = 10-17 23:30 WIB（昨天）。
    await _pump(
      tester,
      FakeGrowthRepository(ledgers: [
        GrowthLedgerPage(items: [
          _e('LIKE_GIVEN', 1, at: DateTime.utc(2026, 10, 17, 17, 30)),
          _e('DAILY_LOGIN', 3, at: DateTime.utc(2026, 10, 17, 16, 30)),
          _e('PLACE_CHECKIN', 5, at: DateTime.utc(2026, 9, 1)),
        ]),
      ]),
    );

    expect(find.byKey(const ValueKey('growthLedgerGroup_today')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLedgerGroup_yesterday')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLedgerGroup_earlier')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLedgerGroup_last7Days')), findsNothing); // 空组不显示
    expect(find.text('00.30'), findsOneWidget); // WIB 时刻，不用手机时区
    expect(find.text('23.30'), findsOneWidget);
    expect(find.text('1 Sep'), findsOneWidget); // 更早显示 d MMM
  });

  testWidgets('名称映射与加减号', (tester) async {
    await _pump(
      tester,
      FakeGrowthRepository(ledgers: [
        GrowthLedgerPage(items: [
          _e('SPEND_SHOP', -30, reason: 'REFUND'),
          _e('LIKE_RECEIVED', -1, reason: 'UNLIKE'),
          _e('COLLECT_RECEIVED', -2, reason: 'UNCOLLECT'),
          _e('POST_PUBLISHED', -5, reason: 'TAKEDOWN'),
          _e('ADMIN_ADJUST', 40, reason: 'ADMIN_ADJUST', note: 'kompensasi'),
          _e('OPS_CAMPAIGN', 100, note: 'Hari Kucing'),
          _e('LAUNCH_BACKFILL', 120),
          _e('POST_PUBLISHED', 5, detail: 'GROWTH_MOMENT'),
          _e('SPEND_DIGITAL', 100, detail: 'ID_HD'),
          _e('SPEND_DIGITAL', 50, detail: 'SOMETHING_NEW'),
          _e('FUTURE_RULE', 7),
        ]),
      ]),
    );

    expect(find.text('Dikurangi · refund'), findsOneWidget);
    expect(find.text('Dikurangi · batal suka'), findsOneWidget);
    expect(find.text('Dikurangi · batal simpan'), findsOneWidget);
    expect(find.text('Dikurangi · konten diturunkan'), findsOneWidget);
    expect(find.text('Penyesuaian admin'), findsOneWidget);
    expect(find.text('Event · Hari Kucing'), findsOneWidget); // note 原样
    expect(find.text('Bonus perjalananmu sejauh ini'), findsOneWidget);
    expect(find.text('Posting Diary'), findsOneWidget);
    expect(find.text('Layanan digital · KTP'), findsOneWidget);
    expect(find.text('Layanan digital'), findsOneWidget); // 未知用途不显示 detail
    expect(find.text('EXP'), findsOneWidget); // 未知代码兜底
    expect(find.text('−30 EXP'), findsOneWidget);
    expect(find.text('+100 EXP'), findsNWidgets(2));
  });

  test('名称映射：OPS_CAMPAIGN 无 note 只显示 Event；帖子类型未知显示 Posting', () async {
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(GrowthLabels.ledgerTitle(l10n, _e('OPS_CAMPAIGN', 5)), 'Event');
    expect(GrowthLabels.ledgerTitle(l10n, _e('POST_PUBLISHED', 5)), 'Posting');
    expect(GrowthLabels.ledgerTitle(l10n, _e('POST_PUBLISHED', 5, detail: 'KNOWLEDGE')), 'Posting Tips');
    expect(GrowthLabels.ledgerTitle(l10n, _e('MILESTONE_L', 25)), 'Tonggak L');
  });

  testWidgets('分页：到底加载第二页；nextCursor = null 不再请求', (tester) async {
    final repo = FakeGrowthRepository(ledgers: [
      GrowthLedgerPage(items: [_e('LIKE_GIVEN', 1)], nextCursor: 'c1'),
      GrowthLedgerPage(items: [_e('DAILY_LOGIN', 3)]),
    ]);
    await _pump(tester, repo);

    expect(repo.ledgerCursors, [null, 'c1']);
    expect(find.text('Menyukai postingan'), findsOneWidget);
    expect(find.text('Login harian'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLedgerLoadingMore')), findsNothing);
  });

  testWidgets('B7 空态', (tester) async {
    await _pump(tester, FakeGrowthRepository(ledgers: [const GrowthLedgerPage()]));

    expect(find.byKey(const ValueKey('growthLedgerEmpty')), findsOneWidget);
    expect(find.text('Belum ada riwayat EXP'), findsOneWidget);
  });

  testWidgets('首屏失败 → 重试', (tester) async {
    final repo = FakeGrowthRepository(ledgers: [StateError('net'), GrowthLedgerPage(items: [_e('LIKE_GIVEN', 1)])]);
    await _pump(tester, repo);

    expect(find.text('Gagal memuat riwayat EXP'), findsOneWidget);
    await tester.tap(find.text('Coba lagi'));
    await tester.pumpAndSettle();
    expect(find.text('Menyukai postingan'), findsOneWidget);
  });

  testWidgets('B8 加载更多失败：重试同一游标，已加载条目不丢', (tester) async {
    final repo = FakeGrowthRepository(ledgers: [
      GrowthLedgerPage(items: [_e('LIKE_GIVEN', 1)], nextCursor: 'c1'),
      StateError('net'),
      GrowthLedgerPage(items: [_e('DAILY_LOGIN', 3)]),
    ]);
    await _pump(tester, repo);

    expect(find.byKey(const ValueKey('growthLedgerLoadMoreRetry')), findsOneWidget);
    expect(find.text('Menyukai postingan'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('growthLedgerLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(repo.ledgerCursors, [null, 'c1', 'c1']);
    expect(find.text('Menyukai postingan'), findsOneWidget);
    expect(find.text('Login harian'), findsOneWidget);
  });
}
