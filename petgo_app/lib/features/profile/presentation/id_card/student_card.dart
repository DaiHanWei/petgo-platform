import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import '../../domain/id_card.dart';

/// 学生卡（Story 6-8，bug 20260721-330）逻辑画布：底图 `student_front_bg.png` 994×600 的 2×。
/// 所有设计坐标 ×2 即本文件常量。页面用 [FittedBox] 缩放；导出用同尺寸 RepaintBoundary。
const Size kStudentCardCanvas = Size(1988, 1200);

/// 学生卡展示字段（全部由快照派生，零新增采集，见 spec 6-8 §4.2）。
@immutable
class StudentFields {
  const StudentFields({
    required this.studentNo,
    required this.name,
    required this.birthday,
    required this.species,
    this.avatarUrl,
  });

  final String studentNo;
  final String name;
  final String birthday;
  final String species;
  final String? avatarUrl;
}

/// 纯函数：快照 → 学生卡字段（无趣味默认外的采集；serial → 证号，pet_type → 物种本地化）。
StudentFields buildStudentFields(IdCardData data) {
  return StudentFields(
    studentNo: _studentNo(data.serialId, data.birthday),
    name: (data.name?.isNotEmpty == true ? data.name! : 'MOCHI').toUpperCase(),
    birthday: data.birthday == null ? '01-01-2022' : _dmy(data.birthday!),
    species: _species(data.petType),
    avatarUrl: data.avatarUrl,
  );
}

/// 16 位学生证号（与 KTP NIK 同区域码范式，随 serial 唯一）：`3276` + 生日 DDMMYY + serial 补零 6 位。
String _studentNo(int? serialId, DateTime? birthday) {
  final ddmmyy = birthday == null
      ? '010122'
      : '${_p2(birthday.day)}${_p2(birthday.month)}${_p2(birthday.year % 100)}';
  return '3276$ddmmyy${(serialId ?? 0).toString().padLeft(6, '0')}';
}

String _species(String? petType) => switch (petType) {
      'CAT' => 'KUCING',
      'DOG' => 'ANJING',
      _ => 'HEWAN',
    };

String _dmy(DateTime d) => '${_p2(d.day)}-${_p2(d.month)}-${d.year}';
String _p2(int n) => n.toString().padLeft(2, '0');

/// 设计稿反解布局（画布 1988×1200 = 设计稿 994×600 的 2×）。
/// ⚠️ 首版坐标按成图比例估算，须回 `docs/design/id-cards/student-mockup.png` 差分复测精调（L2）。
abstract final class _StudentLayout {
  static const double radius = 80;

  // 左上 logo（紫头条内白 logo）。
  static const Rect logo = Rect.fromLTWH(150, 96, 220, 220);

  // 标题（头条内，白字居中偏右）。
  static const double titleCenterX = 1090;
  static const double titleBaseline = 116;
  static const double titleSize = 84;
  static const double subtitleBaseline = 208;
  static const double subtitleSize = 44;

  // 宠物照片（左）。
  static const Rect photo = Rect.fromLTWH(72, 420, 380, 400);
  static const double photoBorder = 8;
  static const double photoRadius = 40;

  // 大号证号 + 下划线（照片右）。
  static const double numLeft = 640;
  static const double numBaseline = 560;
  static const double numSize = 92;
  static const double numTracking = -1.0;
  static const double underlineY = 600;
  static const double underlineRight = 1860;

  // 字段三行（label : value）。
  static const double labelX = 640;
  static const double colonX = 980;
  static const double valueX = 1040;
  static const double firstBaseline = 700;
  static const double pitch = 82;
  static const double fieldSize = 48;

  // 右下圆章（素材 199×262 竖幅）。
  static const Rect stamp = Rect.fromLTWH(1548, 720, 320, 421);
}

/// 全卡文字墨色（学生卡为深紫；头条内为白）。
const Color _kInk = Color(0xFF3D2A63);
const String _kFontFamily = 'Rubik';
const List<FontVariation> _kMedium = [FontVariation('wght', 500)];
const List<FontVariation> _kSemiBold = [FontVariation('wght', 600)];
const List<FontVariation> _kBold = [FontVariation('wght', 700)];

/// TailTopia Academy 学生卡（Story 6-8）。底纹（紫头条 + 爪印水印 + 紫底线）来自
/// `student_front_bg.png`，代码叠 logo / 标题 / 证号 / 字段 / 照片 / 圆章。**娱乐仿制**。
class StudentCardFront extends StatelessWidget {
  const StudentCardFront({super.key, required this.fields});

  final StudentFields fields;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kStudentCardCanvas.width,
      height: kStudentCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(_StudentLayout.radius),
        child: Stack(
          children: [
            const Positioned.fill(
              child: Image(image: AssetImage('assets/student/student_front_bg.png'), fit: BoxFit.fill),
            ),
            _positioned(
              _StudentLayout.logo,
              const Image(image: AssetImage('assets/student/student_logo.png'), fit: BoxFit.fill),
            ),
            _centered('TAILTOPIA ACADEMY', _StudentLayout.titleCenterX, _StudentLayout.titleBaseline,
                _style(size: _StudentLayout.titleSize, weight: _kBold, color: Colors.white)),
            _centered('STUDENT CARD', _StudentLayout.titleCenterX, _StudentLayout.subtitleBaseline,
                _style(size: _StudentLayout.subtitleSize, weight: _kMedium, color: Colors.white, italic: true)),
            _photo(),
            _atBaseline(
              left: _StudentLayout.numLeft,
              baseline: _StudentLayout.numBaseline,
              child: Text(fields.studentNo,
                  style: _style(size: _StudentLayout.numSize, weight: _kBold, tracking: _StudentLayout.numTracking)),
            ),
            _underline(),
            ..._fieldRows(),
            _positioned(
              _StudentLayout.stamp,
              const Image(image: AssetImage('assets/student/student_stamp.png'), fit: BoxFit.contain),
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _fieldRows() {
    final rows = <(String, String)>[
      ('Name', fields.name),
      ('Date of Birth', fields.birthday),
      ('Species', fields.species),
    ];
    final out = <Widget>[];
    for (var i = 0; i < rows.length; i++) {
      final baseline = _StudentLayout.firstBaseline + i * _StudentLayout.pitch;
      out.add(_atBaseline(
        left: _StudentLayout.labelX,
        baseline: baseline,
        child: Text(rows[i].$1, style: _style(size: _StudentLayout.fieldSize, weight: _kMedium)),
      ));
      out.add(_atBaseline(
        left: _StudentLayout.colonX,
        baseline: baseline,
        child: Text(':', style: _style(size: _StudentLayout.fieldSize, weight: _kMedium)),
      ));
      out.add(_atBaseline(
        left: _StudentLayout.valueX,
        baseline: baseline,
        child: Text(rows[i].$2, style: _style(size: _StudentLayout.fieldSize, weight: _kSemiBold)),
      ));
    }
    return out;
  }

  Widget _underline() => Positioned(
        left: _StudentLayout.numLeft,
        top: _StudentLayout.underlineY,
        child: Container(
          width: _StudentLayout.underlineRight - _StudentLayout.numLeft,
          height: 3,
          color: _kInk.withValues(alpha: 0.3),
        ),
      );

  Widget _photo() {
    final r = _StudentLayout.photo;
    return Positioned(
      left: r.left,
      top: r.top,
      width: r.width,
      height: r.height,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(_StudentLayout.photoRadius),
        ),
        padding: const EdgeInsets.all(_StudentLayout.photoBorder),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(_StudentLayout.photoRadius - _StudentLayout.photoBorder),
          child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
              ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 400)
              : Container(color: const Color(0xFFEDE7F7)),
        ),
      ),
    );
  }

  static Widget _positioned(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);

  static Widget _centered(String text, double centerX, double baseline, TextStyle style) {
    // 以 centerX 为中心：用足够宽的容器居中，再整体左移半宽。
    const double w = 1600;
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

  static Widget _atBaseline({required double left, required double baseline, required Widget child}) {
    return Positioned(
      left: left,
      top: 0,
      child: Baseline(baseline: baseline, baselineType: TextBaseline.alphabetic, child: child),
    );
  }

  static TextStyle _style({
    required double size,
    required List<FontVariation> weight,
    double? tracking,
    Color color = _kInk,
    bool italic = false,
  }) {
    return TextStyle(
      fontFamily: _kFontFamily,
      fontVariations: weight,
      fontWeight: identical(weight, _kBold)
          ? FontWeight.w700
          : (identical(weight, _kSemiBold) ? FontWeight.w600 : FontWeight.w500),
      fontStyle: italic ? FontStyle.italic : FontStyle.normal,
      color: color,
      fontSize: size,
      letterSpacing: tracking,
    );
  }
}
