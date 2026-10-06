import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_results_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_routes.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 2.6 · L0：结果列表页（顺序 / 行内容 / 置灰标签 / 点进带 extra / 空态 / 失败 / 重测共用 / 不含佩戴）。
void main() {
  TailsonalityResult r(String token, String letters, String energy, DateTime at) => TailsonalityResult(
        token: token,
        typeCode: '$letters-$energy',
        letters: letters,
        energy: energy,
        questionSet: 'CAT',
        resultIndex: 1,
        unlocked: false,
        contentVersion: 1,
        createdAt: at,
      );

  late List<(String, Map<String, Object>?)> events;
  Object? pushedExtra;
  setUp(() {
    events = [];
    pushedExtra = null;
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester,
      {List<TailsonalityResult>? items, bool fails = false, bool petFails = false}) async {
    final router = GoRouter(initialLocation: TailsonalityRoutes.results, routes: [
      GoRoute(path: TailsonalityRoutes.results, builder: (c, s) => const TailsonalityResultsPage()),
      GoRoute(
        path: TailsonalityRoutes.resultPattern,
        builder: (c, s) {
          pushedExtra = s.extra;
          return Scaffold(body: Text('result:${s.pathParameters['token']}'));
        },
      ),
    ]);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        petProfileProvider.overrideWith((ref) async {
          if (petFails) throw Exception('profile');
          return const PetProfile(id: 1, name: 'Momo', cardToken: 't');
        }),
        tailsonalityResultsProvider.overrideWith((ref) async {
          if (fails) throw Exception('boom');
          return items ?? const [];
        }),
      ],
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();
  }

  final three = [
    r('c', 'ENTJ', 'H', DateTime.utc(2026, 9, 22, 12)),
    r('b', 'ISFP', 'L', DateTime.utc(2026, 9, 10, 12)),
    r('a', 'INTP', 'H', DateTime.utc(2026, 8, 1, 12)),
  ];

  testWidgets('多条按接口顺序；行 = 展开代号 + 角色名 + 日期 + 灰色「Belum dibuka」；无佩戴', (tester) async {
    await pump(tester, items: three);
    expect(find.text('Riwayat Tailsonality'), findsOneWidget);
    final ys = [for (final t in ['c', 'b', 'a']) tester.getTopLeft(find.byKey(ValueKey('tsResultRow_$t'))).dy];
    expect(ys[0] < ys[1] && ys[1] < ys[2], isTrue);
    expect(find.text('E N T J - H'), findsOneWidget);
    expect(find.text('Literally CEO Banget'), findsOneWidget);
    expect(find.text('22 Sep 2026'), findsOneWidget);
    final locked = tester.widgetList<Text>(find.byKey(const ValueKey('tsRowLocked')));
    expect(locked, hasLength(3));
    expect(locked.first.data, 'Belum dibuka');
    expect(find.text('Dipakai'), findsNothing);
    expect(find.text('Pakai ini'), findsNothing);
    expect(
        find.byWidgetPredicate((w) => w.key is ValueKey && '${(w.key as ValueKey).value}'.startsWith('tsEquip')),
        findsNothing);
  });

  testWidgets('未解锁行照样可点 → push 结果路由且带 extra', (tester) async {
    await pump(tester, items: three);
    await tester.tap(find.byKey(const ValueKey('tsResultRow_b')));
    await tester.pumpAndSettle();
    expect(find.text('result:b'), findsOneWidget);
    expect(pushedExtra, isA<TailsonalityResult>());
    expect((pushedExtra! as TailsonalityResult).token, 'b');
  });

  testWidgets('空列表 → EmptyState（Mulai Tes → 说明抽屉），无吸底重测', (tester) async {
    await pump(tester, items: const []);
    expect(find.text('Belum ada hasil tes'), findsOneWidget);
    expect(find.byKey(const ValueKey('tsResultsRetake')), findsNothing);
    await tester.tap(find.text('Mulai Tes'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
  });

  testWidgets('失败 → 通用失败文案 + 重试', (tester) async {
    await pump(tester, fails: true);
    expect(find.byKey(const ValueKey('tsResultsRetry')), findsOneWidget);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.detailNetworkError), findsOneWidget);
  });

  testWidgets('空态 Mulai Tes：档案取不到 → 提示、不开抽屉', (tester) async {
    await pump(tester, items: const [], petFails: true);
    await tester.tap(find.text('Mulai Tes'));
    await tester.pump();
    await tester.pump();
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.detailNetworkError), findsOneWidget);
    expect(find.byKey(const ValueKey('tsIntroSheet')), findsNothing);
    await tester.pumpAndSettle(const Duration(seconds: 5));
  });

  testWidgets('吸底「Tes Ulang」→ 同一重测流程：确认 → 埋点 → 说明抽屉', (tester) async {
    await pump(tester, items: three);
    await tester.tap(find.byKey(const ValueKey('tsResultsRetake')));
    await tester.pumpAndSettle();
    expect(find.text('Tes ulang?'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('confirmSheetConfirm')));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'tailsonality_retake_confirmed'), hasLength(1));
    expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
  });

  test('源码：结果页与列表页都走 startTailsonalityRetake，且都不再各自引用 kTsRetakeDialog', () {
    for (final p in [
      'lib/features/tailsonality/presentation/tailsonality_result_page.dart',
      'lib/features/tailsonality/presentation/tailsonality_results_page.dart',
    ]) {
      final src = File(p).readAsStringSync();
      expect(src, contains('startTailsonalityRetake'), reason: p);
      expect(src, isNot(contains('kTsRetakeDialog')), reason: p);
    }
    expect(File('lib/features/tailsonality/presentation/tailsonality_retake.dart').readAsStringSync(),
        contains('kTsRetakeDialog'));
  });
}
