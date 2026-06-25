import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_archive.dart';
import 'package:tailtopia/features/triage/presentation/triage_result_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/red_alert_overlay.dart';
import 'package:tailtopia/shared/widgets/triage_result_card.dart';

Future<void> _pump(WidgetTester tester, TriageResult result,
    {TriageArchiveHandler? archiveHandler}) async {
  final container = ProviderContainer(overrides: [
    if (archiveHandler != null) triageArchiveHandlerProvider.overrideWithValue(archiveHandler),
    petProfileProvider.overrideWith((ref) => null), // 避免红色 overlay 读档案打网络
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: TriageResultView(result: result, triageId: 7)),
    ),
  ));
  // 不用 pumpAndSettle：红色 overlay 含周期倒计时定时器会阻塞 settle。
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

void main() {
  testWidgets('AC1: 绿色页 = 暂无紧急风险 + 软引导 + 存档 + 免责', (tester) async {
    await _pump(
        tester,
        const TriageResult(
            status: TriageStatus.done,
            dangerLevel: DangerLevel.green,
            advice: '继续观察精神与进食',
            disclaimer: 'AI 仅供参考'));
    expect(find.byKey(const ValueKey('triageGreenPage')), findsOneWidget);
    expect(find.text('Relax, no danger detected'), findsOneWidget); // 绿色 header 文案
    expect(find.text('🟢'), findsOneWidget); // 非颜色单一：等级 emoji
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsOneWidget);
    expect(find.text('AI 仅供参考'), findsOneWidget); // 免责前置
  });

  testWidgets('AC2: 黄色页 = 三项同屏 + 协议块(三要素, #EEF4F7)', (tester) async {
    await _pump(
        tester,
        const TriageResult(
          status: TriageStatus.done,
          dangerLevel: DangerLevel.yellow,
          advice: '密切观察',
          medicationRef: '可补充电解质水',
          observation: TriageObservation(
            indicators: <String>['精神状态', '进食量'],
            timeWindow: '未来 12 小时',
            escalationTriggers: <String>['出现呕吐', '拒食'],
          ),
        ));
    expect(find.byKey(const ValueKey('triageYellowPage')), findsOneWidget);
    final protocol = find.byKey(const ValueKey('triageProtocolBlock'));
    expect(protocol, findsOneWidget);
    // accent-consult 浅底 #EEF4F7
    final box = tester.widget<Container>(protocol);
    expect((box.decoration as BoxDecoration).color, AppColors.triageYellowSurface);
    // 三要素（指标 chip / 时间窗口卡 / 升级触发卡）
    expect(find.text('精神状态'), findsOneWidget);
    expect(find.text('未来 12 小时'), findsOneWidget);
    expect(find.text('出现呕吐 · 拒食'), findsOneWidget); // 升级触发合并展示
    // 用药参考同屏
    expect(find.text('可补充电解质水'), findsOneWidget);
  });

  testWidgets('AC2/F4: 黄色页终结性表述被守卫拦截（不渲染"不严重"）', (tester) async {
    await _pump(
        tester,
        const TriageResult(
            status: TriageStatus.done,
            dangerLevel: DangerLevel.yellow,
            advice: '情况不严重，可以放心'));
    expect(find.textContaining('不严重'), findsNothing);
    expect(find.textContaining('可以放心'), findsNothing);
    // 回退为中性提示
    expect(find.textContaining('Please keep observing'), findsOneWidget);
  });

  testWidgets('F1: 等级以 emoji + 彩色 header 表达（非颜色单一）', (tester) async {
    await _pump(
        tester,
        const TriageResult(
            status: TriageStatus.done, dangerLevel: DangerLevel.yellow, advice: '观察'));
    expect(find.byKey(const ValueKey('triageYellowPage')), findsOneWidget);
    expect(find.text('🟡'), findsOneWidget); // 非颜色单一：等级 emoji
    expect(find.text('Consultation recommended'), findsOneWidget); // 黄色 header 文案
  });

  testWidgets('黄色态仅 2 钮（Konsultasi + Selesai），无独立存档按钮（原型 ai-result）', (tester) async {
    await _pump(
        tester,
        const TriageResult(
            status: TriageStatus.done, dangerLevel: DangerLevel.yellow, advice: '观察'));
    expect(find.byKey(const ValueKey('triageConsultVet')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageDone')), findsOneWidget);
    // 黄色态存档入口折叠进「Selesai」(P-25)，不再单列「存入档案」按钮
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsNothing);
  });

  testWidgets('🔒 红色 → 半屏强提醒；点「我已知晓」退出 AI 问诊（无结果卡 / 无存档）', (tester) async {
    // 退出走 go_router pop/go，故用 GoRouter 承载（home: 无 router context）。
    final container = ProviderContainer(
        overrides: [petProfileProvider.overrideWith((ref) => null)]);
    addTearDown(container.dispose);
    final router = GoRouter(
      initialLocation: '/triage/upload',
      routes: [
        GoRoute(path: '/triage', builder: (c, s) => const Scaffold(body: Text('triage-home'))),
        GoRoute(
            path: '/triage/upload',
            builder: (c, s) => const Scaffold(
                body: TriageResultView(
                    result: TriageResult(
                        status: TriageStatus.done, dangerLevel: DangerLevel.red, advice: 'x'),
                    triageId: 7))),
      ],
    );
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));

    // 半屏强提醒在场；绝不渲染绿/黄结果卡；红色态无任何 CTA / 无存档入口。
    expect(find.byType(RedAlertOverlay), findsOneWidget);
    expect(find.byType(TriageResultCard), findsNothing);
    expect(find.byKey(const ValueKey('triageConsultVet')), findsNothing);
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsNothing);
    expect(find.byKey(const ValueKey('triageRedSaveToArchive')), findsNothing);

    await tester.pump(const Duration(seconds: 5)); // 倒计时结束，解锁「我已知晓」
    await tester.tap(find.byKey(const ValueKey('triageRedAcknowledge')));
    await tester.pumpAndSettle();
    // 点「我已知晓」→ 退出 AI 问诊：overlay 消失 + 离开结果页回到 triage 首页。
    expect(find.byType(RedAlertOverlay), findsNothing);
    expect(find.text('triage-home'), findsOneWidget);
  });

  testWidgets('AC3: 点「存入档案」→ 调起存档回调（FR-16 触发）', (tester) async {
    var called = false;
    await _pump(
      tester,
      const TriageResult(
          status: TriageStatus.done, dangerLevel: DangerLevel.green, advice: '观察'),
      archiveHandler: (context, ref, {required triageId, required level, advice, symptom}) async {
        called = true;
        expect(triageId, 7);
        expect(level, DangerLevel.green);
      },
    );
    await tester.tap(find.byKey(const ValueKey('triageSaveToArchive')));
    await tester.pump();
    expect(called, isTrue);
  });
}
