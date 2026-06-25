import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_case.dart';
import 'package:tailtopia/features/consult/domain/consult_diagnosis.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/consult/presentation/consult_conversation_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// CLOSED 会话仓库替身：状态恒 CLOSED，返回一份定格诊断；病例空（避免触真 Dio）。
class _ClosedRepo extends ConsultRepository {
  _ClosedRepo({this.withDiagnosis = true}) : super(dio: Dio());

  final bool withDiagnosis;

  @override
  Future<ConsultSession> get(int id) async => ConsultSession(
        id: id,
        status: 'CLOSED',
        source: 'DIRECT',
        waitingElapsedSeconds: 0,
        timedOut: false,
        alreadyActive: false,
        vetId: 7,
        closedReason: 'RATED',
      );

  @override
  Future<ConsultDiagnosis?> diagnosis(int sessionId) async => withDiagnosis
      ? const ConsultDiagnosis(
          diagnosis: 'Infeksi kulit ringan',
          generalAdvice: 'Jaga kebersihan',
          needsMedication: true,
          medName: 'Salep XYZ',
          medFrequency: '2x sehari',
        )
      : null;

  @override
  Future<ConsultCase> caseContext(int sessionId) async => const ConsultCase(hasCase: false);
}

Future<void> _pump(WidgetTester tester, ConsultRepository repo) async {
  final router = GoRouter(
    initialLocation: '/c',
    routes: [
      GoRoute(path: '/c', builder: (c, s) => const ConsultConversationPage(sessionId: 9)),
      GoRoute(path: '/consult', builder: (c, s) => const Scaffold(body: Text('entry'))),
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
  await tester.pump(const Duration(milliseconds: 50)); // async get + diagnosis
}

void main() {
  testWidgets('CLOSED：正文平铺只读会诊结果，不显示实时聊天占位', (tester) async {
    await _pump(tester, _ClosedRepo());

    // 平铺只读诊断视图在场 + 主诊断文案落地。
    expect(find.byKey(const ValueKey('consultDiagnosisView')), findsOneWidget);
    expect(find.text('Infeksi kulit ringan'), findsOneWidget);
    expect(find.text('Salep XYZ'), findsOneWidget);

    // 不再显示实时聊天占位 / 「查看会诊结果」入口（CLOSED 改正文平铺）。
    expect(find.byKey(const ValueKey('imChatPlaceholder')), findsNothing);
    expect(find.byKey(const ValueKey('consultViewResult')), findsNothing);
  });

  testWidgets('CLOSED 无诊断：温和空态，不无限转圈', (tester) async {
    await _pump(tester, _ClosedRepo(withDiagnosis: false));
    expect(find.byKey(const ValueKey('consultDiagnosisView')), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
}
