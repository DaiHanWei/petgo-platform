import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_login_response.dart';
import 'package:tailtopia/features/vet/domain/vet_queue.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_inbox_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 VetRepository：dashboard 头部用 me/history/在线态，队列用计费流 vetQueue（Story 3.6）。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({this.queueCount = 0, this.historyCount = 0})
      : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  final int queueCount;
  final int historyCount;
  bool online = false;

  @override
  Future<VetMe> me() async => const VetMe(id: 1, displayName: '王医生', status: 'ACTIVE');

  @override
  Future<VetQueue> vetQueue() async => VetQueue(
        available: List.generate(
          queueCount,
          (i) => VetQueueItem(requestToken: 'req-$i', waitingSeconds: 5),
        ),
      );

  @override
  Future<List<VetHistoryEntry>> history() async => List.generate(
        historyCount,
        (i) => VetHistoryEntry(
          sessionId: i,
          petName: 'p$i',
          summary: 's',
          date: '2026-06-17',
          terminalState: 'CLOSED',
        ),
      );

  @override
  Future<bool> readOnlineStatus() async => online;

  @override
  Future<bool> setOnline(bool next) async {
    online = next;
    return next;
  }

  @override
  Future<void> heartbeat() async {}
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: VetInboxPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('深色顶栏渲染医生名 + 在线开关', (tester) async {
    await _pump(tester, _FakeVetRepository());
    expect(find.byKey(const ValueKey('vetTopBarName')), findsOneWidget);
    expect(find.text('王医生'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetTopBarOnlineToggle')), findsOneWidget);
  });

  testWidgets('3 统计卡：队列=列表数、完成=今日 history 数、评分占位「—」', (tester) async {
    final repo = _FakeVetRepository(queueCount: 2, historyCount: 3);
    await _pump(tester, repo);

    expect(find.text('Queue'), findsOneWidget);
    expect(find.text('Done'), findsOneWidget);
    expect(find.text('Rating'), findsOneWidget);
    expect(find.text('2'), findsOneWidget); // 队列=2
    expect(find.text('3'), findsOneWidget); // 完成=3
    expect(find.text('—'), findsOneWidget); // 评分无端点 → 占位
  });

  testWidgets('空队列 → 分区头 (0) + 空态', (tester) async {
    await _pump(tester, _FakeVetRepository(historyCount: 0));
    expect(find.text('CURRENT QUEUE (0)'), findsOneWidget);
    expect(find.text('No incoming requests'), findsOneWidget);
  });

  testWidgets('顶栏在线药丸 → 弹 V-st 状态抽屉 → 选 Online + Simpan 写 setOnline', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);

    final toggle = find.byKey(const ValueKey('vetTopBarOnlineToggle'));
    expect(toggle, findsOneWidget);
    expect(find.text('Offline'), findsOneWidget); // 默认离线短标签

    // 点药丸不再直接切换，而是弹 V-st 状态切换抽屉。
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('vetStatusSheet')), findsOneWidget);
    expect(repo.online, isFalse); // 仅打开抽屉，未写

    // 选 Online → Simpan → 持久化 + 药丸更新。
    await tester.tap(find.byKey(const ValueKey('vetStatusOptionOnline')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('vetStatusSave')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('vetStatusSheet')), findsNothing); // 抽屉已关
    expect(repo.online, isTrue);
    expect(find.text('Online'), findsOneWidget); // 药丸更新为在线
  });

  testWidgets('V-st 抽屉选 Sibuk + Simpan → 后端不接单(false)，药丸显示 Busy（前端占位态）', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('vetTopBarOnlineToggle')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('vetStatusOptionBusy')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('vetStatusSave')));
    await tester.pumpAndSettle();

    expect(repo.online, isFalse); // Sibuk 映射为不接单
    expect(find.text('Busy'), findsOneWidget); // 药丸保留前端 Sibuk 显示
  });
}
