import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/features/consult/data/consult_repository.dart';
import 'package:petgo/features/consult/domain/consult_session.dart';
import 'package:petgo/features/consult/presentation/consult_conversation_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

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
}
