import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/consult_ai_context.dart';
import 'package:tailtopia/features/vet/domain/vet_inbox_item.dart';
import 'package:tailtopia/features/vet/presentation/vet_conversation_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 方案 C（bug）：AI 参考回复面板每个会话只自动弹一次——首次进入展示并持久化「已展示」，
/// 退出重进不再自动弹（避免反复遮挡页面）。顶部 Template chip 仍可手动重新展开（既有行为）。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository() : super(dio: Dio(), tokenStore: InMemoryTokenStore());

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
      const ConsultAssist(aiReferenceReply: 'Berikan cairan sedikit demi sedikit.', historySummaries: []);
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

void main() {
  testWidgets('AI 参考回复每会话只自动弹一次：首次弹，退出重进不再自动弹', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final repo = _FakeVetRepository();

    // 首次进入 → 自动展示面板。
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('vetAssistPanel')), findsOneWidget);

    // 模拟退出重进（同一 sessionId 的全新页面）→ 读到已记住的「已展示」→ 不再自动弹。
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('vetAssistPanel')), findsNothing);
  });
}
