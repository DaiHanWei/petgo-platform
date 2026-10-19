import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/gift/presentation/gift_panel_sheet.dart';
import 'package:tailtopia/features/gift/presentation/gift_style.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'gift_fakes.dart';

/// V1.4.0 gift Story 1.3 · AC2（L0，结构断言，不写 golden）：送礼面板 B1 ~ B7。
void main() {
  late FakeGiftRepository repo;
  late List<Object?> results;
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    repo = FakeGiftRepository();
    results = [];
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> open(WidgetTester tester,
      {Locale locale = const Locale('id'),
      int initialQuantity = 1,
      String? initialToken,
      bool settle = true}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [giftRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                key: const ValueKey('open'),
                onPressed: () async => results.add(await showGiftPanelSheet(
                  context,
                  recipient: const GiftRecipientRef.pet(42),
                  initialQuantity: initialQuantity,
                  initialGiftToken: initialToken,
                )),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      // 加载中转圈是无限动画，pumpAndSettle 会超时：只推进到抽屉展开。
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
    }
  }

  bool cellSelected(String token) => find
      .descendant(
          of: find.byKey(ValueKey('giftCell-$token')),
          matching: find.byKey(const ValueKey('giftCellCheck')))
      .evaluate()
      .isNotEmpty;

  bool chipSelected(WidgetTester tester, int q) =>
      tester
          .widget<Text>(find.descendant(
              of: find.byKey(ValueKey('giftQtyChip-$q')), matching: find.byType(Text)))
          .style
          ?.color ==
      Colors.white;

  Color? stepIconColor(WidgetTester tester, String key) => tester
      .widget<Icon>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Icon)))
      .color;

  String qty(WidgetTester tester) =>
      tester.widget<Text>(find.byKey(const ValueKey('giftQtyValue'))).data!;

  testWidgets('标题行 + 档标题（价格升序 + 档名）+ 默认选中排序后第一项', (tester) async {
    await open(tester);
    expect(find.text('Kirim hadiah untuk Mochi'), findsOneWidget);
    expect(find.text('Kucing · Persia'), findsOneWidget);
    // 价位升序：Rp1.000 在 Rp5.000 之上。
    final y1 = tester.getTopLeft(find.byKey(const ValueKey('giftTierPrice-1000'))).dy;
    final y5 = tester.getTopLeft(find.byKey(const ValueKey('giftTierPrice-5000'))).dy;
    expect(y1, lessThan(y5));
    expect(find.text('Camilan'), findsOneWidget);
    expect(find.text('Makan enak'), findsOneWidget);
    // 默认选中 = 第一档第一个（后端排序）。
    expect(cellSelected('g-tulang'), isTrue);
    expect(cellSelected('g-ikan'), isFalse);
    expect(find.byKey(const ValueKey('giftCellCheck')), findsOneWidget);
    expect(find.text('Kirim · Rp1.000'), findsOneWidget);
    expect(find.text('Hadiah virtual tidak bisa dikembalikan setelah terkirim.'), findsOneWidget);
    // 锁区块：lockBlock == null（B3）不显示。
    expect(find.byKey(const ValueKey('giftLockBlock')), findsNothing);
  });

  testWidgets('数量：×5 高亮 → 手动 +1 后三个胶囊都不高亮；换礼物不重置数量；合计联动', (tester) async {
    await open(tester);
    expect(chipSelected(tester, 1), isTrue);
    await tester.tap(find.byKey(const ValueKey('giftQtyChip-5')));
    await tester.pump();
    expect(chipSelected(tester, 5), isTrue);
    expect(chipSelected(tester, 1), isFalse);
    expect(tester.widget<Text>(find.byKey(const ValueKey('giftTotal'))).data, 'Rp5.000');
    expect(find.text('Kirim · Rp5.000'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('giftSummaryLine'))).data, 'Tulang × 5');

    await tester.tap(find.byKey(const ValueKey('giftQtyPlus')));
    await tester.pump();
    expect(qty(tester), '6');
    expect([1, 5, 10].any((q) => chipSelected(tester, q)), isFalse);
    expect(find.text('Kirim · Rp6.000'), findsOneWidget);

    // 换礼物（Rp5.000 档）：数量保留 6 → 合计 Rp30.000。
    await tester.tap(find.byKey(const ValueKey('giftCell-g-ayam')));
    await tester.pumpAndSettle();
    expect(cellSelected('g-ayam'), isTrue);
    expect(qty(tester), '6');
    expect(find.text('Kirim · Rp30.000'), findsOneWidget);
  });

  testWidgets('步进器：1 时「−」禁（置灰、点击无效），热区 ≥ 44', (tester) async {
    await open(tester);
    expect(stepIconColor(tester, 'giftQtyMinus'), GiftStyle.disabled);
    expect(stepIconColor(tester, 'giftQtyPlus'), isNot(GiftStyle.disabled));
    await tester.tap(find.byKey(const ValueKey('giftQtyMinus')));
    await tester.pump();
    expect(qty(tester), '1');
    expect(tester.getSize(find.byKey(const ValueKey('giftQtyPlus'))).height, greaterThanOrEqualTo(44));
    expect(tester.getSize(find.byKey(const ValueKey('giftQtyMinus'))).width, greaterThanOrEqualTo(44));
  });

  testWidgets('步进器：99 时「+」禁', (tester) async {
    await open(tester, initialQuantity: 99);
    expect(qty(tester), '99');
    expect(stepIconColor(tester, 'giftQtyPlus'), GiftStyle.disabled);
    await tester.tap(find.byKey(const ValueKey('giftQtyPlus')));
    await tester.pump();
    expect(qty(tester), '99');
    expect(find.text('Kirim · Rp99.000'), findsOneWidget);
  });

  testWidgets('专属组标签（Lv5 / Lv15）排在价位档之后 + 锁区块文案', (tester) async {
    repo.catalog = sampleCatalog(
      exclusiveGroups: const [
        GiftExclusiveGroup(minLevel: 15, gifts: [
          GiftItem(giftToken: 'g-x15', nameEn: 'Castle', nameId: 'Kastil', price: 50000, kind: 'EXCLUSIVE', minLevel: 15),
        ]),
        GiftExclusiveGroup(minLevel: 5, gifts: [mahkota]),
      ],
      lockBlock: const GiftLockBlock(requiredLevel: 20),
    );
    await open(tester);
    expect(find.text('Eksklusif · Lv5+'), findsOneWidget);
    expect(find.text('Eksklusif · Lv15+'), findsOneWidget);
    final yTier = tester.getTopLeft(find.byKey(const ValueKey('giftTierPrice-5000'))).dy;
    final y5 = tester.getTopLeft(find.byKey(const ValueKey('giftExclusiveLabel-5'))).dy;
    final y15 = tester.getTopLeft(find.byKey(const ValueKey('giftExclusiveLabel-15'))).dy;
    expect(yTier, lessThan(y5));
    expect(y5, lessThan(y15));
    await tester.scrollUntilVisible(find.byKey(const ValueKey('giftLockBlock')), 200,
        scrollable: find.descendant(
            of: find.byKey(const ValueKey('giftPanelGrid')), matching: find.byType(Scrollable)));
    expect(find.text('Lv20 buka lebih banyak hadiah'), findsOneWidget);
    expect(find.text('Naik level untuk membuka hadiah eksklusif'), findsOneWidget);
  });

  testWidgets('锁区块 Lv5：点击出 Toast「Terbuka di Lv5」+ 埋点，面板不关', (tester) async {
    repo.catalog = sampleCatalog(lockBlock: const GiftLockBlock(requiredLevel: 5));
    await open(tester);
    expect(find.text('Lv5 buka lebih banyak hadiah'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('giftLockBlock')));
    await tester.pump();
    expect(find.text('Terbuka di Lv5'), findsOneWidget);
    expect(find.byKey(const ValueKey('giftPanelLoaded')), findsOneWidget, reason: '面板不关');
    expect(results, isEmpty);
    expect(events.where((e) => e.$1 == 'gift_locked_block_tapped').single.$2, {'required_level': 5});
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('点「Kirim」→ 返回所选礼物与数量 + 埋点 gift_send_tapped', (tester) async {
    repo.catalog = sampleCatalog(exclusiveGroups: const [
      GiftExclusiveGroup(minLevel: 5, gifts: [mahkota]),
    ]);
    await open(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('giftCell-g-mahkota')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('giftCell-g-mahkota')));
    await tester.tap(find.byKey(const ValueKey('giftQtyChip-10')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('giftSendButton')));
    await tester.pumpAndSettle();
    final r = results.single as GiftPanelSend;
    expect(r.selection.item.giftToken, 'g-mahkota');
    expect(r.selection.quantity, 10);
    expect(r.selection.total, 250000);
    expect(events.where((e) => e.$1 == 'gift_send_tapped').single.$2, {
      'gift_id': 'g-mahkota',
      'quantity': 10,
      'total_amount': 250000,
      'is_exclusive': true,
    });
  });

  testWidgets('重开面板：保留上次礼物与数量', (tester) async {
    await open(tester, initialToken: 'g-ikan', initialQuantity: 5);
    expect(cellSelected('g-ikan'), isTrue);
    expect(chipSelected(tester, 5), isTrue);
  });

  testWidgets('B6 加载中 → B7 失败 → 「Coba lagi」重拉成功', (tester) async {
    repo.catalogGate = Completer<void>();
    repo.catalogError = Exception('net');
    await open(tester, settle: false);
    expect(find.byKey(const ValueKey('giftPanelLoading')), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    repo.catalogGate!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftPanelFailed')), findsOneWidget);
    expect(find.text('Gagal memuat hadiah'), findsOneWidget);
    expect(find.byIcon(Icons.cloud_off), findsOneWidget);

    repo.catalogGate = null;
    repo.catalogError = null;
    await tester.tap(find.byKey(const ValueKey('giftPanelRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftPanelLoaded')), findsOneWidget);
    expect(repo.catalogCalls, 2);
  });

  testWidgets('目录 gift-blocked → 关面板返回 CannotSend（不进 B7）', (tester) async {
    repo.catalogError = problem('gift-blocked', status: 403);
    await open(tester);
    expect(results.single, isA<GiftPanelCannotSend>());
    expect(find.byKey(const ValueKey('giftPanelFailed')), findsNothing);
  });

  testWidgets('名称按 locale：id → nameId、en → nameEn', (tester) async {
    await open(tester);
    expect(find.text('Tulang'), findsOneWidget);
    expect(find.text('Bone'), findsNothing);
  });

  testWidgets('名称按 locale：en → nameEn', (tester) async {
    await open(tester, locale: const Locale('en'));
    expect(find.text('Bone'), findsOneWidget);
    expect(find.text('Tulang'), findsNothing);
    expect(find.text('Send a gift to Mochi'), findsOneWidget);
    expect(find.text('Snacks'), findsOneWidget);
  });
}
