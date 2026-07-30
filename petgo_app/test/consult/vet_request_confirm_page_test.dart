import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/presentation/vet_request_confirm_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget。Story 3.5 确认发起屏：单价 + 说明 + 发起按钮渲染（无 timer，安全）。
/// bug 20260729-417：单价改后端实时下发（后台可配），**无本地兜底**——
/// 拉取失败显示重试件且禁止发起，点重试重拉成功后恢复。
class _FakePriceRepo extends ConsultRepository {
  _FakePriceRepo({this.failuresBeforeSuccess = 0, this.price = 50000})
      : super(dio: Dio());
  final int price;
  int failuresBeforeSuccess; // 前 N 次抛错（模拟拉取失败→重试成功）

  @override
  Future<int> vetConsultPrice() async {
    if (failuresBeforeSuccess > 0) {
      failuresBeforeSuccess--;
      throw StateError('price fetch failed');
    }
    return price;
  }
}

Future<void> _pumpPage(WidgetTester tester, ConsultRepository repo) async {
  tester.platformDispatcher.localesTestValue = const [Locale('id')];
  await tester.pumpWidget(ProviderScope(
    overrides: [consultRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: VetRequestConfirmPage(),
    ),
  ));
  await tester.pump();
}

void main() {
  testWidgets('renders backend-configured price, description and start CTA',
      (tester) async {
    await _pumpPage(tester, _FakePriceRepo(price: 75000));

    expect(find.byKey(const ValueKey('vetRequestDesc')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetRequestPrice')), findsOneWidget);
    // 后台配置价（75000）实时下发，非硬编码 50000（bug 20260729-417）。
    expect(find.text('Rp75.000'), findsOneWidget);
    final startBtn = tester.widget<FilledButton>(
        find.byKey(const ValueKey('vetRequestStart')));
    expect(startBtn.onPressed, isNotNull);
  });

  testWidgets('price fetch failure shows retry and disables start; '
      'retry recovers', (tester) async {
    await _pumpPage(tester, _FakePriceRepo(failuresBeforeSuccess: 1));

    // 失败态：无兜底价、出重试件、发起按钮禁用。
    expect(find.byKey(const ValueKey('vetRequestPrice')), findsNothing);
    expect(find.text('Rp50.000'), findsNothing);
    expect(find.byKey(const ValueKey('vetRequestPriceRetry')), findsOneWidget);
    final startBtn = tester.widget<FilledButton>(
        find.byKey(const ValueKey('vetRequestStart')));
    expect(startBtn.onPressed, isNull);

    // 点重试 → 重拉成功 → 价格出现、按钮恢复可用。
    await tester.tap(find.byKey(const ValueKey('vetRequestPriceRetry')));
    await tester.pump();
    await tester.pump();
    expect(find.text('Rp50.000'), findsOneWidget);
    expect(find.byKey(const ValueKey('vetRequestPriceRetry')), findsNothing);
    final startBtnAfter = tester.widget<FilledButton>(
        find.byKey(const ValueKey('vetRequestStart')));
    expect(startBtnAfter.onPressed, isNotNull);
  });
}
