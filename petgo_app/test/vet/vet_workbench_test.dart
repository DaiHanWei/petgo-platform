import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/core/storage/secure_storage.dart';
import 'package:petgo/features/vet/data/vet_repository.dart';
import 'package:petgo/features/vet/domain/vet_login_response.dart';
import 'package:petgo/features/vet/presentation/vet_workbench_shell.dart';
import 'package:petgo/l10n/app_localizations.dart';

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
    expect(find.text('Inbox'), findsWidgets);
    expect(find.text('Active'), findsWidgets);
    expect(find.text('History'), findsWidgets);
    expect(find.text('Me'), findsWidgets);
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
    final sw = find.byKey(const ValueKey('vetOnlineSwitch'));
    expect(sw, findsOneWidget);
    expect(tester.widget<Switch>(sw).value, isFalse);

    await tester.tap(sw);
    await tester.pumpAndSettle();
    expect(repo.online, isTrue);
    expect(tester.widget<Switch>(find.byKey(const ValueKey('vetOnlineSwitch'))).value, isTrue);
  });
}
