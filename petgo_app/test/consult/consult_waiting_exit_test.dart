import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_case.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/consult/presentation/consult_waiting_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 5.3 R2 / AC7（决策 F12）：等待期间退出 App（含 kill 进程）→ 自动取消匹配。
/// detached = 进程终止前最后信号；仅 paused（切后台）不取消。
ConsultSession _waiting(int id, {String status = 'WAITING'}) => ConsultSession(
      id: id,
      status: status,
      source: 'DIRECT',
      waitingElapsedSeconds: 5,
      timedOut: false,
      alreadyActive: false,
    );

class _FakeConsultRepository extends ConsultRepository {
  _FakeConsultRepository() : super(dio: Dio());

  int? cancelledId;

  @override
  Future<ConsultSession> get(int id) async => _waiting(id);

  @override
  Future<ConsultCase> caseContext(int id) async => const ConsultCase(hasCase: false);

  @override
  Future<ConsultSession> cancel(int id) async {
    cancelledId = id;
    return _waiting(id, status: 'CANCELLED');
  }
}

Future<void> _pump(WidgetTester tester, _FakeConsultRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: ConsultWaitingPage(sessionId: 11),
    ),
  ));
  await tester.pump(); // 初次 _tick（WAITING，不导航）
}

void main() {
  testWidgets('AC7(F12): 等待期间 App detached → 自动取消匹配', (tester) async {
    final repo = _FakeConsultRepository();
    await _pump(tester, repo);

    // 模拟退出 App（进程终止前最后信号）→ detached 取消轮询 + 取消匹配。
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.detached);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));

    expect(repo.cancelledId, 11);
  });

  testWidgets('AC7(F12): 仅 paused（切后台）不取消，保留请求', (tester) async {
    final repo = _FakeConsultRepository();
    await _pump(tester, repo);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(repo.cancelledId, isNull);

    // 收尾：detached 取消 periodic timer，避免测试残留 pending timer。
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.detached);
    await tester.pump();
  });
}
