import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/place/presentation/place_entry_row.dart';
import 'package:tailtopia/features/place/presentation/place_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 Story 3.5 · AD-20：首页场所入口图标换成「折叠地图 + 定位针」SVG（UI 稿 C1）。
void main() {
  Future<void> pumpRow(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        initialLocation: '/home',
        routes: [
          GoRoute(
              path: '/home',
              builder: (_, _) => const Scaffold(body: PlaceEntryRow())),
          GoRoute(
              path: PlaceListPage.routePath,
              builder: (_, _) => const Scaffold(body: Text('place list page'))),
        ],
      ),
    ));
    await tester.pumpAndSettle();
  }

  group('AC2.1 组件结构', () {
    testWidgets('图标为 20×20 的 SvgPicture，不再是 Icons.place_outlined', (tester) async {
      await pumpRow(tester);
      final row = find.byType(PlaceEntryRow);
      final icon = find.descendant(
          of: row, matching: find.byKey(const ValueKey('placeEntryIcon')));
      expect(icon, findsOneWidget);
      expect(tester.widget(icon), isA<SvgPicture>());
      expect(tester.getSize(icon), const Size(20, 20));
      expect(
          find.descendant(of: row, matching: find.byIcon(Icons.place_outlined)),
          findsNothing);
    });

    testWidgets('文字与右箭头不变', (tester) async {
      await pumpRow(tester);
      final row = find.byType(PlaceEntryRow);
      expect(
          find.descendant(
              of: row, matching: find.byIcon(Icons.chevron_right_rounded)),
          findsOneWidget);
      final l10n = AppLocalizations.of(tester.element(row));
      expect(
          find.descendant(of: row, matching: find.text(l10n.placeEntryLabel)),
          findsOneWidget);
    });

    testWidgets('点 placeEntryRow 跳到场所列表路由', (tester) async {
      await pumpRow(tester);
      await tester.tap(find.byKey(const ValueKey('placeEntryRow')));
      await tester.pumpAndSettle();
      expect(find.text('place list page'), findsOneWidget);
    });
  });

  group('AC2.2 源码断言（防误改 SVG）', () {
    final src = File('lib/features/place/presentation/place_entry_row.dart')
        .readAsStringSync();

    test('stroke-width 1.8 与 4 段 path + 1 个 circle 原文都在', () {
      for (final fragment in const [
        'stroke-width="1.8"',
        '<path d="M3 6.5l5-2 5 2 5-2v6.2"/>',
        '<path d="M3 6.5v13l5-2 3 1.2"/>',
        '<path d="M8 4.5v13"/>',
        '<path d="M18 21.5s3.6-3 3.6-5.9a3.6 3.6 0 1 0-7.2 0c0 2.9 3.6 5.9 3.6 5.9z"/>',
        '<circle cx="18" cy="15.6" r="1.2"/>',
      ]) {
        expect(src, contains(fragment), reason: fragment);
      }
    });

    test('常量内容与 kPlaceEntryMapPinSvg 一致', () {
      expect(kPlaceEntryMapPinSvg, contains('stroke="#000000"'));
      expect(kPlaceEntryMapPinSvg, isNot(contains('currentColor')));
      expect(RegExp('<path ').allMatches(kPlaceEntryMapPinSvg).length, 4);
      expect(RegExp('<circle ').allMatches(kPlaceEntryMapPinSvg).length, 1);
    });
  });
}
