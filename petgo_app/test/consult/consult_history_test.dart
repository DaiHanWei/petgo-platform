import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_history_item.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/triage/presentation/triage_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

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
  // 换肤后问诊 hub 较长（Momo 头 + 双入口卡 + 在线兽医条），历史在下方；
  // 用高视口让整页布局，历史项落在可见/缓存区（否则 ListView 懒加载不构建）。
  tester.view.physicalSize = const Size(1170, 6000);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

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
  // AI 卡在线脉冲为常驻动画，pumpAndSettle 不收敛——固定帧推进（含历史 FutureBuilder 微任务）。
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pump(const Duration(milliseconds: 50));
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
