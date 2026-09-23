import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_request.dart';
import 'package:tailtopia/features/consult/presentation/consult_case_form_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget。bug 20260805-449：直连问诊病例页症状为空/纯空白 → 拦截提交（不发请求）+ 红框 + 提示；
/// 输入后提示消失，非空才真正发起。
class _FakeRepo extends ConsultRepository {
  _FakeRepo() : super(dio: Dio());
  final List<String?> calls = [];

  @override
  Future<int> vetConsultPrice() async => 59000;

  @override
  Future<ConsultRequest> createRequest({String? symptomText, List<String>? imageObjectKeys}) async {
    calls.add(symptomText);
    // 抛网络错走页面既有失败分支（toast），避免测试里触发路由跳转。
    throw DioException(requestOptions: RequestOptions(path: '/x'));
  }
}

Future<void> _pump(WidgetTester tester, _FakeRepo repo) async {
  tester.platformDispatcher.localesTestValue = const [Locale('en')];
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: ConsultCaseFormPage(),
    ),
  ));
  await tester.pump();
  await tester.pump();
}

void main() {
  testWidgets('empty / whitespace symptoms block submit and show error', (tester) async {
    final repo = _FakeRepo();
    await _pump(tester, repo);
    final errorFinder = find.byKey(const ValueKey('consultCaseSymptomError'));
    expect(errorFinder, findsNothing);

    await tester.tap(find.byKey(const ValueKey('consultCaseSubmit')));
    await tester.pump();
    expect(repo.calls, isEmpty);
    expect(errorFinder, findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('consultCaseSymptom')), '   ');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('consultCaseSubmit')));
    await tester.pump();
    expect(repo.calls, isEmpty);
    expect(errorFinder, findsOneWidget);
  });

  testWidgets('typing clears error and non-empty symptoms submit', (tester) async {
    final repo = _FakeRepo();
    await _pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('consultCaseSubmit')));
    await tester.pump();
    expect(find.byKey(const ValueKey('consultCaseSymptomError')), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('consultCaseSymptom')), 'Muntah sejak pagi');
    await tester.pump();
    expect(find.byKey(const ValueKey('consultCaseSymptomError')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('consultCaseSubmit')));
    await tester.pump();
    expect(repo.calls, ['Muntah sejak pagi']);
    await tester.pump(const Duration(seconds: 5)); // 让失败 toast 计时器走完
  });
}
