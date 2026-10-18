import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/consult/domain/consult_request.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/consult/presentation/consult_entry_page.dart';
import 'package:tailtopia/features/consult/presentation/consult_rating_dialog.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

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
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pump(const Duration(milliseconds: 50));
}

void main() {
  testWidgets('无兽医在线 → 进页直接显示离线引导（不必先点 Mulai）', (tester) async {
    await _pump(tester, _FakeConsultRepository(online: false));
    // 进页即查在线态：无兽医 → 直接离线引导，不再先展示就绪态/发起按钮。
    expect(find.byKey(const ValueKey('consultOfflineState')), findsOneWidget);
    expect(find.text('No vets online right now'), findsOneWidget);
    expect(find.byKey(const ValueKey('consultOfflineUseAi')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultStartButton')), findsNothing);
  });

  testWidgets('有兽医在线 → 进页显示就绪态（流程 + 发起按钮，无离线引导）', (tester) async {
    await _pump(tester, _FakeConsultRepository(online: true));
    expect(find.byKey(const ValueKey('consultStartButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('consultOfflineState')), findsNothing);
    // 删除的「Vets available now」概率性提示条不再出现。
    expect(find.textContaining('usually online'), findsNothing);
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
    // 全屏评价页较高，设大视口让「Lewati」可见可点。
    tester.view.physicalSize = const Size(400, 1000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _FakeConsultRepository(
      online: true,
      pending: _session(9, 'CLOSED'),
    );
    await _pump(tester, repo);
    expect(find.byType(ConsultRatingDialog), findsOneWidget);
    // 用户跳过（点「Lewati」关闭全屏评价页）→ 弹后置 PROMPTED 不再弹（mark 在页关闭后才调）。
    // 在线态绿脉冲常驻 → 用固定帧推进而非 pumpAndSettle。
    await tester.tap(find.byKey(const ValueKey('ratingSkip')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(ConsultRatingDialog), findsNothing);
    expect(repo.markPromptedCalls, 1);
  });

  // ---- V1.4.0 multi-pet Story 4.1 · AC7.5（H4）：兽医咨询自填路径先选宠物；AI 升级路径不弹 ----
  group('Story 4.1 H4 选宠物', () {
    const pets = [
      PetListItem(id: 7, name: 'Momo', isDefault: true),
      PetListItem(id: 8, name: 'Kopi'),
    ];

    Future<_UpgradeRepo> pumpRouted(WidgetTester tester, {int? triageTaskId}) async {
      SharedPreferences.setMockInitialValues({});
      final repo = _UpgradeRepo();
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => ConsultEntryPage(triageTaskId: triageTaskId)),
        GoRoute(path: '/consult/case', builder: (c, s) => Scaffold(body: Text('case:${s.uri.query}'))),
        GoRoute(
            path: '/consult/vet-request/waiting/:token',
            builder: (c, s) => Scaffold(body: Text('waiting:${s.pathParameters['token']}'))),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          consultRepositoryProvider.overrideWithValue(repo),
          petListProvider.overrideWith((ref) async => pets),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pump(const Duration(milliseconds: 50));
      return repo;
    }

    Future<void> frames(WidgetTester tester) async {
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    testWidgets('≥2 只：Mulai konsultasi → 弹 H4 → Lanjut → /consult/case?petId=', (tester) async {
      await pumpRouted(tester);
      await tester.tap(find.byKey(const ValueKey('consultStartButton')));
      await frames(tester);
      expect(find.text('Konsultasi untuk siapa?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petPickerRow-8')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
      await frames(tester);
      expect(find.text('case:petId=8'), findsOneWidget);
    });

    testWidgets('≥2 只：取消 H4 → 停留入口页，不进病例页', (tester) async {
      await pumpRouted(tester);
      await tester.tap(find.byKey(const ValueKey('consultStartButton')));
      await frames(tester);
      await tester.tapAt(const Offset(10, 10));
      await frames(tester);
      expect(find.textContaining('case:'), findsNothing);
      expect(find.byKey(const ValueKey('consultStartButton')), findsOneWidget);
    });

    testWidgets('AI 升级路径不弹选择、不传 petId（后端按分诊任务的宠物）', (tester) async {
      final repo = await pumpRouted(tester, triageTaskId: 42);
      await tester.tap(find.byKey(const ValueKey('consultStartButton')));
      await frames(tester);
      expect(find.text('Konsultasi untuk siapa?'), findsNothing);
      expect(repo.upgradeCalls, [42]);
      expect(find.text('waiting:tok-1'), findsOneWidget);
    });
  });
}

/// Story 4.1：带在线态与升级入队的 fake（升级只记 triageTaskId，返回入队中请求）。
class _UpgradeRepo extends _FakeConsultRepository {
  _UpgradeRepo() : super(online: true);
  final List<int> upgradeCalls = [];

  @override
  Future<bool> vetOnline() async => true;

  @override
  Future<ConsultRequest> createRequestFromUpgrade(int triageTaskId) async {
    upgradeCalls.add(triageTaskId);
    return ConsultRequest.fromJson(const {'requestToken': 'tok-1', 'state': 'QUEUEING'});
  }
}
