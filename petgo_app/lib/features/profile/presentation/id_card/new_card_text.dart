/// V1.3.4 ktp 新卡面（SIM / 居住证）共用的文字工具、默认值与日期格式。
library;

import 'package:flutter/material.dart';

/// SIM / 居住证卡面趣味默认值（FR-142D.5：同 KTP 默认语义；大小写按 UI 稿首字母大写，T-3）。
abstract final class NewCardDefaults {
  static const String nama = 'Mochi';
  static const String tempatKota = 'Bandung';
  static const String tanggalLahir = '01-01-2020';
  static const String alamat = 'Jl. Melati No. 25, Bandung';
  static const String pekerjaan = 'Chief Happiness Officer';

  /// petType → 印尼语物种（首字母大写）。
  static String spesies(String? petType) => switch (petType) {
        'CAT' => 'Kucing',
        'DOG' => 'Anjing',
        _ => 'Hewan',
      };

  /// gender wire 值 → Jenis Kelamin：公 Jantan / 母 Betina / 未知 `-`；null（不应出现）→ Jantan（同 KTP 旧默认）。
  static String jenisKelamin(String? gender) => switch (gender) {
        'MALE' => 'Jantan',
        'FEMALE' => 'Betina',
        'UNKNOWN' => '-',
        _ => 'Jantan',
      };
}

/// 选填文本：trim 后为空 → null（卡面据 null 渲染趣味默认）。
String? newCardTrimOrNull(String? v) => (v == null || v.trim().isEmpty) ? null : v.trim();

/// 卡面日期 `DD-MM-YYYY`。
String newCardDmy(DateTime d) =>
    '${d.day.toString().padLeft(2, '0')}-${d.month.toString().padLeft(2, '0')}-${d.year}';

/// 新卡面通用：CSS 文字盒左上角定位（[at]）。给了 [width] 则单行、超宽等比缩字（T-6），不越出自己的栏。
Widget newCardText(String text, Offset at, TextStyle style, {double? width, Key? key}) {
  final t = Text(text, key: key, style: style, maxLines: 1, softWrap: false);
  return Positioned(
    left: at.dx,
    top: at.dy,
    width: width,
    child: width == null
        ? t
        : FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: t),
  );
}

/// 新卡面通用字体：Rubik 可变字体按 `wght` 取字重；`line-height 1.1` 与 UI 稿同口径；[tracking] 单位 em。
TextStyle newCardTextStyle(double size, List<FontVariation> weight, Color color,
    {bool italic = false, bool tabular = false, double? tracking}) {
  final w = (weight.first.value).round();
  return TextStyle(
    fontFamily: 'Rubik',
    fontVariations: weight,
    fontWeight: FontWeight.values.firstWhere((fw) => fw.value == w, orElse: () => FontWeight.w400),
    fontStyle: italic ? FontStyle.italic : FontStyle.normal,
    fontSize: size,
    height: 1.1,
    leadingDistribution: TextLeadingDistribution.even,
    letterSpacing: tracking == null ? null : size * tracking,
    color: color,
    fontFeatures: tabular ? const [FontFeature.tabularFigures()] : null,
  );
}
