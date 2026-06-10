import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/triage/presentation/triage_result_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 5.4 F1：绿/黄态有「咨询兽医」升级入口；红色态严禁出现（零兽医引流，架构红线）。
Future<void> _pump(WidgetTester tester, TriageResult result) async {
  final container = ProviderContainer(overrides: [
    petProfileProvider.overrideWith((ref) => null),
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
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

void main() {
  testWidgets('AC1: 绿色态有「咨询兽医」升级入口', (tester) async {
    await _pump(tester, const TriageResult(
        status: TriageStatus.done, dangerLevel: DangerLevel.green, advice: '继续观察'));
    expect(find.byKey(const ValueKey('triageConsultVet')), findsOneWidget);
  });

  testWidgets('AC1: 黄色态有「咨询兽医」升级入口', (tester) async {
    await _pump(tester, const TriageResult(
        status: TriageStatus.done, dangerLevel: DangerLevel.yellow, advice: '密切观察'));
    expect(find.byKey(const ValueKey('triageConsultVet')), findsOneWidget);
  });

  testWidgets('🔒 AC1: 红色态严禁出现兽医升级入口（零兽医引流红线）', (tester) async {
    await _pump(tester, const TriageResult(
        status: TriageStatus.done, dangerLevel: DangerLevel.red, advice: '立即就医'));
    expect(find.byKey(const ValueKey('triageConsultVet')), findsNothing);
  });
}
