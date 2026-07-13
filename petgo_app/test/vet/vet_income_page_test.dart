import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_income.dart';
import 'package:tailtopia/features/vet/presentation/vet_income_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.7 兽医收入页：当月待结算卡（到手千分位）+ 历史月结倒序 + 空态 + 加载失败态。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository({this.result, this.throws = false})
      : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  final VetIncome? result;
  final bool throws;

  @override
  Future<VetIncome> income() async {
    if (throws) throw DioException(requestOptions: RequestOptions(path: 'x'));
    return result!;
  }
}

Future<void> _pump(WidgetTester tester, _FakeVetRepository repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [vetRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: VetIncomePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('当月待结算卡：到手千分位 + 单数 + 待结算徽章', (tester) async {
    await _pump(tester, _FakeVetRepository(
      result: const VetIncome(
        currentMonth: VetIncomePeriod(period: '2026-07', orderCount: 2, grossAmount: 100000, payoutAmount: 60000),
        history: [],
      ),
    ));

    expect(find.byKey(const ValueKey('vetIncomeCurrentCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetIncomeCurrentPayout')), findsOneWidget);
    expect(find.text('Rp60.000'), findsOneWidget); // 千分位
    expect(find.text('2 consultations'), findsOneWidget);
    expect(find.text('Unsettled'), findsOneWidget); // 当月待结算徽章
    expect(find.text('No monthly earnings yet'), findsOneWidget); // 无历史
  });

  testWidgets('历史月结倒序渲染 + 状态徽章', (tester) async {
    await _pump(tester, _FakeVetRepository(
      result: const VetIncome(
        currentMonth: VetIncomePeriod(period: '2026-07'),
        history: [
          VetIncomePeriod(period: '2026-06', orderCount: 3, grossAmount: 150000, payoutAmount: 90000, status: 'SETTLED'),
          VetIncomePeriod(period: '2026-05', orderCount: 1, grossAmount: 50000, payoutAmount: 30000, status: 'PENDING'),
        ],
      ),
    ));

    expect(find.byKey(const ValueKey('vetIncomeHistory_2026-06')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetIncomeHistory_2026-05')), findsOneWidget);
    expect(find.text('Rp90.000'), findsOneWidget);
    expect(find.text('Rp30.000'), findsOneWidget);
    expect(find.text('Settled'), findsOneWidget); // 2026-06 已结算徽章
  });

  testWidgets('加载失败 → 失败态', (tester) async {
    await _pump(tester, _FakeVetRepository(throws: true));
    expect(find.text('Failed to load earnings'), findsOneWidget);
  });
}
