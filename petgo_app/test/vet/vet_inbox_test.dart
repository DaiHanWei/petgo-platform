import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/core/storage/secure_storage.dart';
import 'package:petgo/features/vet/data/vet_repository.dart';
import 'package:petgo/features/vet/domain/vet_inbox_item.dart';
import 'package:petgo/features/vet/presentation/vet_inbox_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeVetRepository extends VetRepository {
  _FakeVetRepository(this._items) : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  final List<VetInboxItem> _items;
  int acceptCalls = 0;

  @override
  Future<List<VetInboxItem>> waitingList() async => _items;

  @override
  Future<VetSession> accept(int sessionId) async {
    acceptCalls++;
    return VetSession(id: sessionId, status: 'IN_PROGRESS', source: 'AI_UPGRADE', hasAiContext: true);
  }
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (c, s) => const VetInboxPage()),
      GoRoute(
        path: '/vet/conversation/:id',
        builder: (c, s) => const Scaffold(body: Text('conversation')),
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
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
  testWidgets('待接单空态占位', (tester) async {
    await _pump(tester, _FakeVetRepository(const []));
    expect(find.text('No incoming requests'), findsOneWidget);
  });

  testWidgets('AC1: 待接单项展示 AI 摘要 + 接单调用', (tester) async {
    final repo = _FakeVetRepository(const [
      VetInboxItem(
        sessionId: 5,
        source: 'AI_UPGRADE',
        aiDangerLevel: 'YELLOW',
        symptomPreview: '呕吐两次',
        imageCount: 2,
        waitingElapsedSeconds: 30,
      ),
    ]);
    await _pump(tester, repo);

    expect(find.text('呕吐两次'), findsOneWidget);
    expect(find.text('AI: watch closely'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetAccept_5')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('vetAccept_5')));
    await tester.pump();
    expect(repo.acceptCalls, 1);
  });

  testWidgets('DIRECT 项无 AI 摘要', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 6, source: 'DIRECT', imageCount: 0, waitingElapsedSeconds: 5),
    ]));
    expect(find.text('Direct request'), findsOneWidget);
  });
}
