import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/consult/data/consult_repository.dart';
import 'package:petgo/features/consult/domain/consult_session.dart';
import 'package:petgo/features/consult/presentation/consult_entry_page.dart';
import 'package:petgo/features/consult/presentation/consult_rating_dialog.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeConsultRepository extends ConsultRepository {
  _FakeConsultRepository({required this.online, this.activeSession, this.pending})
      : super(dio: Dio());

  final bool online;
  final ConsultSession? activeSession;
  final ConsultSession? pending;
  int markPromptedCalls = 0;

  @override
  Future<ConsultAvailability> availability() async =>
      ConsultAvailability(vetOnline: online, expectedWindow: 'WEEKDAY_8_23');

  @override
  Future<ConsultSession?> active() async => activeSession;

  @override
  Future<ConsultSession?> pendingRating() async => pending;

  @override
  Future<void> markRatingPrompted(int id) async {
    markPromptedCalls++;
  }
}

ConsultSession _session(int id, String status) => ConsultSession(
      id: id,
      status: status,
      source: 'DIRECT',
      waitingElapsedSeconds: 0,
      timedOut: false,
      alreadyActive: status != 'CLOSED',
    );

Future<void> _pump(WidgetTester tester, _FakeConsultRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: ConsultEntryPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2: 在线态 → 概率性文案（不显示人数）+ 发起按钮', (tester) async {
    await _pump(tester, _FakeConsultRepository(online: true));
    expect(find.byKey(const ValueKey('consultStartButton')), findsOneWidget);
    expect(find.textContaining('usually online'), findsOneWidget);
    // 不显示在线人数（无数字人数文案）
    expect(find.textContaining('online now'), findsNothing);
  });

  testWidgets('AC5: 离线态 → 暂无兽医在线 + 恢复时段 + 软引导 AI（不强制）', (tester) async {
    await _pump(tester, _FakeConsultRepository(online: false));
    expect(find.byKey(const ValueKey('consultOfflineState')), findsOneWidget);
    expect(find.text('No vets online right now'), findsOneWidget);
    expect(find.byKey(const ValueKey('consultOfflineUseAi')), findsOneWidget);
    // 离线态不强制：无自动跳转，发起按钮不存在
    expect(find.byKey(const ValueKey('consultStartButton')), findsNothing);
  });

  testWidgets('AC2: 已有进行中 → 查看进行中跳转入口', (tester) async {
    await _pump(
      tester,
      _FakeConsultRepository(
        online: true,
        activeSession: const ConsultSession(
          id: 5,
          status: 'WAITING',
          source: 'DIRECT',
          waitingElapsedSeconds: 10,
          timedOut: false,
          alreadyActive: true,
        ),
      ),
    );
    expect(find.byKey(const ValueKey('consultViewActive')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultStartButton')), findsNothing);
  });

  testWidgets('AC5: 有进行中会话 → 推迟补弹（不弹评分，仅显示恢复入口）', (tester) async {
    final repo = _FakeConsultRepository(
      online: true,
      activeSession: _session(5, 'IN_PROGRESS'),
      pending: _session(9, 'CLOSED'), // 即便有待补弹，也因活跃会话而推迟
    );
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('consultViewActive')), findsOneWidget);
    // 推迟：不弹评分弹窗、不置 PROMPTED。
    expect(find.byType(ConsultRatingDialog), findsNothing);
    expect(repo.markPromptedCalls, 0);
  });

  testWidgets('AC5: 无进行中会话 + 有待补弹 → 补弹评分一次', (tester) async {
    final repo = _FakeConsultRepository(
      online: true,
      pending: _session(9, 'CLOSED'),
    );
    await _pump(tester, repo);
    expect(find.byType(ConsultRatingDialog), findsOneWidget);
    // 用户跳过（点击遮罩关闭）→ 弹后置 PROMPTED 不再弹（mark 在弹窗关闭后才调）。
    await tester.tapAt(const Offset(20, 20));
    await tester.pumpAndSettle();
    expect(find.byType(ConsultRatingDialog), findsNothing);
    expect(repo.markPromptedCalls, 1);
  });
}
