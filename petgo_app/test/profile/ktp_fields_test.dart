import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_fields.dart';

/// L0：KTP 字段构建纯函数（Story 6.2 · AC1/AC3）。默认/覆盖/NIK/物种映射 + 会话编辑不触档案。
void main() {
  final data = IdCardData(
    generated: true,
    serialId: 123,
    name: 'Mochi',
    petType: 'CAT',
    breed: 'British Shorthair',
    birthday: DateTime(2022, 1, 1),
    avatarUrl: 'https://cdn/a.jpg',
  );

  test('空编辑 → 取档案数据 + 趣味默认', () {
    final f = buildKtpFields(data, KtpEdits.empty);
    expect(f.nama, 'Mochi');
    expect(f.spesies, 'KUCING'); // CAT → 印尼语
    expect(f.ras, 'British Shorthair');
    expect(f.pekerjaan, KtpDefaults.pekerjaan); // 趣味默认
    expect(f.berlakuHingga, 'SEUMUR HIDUP');
    expect(f.avatarUrl, 'https://cdn/a.jpg');
  });

  test('NIK = 16 位，含 serial（区域码+生日+序号补零）', () {
    final f = buildKtpFields(data, KtpEdits.empty);
    expect(f.nik.length, 16);
    expect(f.nik.startsWith('3276'), isTrue);
    expect(f.nik.endsWith('000123'), isTrue);
  });

  test('物种映射：DOG→ANJING、OTHER→HEWAN', () {
    expect(KtpDefaults.spesies('DOG'), 'ANJING');
    expect(KtpDefaults.spesies('OTHER'), 'HEWAN');
    expect(KtpDefaults.spesies(null), 'HEWAN');
  });

  test('AC3：编辑覆盖只作用返回值，不改源 data', () {
    final edits = KtpEdits.empty.copyWith(nama: 'Momo', pekerjaan: 'CEO');
    final f = buildKtpFields(data, edits);
    expect(f.nama, 'Momo'); // 覆盖生效
    expect(f.pekerjaan, 'CEO');
    // 源档案未变；空编辑重建仍得档案原值（会话编辑不写档案）。
    expect(data.name, 'Mochi');
    final f2 = buildKtpFields(data, KtpEdits.empty);
    expect(f2.nama, 'Mochi');
    expect(f2.pekerjaan, KtpDefaults.pekerjaan);
  });

  test('缺档案字段兜底：无名/无品种/无生日', () {
    const bare = IdCardData(generated: true, serialId: 5);
    final f = buildKtpFields(bare, KtpEdits.empty);
    expect(f.nama, KtpDefaults.namaFallback);
    expect(f.ras, '-');
    expect(f.nik.length, 16);
  });

  group('新编码规则（spec ktp-pet-idcode-numbering）', () {
    test('新卡：NIK 直显后端 cardNo（14 位 TT 号）', () {
      final withNo = IdCardData(
        generated: true,
        serialId: 123,
        cardNo: 'TT600324020002',
        birthday: DateTime(2024, 3, 10),
      );
      final f = buildKtpFields(withNo, KtpEdits.empty);
      expect(f.nik, 'TT600324020002');
    });

    test('旧卡（cardNo=null）：NIK 与改动前完全相同（旧拼号 3276+DDMMYY+serial）', () {
      final f = buildKtpFields(data, KtpEdits.empty);
      expect(f.nik, '3276010122000123'); // 3276 + 010122 + 000123
    });

    test('Jenis Kelamin 按 gender 联动三态 + null 默认', () {
      IdCardData d(String? g) => IdCardData(generated: true, gender: g);
      expect(buildKtpFields(d('MALE'), KtpEdits.empty).jenisKelamin, 'JANTAN');
      expect(buildKtpFields(d('FEMALE'), KtpEdits.empty).jenisKelamin, 'BETINA');
      expect(buildKtpFields(d('UNKNOWN'), KtpEdits.empty).jenisKelamin, '-');
      // null（旧卡）→ 维持现默认 JANTAN，旧卡展示零变化。
      expect(buildKtpFields(d(null), KtpEdits.empty).jenisKelamin, KtpDefaults.jenisKelamin);
      // 会话编辑覆盖仍最优先。
      expect(
          buildKtpFields(d('MALE'), KtpEdits.empty.copyWith(jenisKelamin: 'BETINA')).jenisKelamin,
          'BETINA');
    });

    test('占位身份码：2024-03-10 母猫 → TT600324020000（TT600324 + 02 + 0000）', () {
      final no = buildPreviewCardNo(
          birthday: DateTime(2024, 3, 10), gender: 'FEMALE', petType: 'CAT');
      expect(no, 'TT600324020000');
      expect(no!.startsWith('TT600324'), isTrue); // 日 10+50=60
      expect(no.substring(8, 10), '02'); // SP：猫 02
      expect(no.length, 14);
    });

    test('占位身份码：性别加码（公+10/未知+0）与物种段（狗01/其他00）', () {
      expect(buildPreviewCardNo(birthday: DateTime(2024, 3, 3), gender: 'MALE', petType: 'DOG'),
          'TT130324010000'); // 日 3+10=13，SP 狗 01
      expect(buildPreviewCardNo(birthday: DateTime(2024, 3, 10), gender: 'UNKNOWN', petType: null),
          'TT100324000000'); // 日 10+0，SP 其他/未选 00
    });

    test('占位身份码：生日缺失 → null（走旧占位 NIK）', () {
      expect(buildPreviewCardNo(birthday: null, gender: 'FEMALE', petType: 'CAT'), isNull);
    });

    test('占位护照号：TT+SP+P+年后两位+00000（12 位）', () {
      expect(buildPreviewPassportNo(petType: 'CAT', year: 2026), 'TT02P2600000');
      expect(buildPreviewPassportNo(petType: 'DOG', year: 2026), 'TT01P2600000');
      expect(buildPreviewPassportNo(petType: null, year: 2026), 'TT00P2600000');
      expect(buildPreviewPassportNo(petType: 'CAT', year: 2026).length, 12);
    });
  });
}
