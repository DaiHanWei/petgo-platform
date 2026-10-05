import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pet_passport/data/passport_share_reward_repository.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/keepsake/data/keepsake_repository.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/passport_share_grid.dart';
import 'package:tailtopia/features/pet_passport/domain/passport_snapshot.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/pet_passport/presentation/passport_page_face.dart';
import 'package:tailtopia/features/pet_passport/presentation/passport_versions_page.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_page.dart';
import 'package:tailtopia/features/pet_passport/presentation/share/passport_share_card.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';
import '../keepsake/fake_share_reward_repos.dart';

/// V1.3.2 Story 4.3：护照卡分享（L0 部分）。

// V1.3.2 Story 4.5：领奖上报替身（每个用例重置）。
late FakePassportShareReward shareReward;

void main() {
  setUp(() => shareReward = FakePassportShareReward());
  PassportStamp stamp(int i) => PassportStamp(
        placeToken: 't$i'.padRight(32, '0'),
        placeName: 'Tempat $i',
        placeType: PlaceType.values[i % PlaceType.values.length],
        available: true,
        visitCount: 1,
        firstVisitDate: DateTime(2026, 9, 1 + i % 28),
      );

  group('AC1.2 章格自适应 + +N 折叠（纯函数）', () {
    // 9:16 主体段大致尺寸：去掉内边距后约 936 × 1100（画布像素）。
    PassportShareGridLayout grid(int n) =>
        passportShareGrid(width: 936, height: 1100, count: n, minCellWidth: 1080 / 6);

    test('1 枚：全部显示、不折叠', () {
      final g = grid(1);
      expect(g.visibleStamps, 1);
      expect(g.overflowCount, 0);
      expect(g.cellWidth, greaterThanOrEqualTo(180));
    });

    test('9 / 12 枚：全部显示，列数随章数增加，cell ≥ 画布宽 1/6', () {
      for (final n in [9, 12]) {
        final g = grid(n);
        expect(g.visibleStamps, n, reason: '$n');
        expect(g.overflowCount, 0, reason: '$n');
        expect(g.cellWidth, greaterThanOrEqualTo(180), reason: '$n');
        expect(g.rows * g.cellHeight + 24 * (g.rows - 1), lessThanOrEqualTo(1100), reason: '$n');
      }
      expect(grid(12).columns, greaterThanOrEqualTo(grid(1).columns));
    });

    test('40 枚：cell 到下限仍装不下 → 末格 +N，显示数 + N = 总数', () {
      final g = grid(40);
      expect(g.overflowCount, greaterThan(0));
      expect(g.visibleStamps + g.overflowCount, 40);
      expect(g.visibleStamps + 1, g.columns * g.rows, reason: '留一格给 +N');
      expect(g.cellWidth, greaterThanOrEqualTo(180));
    });

    test('0 枚：不出格子', () {
      expect(grid(0).visibleStamps, 0);
    });
  });

  group('卡面', () {
    Future<void> pumpCard(WidgetTester tester, PassportShareData data) async {
      tester.view.physicalSize = const Size(1080, 1920);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: SizedBox(width: 1080, height: 1920, child: PassportShareCard(data: data, canvas: CardCanvas.story)),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('护照号 12 位完整（单独一行）、章 cell 复用纵览组件、信息段、码指向 /get、无分母', (tester) async {
      await pumpCard(tester,
          PassportShareData(petName: 'Momo', passportNo: 'TT02P2600128', stamps: [for (var i = 0; i < 9; i++) stamp(i)]));
      expect(tester.widget<Text>(find.byKey(const ValueKey('passportShareCardNo'))).data, 'TT02P2600128');
      expect(find.byType(PassportStampCell), findsNWidgets(9));
      expect(find.text('Paspor Momo · 9 Cap'), findsOneWidget);
      expect(tester.widget<CardQr>(find.byType(CardQr)).data, petDownloadUrl());
      expect(find.textContaining('/'), findsNothing, reason: '不出现「x / 12」之类的总数分母');
      expect(find.byKey(const ValueKey('passportShareCardOverflow')), findsNothing);
    });

    testWidgets('40 枚：末格 +N 且不溢出', (tester) async {
      await pumpCard(tester,
          PassportShareData(petName: 'Momo', passportNo: 'TT02P2600128', stamps: [for (var i = 0; i < 40; i++) stamp(i)]));
      expect(tester.takeException(), isNull);
      expect(find.byKey(const ValueKey('passportShareCardOverflow')), findsOneWidget);
      final shown = find.byType(PassportStampCell).evaluate().length;
      expect(find.text('+${40 - shown}'), findsOneWidget);
      expect(find.text('Paspor Momo · 40 Cap'), findsOneWidget, reason: '信息段是卡面代表的章数');
    });
  });

  group('入口与水印（AC1 / AC2 / AC3 / AC5）', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpPassport(WidgetTester tester, PetPassport p) async {
      tester.view.physicalSize = const Size(400, 1000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [
        passportShareRewardRepositoryProvider.overrideWithValue(shareReward),
          petPassportProvider.overrideWith((ref) async => p),
          keepsakePricingProvider.overrideWith((ref) async =>
              const KeepsakePricing(ktpHd: 10000, passportSnapshot: 2000, boardingPass: 1000, tailsonality: 5000)),
        ],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const PetPassportPage(),
        ),
      ));
      await tester.pumpAndSettle();
      // 2026-10-06 起有章时默认纵览：沿用原用例口径，先切到单章页（纵览相关用例再自行切回）。
      final toSingle = find.byKey(const ValueKey('passportToggleSingle'));
      if (toSingle.evaluate().isNotEmpty) {
        await tester.tap(toSingle);
        await tester.pumpAndSettle();
      }
    }

    PetPassport live({required bool unlocked, int n = 3}) => PetPassport(
        petName: 'Momo',
        passportNo: 'TT02P2600128',
        stamps: [for (var i = 0; i < n; i++) stamp(i)],
        currentVersionUnlocked: unlocked);

    testWidgets('B2「Bagikan」：当前版本未买 → 预览带水印；固定 9:16 无切换', (tester) async {
      await pumpPassport(tester, live(unlocked: false));
      await tester.tap(find.byKey(const ValueKey('passportShareCta')));
      await tester.pumpAndSettle();
      expect(find.byType(PassportShareCard), findsOneWidget);
      expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isTrue);
      expect(find.byType(CardWatermark), findsOneWidget);
      expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsNothing);
    });

    testWidgets('B2「Bagikan」：当前版本已买（服务端字段）→ 无水印', (tester) async {
      await pumpPassport(tester, live(unlocked: true));
      await tester.tap(find.byKey(const ValueKey('passportShareCta')));
      await tester.pumpAndSettle();
      expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isFalse);
      expect(find.byType(CardWatermark), findsNothing);
    });

    testWidgets('B2b 纵览吸底仍是快照 CTA、B1 空态不出 Bagikan', (tester) async {
      await pumpPassport(tester, live(unlocked: false));
      await tester.tap(find.byKey(const ValueKey('passportToggleGrid')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('passportShareCta')), findsNothing);
      expect(find.byKey(const ValueKey('passportSnapshotCta')), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await pumpPassport(tester, live(unlocked: false, n: 0));
      expect(find.byKey(const ValueKey('passportShareCta')), findsNothing);
    });

    testWidgets('已购版本回看页「Bagikan」：卡面取快照（与实时不同）、恒无水印', (tester) async {
      tester.view.physicalSize = const Size(400, 1000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final snap = PassportSnapshotDetail(
        snapshotToken: 's' * 32,
        paidAt: DateTime.utc(2026, 9, 20),
        petName: 'Momo',
        passportNo: 'TT02P2600128',
        stamps: [stamp(7), stamp(8)],
      );
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [
        passportShareRewardRepositoryProvider.overrideWithValue(shareReward),
          // 实时护照有 5 枚、且当前版本未买 —— 回看分享不得读它。
          petPassportProvider.overrideWith((ref) async => live(unlocked: false, n: 5)),
          passportSnapshotProvider('s' * 32).overrideWith((ref) async => snap),
        ],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: PassportVersionPage(token: 's' * 32),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('passportVersionShareCta')));
      await tester.pumpAndSettle();

      expect(find.byType(CardWatermark), findsNothing);
      expect(find.text('Paspor Momo · 2 Cap'), findsOneWidget);
      expect(find.text('Tempat 7'), findsOneWidget);
      expect(find.text('Tempat 8'), findsOneWidget);
      expect(find.text('Tempat 0'), findsNothing);
    });

    // 待确认 4.6（2026-10-02）按新规则更新：多带 card_type=page；出图报 keepsake_card_generated。
    testWidgets('passport_card_shared 只在分享成功回调时报，带 card_type=page 与 stamp_count', (tester) async {
      ShareCardPreviewPage.captureForTest = (_) async => Uint8List.fromList(const [1, 2, 3]);
      addTearDown(() => ShareCardPreviewPage.captureForTest = null);
      await pumpPassport(tester, live(unlocked: true));
      await tester.tap(find.byKey(const ValueKey('passportShareCta')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.$1 == 'passport_card_shared'), isEmpty);

      // Story 4.5：面板未回调成功（如取消）之前不上报领奖。
    expect(shareReward.calls, isEmpty);
    tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).onShared!('other');
    await tester.pump();
    expect(shareReward.calls, ['PAGE'], reason: '分享成功回调后上报一次，卡类型正确');
      expect(events.where((e) => e.$1 == 'passport_card_shared').single.$2, {'card_type': 'page', 'stamp_count': 3});
      final gen = events.where((e) => e.$1 == 'keepsake_card_generated').single.$2!;
      expect(gen['card_type'], 'page');
      expect(gen['duration_ms'], isA<int>());
    });
  });
}
