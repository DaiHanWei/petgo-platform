import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/consult/presentation/consult_conversation_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeConsultRepository extends ConsultRepository {
  _FakeConsultRepository(this._status) : super(dio: Dio());

  final String _status;

  @override
  Future<ConsultSession> get(int id) async => ConsultSession(
        id: id,
        status: _status,
        source: 'DIRECT',
        waitingElapsedSeconds: 0,
        timedOut: false,
        alreadyActive: false,
      );
}

Future<void> _pump(WidgetTester tester, String status) async {
  final router = GoRouter(
    initialLocation: '/c',
    routes: [
      GoRoute(path: '/c', builder: (c, s) => const ConsultConversationPage(sessionId: 9)),
      GoRoute(path: '/consult', builder: (c, s) => const Scaffold(body: Text('entry'))),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(_FakeConsultRepository(status))],
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      routerConfig: router,
    ),
  ));
  await tester.pump(); // initState + first tick
  await tester.pump(const Duration(milliseconds: 50));
}

void main() {
  testWidgets('Story 5.7 F1: INTERRUPTED → 只读终态 + 重新发起入口', (tester) async {
    await _pump(tester, 'INTERRUPTED');
    expect(find.byKey(const ValueKey('consultInterruptedState')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultReconsult')), findsOneWidget);
    expect(find.text('The vet went offline. This consultation was interrupted.'), findsOneWidget);
  });

  testWidgets('IN_PROGRESS 不显示中断态', (tester) async {
    await _pump(tester, 'IN_PROGRESS');
    expect(find.byKey(const ValueKey('consultInterruptedState')), findsNothing);
    expect(find.byKey(const ValueKey('consultDisclaimerBanner')), findsOneWidget);
  });

  // ===== AC3（F12 语义对齐）：封禁中断 vs 断线可恢复，状态/文案严格区分不混淆 =====

  testWidgets('AC3: INTERRUPTED=封禁终态（已中断+重新发起，无活跃输入）', (tester) async {
    await _pump(tester, 'INTERRUPTED');
    // 终态：已中断 + 重新发起入口；无 IM 活跃输入区（不可恢复）。
    expect(find.byKey(const ValueKey('consultReconsult')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultTerminalLabel')), findsOneWidget);
  });

  testWidgets('AC3: IN_PROGRESS=断线可恢复（活跃对话，绝不显示「已中断」/重新发起）', (tester) async {
    await _pump(tester, 'IN_PROGRESS');
    // kill 断线属保护窗口可恢复——仍是活跃对话，绝不复用封禁中断的终态/文案。
    expect(find.byKey(const ValueKey('consultInterruptedState')), findsNothing);
    expect(find.byKey(const ValueKey('consultReconsult')), findsNothing);
    expect(find.byKey(const ValueKey('consultTerminalLabel')), findsNothing);
  });

  testWidgets('AC3: PENDING_CLOSE=可恢复评分窗口（非封禁终态，不显示中断态）', (tester) async {
    await _pump(tester, 'PENDING_CLOSE');
    // 兽医正常结束的评分窗口（5.6）——非封禁中断，绝不显示「已中断」终态。
    expect(find.byKey(const ValueKey('consultInterruptedState')), findsNothing);
    expect(find.byKey(const ValueKey('consultReconsult')), findsNothing);
    expect(find.byKey(const ValueKey('consultRatePromptBanner')), findsOneWidget);
  });
}
