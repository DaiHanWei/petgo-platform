import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import '../../domain/id_card.dart';

/// 学生卡（Story 6-8，bug 20260721-330）逻辑画布：底图 `student_front_bg.png` 994×600 的 2×。
/// 所有设计坐标 ×2 即本文件常量。页面用 [FittedBox] 缩放；导出用同尺寸 RepaintBoundary。
const Size kStudentCardCanvas = Size(1988, 1200);

/// 学生卡卡面圆角（画布坐标系）。供预览水印层（[IdCardWatermark]）裁剪对齐。
const double kStudentCardCanvasRadius = _StudentLayout.radius;

/// 学生卡展示字段（全部由快照派生，零新增采集，见 spec 6-8 §4.2）。
/// spec ktp-pet-idcode-numbering：学生卡面**取消证号展示**，无 studentNo。
@immutable
class StudentFields {
  const StudentFields({
    required this.name,
    required this.birthday,
    required this.species,
    required this.school,
    required this.faculty,
    this.avatarUrl,
  });

  final String name;
  final String birthday;
  final String species;

  /// 学校/学院（bug 20260730-429：用户填写快照；null → 趣味默认）。
  final String school;
  final String faculty;
  final String? avatarUrl;
}

/// 纯函数：快照 → 学生卡字段（pet_type → 物种本地化；school/faculty 空落趣味默认）。
StudentFields buildStudentFields(IdCardData data) {
  return StudentFields(
    name: (data.name?.isNotEmpty == true ? data.name! : 'MOCHI').toUpperCase(),
    birthday: data.birthday == null ? '01-01-2022' : _dmy(data.birthday!),
    species: _species(data.petType),
    school: (data.school?.trim().isNotEmpty == true)
        ? data.school!.trim().toUpperCase()
        : 'TAILTOPIA ACADEMY',
    faculty: (data.faculty?.trim().isNotEmpty == true)
        ? data.faculty!.trim().toUpperCase()
        : 'FAKULTAS KEBAHAGIAAN',
    avatarUrl: data.avatarUrl,
  );
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

  // 字段五行（label : value）。bug 20260730-429：加 School/Faculty 两行并整体上移
  // （700→540；与照片区大致纵向居中）。2026-08-07 用户反馈再上移：改为**首行与照片顶端平齐**。
  static const double labelX = 640;
  static const double colonX = 980;
  static const double valueX = 1040;

  /// Rubik 的 capHeight = 700/1000 em（读自 `assets/fonts/Rubik-VariableFont_wght.ttf`
  /// 的 OS/2 表，'N'/'F'/'D' 的 yMax 亦为 700）。字号 × 此比例 = 大写字母顶端到基线的距离。
  static const double _capHeightRatio = 0.70;

  /// 首行基线 = 照片顶端 + 一个 cap 高 ⇒ 「Name」的**字母顶端**与照片上沿齐平（≈453.6）。
  ///
  /// 之所以写成派生式而不是拍一个数：这个位置的设计意图就是「跟照片顶端对齐」，
  /// photo 或 fieldSize 一改，它必须跟着走。写死数字的话下次调照片位置就会悄悄错位。
  /// ⚠️ 用 capHeight 而非字体 ascent（0.935 em）—— ascent 含字母上方的空白，
  /// 按它对齐会让肉眼看到的字比照片低一截。
  static final double firstBaseline = photo.top + fieldSize * _capHeightRatio;
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
      ('School', fields.school),
      ('Faculty', fields.faculty),
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
          // 加载失败（快照 URL 死链/网络故障）与无照片同占位，不静默空白。
          child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
              ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 400,
                  errorBuilder: (_, _, _) => Container(color: const Color(0xFFEDE7F7)))
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
