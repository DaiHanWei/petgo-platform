import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/consult/domain/consult_diagnosis.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_history_detail_page.dart';
import 'package:tailtopia/features/vet/presentation/vet_history_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Bug 20260701-196：兽医「历史」卡「View」原是裸 Text 无点击手势（点击无反应）。
/// 修复：整卡可点 → 只读问诊结果页（复用 ConsultDiagnosisView），无诊断 → 空态。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({this.entries = const [], this.diag})
      : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  final List<VetHistoryEntry> entries;
  final ConsultDiagnosis? diag;
  int? diagnosisCalledWith;

  @override
  Future<List<VetHistoryEntry>> history() async => entries;

  @override
  Future<ConsultDiagnosis?> diagnosis(int sessionId) async {
    diagnosisCalledWith = sessionId;
    return diag;
  }
}

VetHistoryEntry _entry(int id) => VetHistoryEntry(
      sessionId: id,
      petName: 'Mochi',
      summary: 'muntah busa putih',
      date: '2026-07-01',
      terminalState: 'CLOSED',
      dangerLevel: 'YELLOW',
      ownerHandle: 'yny',
      petSpecies: 'CAT',
    );

const _diag = ConsultDiagnosis(diagnosis: 'Gastritis akut ringan', generalAdvice: 'Puasa 12 jam');

Future<void> _pumpDetail(WidgetTester tester, _FakeVetRepository repo, {VetHistoryEntry? entry}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: VetHistoryDetailPage(sessionId: 11, entry: entry),
    ),
  ));
  await tester.pump(); // diagnosis future 解析
  await tester.pump();
}

void main() {
  testWidgets('Bug196: 结果页按 sessionId 拉诊断并只读渲染', (tester) async {
    final repo = _FakeVetRepository(diag: _diag);
    await _pumpDetail(tester, repo, entry: _entry(11));

    expect(repo.diagnosisCalledWith, 11);
    expect(find.byKey(const ValueKey('consultDiagnosisView')), findsOneWidget);
    expect(find.text('Gastritis akut ringan'), findsOneWidget);
  });

  testWidgets('Bug196: 无诊断（如 INTERRUPTED 未提交）→ 空态不崩', (tester) async {
    final repo = _FakeVetRepository(diag: null);
    await _pumpDetail(tester, repo);

    expect(find.byKey(const ValueKey('consultDiagnosisView')), findsNothing);
    expect(find.text('No diagnosis was recorded for this consultation.'), findsOneWidget);
  });

  testWidgets('Bug196: 历史卡整卡可点 → 导航进结果页（原 View 无手势→点击无反应）', (tester) async {
    final repo = _FakeVetRepository(entries: [_entry(11)], diag: _diag);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const VetHistoryPage()),
      GoRoute(
        path: '/vet/history/:id',
        builder: (c, s) => VetHistoryDetailPage(
          sessionId: int.parse(s.pathParameters['id']!),
          entry: s.extra as VetHistoryEntry?,
        ),
      ),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [vetRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
      ),
    ));
    await tester.pump(); // 历史列表加载
    await tester.pump();

    // 修复核心：卡片包了带 key 的可点控件（原 bug：View 是裸 Text，无 hit-test 响应体）
    final card = find.byKey(const ValueKey('vetHistoryView_11'));
    expect(card, findsOneWidget);

    await tester.tap(card);
    await tester.pump(); // 导航
    await tester.pump(); // 结果页 diagnosis future
    await tester.pump();

    expect(repo.diagnosisCalledWith, 11);
    expect(find.byKey(const ValueKey('consultDiagnosisView')), findsOneWidget);
  });
}
