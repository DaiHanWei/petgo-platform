import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/triage/data/triage_repository.dart';
import 'package:petgo/features/triage/domain/triage_result_controller.dart';
import 'package:petgo/features/triage/domain/triage_upload_controller.dart';
import 'package:petgo/features/triage/presentation/triage_upload_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeTriageRepo implements TriageRepository {
  _FakeTriageRepo(this.results);
  final List<TriageResult> results;
  int calls = 0;

  @override
  Future<int> submitTriage({
    String? symptomText,
    List<String> imageObjectKeys = const <String>[],
    int? petId,
    String? idempotencyKey,
  }) async =>
      1;

  @override
  Future<TriageResult> pollTriage(int triageId) async {
    final r = results[calls < results.length ? calls : results.length - 1];
    calls++;
    return r;
  }
}

ProviderContainer _container(_FakeTriageRepo repo) {
  final c = ProviderContainer(overrides: [
    triageRepositoryProvider.overrideWithValue(repo),
    triagePollIntervalProvider.overrideWithValue(const Duration(milliseconds: 1)),
    triageTimeoutProvider.overrideWithValue(const Duration(milliseconds: 120)),
  ]);
  addTearDown(c.dispose);
  return c;
}

Future<void> _pump(WidgetTester tester, ProviderContainer c) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TriageUploadPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2/AC3: 初始表单 + 提交按钮在无输入时禁用', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.done)]));
    await _pump(tester, c);
    expect(find.byKey(const ValueKey('triageSymptomField')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageAddImage')), findsOneWidget);
    final submit = tester.widget<FilledButton>(find.byKey(const ValueKey('triageSubmit')));
    expect(submit.onPressed, isNull); // 无输入禁用
  });

  testWidgets('AC4: 服务异常(FAILED) → 异常态 + 软引导兽医', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.failed)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('误食巧克力');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageError')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageContactVet')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageResubmit')), findsOneWidget);
  });

  testWidgets('AC4: 一直处理中 → 超时态 + 重新提交', (tester) async {
    final c = _container(_FakeTriageRepo([const TriageResult(status: TriageStatus.processing)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    // 轮询含 Future.delayed —— testWidgets 假时钟不推进真实定时器，用 runAsync 跑真实事件循环。
    await tester.runAsync(() => c.read(triageUploadProvider.notifier).submit());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageTimeout')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageResubmit')), findsOneWidget);
  });

  testWidgets('AC3/F5: DONE(黄) → 渲染黄色结果页（4.4 三态卡）', (tester) async {
    final c = _container(_FakeTriageRepo(
        [const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.yellow)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageYellowPage')), findsOneWidget);
  });

  testWidgets('F5: DONE(红) → 不在本页渲染结果，交棒 4.5 占位', (tester) async {
    final c = _container(_FakeTriageRepo(
        [const TriageResult(status: TriageStatus.done, dangerLevel: DangerLevel.red)]));
    await _pump(tester, c);
    c.read(triageUploadProvider.notifier).setSymptom('x');
    await c.read(triageUploadProvider.notifier).submit();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('triageRedHandoff')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageYellowPage')), findsNothing);
    expect(find.byKey(const ValueKey('triageGreenPage')), findsNothing);
  });
}
