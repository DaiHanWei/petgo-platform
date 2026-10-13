import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/kitas_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/sim_card.dart';

/// L0（V1.3.4 ktp Story 1.3，UI 稿 F3 / F4）：SIM / 居住证卡面结构断言（不做 golden，卡面 1:1 比对为 L2）。
void main() {
  setUpAll(() async {
    final loader = FontLoader('Rubik')
      ..addFont(File('assets/fonts/Rubik-VariableFont_wght.ttf')
          .readAsBytes()
          .then((b) => ByteData.view(b.buffer)));
    await loader.load();
  });

  final created = DateTime.utc(2026, 3, 23, 2); // 本地日期在任何时区都落在 23 日前后一天内
  String localDmy(DateTime d) {
    final l = d.toLocal();
    return '${l.day.toString().padLeft(2, '0')}-${l.month.toString().padLeft(2, '0')}-${l.year}';
  }

  IdCardData data({String? cardNo = 'TT730326020110', String? gender = 'MALE', DateTime? createdAt}) =>
      IdCardData(
        generated: true,
        serialId: 123456,
        name: 'Oyen',
        petType: 'CAT',
        breed: 'Domestik',
        birthday: DateTime(2026, 3, 23),
        gender: gender,
        cardNo: cardNo,
        createdAt: createdAt ?? created,
      );

  Future<void> pump(WidgetTester tester, Widget card) async {
    tester.view.physicalSize = const Size(1988, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: Align(alignment: Alignment.topLeft, child: card))));
    await tester.pump();
  }

  group('SIM', () {
    testWidgets('1988×1200 + 抬头 / T / 签发方 / 格式化编号 / 签发日 = createdAt', (tester) async {
      await pump(tester, SimCardFront(fields: buildSimFields(data())));
      expect(tester.getSize(find.byType(SimCardFront)), const Size(1988, 1200));
      expect(find.text('SURAT IZIN MENGEMUDI HEWAN'), findsOneWidget);
      expect(find.text('T'), findsOneWidget);
      expect(find.text('Diterbitkan Oleh'), findsOneWidget);
      expect(find.text('Tailtopia'), findsOneWidget);
      expect(find.text('TT7303-2026-020110'), findsOneWidget);
      expect(find.text(localDmy(created)), findsOneWidget);
      expect(find.text('Oyen'), findsOneWidget);
      expect(find.text('Jantan'), findsOneWidget);
    });

    testWidgets('选填留空 → 趣味默认；UNKNOWN → -；不出现 serialId 派生文本', (tester) async {
      await pump(tester, SimCardFront(fields: buildSimFields(data(gender: 'UNKNOWN'))));
      expect(find.text('Bandung, 23-03-2026'), findsOneWidget);
      expect(find.text('Jl. Melati No. 25, Bandung'), findsOneWidget);
      expect(find.text('Chief Happiness Officer'), findsOneWidget);
      expect(find.text('-'), findsOneWidget);
      expect(find.textContaining('123456'), findsNothing);
    });

    testWidgets('card_no 为空 → 编号位留空（不回落 serial）', (tester) async {
      await pump(tester, SimCardFront(fields: buildSimFields(data(cardNo: null))));
      expect(find.byKey(const ValueKey('simCardNumber')), findsNothing);
      expect(find.textContaining('123456'), findsNothing);
    });

    test('createdAt 为 null（预览）→ 签发日为今天', () {
      const d = IdCardData(generated: true);
      final before = DateTime.now();
      final issue = buildSimFields(d).issueDate;
      final after = DateTime.now();
      expect([localDmy(before), localDmy(after)], contains(issue));
    });

    testWidgets('超长值不越出值栏、不压签章', (tester) async {
      await pump(tester, SimCardFront(fields: buildSimFields(IdCardData(
        generated: true,
        name: 'N' * 60,
        address: 'A' * 80,
        occupation: 'O' * 40,
        birthCity: 'C' * 40,
        birthday: DateTime(2026, 3, 23),
        gender: 'MALE',
        cardNo: 'TT730326020110',
        createdAt: created,
      ))));
      for (final t in ['N' * 60, 'A' * 80, 'O' * 40]) {
        final r = tester.getRect(find.text(t));
        expect(r.right, lessThanOrEqualTo(1500.9), reason: t);
      }
      expect(tester.takeException(), isNull);
    });
  });

  group('居住证', () {
    testWidgets('1988×1200 + 抬头 / 副标题 / 格式化编号（右对齐到 95.5%）', (tester) async {
      await pump(tester, KitasCardFront(fields: buildKitasFields(data())));
      expect(tester.getSize(find.byType(KitasCardFront)), const Size(1988, 1200));
      expect(find.text('Pengurus Administrasi Hewan'), findsOneWidget);
      expect(find.text('TAILTOPIA'), findsOneWidget);
      expect(find.text('Kartu Izin Tinggal Hewan'), findsOneWidget);
      expect(find.text('TT-730326020110'), findsOneWidget);
      expect(tester.getRect(find.text('TT-730326020110')).right, moreOrLessEquals(1898.5, epsilon: 0.5));
      expect(find.text('Oyen'), findsOneWidget);
      expect(find.text('Kucing'), findsOneWidget);
      expect(find.text('Domestik'), findsOneWidget);
      expect(find.text('23-03-2026'), findsOneWidget);
      expect(find.text('Jantan'), findsOneWidget);
    });

    testWidgets('品种空 → -；UNKNOWN → -；不出现 serialId 派生文本；无号留空', (tester) async {
      await pump(tester, KitasCardFront(fields: buildKitasFields(const IdCardData(
        generated: true, serialId: 123456, petType: 'DOG', gender: 'UNKNOWN'))));
      expect(find.text('Anjing'), findsOneWidget);
      expect(find.text('-'), findsNWidgets(2)); // Ras + Jenis Kelamin
      expect(find.text('Mochi'), findsOneWidget);
      expect(find.textContaining('123456'), findsNothing);
      expect(find.byKey(const ValueKey('kitasCardNumber')), findsNothing);
    });
  });

  test('组件源码不含概念图示例号码 / 示例值', () {
    for (final f in [
      'lib/features/profile/presentation/id_card/sim_card.dart',
      'lib/features/profile/presentation/id_card/kitas_card.dart',
      'lib/features/profile/presentation/id_card/new_card_text.dart',
    ]) {
      final src = File(f).readAsStringSync();
      for (final sample in ['TT1301-1231-123132', 'Jl.Harapan', 'Tukang Jaga Uang', 'TT-00000000000', 'TT230326020110']) {
        expect(src.contains(sample), isFalse, reason: '$f 含 $sample');
      }
    }
  });
}
