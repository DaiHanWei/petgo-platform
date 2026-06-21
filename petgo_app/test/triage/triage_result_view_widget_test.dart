import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/triage/domain/triage_archive.dart';
import 'package:tailtopia/features/triage/presentation/triage_result_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/red_alert_overlay.dart';
import 'package:tailtopia/shared/widgets/triage_result_card.dart';

Future<void> _pump(WidgetTester tester, TriageResult result,
    {TriageArchiveHandler? archiveHandler, TriageArchiveHandler? redArchiveHandler}) async {
  final container = ProviderContainer(overrides: [
    if (archiveHandler != null) triageArchiveHandlerProvider.overrideWithValue(archiveHandler),
    if (redArchiveHandler != null)
      triageRedArchiveHandlerProvider.overrideWithValue(redArchiveHandler),
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

  testWidgets('🔒 红色 → 自底滑起半屏强提醒 + 保留红色摘要（无绿黄结果卡）', (tester) async {
    await _pump(tester,
        const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.red, advice: 'x'));
    // 红色走 4.5 半屏强提醒，绝不渲染绿/黄结果卡
    expect(find.byType(RedAlertOverlay), findsOneWidget);
    expect(find.byKey(const ValueKey('triageRedSummary')), findsOneWidget);
    expect(find.byType(TriageResultCard), findsNothing);
    // 🔒 零兽医 CTA / 零地图（红色态）；绿/黄存档键不复用于红色
    expect(find.byKey(const ValueKey('triageConsultVet')), findsNothing);
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsNothing);
    // 🆕 R2（FR-3）：红色态新增「存入档案」入口（独立键），存档≠变现护栏不变
    expect(find.byKey(const ValueKey('triageRedSaveToArchive')), findsOneWidget);
    await tester.pump(const Duration(seconds: 5)); // 倒计时结束，清理定时器
  });

  testWidgets('AC4(R2·FR-3): 红色态「存入档案」→ 调红色存档回调（守零变现）', (tester) async {
    var called = false;
    await _pump(
      tester,
      const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.red, advice: 'x'),
      redArchiveHandler: (context, ref, {required triageId, required level, advice, symptom}) async {
        called = true;
        expect(triageId, 7);
        expect(level, DangerLevel.red);
      },
    );
    // 关闭 5s 锁定遮罩（唯一出口「我已知晓」），再点结果页存档入口
    await tester.pump(const Duration(seconds: 5));
    await tester.tap(find.byKey(const ValueKey('triageRedAcknowledge')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('triageRedSaveToArchive')));
    await tester.pump();
    expect(called, isTrue);
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
