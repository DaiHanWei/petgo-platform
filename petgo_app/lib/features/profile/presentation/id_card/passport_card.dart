import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import '../../domain/id_card.dart';

/// 宠物护照（Story 6-8，bug 20260721-330）逻辑画布：底图 `passport_front_bg.png` 995×774 的 2×。
const Size kPassportCardCanvas = Size(1990, 1548);

/// 护照展示字段（多为静态/派生，见 spec 6-8 §4.1；Sex/出生地走趣味默认）。
@immutable
class PassportFields {
  const PassportFields({
    required this.passportNo,
    required this.name,
    required this.sex,
    required this.dateOfBirth,
    required this.placeOfBirth,
    required this.dateOfIssue,
    required this.dateOfExpiry,
    required this.regNo,
    required this.nikim,
    required this.mrz1,
    required this.mrz2,
    this.avatarUrl,
  });

  final String passportNo;
  final String name;
  final String sex;
  final String dateOfBirth;
  final String placeOfBirth;
  final String dateOfIssue;
  final String dateOfExpiry;
  final String regNo;
  final String nikim;
  final String mrz1;
  final String mrz2;
  final String? avatarUrl;

  // 静态常量（照设计稿，非 app-locale）。
  static const String type = 'P';
  static const String countryCode = 'IDN';
  static const String nationality = 'INDONESIA';
  static const String issuingOffice = 'TAILTOPIA';
}

/// 纯函数：快照 → 护照字段。护照号/Reg/NIKIM 由 serial 派生；签发/到期由 createdAt 派生；Sex/出生地默认。
PassportFields buildPassportFields(IdCardData data) {
  final serial = data.serialId ?? 0;
  final nikim = _nikim(serial, data.birthday);
  final name = (data.name?.isNotEmpty == true ? data.name! : 'MOCHI').toUpperCase();
  final issue = DateTime.now();
  final expiry = DateTime(issue.year + 5, issue.month, issue.day);
  return PassportFields(
    passportNo: 'A ${serial.toString().padLeft(6, '0').substring(0, 6)}',
    name: name,
    sex: 'L/M',
    dateOfBirth: data.birthday == null ? '00 SEP 0000' : _dmyUpper(data.birthday!),
    placeOfBirth: 'BANDUNG',
    dateOfIssue: _dmyUpper(issue),
    dateOfExpiry: _dmyUpper(expiry),
    regNo: serial.toString().padLeft(9, '0'),
    nikim: nikim,
    mrz1: _mrz('P<IDN${_mrzName(name)}'),
    mrz2: _mrz('$nikim${PassportFields.countryCode}'),
    avatarUrl: data.avatarUrl,
  );
}

/// NIKIM 12 位（微芯片号，仿制）：生日 DDMMYY + serial 补零 6 位。设计稿为 12 位，勿加区域码前缀
/// （否则过长压到 Reg.No 列，bug 20260721-330 首版返工）。
String _nikim(int serial, DateTime? birthday) {
  final ddmmyy = birthday == null
      ? '000000'
      : '${_p2(birthday.day)}${_p2(birthday.month)}${_p2(birthday.year % 100)}';
  return '$ddmmyy${serial.toString().padLeft(6, '0')}';
}

/// MRZ 行（**装饰性仿真**，非真 ICAO 校验）：非字母数字转 `<`，右侧补 `<` 到 44 位。
String _mrz(String raw) {
  final cleaned = raw.toUpperCase().replaceAll(RegExp('[^A-Z0-9]'), '<');
  return cleaned.length >= 44 ? cleaned.substring(0, 44) : cleaned.padRight(44, '<');
}

String _mrzName(String name) => name.replaceAll(' ', '<');

const List<String> _months = [
  'JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'
];
String _dmyUpper(DateTime d) => '${_p2(d.day)} ${_months[d.month - 1]} ${d.year}';
String _p2(int n) => n.toString().padLeft(2, '0');

/// 设计稿反解布局（画布 1990×1548 = 设计稿 995×774 的 2×）。
/// ⚠️ 首版坐标按成图比例估算，须回 `docs/design/id-cards/passport-mockup.png` 差分复测精调（L2）。
abstract final class _PL {
  static const double radius = 44;
  static const Rect logo = Rect.fromLTWH(44, 44, 216, 216);

  static const double titleCenterX = 1092;
  static const double titleBaseline = 132;
  static const double titleSize = 76;
  static const double subtitleBaseline = 206;
  static const double subtitleSize = 40;

  static const Rect photo = Rect.fromLTWH(60, 360, 580, 720);
  static const double photoRadius = 36;

  // 字段字号。
  static const double labelSize = 34;
  static const double valueSize = 48;

  // NIKIM（照片下方，与 Reg.No 值同底线，值 12 位不越到 Reg.No 列 x=650）。
  static const double nikimBaseline = 1176;
  static const double nikimLabelLeft = 76;
}

const Color _kInk = Color(0xFF1A1A1A);
const String _kFont = 'Rubik';
const List<FontVariation> _kReg = [FontVariation('wght', 400)];
const List<FontVariation> _kBold = [FontVariation('wght', 700)];

/// 单个字段规格：标签 + 值 + 位置 + 对齐（右对齐给 rightX，否则给 left）。
class _Field {
  const _Field(this.label, this.value, this.labelBaseline, this.valueBaseline,
      {this.left, this.rightX});
  final String label;
  final String value;
  final double labelBaseline;
  final double valueBaseline;
  final double? left; // 左对齐锚
  final double? rightX; // 右对齐锚（标签/值右缘对齐此 x）
}

/// 宠物护照卡面（Story 6-8）。底纹（绿波纹 + 底部 MRZ 灰条）来自 `passport_front_bg.png`，
/// 代码叠 logo / 标题 / 字段网格 / 照片 / NIKIM / MRZ 双行。**娱乐仿制**（MRZ 非真 ICAO）。
class PassportCardFront extends StatelessWidget {
  const PassportCardFront({super.key, required this.fields});

  final PassportFields fields;

  List<_Field> _fields() => <_Field>[
        // 左列。
        _Field('Jenis / Type', PassportFields.type, 300, 360, left: 650),
        _Field('Name / Name', fields.name, 470, 530, left: 650),
        _Field('Kewarganegaraan / Nationality', PassportFields.nationality, 640, 700, left: 650),
        _Field('Tgl.Lahir / Date of Birth', fields.dateOfBirth, 800, 860, left: 650),
        _Field('Tgl.Pengeluaran / Date of Issue', fields.dateOfIssue, 960, 1020, left: 650),
        _Field('Reg.No', fields.regNo, 1120, 1180, left: 650),
        // 中列。
        _Field('Kode Negara / Country Code', PassportFields.countryCode, 300, 360, left: 980),
        // 右列（右对齐到卡右缘）。
        _Field('No.Paspor / Passport.No', fields.passportNo, 300, 360, rightX: 1920),
        _Field('Kelamin / Sex', fields.sex, 470, 530, rightX: 1920),
        _Field('Tempat Lahir / Place of Birth', fields.placeOfBirth, 800, 860, rightX: 1920),
        _Field('Tgl.Habis Berlaku / Date of Expiry', fields.dateOfExpiry, 960, 1020, rightX: 1920),
        _Field('Kantor Yang Mengeluarkan / Issuing Office', PassportFields.issuingOffice, 1120, 1200,
            rightX: 1920),
      ];

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kPassportCardCanvas.width,
      height: kPassportCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(_PL.radius),
        child: Stack(
          children: [
            const Positioned.fill(
              child: Image(image: AssetImage('assets/passport/passport_front_bg.png'), fit: BoxFit.fill),
            ),
            _positioned(
              _PL.logo,
              const Image(image: AssetImage('assets/passport/passport_logo.png'), fit: BoxFit.fill),
            ),
            _centered('PASPOR HEWAN PELIHARAAN', _PL.titleCenterX, _PL.titleBaseline,
                _style(size: _PL.titleSize, weight: _kBold)),
            _centered('PET PASSPORT', _PL.titleCenterX, _PL.subtitleBaseline,
                _style(size: _PL.subtitleSize, weight: _kReg, italic: true)),
            _photo(),
            for (final f in _fields()) ..._fieldWidgets(f),
            // NIKIM（照片下方）。
            _left('NIKIM', _PL.nikimLabelLeft, _PL.nikimBaseline, _style(size: _PL.labelSize, weight: _kReg)),
            _left(fields.nikim, _PL.nikimLabelLeft + 190, _PL.nikimBaseline,
                _style(size: _PL.valueSize * 0.85, weight: _kBold)),
            // MRZ 双行（底部灰条）。
            _left(fields.mrz1, 76, 1416, _style(size: 46, weight: _kBold, tracking: 6)),
            _left(fields.mrz2, 76, 1486, _style(size: 46, weight: _kBold, tracking: 6)),
          ],
        ),
      ),
    );
  }

  List<Widget> _fieldWidgets(_Field f) {
    final label = Text(f.label, style: _style(size: _PL.labelSize, weight: _kReg));
    final value = Text(f.value, style: _style(size: _PL.valueSize, weight: _kBold));
    if (f.rightX != null) {
      return [
        _right(f.label, f.rightX!, f.labelBaseline, _style(size: _PL.labelSize, weight: _kReg)),
        _right(f.value, f.rightX!, f.valueBaseline, _style(size: _PL.valueSize, weight: _kBold)),
      ];
    }
    return [
      _atBaseline(left: f.left!, baseline: f.labelBaseline, child: label),
      _atBaseline(left: f.left!, baseline: f.valueBaseline, child: value),
    ];
  }

  Widget _photo() {
    final r = _PL.photo;
    return Positioned(
      left: r.left,
      top: r.top,
      width: r.width,
      height: r.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(_PL.photoRadius),
        child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
            ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 560)
            : Container(color: const Color(0xFF6DA8E0)), // 护照照片蓝底
      ),
    );
  }

  static Widget _positioned(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);

  static Widget _left(String text, double left, double baseline, TextStyle style) =>
      _atBaseline(left: left, baseline: baseline, child: Text(text, style: style));

  /// 右对齐：文字右缘落在 rightX（用宽容器右对齐再左移）。
  static Widget _right(String text, double rightX, double baseline, TextStyle style) {
    const double w = 1200;
    return Positioned(
      left: rightX - w,
      top: 0,
      child: Baseline(
        baseline: baseline,
        baselineType: TextBaseline.alphabetic,
        child: SizedBox(width: w, child: Text(text, textAlign: TextAlign.right, style: style)),
      ),
    );
  }

  static Widget _centered(String text, double centerX, double baseline, TextStyle style) {
    const double w = 1400;
    return Positioned(
      left: centerX - w / 2,
      top: 0,
      child: Baseline(
        baseline: baseline,
        baselineType: TextBaseline.alphabetic,
        child: SizedBox(width: w, child: Text(text, textAlign: TextAlign.center, style: style)),
      ),
    );
  }

  static Widget _atBaseline({required double left, required double baseline, required Widget child}) =>
      Positioned(
        left: left,
        top: 0,
        child: Baseline(baseline: baseline, baselineType: TextBaseline.alphabetic, child: child),
      );

  static TextStyle _style({
    required double size,
    required List<FontVariation> weight,
    double? tracking,
    bool italic = false,
  }) {
    return TextStyle(
      fontFamily: _kFont,
      fontVariations: weight,
      fontWeight: identical(weight, _kBold) ? FontWeight.w700 : FontWeight.w400,
      fontStyle: italic ? FontStyle.italic : FontStyle.normal,
      color: _kInk,
      fontSize: size,
      letterSpacing: tracking,
    );
  }
}
