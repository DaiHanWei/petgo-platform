import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/pet_passport/data/pet_passport_repository.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/pet_passport/presentation/default_stamp_assets.dart';
import 'package:tailtopia/features/pet_passport/presentation/pet_passport_page.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/widgets/place_stamp_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 1.2 · L0：护照页三态（AC5）。
void main() {
  PassportStamp stamp(int i, {int visits = 1}) => PassportStamp(
        placeToken: 't$i'.padRight(32, '0'),
        placeName: 'Tempat $i',
        placeType: PlaceType.values[i % PlaceType.values.length],
        available: true,
        visitCount: visits,
        firstVisitDate: DateTime(2026, 9, 1 + i),
      );

  PetPassport passport(List<PassportStamp> stamps) =>
      PetPassport(petName: 'Momo', passportNo: 'TT02P2600128', stamps: stamps);

  Future<void> pump(WidgetTester tester, PetPassport p, {String? focus}) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(400, 1000);
    addTearDown(tester.view.reset);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => PetPassportPage(focus: focus)),
      GoRoute(path: '/places', builder: (c, s) => const Scaffold(body: Text('places-list'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [petPassportProvider.overrideWith((ref) async => p)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('B1 空态：无 ⊞、无翻页箭头、「0 cap」、吸底「Cari Tempat」→ /places', (tester) async {
    await pump(tester, passport(const []));

    expect(find.byKey(const ValueKey('passportEmpty')), findsOneWidget);
    expect(find.text('Belum ada cap'), findsOneWidget);
    expect(find.text('0 cap'), findsOneWidget);
    expect(find.byKey(const ValueKey('passportToggleGrid')), findsNothing);
    expect(find.byKey(const ValueKey('passportPrev')), findsNothing);
    expect(find.byKey(const ValueKey('passportNext')), findsNothing);
    expect(find.text('TT02P2600128'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('passportFindPlaces')));
    await tester.pumpAndSettle();
    expect(find.text('places-list'), findsOneWidget);
  });

  testWidgets('B2 单章：页脚分母 = 已集章数；×N 仅 N≥2', (tester) async {
    await pump(tester, passport([stamp(0), stamp(1, visits: 3), stamp(2)]));

    expect(find.text('Cap 1 / 3'), findsOneWidget);
    expect(find.byKey(const ValueKey('passportVisitBadge')), findsNothing, reason: '第 1 枚只到访 1 次');

    await tester.tap(find.byKey(const ValueKey('passportNext')));
    await tester.pumpAndSettle();
    expect(find.text('Cap 2 / 3'), findsOneWidget);
    expect(find.text('×3'), findsOneWidget);
  });

  testWidgets('focus：停在该章；找不到停第 1 页', (tester) async {
    await pump(tester, passport([stamp(0), stamp(1), stamp(2)]), focus: stamp(2).placeToken);
    expect(find.text('Cap 3 / 3'), findsOneWidget);
    expect(find.text('Tempat 2'), findsOneWidget);
  });

  testWidgets('focus 找不到 → 第 1 页', (tester) async {
    await pump(tester, passport([stamp(0), stamp(1)]), focus: 'nope');
    expect(find.text('Cap 1 / 2'), findsOneWidget);
  });

  testWidgets('B2b 纵览：12 格一页、点章回单章并停在那一页', (tester) async {
    await pump(tester, passport([for (var i = 0; i < 14; i++) stamp(i)]));

    await tester.tap(find.byKey(const ValueKey('passportToggleGrid')));
    await tester.pumpAndSettle();
    expect(find.text('14 cap · Halaman 1'), findsOneWidget);
    expect(find.byKey(const ValueKey('passportGridCell_11')), findsOneWidget);
    expect(find.byKey(const ValueKey('passportGridCell_12')), findsNothing, reason: '第 13 枚在第 2 页');

    await tester.tap(find.byKey(const ValueKey('passportGridCell_4')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('passportToggleGrid')), findsOneWidget, reason: '回到单章');
    expect(find.text('Cap 5 / 14'), findsOneWidget);
  });

  testWidgets('复审：纵览第 4 行完整落在内页块内（不被裁切、可点）', (tester) async {
    await pump(tester, passport([for (var i = 0; i < 12; i++) stamp(i)]));
    await tester.tap(find.byKey(const ValueKey('passportToggleGrid')));
    await tester.pumpAndSettle();
    final pager = tester.getRect(find.byKey(const ValueKey('passportGridPager')));
    final last = tester.getRect(find.byKey(const ValueKey('passportGridCell_11')));
    expect(last.bottom, lessThanOrEqualTo(pager.bottom + 0.5));
    await tester.tap(find.byKey(const ValueKey('passportGridCell_11')));
    await tester.pumpAndSettle();
    expect(find.text('Cap 12 / 12'), findsOneWidget);
  });

  testWidgets('无 ⋯、无付费、无 Bagikan', (tester) async {
    await pump(tester, passport([stamp(0)]));
    expect(find.byIcon(Icons.more_horiz), findsNothing);
    expect(find.byIcon(Icons.more_vert), findsNothing);
    expect(find.text('Bagikan'), findsNothing);
    expect(find.textContaining('Rp'), findsNothing);
  });

  group('默认章素材（D-21：不入库、缺文件回落占位）', () {
    test('7 类都映射到约定路径（穷举）', () {
      for (final t in PlaceType.values) {
        expect(defaultStampAssetFor(t), startsWith('assets/place_stamp/'));
      }
      expect(defaultStampAssetFor(null), isNull);
    });

    test('素材目录存在 README 且在 pubspec 声明', () {
      expect(File('assets/place_stamp/README.md').existsSync(), isTrue);
      expect(File('pubspec.yaml').readAsStringSync(), contains('- assets/place_stamp/'));
    });

    testWidgets('文件缺失时不崩、显示代码绘制的占位章', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Center(child: PlaceStampView(placeType: PlaceType.park, size: 96)),
      ));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byKey(const ValueKey('placeStampPlaceholder')), findsOneWidget);
    });
  });
}
