import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_placeholder.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/passport_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_variant_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/l10n/app_localizations_en.dart';
import 'package:tailtopia/l10n/app_localizations_id.dart';

/// L0（V1.3.4 ktp Story 1.2，AD-6 / D-7）：卡款注册表 8 项属性、反查口径、名称。
void main() {
  test('枚举顺序 = 网格顺序', () {
    expect(IdCardStyle.values, [
      IdCardStyle.ktp,
      IdCardStyle.passport,
      IdCardStyle.kitas,
      IdCardStyle.sim,
      IdCardStyle.studentBintang,
      IdCardStyle.studentMalam,
      IdCardStyle.studentCeria,
      IdCardStyle.studentKlasik,
    ]);
  });

  test('8 项属性逐项（cardType / cardVariant / group / isNew / analyticsCardStyle / 缩略图）', () {
    final expected = <IdCardStyle, (String, String?, IdCardStyleGroup, bool, int, String)>{
      IdCardStyle.ktp: ('KTP', null, IdCardStyleGroup.identity, false, 0, 'ktp'),
      IdCardStyle.passport: ('PASSPORT', null, IdCardStyleGroup.identity, false, 1, 'passport'),
      IdCardStyle.kitas: ('KITAS', null, IdCardStyleGroup.identity, true, 4, 'kitas'),
      IdCardStyle.sim: ('SIM', null, IdCardStyleGroup.identity, true, 3, 'sim'),
      IdCardStyle.studentBintang: ('STUDENT', 'BINTANG', IdCardStyleGroup.student, true, 2, 'student_bintang'),
      IdCardStyle.studentMalam: ('STUDENT', 'MALAM', IdCardStyleGroup.student, true, 2, 'student_malam'),
      IdCardStyle.studentCeria: ('STUDENT', 'CERIA', IdCardStyleGroup.student, true, 2, 'student_ceria'),
      IdCardStyle.studentKlasik: ('STUDENT', 'KLASIK', IdCardStyleGroup.student, false, 2, 'student_klasik'),
    };
    for (final e in expected.entries) {
      final s = e.key;
      final (type, variant, group, isNew, cs, thumb) = e.value;
      expect(s.cardType, type, reason: '$s');
      expect(s.cardVariant, variant, reason: '$s');
      expect(s.group, group, reason: '$s');
      expect(s.isNew, isNew, reason: '$s');
      expect(s.analyticsCardStyle, cs, reason: '$s');
      expect(s.thumbnailAsset, 'assets/id_card/styles/$thumb.webp', reason: '$s');
    }
    final thumbs = IdCardStyle.values.map((s) => s.thumbnailAsset).toSet();
    expect(thumbs, hasLength(8), reason: '缩略图路径两两不同');
  });

  test('表单字段集（PRD §3.4）：KTP 9 · Paspor 5 · 居住证 5 · SIM 7 · 学生卡 6×4', () {
    const f = IdCardFormField.values;
    expect(f, hasLength(11));
    expect(IdCardStyle.ktp.formFields, {
      IdCardFormField.name, IdCardFormField.petType, IdCardFormField.breed, IdCardFormField.gender,
      IdCardFormField.birthday, IdCardFormField.birthCity, IdCardFormField.address,
      IdCardFormField.occupation, IdCardFormField.maritalStatus,
    });
    expect(IdCardStyle.passport.formFields, {
      IdCardFormField.name, IdCardFormField.petType, IdCardFormField.gender, IdCardFormField.birthday,
      IdCardFormField.birthCity,
    });
    expect(IdCardStyle.kitas.formFields, {
      IdCardFormField.name, IdCardFormField.petType, IdCardFormField.breed, IdCardFormField.gender,
      IdCardFormField.birthday,
    });
    expect(IdCardStyle.sim.formFields, {
      IdCardFormField.name, IdCardFormField.petType, IdCardFormField.gender, IdCardFormField.birthday,
      IdCardFormField.birthCity, IdCardFormField.address, IdCardFormField.occupation,
    });
    for (final s in [
      IdCardStyle.studentBintang,
      IdCardStyle.studentMalam,
      IdCardStyle.studentCeria,
      IdCardStyle.studentKlasik,
    ]) {
      expect(s.formFields, {
        IdCardFormField.name, IdCardFormField.petType, IdCardFormField.gender, IdCardFormField.birthday,
        IdCardFormField.school, IdCardFormField.faculty,
      }, reason: '$s');
    }
  });

  group('fromCard', () {
    IdCard card(String? type, [String? variant]) =>
        IdCard(id: 1, cardType: type, cardVariant: variant);

    test('cardType null → KTP', () => expect(IdCardStyle.fromCard(card(null)), IdCardStyle.ktp));
    test('STUDENT + null 配色 → Klasik',
        () => expect(IdCardStyle.fromCard(card('STUDENT')), IdCardStyle.studentKlasik));
    test('STUDENT + 四配色', () {
      expect(IdCardStyle.fromCard(card('STUDENT', 'BINTANG')), IdCardStyle.studentBintang);
      expect(IdCardStyle.fromCard(card('STUDENT', 'MALAM')), IdCardStyle.studentMalam);
      expect(IdCardStyle.fromCard(card('STUDENT', 'CERIA')), IdCardStyle.studentCeria);
      expect(IdCardStyle.fromCard(card('STUDENT', 'KLASIK')), IdCardStyle.studentKlasik);
    });
    test('STUDENT + 未知配色 → Klasik',
        () => expect(IdCardStyle.fromCard(card('STUDENT', 'UNGU')), IdCardStyle.studentKlasik));
    test('未知卡种 → KTP（不崩溃）',
        () => expect(IdCardStyle.fromCard(card('ALIEN')), IdCardStyle.ktp));
    test('其余卡种', () {
      expect(IdCardStyle.fromCard(card('PASSPORT')), IdCardStyle.passport);
      expect(IdCardStyle.fromCard(card('KITAS')), IdCardStyle.kitas);
      expect(IdCardStyle.fromCard(card('SIM')), IdCardStyle.sim);
      expect(IdCardStyle.fromCard(card('KTP')), IdCardStyle.ktp);
    });
    test('每一款都能由自身 wire 值反查回来', () {
      for (final s in IdCardStyle.values) {
        expect(IdCardStyle.fromWire(s.cardType, s.cardVariant), s);
      }
    });
  });

  test('8 个名称在 en / id 下都非空且两两不同', () {
    for (final AppLocalizations l10n in [AppLocalizationsEn(), AppLocalizationsId()]) {
      final labels = IdCardStyle.values.map((s) => s.label(l10n)).toList();
      expect(labels.every((l) => l.trim().isNotEmpty), isTrue);
      expect(labels.toSet(), hasLength(8), reason: labels.join(' / '));
    }
    final en = AppLocalizationsEn();
    expect(IdCardStyle.kitas.label(en), 'Residence Permit');
    expect(IdCardStyle.sim.label(en), 'Driving License');
    expect(IdCardStyle.studentBintang.label(en), 'Student Star');
    expect(IdCardStyle.studentKlasik.label(AppLocalizationsId()), 'Pelajar Klasik');
  });

  test('画布与圆角：学生卡 1988×1200；护照 1990×1548；SIM / 居住证占位同 KTP 画布', () {
    expect(IdCardStyle.ktp.canvas, kIdCardCanvas);
    expect(IdCardStyle.passport.canvas, kPassportCardCanvas);
    expect(IdCardStyle.kitas.canvas, const Size(1988, 1200));
    expect(IdCardStyle.sim.canvas, const Size(1988, 1200));
    expect(IdCardStyle.kitas.canvasRadius, kIdCardComingSoonRadius);
    for (final s in IdCardStyle.values.where((s) => s.group == IdCardStyleGroup.student)) {
      expect(s.canvas, const Size(1988, 1200), reason: '$s');
    }
    expect(IdCardStyle.studentKlasik.canvasRadius, kStudentCardCanvasRadius);
  });

  test('buildFront 按款返回对应卡面组件', () {
    const data = IdCardData(generated: true);
    expect(IdCardStyle.ktp.buildFront(data), isA<KtpCardFront>());
    expect(IdCardStyle.passport.buildFront(data), isA<PassportCardFront>());
    expect(IdCardStyle.studentKlasik.buildFront(data), isA<StudentCardFront>());
    for (final s in [IdCardStyle.studentBintang, IdCardStyle.studentMalam, IdCardStyle.studentCeria]) {
      expect(s.buildFront(data), isA<StudentVariantCardFront>(), reason: '$s');
    }
  });

  testWidgets('SIM / 居住证占位：IdCardComingSoon + 款名 + 「设计中」', (tester) async {
    for (final s in [IdCardStyle.sim, IdCardStyle.kitas]) {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: FittedBox(child: s.buildFront(const IdCardData(generated: true))),
      ));
      expect(find.byType(IdCardComingSoon), findsOneWidget);
      expect(find.text(s.label(AppLocalizationsId())), findsOneWidget);
      expect(find.text('Dalam desain'), findsOneWidget);
    }
  });
}
