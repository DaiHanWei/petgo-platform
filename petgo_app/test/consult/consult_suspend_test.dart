import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/consult/presentation/consult_conversation_page.dart';

/// Story 3.8（H-5）：封禁挂起逃生横幅 + 「立即结束」→ escape → INTERRUPTED 终态。
class _FakeConsultRepository extends ConsultRepository {
  _FakeConsultRepository({required this.suspended}) : super(dio: Dio());

  final bool suspended;
  bool escapeCalled = false;

  @override
  Future<ConsultSession> get(int id) async => ConsultSession(
        id: id,
        status: 'IN_PROGRESS',
        source: 'DIRECT',
        vetId: 5,
        waitingElapsedSeconds: 0,
        timedOut: false,
        alreadyActive: false,
        suspendDeadlineAt:
            suspended ? DateTime.now().toUtc().add(const Duration(seconds: 890)) : null,
      );

  @override
  Future<ConsultSession> escapeSuspended(int id) async {
    escapeCalled = true;
    return ConsultSession(
      id: id,
      status: 'INTERRUPTED',
      source: 'DIRECT',
      waitingElapsedSeconds: 0,
      timedOut: false,
      alreadyActive: false,
      interruptedReason: 'VET_BANNED',
    );
  }
}

Future<void> _pump(WidgetTester tester, _FakeConsultRepository repo) async {
  final router = GoRouter(
    initialLocation: '/c',
    routes: [
      GoRoute(path: '/c', builder: (c, s) => const ConsultConversationPage(sessionId: 9)),
      GoRoute(path: '/consult', builder: (c, s) => const Scaffold(body: Text('entry'))),
      GoRoute(path: '/triage', builder: (c, s) => const Scaffold(body: Text('triage'))),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(repo)],
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
  testWidgets('挂起态 → 逃生横幅 + 倒计时 + 结束/上报 CTA', (tester) async {
    await _pump(tester, _FakeConsultRepository(suspended: true));

    expect(find.byKey(const ValueKey('consultSuspendBanner')), findsOneWidget);
    expect(find.text('The vet has been banned'), findsOneWidget);
    final countdown = tester.widget<Text>(find.byKey(const ValueKey('consultSuspendCountdown')));
    expect(countdown.data, matches(RegExp(r'^\d{2}:\d{2}$'))); // 服务端权威倒计时 MM:SS
    expect(find.byKey(const ValueKey('consultSuspendEndNow')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultSuspendReport')), findsOneWidget);
  });

  testWidgets('非挂起 IN_PROGRESS → 无逃生横幅', (tester) async {
    await _pump(tester, _FakeConsultRepository(suspended: false));
    expect(find.byKey(const ValueKey('consultSuspendBanner')), findsNothing);
    expect(find.byKey(const ValueKey('consultDisclaimerBanner')), findsOneWidget);
  });

  testWidgets('点「立即结束」→ escapeSuspended + 转 INTERRUPTED 终态 + Toast', (tester) async {
    final repo = _FakeConsultRepository(suspended: true);
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('consultSuspendEndNow')));
    await tester.pump(); // escapeSuspended future
    await tester.pump(); // setState + toast overlay

    expect(repo.escapeCalled, isTrue);
    expect(find.byKey(const ValueKey('consultSuspendBanner')), findsNothing); // 挂起态清
    expect(find.byKey(const ValueKey('consultInterruptedState')), findsOneWidget); // INTERRUPTED 终态
    expect(find.text('Consultation ended, refund issued.'), findsOneWidget); // Toast
    await tester.pump(const Duration(seconds: 3)); // 排空 toast 静态计时器
  });
}
