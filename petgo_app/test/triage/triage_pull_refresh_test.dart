import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_history_item.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/triage/presentation/triage_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 下拉刷新问诊页 → 重拉进行中会话 + 问诊历史（聊天列表）。
class _CountingConsultRepository extends ConsultRepository {
  _CountingConsultRepository() : super(dio: Dio());

  int activeCalls = 0;
  int historyCalls = 0;

  @override
  Future<ConsultSession?> active() async {
    activeCalls++;
    return null;
  }

  @override
  Future<ConsultSession?> pendingRating() async => null;

  @override
  Future<ConsultHistoryPage> history({String? cursor, int limit = 20}) async {
    historyCalls++;
    return const ConsultHistoryPage(items: <ConsultHistoryItem>[], hasMore: false);
  }
}

void main() {
  testWidgets('问诊页下拉 → 重拉进行中 + 历史（聊天列表）', (tester) async {
    tester.view.physicalSize = const Size(1170, 6000);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final repo = _CountingConsultRepository();
    final container = ProviderContainer(overrides: [
      consultRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyVetLogin(); // 仅借 isLoggedIn=true

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: TriagePage(),
      ),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.byType(RefreshIndicator), findsOneWidget);
    final baseActive = repo.activeCalls;
    final baseHistory = repo.historyCalls;
    expect(baseActive, greaterThanOrEqualTo(1));
    expect(baseHistory, greaterThanOrEqualTo(1));

    // 程序化触发 RefreshIndicator.onRefresh（等价用户下拉；避免脉冲动画致 pumpAndSettle 不收敛）。
    final RefreshIndicatorState refresh =
        tester.state<RefreshIndicatorState>(find.byType(RefreshIndicator));
    unawaited(refresh.show());
    await tester.pump();
    await tester.pump(const Duration(seconds: 1)); // onRefresh + future 落定
    await tester.pump(const Duration(seconds: 1));

    expect(repo.activeCalls, greaterThan(baseActive), reason: '下拉应重拉进行中会话');
    expect(repo.historyCalls, greaterThan(baseHistory), reason: '下拉应重拉问诊历史');
  });
}
