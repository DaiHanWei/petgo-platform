import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_inbox_item.dart';
import 'package:tailtopia/features/vet/presentation/vet_inbox_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeVetRepository extends VetRepository {
  _FakeVetRepository(this._items) : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  final List<VetInboxItem> _items;

  @override
  Future<List<VetInboxItem>> waitingList() async => _items;
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (c, s) => const VetInboxPage()),
      GoRoute(
        path: '/vet/request/:id',
        builder: (c, s) => Scaffold(body: Text('detail ${s.pathParameters['id']}')),
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

  testWidgets('AC5: 抢单请求卡片展示 AI 摘要，点卡片进请求详情/预览页', (tester) async {
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
    // 抢单模式：卡片整体可点（无列表内联接单按钮）。
    expect(find.byKey(const ValueKey('vetRequestCard_5')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('vetRequestCard_5')));
    await tester.pumpAndSettle();
    // 进入请求详情/预览页。
    expect(find.text('detail 5'), findsOneWidget);
  });

  testWidgets('DIRECT 项无 AI 摘要', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 6, source: 'DIRECT', imageCount: 0, waitingElapsedSeconds: 5),
    ]));
    expect(find.text('Direct request'), findsOneWidget);
  });
}
