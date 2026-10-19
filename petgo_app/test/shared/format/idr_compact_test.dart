import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/format/idr_compact.dart';

/// L0：卢比金额缩写（V1.4.0 gift Story 2.2 · AC1）。
///
/// 🔴 用例表**逐行照抄** Story 2.1 AC7 的单一事实源 ——
/// 对端 `petgo-backend/src/test/java/com/tailtopia/shared/format/IdrCompactTest.java`。改一边必须改另一边。
void main() {
  const table = <int, String>{
    -5: 'Rp0',
    0: 'Rp0',
    999: 'Rp999',
    1000: 'Rp1rb',
    1500: 'Rp1,5rb',
    1999: 'Rp1,9rb',
    53000: 'Rp53rb',
    188000: 'Rp188rb',
    188500: 'Rp188,5rb',
    999999: 'Rp999,9rb',
    1000000: 'Rp1jt',
    1050000: 'Rp1jt',
    1200000: 'Rp1,2jt',
    1290000: 'Rp1,2jt',
    1999999: 'Rp1,9jt',
    25000000: 'Rp25jt',
    1234567890: 'Rp1234,5jt',
  };

  table.forEach((amount, expected) {
    test('$amount → $expected', () {
      expect(formatIdrCompact(amount), expected);
    });
  });

  const parts = <int, (String, String)>{
    188000: ('188', 'rb'),
    1500: ('1,5', 'rb'),
    0: ('0', ''),
    1200000: ('1,2', 'jt'),
  };

  parts.forEach((amount, expected) {
    test('parts $amount → $expected', () {
      final p = idrCompactParts(amount);
      expect((p.number, p.unit), expected);
    });
  });
}
