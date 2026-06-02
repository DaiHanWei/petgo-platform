import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/triage/presentation/triage_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';

Future<void> _pump(WidgetTester tester) async {
  final container = ProviderContainer();
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TriagePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 双入口卡平级 + 均显「免费」', (tester) async {
    await _pump(tester);
    expect(find.byKey(const ValueKey('triageEntryAI')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageEntryVet')), findsOneWidget);
    expect(find.text('Free'), findsNWidgets(2)); // 两卡均免费
  });

  testWidgets('AC1/FR-0C: 游客点兽医咨询入口触发强登录引导（Story 5.8 接入 5.3 发起）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryVet')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC1/FR-0C: 游客点 AI 入口触发强登录引导（不进入上传页）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });
}
