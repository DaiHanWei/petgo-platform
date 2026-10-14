import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/passport_card.dart';

/// L0（V1.3.4 ktp Story 1.3，D-5）：护照签发 / 到期日 = 建卡日 / +5 年，不再取渲染当天。
void main() {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
  String upper(DateTime d) => '${d.day.toString().padLeft(2, '0')} ${months[d.month - 1]} ${d.year}';

  test('签发日 = createdAt 本地日期；到期 = +5 年；与运行当天无关', () {
    final created = DateTime.parse('2026-08-01T10:00:00Z');
    final data = IdCardData(generated: true, serialId: 42, birthday: DateTime(2022, 1, 1), createdAt: created);
    final a = buildPassportFields(data);
    final b = buildPassportFields(data);
    final local = created.toLocal();
    expect(a.dateOfIssue, upper(local));
    expect(a.dateOfExpiry, upper(DateTime(local.year + 5, local.month, local.day)));
    expect(b.dateOfIssue, a.dateOfIssue);
    expect(b.dateOfExpiry, a.dateOfExpiry);
  });

  test('createdAt 为 null（建卡页预览）→ 签发日为今天', () {
    // 前后各取一次「今天」，防用例恰好跨本地午夜。
    final before = DateTime.now();
    final f = buildPassportFields(const IdCardData(generated: true));
    final after = DateTime.now();
    expect([upper(before), upper(after)], contains(f.dateOfIssue));
    expect([before, after].map((n) => upper(DateTime(n.year + 5, n.month, n.day))), contains(f.dateOfExpiry));
  });

  test('Reg.No / NIKIM / 旧卡护照号仍由 serial 派生（回归）', () {
    final f = buildPassportFields(IdCardData(
        generated: true, serialId: 42, birthday: DateTime(2022, 1, 5), createdAt: DateTime.utc(2026, 8, 1)));
    expect(f.regNo, '000000042');
    expect(f.nikim, '050122000042');
    expect(f.passportNo, 'A 000042');
    final withNo = buildPassportFields(const IdCardData(generated: true, serialId: 42, passportNo: 'TT02P2600001'));
    expect(withNo.passportNo, 'TT02P2600001');
  });

  test('IdCard.toIdCardData 透传 createdAt', () {
    final created = DateTime.utc(2026, 8, 1, 10);
    expect(IdCard(id: 1, createdAt: created).toIdCardData().createdAt, created);
  });
}
