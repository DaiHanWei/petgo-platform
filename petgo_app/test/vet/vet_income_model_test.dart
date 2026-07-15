import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/vet/domain/vet_income.dart';

/// Story 3.7 兽医收入 model fromJson。
void main() {
  test('VetIncome.fromJson: currentMonth + history 倒序映射', () {
    final income = VetIncome.fromJson({
      'currentMonth': {
        'period': '2026-07',
        'orderCount': 2,
        'grossAmount': 100000,
        'payoutAmount': 60000,
        'status': 'PENDING',
      },
      'history': [
        {'period': '2026-06', 'orderCount': 3, 'grossAmount': 150000, 'payoutAmount': 90000, 'status': 'SETTLED'},
        {'period': '2026-05', 'orderCount': 1, 'grossAmount': 50000, 'payoutAmount': 30000, 'status': 'PENDING'},
      ],
    });

    expect(income.currentMonth.period, '2026-07');
    expect(income.currentMonth.orderCount, 2);
    expect(income.currentMonth.payoutAmount, 60000);
    expect(income.currentMonth.status, 'PENDING');
    expect(income.history, hasLength(2));
    expect(income.history[0].period, '2026-06');
    expect(income.history[0].isSettled, isTrue);
    expect(income.history[1].payoutAmount, 30000);
    expect(income.history[1].isSettled, isFalse);
  });

  test('VetIncome.fromJson: 缺省字段兜底零值/PENDING', () {
    final income = VetIncome.fromJson({'currentMonth': {'period': '2026-07'}});
    expect(income.currentMonth.orderCount, 0);
    expect(income.currentMonth.payoutAmount, 0);
    expect(income.currentMonth.status, 'PENDING');
    expect(income.history, isEmpty);
  });
}
