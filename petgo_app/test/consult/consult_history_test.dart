import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/consult/data/consult_repository.dart';
import 'package:petgo/features/consult/domain/consult_history_item.dart';
import 'package:petgo/features/consult/domain/consult_session.dart';
import 'package:petgo/features/triage/presentation/triage_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeConsultRepository extends ConsultRepository {
  _FakeConsultRepository(this._items) : super(dio: Dio());

  final List<ConsultHistoryItem> _items;

  @override
  Future<ConsultSession?> active() async => null;

  @override
  Future<ConsultSession?> pendingRating() async => null;

  @override
  Future<ConsultHistoryPage> history({String? cursor, int limit = 20}) async =>
      ConsultHistoryPage(items: _items, hasMore: false);
}

Future<void> _pump(WidgetTester tester, List<ConsultHistoryItem> items) async {
  final container = ProviderContainer(overrides: [
    consultRepositoryProvider.overrideWithValue(_FakeConsultRepository(items)),
  ]);
  addTearDown(container.dispose);
  // 置已登录态，使问诊 Tab 加载历史（仅借 isLoggedIn=true）。
  container.read(authControllerProvider.notifier).applyVetLogin();
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: TriagePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2: 历史空态', (tester) async {
    await _pump(tester, const []);
    expect(find.byKey(const ValueKey('consultHistoryEmpty')), findsOneWidget);
  });

  testWidgets('AC2: AI + 兽医两类条目（未评分/已存档标记）', (tester) async {
    await _pump(tester, [
      ConsultHistoryItem(
        type: 'AI',
        date: DateTime(2026, 6, 2),
        triageId: 1,
        dangerLevel: 'GREEN',
        symptomSummary: '继续观察',
      ),
      ConsultHistoryItem(
        type: 'VET',
        date: DateTime(2026, 6, 1),
        sessionId: 11,
        vetDisplayName: '王医生',
        userStars: null,
        archived: true,
        terminalState: 'CLOSED',
      ),
    ]);
    expect(find.byKey(const ValueKey('historyAi_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('historyVet_11')), findsOneWidget);
    expect(find.textContaining('王医生'), findsOneWidget);
    expect(find.textContaining('Not rated'), findsOneWidget); // 未评分
    expect(find.textContaining('Archived'), findsOneWidget); // 已存档标记
  });
}
