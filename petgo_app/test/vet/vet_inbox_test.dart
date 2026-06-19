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

  testWidgets('AC5: 抢单卡 RINGKASAN AI 摘要 + 等级徽章，点 Detail 进详情/预览页', (tester) async {
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
    expect(find.text('Needs consult'), findsOneWidget); // 等级徽章（queue 措辞）
    expect(find.text('AI SUMMARY'), findsOneWidget); // RINGKASAN AI 框
    expect(find.text('2 photos attached'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetRequestCard_5')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('vetDetail_5')));
    await tester.pumpAndSettle();
    expect(find.text('detail 5'), findsOneWidget);
  });

  testWidgets('RED 卡显紧急横幅', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 9, source: 'AI_UPGRADE', aiDangerLevel: 'RED', symptomPreview: 'darurat', imageCount: 1, waitingElapsedSeconds: 20),
    ]));
    expect(find.text('⚠️ IMMEDIATE ATTENTION'), findsOneWidget);
    expect(find.text('Urgent'), findsOneWidget);
  });

  testWidgets('宠物身份块渲染（名 + meta 行）', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(
        sessionId: 11, source: 'AI_UPGRADE', aiDangerLevel: 'YELLOW',
        symptomPreview: 'lemas', imageCount: 1, waitingElapsedSeconds: 30,
        petName: 'Oyen', petSpecies: 'CAT', petAgeMonths: 24, ownerHandle: 'rani',
      ),
    ]));
    expect(find.text('Oyen'), findsOneWidget);
    expect(find.text('Cat · 2 yr · @rani'), findsOneWidget);
  });

  testWidgets('无 petName → 降级不显身份块', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 12, source: 'AI_UPGRADE', aiDangerLevel: 'GREEN', symptomPreview: 'ok', imageCount: 0, waitingElapsedSeconds: 5),
    ]));
    // 卡仍在，但无 @ 主人 meta（身份块未渲染）
    expect(find.byKey(const ValueKey('vetRequestCard_12')), findsOneWidget);
    expect(find.textContaining('@'), findsNothing);
  });

  testWidgets('Lewati 跳过 → 卡片本地移除', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 7, source: 'AI_UPGRADE', aiDangerLevel: 'GREEN', symptomPreview: 'ringan', imageCount: 0, waitingElapsedSeconds: 10),
    ]));
    expect(find.byKey(const ValueKey('vetRequestCard_7')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('vetSkip_7')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('vetRequestCard_7')), findsNothing);
  });

  testWidgets('DIRECT 项无 AI 摘要框', (tester) async {
    await _pump(tester, _FakeVetRepository(const [
      VetInboxItem(sessionId: 6, source: 'DIRECT', imageCount: 0, waitingElapsedSeconds: 5),
    ]));
    expect(find.text('Direct request'), findsOneWidget);
    expect(find.text('AI SUMMARY'), findsNothing);
  });
}
