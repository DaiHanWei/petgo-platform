import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';

/// L0：KTP 证件卡渲染（Story 6.2 · AC1/AC2）——1600×900 导出画布 + 标题/爪印戳存在。
void main() {
  const fields = KtpFields(
    nik: '3276010122000123',
    nama: 'MOCHI',
    tempatTglLahir: 'BANDUNG, 01-01-2022',
    spesies: 'KUCING',
    ras: 'BRITISH SHORTHAIR',
    jenisKelamin: 'JANTAN',
    alamat: 'JL. MELATI NO. 25',
    statusPerkawinan: 'LAJANG',
    pekerjaan: 'CHIEF HAPPINESS OFFICER',
    kewarganegaraan: 'INDONESIA',
    berlakuHingga: 'SEUMUR HIDUP',
    placeLine: 'BANDUNG',
    dateLine: '01-01-2022',
  );

  testWidgets('KTP 正面渲染于 1600×900 画布 + 标题 + 爪印戳', (tester) async {
    tester.view.physicalSize = const Size(1600, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: KtpCardFront(fields: fields)),
    ));
    await tester.pump();

    // 导出画布固定 1600×900（AC1）。
    expect(tester.getSize(find.byType(KtpCardFront)), const Size(1600, 900));
    // 标题品牌化（区分真证件）。
    expect(find.textContaining('KARTU TANDA'), findsOneWidget);
    // 字段值渲染（大写）。
    expect(find.text('CHIEF HAPPINESS OFFICER'), findsOneWidget);
    expect(find.text('3276010122000123'), findsOneWidget);
    // 爪印戳（替代指纹 = 娱乐仿制标记）+ logo 均用 Icons.pets。
    expect(find.byIcon(Icons.pets), findsWidgets);
  });
}
