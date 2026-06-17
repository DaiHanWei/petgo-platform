import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/presentation/triage_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

Future<void> _pump(WidgetTester tester) async {
  final container = ProviderContainer();
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      locale: Locale('id'), // 文案已迁 arb：固定印尼语以断言 'Tanya AI (Triase)' 等 id 文案
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TriagePage(),
    ),
  ));
  // 在线脉冲点为常驻动画，pumpAndSettle 不会收敛——用固定帧推进。
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  testWidgets('AC1: 双入口卡平级（AI 分诊 + 兽医咨询，TailTopia Prototype 换肤）', (tester) async {
    await _pump(tester);
    expect(find.byKey(const ValueKey('triageEntryAI')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageEntryVet')), findsOneWidget);
    // 双入口标题 + CTA（换肤后印尼语文案）。
    expect(find.text('Tanya AI (Triase)'), findsOneWidget);
    expect(find.text('Chat Dokter Hewan'), findsOneWidget);
    expect(find.text('Mulai triase'), findsOneWidget);
    expect(find.text('Mulai konsultasi'), findsOneWidget);
  });

  testWidgets('AC1/FR-0C: 游客点兽医咨询入口触发强登录引导（Story 5.8 接入 5.3 发起）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryVet')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC1/FR-0C: 游客点 AI 入口触发强登录引导（不进入上传页）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });
}
