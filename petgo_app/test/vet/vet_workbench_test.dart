import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_login_response.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_workbench_shell.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 测试用假 VetRepository：返回固定 me/在线态，不打网络。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository() : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  bool online = false;

  @override
  Future<VetMe> me() async => const VetMe(id: 1, displayName: '王医生', status: 'ACTIVE');

  @override
  Future<bool> readOnlineStatus() async => online;

  @override
  Future<bool> setOnline(bool next) async {
    online = next;
    return next;
  }

  @override
  Future<void> heartbeat() async {}

  @override
  Future<List<VetHistoryEntry>> history() async => const [];
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: VetWorkbenchShell(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 独立四 Tab 工作台（待接单/进行中/历史/我的），无用户侧发布 FAB', (tester) async {
    await _pump(tester, _FakeVetRepository());

    expect(find.byKey(const ValueKey('vetBottomNav')), findsOneWidget);
    expect(find.text('Queue'), findsWidgets);
    expect(find.text('Active'), findsWidgets);
    expect(find.text('History'), findsWidgets);
    expect(find.text('Profile'), findsWidgets);
    // 默认在待接单 Tab：空态占位
    expect(find.text('No incoming requests'), findsOneWidget);
    // 无用户侧凸起「+」发布 FAB
    expect(find.byType(FloatingActionButton), findsNothing);
  });

  testWidgets('AC2: 「我的」Tab 在线/离线开关切换', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);

    // 切到「我的」Tab
    await tester.tap(find.byIcon(Icons.person_outline));
    await tester.pumpAndSettle();

    expect(find.text('王医生'), findsOneWidget);
    // 在线状态分段控件：默认离线 → 点「Online」段切在线。
    final onlineSeg = find.byKey(const ValueKey('vetStatusOnline'));
    expect(onlineSeg, findsOneWidget);
    expect(repo.online, isFalse);

    await tester.tap(onlineSeg);
    await tester.pumpAndSettle();
    expect(repo.online, isTrue);
  });

  testWidgets('AC2: 「我的」Tab 可切到 Sibuk（前端占位态 → 后端置不接单 false）', (tester) async {
    final repo = _FakeVetRepository();
    await _pump(tester, repo);

    await tester.tap(find.byIcon(Icons.person_outline));
    await tester.pumpAndSettle();

    // 先上线 → 再切 Sibuk，验证 Sibuk 段真生效（不再是 no-op）：后端被置为不接单。
    await tester.tap(find.byKey(const ValueKey('vetStatusOnline')));
    await tester.pumpAndSettle();
    expect(repo.online, isTrue);

    await tester.tap(find.byKey(const ValueKey('vetStatusBusy')));
    await tester.pumpAndSettle();
    expect(repo.online, isFalse); // Sibuk 映射为不接单
  });
}
