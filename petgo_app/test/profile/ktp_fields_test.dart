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
}
