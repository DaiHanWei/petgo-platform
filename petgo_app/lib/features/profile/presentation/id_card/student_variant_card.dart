import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import 'student_card.dart';

/// V1.3.4 ktp 三款新学生卡（Bintang / Malam / Ceria，UI 稿 F5 ~ F7，AD-7）共用的卡面组件。
///
/// 一个组件 + 每款一份布局常量（[StudentVariantLayout]）：Bintang 照片在右、文字在左，与 Malam / Ceria
/// 方向相反（T-5），所以三款各有自己的坐标，而不是同一套坐标换底图。
///
/// 画布 1988×1200 = 设计素材 994×600 的 2×；底图（已含圆章 / 星星边框 / 分隔线）`BoxFit.fill` 铺满，
/// 组件只叠 Logo / 抬头 / 字段 / 编号 / 照片。字段取值复用 [buildStudentFields]（趣味默认值同 Klasik）。
/// 坐标为 UI 稿百分比换算起点，⚠️ L2 须对照 `Student02.png` / `Student03.png` / `Student.png` 像素差分校准。
///
/// 编号只来自 [StudentFields.cardNo]（不写任何示例号码）；为 null / 空串（V95 前旧卡、预览未填生日）→
/// 「Nomor Induk Anabul」与编号整块不渲染，其余元素位置不动。
class StudentVariantCardFront extends StatelessWidget {
  const StudentVariantCardFront({super.key, required this.layout, required this.fields});

  final StudentVariantLayout layout;
  final StudentFields fields;

  @override
  Widget build(BuildContext context) {
    final l = layout;
    final cardNo = fields.cardNo;
    return SizedBox(
      width: kStudentCardCanvas.width,
      height: kStudentCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(kStudentVariantCanvasRadius),
        child: Stack(
          children: [
            Positioned.fill(child: Image(image: AssetImage(l.background), fit: BoxFit.fill)),
            Positioned(
              left: l.logo.dx,
              top: l.logo.dy,
              width: l.logoWidth,
              height: l.logoWidth,
              child: Image(image: AssetImage(l.logoAsset), fit: BoxFit.contain),
            ),
            _text('TAILTOPIA ACADEMY', l.title, _s(88.5, _kExtraBold, l.titleColor)),
            _text('STUDENT CARD', l.subtitle, _s(45.7, _kMedium, l.subtitleColor, italic: true)),
            _photo(),
            // 学校 / 名字单行：超宽等比缩小字号（T-6）；院系最多两行。
            _line(fields.school, l.school, l.schoolWidth, _s(57.7, _kBold, l.schoolColor)),
            _text(fields.faculty, l.faculty, _s(51.7, _kMedium, l.ink),
                width: l.textWidth, maxLines: 2),
            _line(fields.name, l.name, l.textWidth,
                _s(l.nameSize, l.nameBold ? _kBold : _kMedium, l.ink)),
            _line(fields.species, l.species, l.speciesWidth, _s(41.7, _kMedium, l.ink)),
            _line(fields.birthday, l.birthday, l.birthdayWidth, _s(41.7, _kMedium, l.ink, tabular: true)),
            if (cardNo != null && cardNo.isNotEmpty) ...[
              _text('Nomor Induk Anabul', l.numberLabel, _s(34.8, _kMedium, l.ink),
                  key: const ValueKey('studentCardNumberLabel')),
              _line(cardNo, l.number, l.textWidth,
                  _s(61.6, _kBold, l.ink, tracking: 61.6 * 0.02, tabular: true),
                  key: const ValueKey('studentCardNumber')),
            ],
          ],
        ),
      ),
    );
  }

  Widget _photo() {
    final r = layout.photo;
    final placeholder = Container(color: layout.photoPlaceholder);
    return Positioned(
      left: r.left,
      top: r.top,
      width: r.width,
      height: r.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(kStudentVariantPhotoRadius),
        // 无图或加载失败用浅色占位（与 Klasik 同口径，不空白）。
        child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
            ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 600,
                errorBuilder: (_, _, _) => placeholder)
            : placeholder,
      ),
    );
  }

  /// 左上角定位的文字盒（CSS `left/top` + `line-height 1.1` 同口径）。
  static Widget _text(String text, Offset at, TextStyle style,
      {double? width, int maxLines = 1, Key? key}) {
    return Positioned(
      left: at.dx,
      top: at.dy,
      width: width,
      child: Text(
        text,
        key: key,
        style: style,
        maxLines: maxLines,
        softWrap: maxLines > 1,
        overflow: maxLines > 1 ? TextOverflow.ellipsis : TextOverflow.visible,
      ),
    );
  }

  /// 单行文字：栏宽内放得下就原字号，放不下等比缩小（T-6），绝不越出自己的文字栏。
  static Widget _line(String text, Offset at, double width, TextStyle style, {Key? key}) {
    return Positioned(
      left: at.dx,
      top: at.dy,
      width: width,
      child: FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.centerLeft,
        child: Text(text, key: key, style: style, maxLines: 1, softWrap: false),
      ),
    );
  }

  static TextStyle _s(double size, List<FontVariation> weight, Color color,
      {bool italic = false, double? tracking, bool tabular = false}) {
    return TextStyle(
      fontFamily: 'Rubik',
      fontVariations: weight,
      fontWeight: identical(weight, _kExtraBold)
          ? FontWeight.w800
          : (identical(weight, _kBold) ? FontWeight.w700 : FontWeight.w500),
      fontStyle: italic ? FontStyle.italic : FontStyle.normal,
      fontSize: size,
      height: 1.1,
      leadingDistribution: TextLeadingDistribution.even,
      letterSpacing: tracking,
      color: color,
      fontFeatures: tabular ? const [FontFeature.tabularFigures()] : null,
    );
  }
}

/// 三款新学生卡圆角（画布坐标）：底图圆角实测设计 ≈40px（×2）。供水印层裁剪对齐。
const double kStudentVariantCanvasRadius = 80;

/// 照片圆角（参考图约设计 24px，×2）。
const double kStudentVariantPhotoRadius = 48;

const List<FontVariation> _kMedium = [FontVariation('wght', 500)];
const List<FontVariation> _kBold = [FontVariation('wght', 700)];
const List<FontVariation> _kExtraBold = [FontVariation('wght', 800)];

/// 单款学生卡布局常量（画布 1988×1200 坐标，文字为左上角）。来源：UI 稿 F5 ~ F7（story 1-2 Dev Notes「三款布局」）。
@immutable
class StudentVariantLayout {
  const StudentVariantLayout({
    required this.background,
    required this.logoAsset,
    required this.logo,
    required this.logoWidth,
    required this.photo,
    required this.title,
    required this.subtitle,
    required this.school,
    required this.faculty,
    required this.name,
    required this.nameSize,
    required this.nameBold,
    required this.species,
    required this.birthday,
    required this.numberLabel,
    required this.number,
    required this.textWidth,
    required this.speciesWidth,
    required this.birthdayWidth,
    required this.ink,
    required this.titleColor,
    required this.subtitleColor,
    required this.schoolColor,
    required this.photoPlaceholder,
  });

  final String background;
  final String logoAsset;
  final Offset logo;
  final double logoWidth;
  final Rect photo;
  final Offset title;
  final Offset subtitle;
  final Offset school;
  final Offset faculty;
  final Offset name;
  final double nameSize;
  final bool nameBold;
  final Offset species;
  final Offset birthday;
  final Offset numberLabel;
  final Offset number;

  /// 文字栏宽（学校 / 院系 / 名字 / 编号），右缘不压照片、不出卡。
  final double textWidth;

  /// 物种栏宽（Bintang 与生日同行，栏宽到生日左缘为止）。
  final double speciesWidth;
  final double birthdayWidth;

  final Color ink;
  final Color titleColor;
  final Color subtitleColor;
  final Color schoolColor;
  final Color photoPlaceholder;

  double get schoolWidth => textWidth;

  /// F5 Bintang：照片在右、文字在左；物种与生日同一行。
  static const bintang = StudentVariantLayout(
    background: 'assets/id_card/student_bintang/background.png',
    logoAsset: 'assets/id_card/student_bintang/logo.png',
    logo: Offset(1644, 154),
    logoWidth: 189,
    photo: Rect.fromLTWH(1272, 370, 557, 664),
    title: Offset(193, 174),
    subtitle: Offset(197, 278),
    school: Offset(209, 414),
    faculty: Offset(209, 504),
    name: Offset(209, 720),
    nameSize: 41.7,
    nameBold: false,
    species: Offset(209, 786),
    birthday: Offset(934, 786),
    numberLabel: Offset(209, 906),
    number: Offset(209, 972),
    textWidth: 1023, // 209 → 照片左缘 1272 留 40
    speciesWidth: 685, // 209 → 生日 934 留 40
    birthdayWidth: 298, // 934 → 照片左缘留 40
    ink: Color(0xFF5A5000),
    titleColor: Color(0xFF6E6200),
    subtitleColor: Color(0xFF5A5000),
    schoolColor: Color(0xFF5A5000),
    photoPlaceholder: Color(0xFFFFF7CC),
  );

  /// F6 Malam：照片在左、文字在右；深底白字，抬头与学校 `#C9BAFB`。
  static const malam = StudentVariantLayout(
    background: 'assets/id_card/student_malam/background.png',
    logoAsset: 'assets/id_card/student_malam/logo.png',
    logo: Offset(1769, 54),
    logoWidth: 167,
    photo: Rect.fromLTWH(105, 350, 493, 672),
    title: Offset(111, 76),
    subtitle: Offset(115, 174),
    school: Offset(688, 364),
    faculty: Offset(688, 480),
    name: Offset(688, 678),
    nameSize: 57.7,
    nameBold: true,
    species: Offset(688, 756),
    birthday: Offset(688, 810),
    numberLabel: Offset(688, 892),
    number: Offset(688, 942),
    textWidth: 1220, // 688 → 卡右缘 1988 留 80
    speciesWidth: 1220,
    birthdayWidth: 1220,
    ink: Colors.white,
    titleColor: Color(0xFFC9BAFB),
    subtitleColor: Colors.white,
    schoolColor: Color(0xFFC9BAFB),
    photoPlaceholder: Color(0xFF3A2F6B),
  );

  /// F7 Ceria：照片在左、文字在右；正文 `#22346E`、抬头 `#8396F5`。
  static const ceria = StudentVariantLayout(
    background: 'assets/id_card/student_ceria/background.png',
    logoAsset: 'assets/id_card/student_ceria/logo.png',
    logo: Offset(1745, 70),
    logoWidth: 179,
    photo: Rect.fromLTWH(80, 264, 616, 738),
    title: Offset(87, 78),
    subtitle: Offset(91, 178),
    school: Offset(761, 312),
    faculty: Offset(761, 444),
    name: Offset(761, 630),
    nameSize: 57.7,
    nameBold: true,
    species: Offset(761, 714),
    birthday: Offset(761, 774),
    numberLabel: Offset(761, 858),
    number: Offset(761, 912),
    textWidth: 1147, // 761 → 卡右缘 1988 留 80
    speciesWidth: 1147,
    birthdayWidth: 1147,
    ink: Color(0xFF22346E),
    titleColor: Color(0xFF8396F5),
    subtitleColor: Color(0xFF22346E),
    schoolColor: Color(0xFF22346E),
    photoPlaceholder: Color(0xFFE6EBFF),
  );
}
