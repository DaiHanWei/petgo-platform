import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/consult_ai_context.dart';
import 'package:tailtopia/features/vet/domain/vet_diagnosis_draft.dart';
import 'package:tailtopia/features/vet/domain/vet_inbox_item.dart';
import 'package:tailtopia/features/vet/presentation/vet_conversation_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 结束会话二次确认（修 20260702-212）：提交最终诊断后必须先弹「End this consultation?」
/// 确认框；取消则不调 endSession（原缺陷：提交即直接结束，无任何二次确认）。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository() : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  int endSessionCalls = 0;

  @override
  Future<VetSession> session(int sessionId) async => const VetSession(
        id: 1,
        status: 'IN_PROGRESS',
        source: 'DIRECT',
        hasAiContext: false,
        petName: 'Mochi',
      );

  @override
  Future<ConsultAiContext> aiContext(int sessionId) async =>
      const ConsultAiContext(hasAiContext: false);

  @override
  Future<ConsultAssist> assist(int sessionId) async =>
      const ConsultAssist(aiReferenceReply: '', historySummaries: []);

  @override
  Future<VetSession> endSession(int sessionId, VetDiagnosisDraft diagnosis) async {
    endSessionCalls++;
    return const VetSession(id: 1, status: 'CLOSED', source: 'DIRECT', hasAiContext: false);
  }
}

class _FakeIm implements ImService {
  @override
  Future<void> loginIfNeeded() async {}
  @override
  Future<void> logout() async {}
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  // 高视口：最终诊断页 ListView 一次性构建全部必填字段（否则离屏字段填不到）。
  tester.view.physicalSize = const Size(1400, 5000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  // 用 GoRouter：结束成功后页面 context.go('/vet/workbench') 需路由存在。
  final router = GoRouter(
    initialLocation: '/conv',
    routes: [
      GoRoute(path: '/conv', builder: (c, s) => const VetConversationPage(sessionId: 1)),
      GoRoute(path: '/vet/workbench', builder: (c, s) => const Scaffold(body: Text('workbench'))),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      vetRepositoryProvider.overrideWithValue(repo),
      imServiceProvider.overrideWithValue(_FakeIm()),
    ],
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      routerConfig: router,
    ),
  ));
  await tester.pumpAndSettle();
}

/// 驱到「提交最终诊断」：点结束会话 → 填满诊断表单 → 提交。
Future<void> _submitDiagnosis(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('vetEndSession')));
  await tester.pumpAndSettle();
  // 填满当前可见文本框（不需用药默认，5 个字段即启用提交）。
  final fields = find.byType(TextField);
  for (var i = 0; i < tester.widgetList(fields).length; i++) {
    await tester.enterText(fields.at(i), 'x');
  }
  await tester.pump();
  await tester.tap(find.byKey(const ValueKey('vetDiagSubmit')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('提交诊断后弹二次确认框（不直接结束）', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);
    await _submitDiagnosis(tester);

    // 确认框出现，endSession 尚未调用。
    expect(find.text('End this consultation?'), findsOneWidget);
    expect(repo.endSessionCalls, 0);
  });

  testWidgets('确认框选「Keep open」→ 不结束（endSession 不调用）', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);
    await _submitDiagnosis(tester);

    await tester.tap(find.text('Keep open'));
    await tester.pumpAndSettle();
    expect(find.text('End this consultation?'), findsNothing);
    expect(repo.endSessionCalls, 0);
  });

  testWidgets('确认框选「End」→ 真正结束（endSession 调用一次）', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);
    await _submitDiagnosis(tester);

    await tester.tap(find.text('End'));
    await tester.pumpAndSettle();
    expect(repo.endSessionCalls, 1);
  });
}
