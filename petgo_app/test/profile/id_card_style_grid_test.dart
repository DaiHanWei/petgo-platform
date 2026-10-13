import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_style_grid.dart';
import 'package:tailtopia/features/profile/presentation/id_card_create_page.dart';
import 'package:tailtopia/features/profile/presentation/id_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 ktp Story 1.5：身份证列表页双 Tab、「Pilih Kartu」样式网格、new 角标、点格跳转与两条埋点。
class _Repo implements IdCardRepository {
  _Repo(this.list);
  final Future<List<IdCard>> Function() list;

  @override
  Future<List<IdCard>> listCards() => list();
  @override
  Future<IdCard> getCard(int cardId) async => IdCard(id: cardId);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final _beforeCutoff = DateTime.utc(2026, 10, 13);

Future<void> _pump(WidgetTester tester, Future<List<IdCard>> Function() list,
    {Locale locale = const Locale('en'), DateTime? now}) async {
  final router = GoRouter(
    routes: [
      GoRoute(path: '/', builder: (c, s) => IdCardPage(clock: () => now ?? _beforeCutoff)),
      GoRoute(
          path: '/profile/id-cards/create',
          builder: (c, s) => Scaffold(body: Text('create:${s.uri.queryParameters['style']}'))),
      GoRoute(
          path: '/profile/id-cards/:id',
          builder: (c, s) => Scaffold(
              body: TextButton(onPressed: () => c.pop(), child: Text('detail:${s.pathParameters['id']}')))),
    ],
  );
  tester.view.physicalSize = const Size(400, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [idCardRepositoryProvider.overrideWithValue(_Repo(list))],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> propsOf(String name) => [for (final e in events) if (e.$1 == name) e.$2];

  test('网格分组与组内顺序来自注册表', () {
    expect(IdCardStyleGrid.stylesOf(IdCardStyleGroup.identity),
        [IdCardStyle.ktp, IdCardStyle.passport, IdCardStyle.kitas, IdCardStyle.sim]);
    expect(IdCardStyleGrid.stylesOf(IdCardStyleGroup.student), [
      IdCardStyle.studentBintang,
      IdCardStyle.studentMalam,
      IdCardStyle.studentCeria,
      IdCardStyle.studentKlasik,
    ]);
  });

  testWidgets('进入页面默认 Pilih Kartu：8 格按组顺序 + 标题句与两组标题（en）', (tester) async {
    await _pump(tester, () async => const []);
    expect(tester.widget<Tab>(find.byKey(const ValueKey('idCardTab_style'))).text, 'Create new');
    expect(find.text('My Cards'), findsOneWidget);
    expect(find.text('Pick and create an ID card for your pet!'), findsOneWidget);
    expect(find.text('ID Cards'), findsOneWidget);
    expect(find.text('Student Cards'), findsOneWidget);
    final cells = [for (final s in IdCardStyle.values) find.byKey(ValueKey('idCardStyleCell_${s.name}'))];
    for (final c in cells) {
      expect(c, findsOneWidget);
    }
    // 两列：同组内 0/1 同行、2/3 在下一行；学生组整体在身份组下方。
    Rect r(IdCardStyle s) => tester.getRect(find.byKey(ValueKey('idCardStyleCell_${s.name}')));
    expect(r(IdCardStyle.ktp).top, r(IdCardStyle.passport).top);
    expect(r(IdCardStyle.passport).left, greaterThan(r(IdCardStyle.ktp).left));
    expect(r(IdCardStyle.kitas).top, greaterThan(r(IdCardStyle.ktp).bottom));
    expect(r(IdCardStyle.studentBintang).top, greaterThan(r(IdCardStyle.sim).bottom));
    expect(r(IdCardStyle.studentKlasik).left, greaterThan(r(IdCardStyle.studentCeria).left));
    // 默认不在 Kartuku：空态文字不可见。
    expect(find.text('No ID cards yet'), findsNothing);
  });

  testWidgets('id 文案：Pilih Kartu / Kartuku / 标题句 / 两组标题', (tester) async {
    await _pump(tester, () async => const [], locale: const Locale('id'));
    expect(find.text('Pilih Kartu'), findsOneWidget);
    expect(find.text('Kartuku'), findsOneWidget);
    expect(find.text('Pilih dan buat kartu identitas untuk hewanmu!'), findsOneWidget);
    // 应用栏标题 idCardTitle 在 id 下也是「Kartu Identitas」，组标题只在网格内找。
    Finder inGrid(String t) => find.descendant(of: find.byType(IdCardStyleGrid), matching: find.text(t));
    expect(inGrid('Kartu Identitas'), findsOneWidget);
    expect(inGrid('Kartu Pelajar'), findsOneWidget);
  });

  testWidgets('列表接口失败时网格照常显示；失败文案只在 Kartuku 内', (tester) async {
    await _pump(tester, () async => throw Exception('boom'));
    expect(find.byKey(const ValueKey('idCardStyleCell_ktp')), findsOneWidget);
    expect(find.text('Failed to load ID card. Please try again.'), findsNothing);
    // T-9：加载失败时 tab_viewed 省略 has_cards。
    expect(propsOf('id_card_tab_viewed'), [
      {'tab': 'style'}
    ]);
  });

  testWidgets('列表接口挂起时网格照常显示（pump）', (tester) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => IdCardPage(clock: () => _beforeCutoff)),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        idCardRepositoryProvider.overrideWithValue(_Repo(() => Completer<List<IdCard>>().future))
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
      ),
    ));
    await tester.pump();
    expect(find.byKey(const ValueKey('idCardStyleCell_sim')), findsOneWidget);
    expect(propsOf('id_card_tab_viewed'), isEmpty, reason: '首个结果出来前不报');
  });

  group('new 角标', () {
    test('idCardStyleShowsNewBadge：截止前 / 截止时刻 / 截止后', () {
      final cutoff = kIdCardNewStylesBadgeUntil;
      final before = cutoff.subtract(const Duration(seconds: 1));
      final after = cutoff.add(const Duration(days: 1));
      expect(idCardStyleShowsNewBadge(IdCardStyle.sim, now: before), isTrue);
      expect(idCardStyleShowsNewBadge(IdCardStyle.sim, now: cutoff), isFalse);
      expect(idCardStyleShowsNewBadge(IdCardStyle.sim, now: after), isFalse);
      // 非 new 款永不显示。
      expect(idCardStyleShowsNewBadge(IdCardStyle.ktp, now: before), isFalse);
      expect(idCardStyleShowsNewBadge(IdCardStyle.studentKlasik, now: before), isFalse);
      // 本地时区的 now 同样按 UTC 比较。
      expect(idCardStyleShowsNewBadge(IdCardStyle.kitas, now: before.toLocal()), isTrue);
    });

    testWidgets('截止前恰好 5 个角标，就是那 5 款', (tester) async {
      await _pump(tester, () async => const []);
      final shown = [
        for (final s in IdCardStyle.values)
          if (find.byKey(ValueKey('idCardStyleNewBadge_${s.name}')).evaluate().isNotEmpty) s
      ];
      expect(shown, [
        IdCardStyle.kitas,
        IdCardStyle.sim,
        IdCardStyle.studentBintang,
        IdCardStyle.studentMalam,
        IdCardStyle.studentCeria,
      ]);
      expect(find.text('new'), findsNWidgets(5));
    });

    testWidgets('截止后一个角标都没有', (tester) async {
      await _pump(tester, () async => const [], now: kIdCardNewStylesBadgeUntil);
      for (final s in IdCardStyle.values) {
        expect(find.byKey(ValueKey('idCardStyleNewBadge_${s.name}')), findsNothing);
      }
    });
  });

  testWidgets('点 Malam 格 → 建卡页 ?style=studentMalam + style_tapped{STUDENT, MALAM}', (tester) async {
    await _pump(tester, () async => const []);
    await tester.ensureVisible(find.byKey(const ValueKey('idCardStyleCell_studentMalam')));
    await tester.tap(find.byKey(const ValueKey('idCardStyleCell_studentMalam')));
    await tester.pumpAndSettle();
    expect(find.text('create:studentMalam'), findsOneWidget);
    expect(propsOf('id_card_style_tapped'), [
      {'card_type': 'STUDENT', 'card_variant': 'MALAM'}
    ]);
  });

  testWidgets('点 KTP 格不带 card_variant 键', (tester) async {
    await _pump(tester, () async => const []);
    await tester.tap(find.byKey(const ValueKey('idCardStyleCell_ktp')));
    await tester.pumpAndSettle();
    expect(find.text('create:ktp'), findsOneWidget);
    expect(propsOf('id_card_style_tapped'), [
      {'card_type': 'KTP'}
    ]);
  });

  group('id_card_tab_viewed', () {
    testWidgets('进入报 style；切 Kartuku 报 created；同 Tab 重复点不报（空列表 has_cards=false）', (tester) async {
      await _pump(tester, () async => const []);
      expect(propsOf('id_card_tab_viewed'), [
        {'tab': 'style', 'has_cards': false}
      ]);
      await tester.tap(find.byKey(const ValueKey('idCardTab_created')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('idCardTab_created')));
      await tester.pumpAndSettle();
      expect(propsOf('id_card_tab_viewed'), [
        {'tab': 'style', 'has_cards': false},
        {'tab': 'created', 'has_cards': false},
      ]);
      expect(find.text('No ID cards yet'), findsOneWidget);
    });

    testWidgets('首个结果前已切到 Kartuku：结果到达后先报 style 再补报 created', (tester) async {
      final pending = Completer<List<IdCard>>();
      await tester.pumpWidget(ProviderScope(
        overrides: [idCardRepositoryProvider.overrideWithValue(_Repo(() => pending.future))],
        child: MaterialApp.router(
          routerConfig: GoRouter(routes: [
            GoRoute(path: '/', builder: (c, s) => IdCardPage(clock: () => _beforeCutoff)),
          ]),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
        ),
      ));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('idCardTab_created')));
      await tester.pump(const Duration(seconds: 1));
      expect(propsOf('id_card_tab_viewed'), isEmpty);
      pending.complete(const []);
      await tester.pumpAndSettle();
      expect(propsOf('id_card_tab_viewed'), [
        {'tab': 'style', 'has_cards': false},
        {'tab': 'created', 'has_cards': false},
      ]);
    });

    testWidgets('有卡 has_cards=true', (tester) async {
      await _pump(tester, () async => [IdCard(id: 1, serialId: 12, name: 'Mochi')]);
      expect(propsOf('id_card_tab_viewed'), [
        {'tab': 'style', 'has_cards': true}
      ]);
    });

    testWidgets('D-14：从 Kartuku 点卡进详情再返回，仍停在 Kartuku、不补报', (tester) async {
      await _pump(tester, () async => [IdCard(id: 1, serialId: 12, name: 'Mochi')]);
      await tester.tap(find.byKey(const ValueKey('idCardTab_created')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('idCardTile_1')));
      await tester.pumpAndSettle();
      expect(find.text('detail:1'), findsOneWidget);
      await tester.tap(find.text('detail:1'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('idCardTile_1')), findsOneWidget, reason: '仍在 Kartuku');
      expect(propsOf('id_card_tab_viewed').map((p) => p!['tab']), ['style', 'created']);
    });
  });

  group('建卡页路由带卡款', () {
    test('无参 / 非法值回落 KTP；合法值反查', () {
      expect(IdCardCreatePage.styleFromQuery(null), IdCardStyle.ktp);
      expect(IdCardCreatePage.styleFromQuery('bogus'), IdCardStyle.ktp);
      expect(IdCardCreatePage.styleFromQuery('studentMalam'), IdCardStyle.studentMalam);
      for (final s in IdCardStyle.values) {
        expect(Uri.parse(IdCardCreatePage.locationFor(s)).queryParameters['style'], s.name);
        expect(IdCardCreatePage.styleFromQuery(s.name), s);
      }
    });

    test('路由表用 styleFromQuery 读 ?style（字面量路径保留）', () {
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      expect(src, contains("'/profile/id-cards/create'"));
      expect(src, contains("IdCardCreatePage.styleFromQuery(s.uri.queryParameters['style'])"));
    });

    test('建卡请求按卡款带 cardVariant；为 null 时不发该键', () {
      const student = CreateIdCardRequest(name: 'Oyen', cardType: 'STUDENT', cardVariant: 'BINTANG');
      expect(student.toJson()['cardVariant'], 'BINTANG');
      const ktp = CreateIdCardRequest(name: 'Oyen', cardType: 'KTP');
      expect(ktp.toJson().containsKey('cardVariant'), isFalse);
    });
  });

  test('8 张缩略图都在磁盘上、路径互不相同', () {
    final paths = IdCardStyle.values.map((s) => s.thumbnailAsset).toSet();
    expect(paths, hasLength(8));
    for (final p in paths) {
      expect(File(p).existsSync(), isTrue, reason: p);
    }
  });
}
