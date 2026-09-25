import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/presentation/widgets/triage_paywall.dart';

/// L0 · bug 20260720-307：AI 结果付费抽屉的 PawCoin 余额要带千分位（印尼口径用「.」）。
void main() {
  test('formatKoin 千分位', () {
    expect(formatKoin(0), '0');
    expect(formatKoin(999), '999');
    expect(formatKoin(50000), '50.000');
    expect(formatKoin(1234567), '1.234.567');
    expect(formatIdr(10000), 'Rp10.000');
  });
}
