import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_number_format.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_fields.dart';

/// L0（V1.3.4 ktp Story 1.3，FR-142E.7 / T-1）：SIM / 居住证编号格式化。
void main() {
  test('epics 示例', () {
    expect(formatSimCardNo('TT230326020110', birthday: DateTime(2026, 3, 23)), 'TT2303-2026-020110');
    expect(formatKitasCardNo('TT230326020110'), 'TT-230326020110');
  });

  test('PRD 示例', () {
    expect(formatSimCardNo('TT130326020110', birthday: DateTime(2026, 3, 13)), 'TT1303-2026-020110');
    expect(formatKitasCardNo('TT030326020110'), 'TT-030326020110');
  });

  test('T-1：4 位年份取快照生日年；无生日按 20YY', () {
    expect(formatSimCardNo('TT600399010001', birthday: DateTime(1999, 3, 10)), 'TT6003-1999-010001');
    expect(formatSimCardNo('TT600324020001'), 'TT6003-2024-020001');
    // 生日年与 card_no 的 YY 不一致（不应出现）→ 不拿生日年覆盖，回落 20YY。
    expect(formatSimCardNo('TT600324020001', birthday: DateTime(2025, 3, 10)), 'TT6003-2024-020001');
  });

  test('DD 含性别加码：只重排、不解码', () {
    // 母 +50：生日 10 日 → DD = 60。
    expect(formatSimCardNo('TT600324020001', birthday: DateTime(2024, 3, 10)), 'TT6003-2024-020001');
    expect(formatKitasCardNo('TT600324020001'), 'TT-600324020001');
  });

  test('预览占位号（末四位 0000）照常格式化', () {
    final birthday = DateTime(2024, 3, 10);
    final preview = buildPreviewCardNo(birthday: birthday, gender: 'FEMALE', petType: 'CAT');
    expect(preview, 'TT600324020000');
    expect(formatSimCardNo(preview!, birthday: birthday), 'TT6003-2024-020000');
    expect(formatKitasCardNo(preview), 'TT-600324020000');
  });

  test('非法输入原样返回、不抛异常', () {
    for (final bad in ['', 'TT1234', 'XX230326020110', 'TT23032602011A', 'TT2303260201100', '0042']) {
      expect(formatSimCardNo(bad), bad);
      expect(formatKitasCardNo(bad), bad);
    }
  });
}
