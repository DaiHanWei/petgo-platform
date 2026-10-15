import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_variant_card.dart';

/// L0（V1.3.4 ktp Story 1.2，AD-7 / D-4）：三款新学生卡与 Klasik 编号位的结构断言
/// （不做 golden：云端与本机字体渲染不同，卡面 1:1 比对为 L2）。
void main() {
  // 几何断言（栏宽 / 不压圆章）要用真实字体量：flutter_test 默认字体每字 1em 宽，会严重高估宽度。
  setUpAll(() async {
    final loader = FontLoader('Rubik')
      ..addFont(File('assets/fonts/Rubik-VariableFont_wght.ttf')
          .readAsBytes()
          .then((b) => ByteData.view(b.buffer)));
    await loader.load();
  });

  // 测试数据里的示例号码（只允许出现在测试里，不得写进组件或默认值）。
  const sampleNo = 'TT230326020110';

  IdCardData data({String? cardNo, String? school, String? faculty, String? name}) => IdCardData(
        generated: true,
        name: name ?? 'Oyen',
        petType: 'CAT',
        birthday: DateTime(2026, 3, 13),
        cardNo: cardNo,
        school: school,
        faculty: faculty,
      );

  Future<void> pumpCard(WidgetTester tester, Widget card) async {
    tester.view.physicalSize = const Size(1988, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: Align(alignment: Alignment.topLeft, child: card))));
    await tester.pump();
  }

  final layouts = {
    'Bintang': StudentVariantLayout.bintang,
    'Malam': StudentVariantLayout.malam,
    'Ceria': StudentVariantLayout.ceria,
  };

  test('buildStudentFields 透传 cardNo', () {
    expect(buildStudentFields(data(cardNo: sampleNo)).cardNo, sampleNo);
    expect(buildStudentFields(data()).cardNo, isNull);
  });

  for (final e in layouts.entries) {
    testWidgets('${e.key}：画布 1988×1200 + 五字段 + 编号位', (tester) async {
      await pumpCard(tester,
          StudentVariantCardFront(layout: e.value, fields: buildStudentFields(data(cardNo: sampleNo))));
      expect(tester.getSize(find.byType(StudentVariantCardFront)), const Size(1988, 1200));
      expect(find.text('TAILTOPIA ACADEMY'), findsWidgets); // 抬头 + 学校默认值
      expect(find.text('STUDENT CARD'), findsOneWidget);
      expect(find.text('OYEN'), findsOneWidget);
      expect(find.text('KUCING'), findsOneWidget);
      expect(find.text('13-03-2026'), findsOneWidget);
      expect(find.text('FAKULTAS KEBAHAGIAAN'), findsOneWidget);
      expect(find.text('Nomor Induk Anabul'), findsOneWidget);
      expect(find.text(sampleNo), findsOneWidget);
    });

    testWidgets('${e.key}：cardNo null / 空串 → 标签与编号整块不渲染', (tester) async {
      for (final no in [null, '']) {
        await pumpCard(tester,
            StudentVariantCardFront(layout: e.value, fields: buildStudentFields(data(cardNo: no))));
        expect(find.text('Nomor Induk Anabul'), findsNothing, reason: 'cardNo=$no');
        expect(find.byKey(const ValueKey('studentCardNumber')), findsNothing);
        expect(find.text('OYEN'), findsOneWidget); // 其余元素照常
      }
    });

    testWidgets('${e.key}：学校 / 院系空 → 趣味默认；有值大写', (tester) async {
      await pumpCard(tester, StudentVariantCardFront(
          layout: e.value, fields: buildStudentFields(data(school: 'uni meong', faculty: 'tidur'))));
      expect(find.text('UNI MEONG'), findsOneWidget);
      expect(find.text('TIDUR'), findsOneWidget);
    });

    testWidgets('${e.key}：超长文本不越出文字栏（名字 60 / 学校 40 / 院系 40 字）', (tester) async {
      await pumpCard(tester, StudentVariantCardFront(
        layout: e.value,
        fields: buildStudentFields(data(
          name: 'N' * 60,
          school: 'S' * 40,
          faculty: 'FAKULTAS PEMBURU TIKUS LIAR DAN TEMAN',
          cardNo: sampleNo,
        )),
      ));
      final card = tester.getRect(find.byType(StudentVariantCardFront));
      final l = e.value;
      final photo = l.photo.shift(card.topLeft);
      for (final text in ['N' * 60, 'S' * 40, 'FAKULTAS PEMBURU TIKUS LIAR DAN TEMAN', sampleNo]) {
        final r = tester.getRect(find.text(text));
        expect(card.contains(r.topLeft) && card.contains(r.bottomRight - const Offset(0.01, 0.01)), isTrue,
            reason: '$text 越出卡面');
        expect(r.overlaps(photo), isFalse, reason: '$text 压到照片');
      }
      expect(tester.takeException(), isNull);
    });
  }

  test('Bintang 照片在右、Malam / Ceria 照片在左（T-5：三份坐标）', () {
    expect(StudentVariantLayout.bintang.photo.left, greaterThan(StudentVariantLayout.bintang.school.dx));
    expect(StudentVariantLayout.malam.photo.left, lessThan(StudentVariantLayout.malam.school.dx));
    expect(StudentVariantLayout.ceria.photo.left, lessThan(StudentVariantLayout.ceria.school.dx));
  });

  test('组件源码不含概念图示例号码', () {
    for (final f in [
      'lib/features/profile/presentation/id_card/student_variant_card.dart',
      'lib/features/profile/presentation/id_card/student_card.dart',
    ]) {
      final src = File(f).readAsStringSync();
      expect(src.contains('3276010101230001'), isFalse, reason: f);
      expect(src.contains('TT230326020110'), isFalse, reason: f);
    }
  });

  group('Klasik（F8 / F9）', () {
    testWidgets('有号：显示「Nomor Induk Anabul」+ cardNo，落在字段区下方、底线之上、不压右下圆章', (tester) async {
      await pumpCard(tester, StudentCardFront(fields: buildStudentFields(data(cardNo: sampleNo))));
      expect(find.text('Nomor Induk Anabul'), findsOneWidget);
      expect(find.text(sampleNo), findsOneWidget);
      final card = tester.getRect(find.byType(StudentCardFront));
      final label = tester.getRect(find.text('Nomor Induk Anabul')).shift(-card.topLeft);
      final no = tester.getRect(find.text(sampleNo)).shift(-card.topLeft);
      final faculty = tester.getRect(find.text('FAKULTAS KEBAHAGIAAN')).shift(-card.topLeft);
      expect(label.left, 640); // 与字段标签列对齐
      expect(label.top, greaterThan(faculty.bottom));
      expect(no.bottom, lessThan(1090)); // 底部紫线之上
      expect(no.right, lessThan(1548)); // 不压右下圆章
    });

    testWidgets('无号（V95 前旧卡）：编号位整块隐藏，其余元素数量不变', (tester) async {
      await pumpCard(tester, StudentCardFront(fields: buildStudentFields(data(cardNo: sampleNo))));
      final withNo = find.byType(Text).evaluate().length;
      for (final no in [null, '']) {
        await pumpCard(tester, StudentCardFront(fields: buildStudentFields(data(cardNo: no))));
        expect(find.text('Nomor Induk Anabul'), findsNothing);
        expect(find.byType(Text).evaluate().length, withNo - 2, reason: 'cardNo=$no：只少编号位两段文字');
      }
    });
  });
}
