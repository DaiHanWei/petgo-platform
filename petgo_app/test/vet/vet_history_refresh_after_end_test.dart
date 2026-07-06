import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_history_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 回归：兽医结束会话后，历史 Tab 应能重拉出刚结束（PENDING_CLOSE）的会话。
/// 后端已证实 /history 返回该会话（status=200），故此处仅验前端刷新 + 渲染。
///
/// fake：首次 history()（登录期 initState 拉）返回旧列表；结束后每次拉返回含新会话的列表。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository() : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  int calls = 0;

  @override
  Future<List<VetHistoryEntry>> history() async {
    calls++;
    if (calls == 1) {
      return [_entry(40, 'CLOSED', 'lama')];
    }
    // 兽医刚结束 → 后端把 PENDING_CLOSE 会话置于列表首位
    return [_entry(69, 'PENDING_CLOSE', 'baru diakhiri'), _entry(40, 'CLOSED', 'lama')];
  }
}

VetHistoryEntry _entry(int id, String state, String summary) => VetHistoryEntry(
      sessionId: id,
      petName: 'dede',
      summary: summary,
      date: '2026-07-06',
      terminalState: state,
      ownerHandle: 'Liu Xi',
      petSpecies: 'DOG',
    );

void main() {
  testWidgets('结束会话后切历史 Tab（bump）→ 刚结束的 PENDING_CLOSE 会话出现在历史', (tester) async {
    final container = ProviderContainer(
      overrides: [vetRepositoryProvider.overrideWithValue(_FakeVetRepository())],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: const VetHistoryPage(),
      ),
    ));
    await tester.pumpAndSettle();

    // 登录期只有旧会话。
    expect(find.byKey(const ValueKey('vetHistoryView_40')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetHistoryView_69')), findsNothing);

    // 模拟工作台切到历史 Tab（_select(2)）：bump 刷新信号。
    container.read(vetHistoryRefreshProvider.notifier).bump();
    await tester.pumpAndSettle();

    // 刚结束的会话应出现。
    expect(find.byKey(const ValueKey('vetHistoryView_69')), findsOneWidget,
        reason: '结束后 bump 应重拉并渲染 PENDING_CLOSE 会话');
  });
}
