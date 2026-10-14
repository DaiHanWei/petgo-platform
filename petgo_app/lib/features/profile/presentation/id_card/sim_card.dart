import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import '../../domain/id_card.dart';
import 'id_card_number_format.dart';
import 'new_card_text.dart';

/// SIM 卡面（V1.3.4 ktp Story 1.3，UI 稿 F4，AD-7）逻辑画布：底图 `Driving.png` 994×600 的 2×，与 KTP 同画布。
const Size kSimCardCanvas = Size(1988, 1200);

/// SIM 卡面圆角（画布坐标）：底图圆角实测设计 ≈41px（×2），与 KTP / Klasik 取同值。供水印层裁剪对齐。
const double kSimCardCanvasRadius = 80;

/// SIM 展示字段（全部由快照派生）。大小写按 UI 稿（T-3）：用户填的值原样（trim），默认值首字母大写。
@immutable
class SimFields {
  const SimFields({
    required this.nama,
    required this.tempatTglLahir,
    required this.jenisKelamin,
    required this.alamat,
    required this.pekerjaan,
    required this.issueDate,
    this.cardNo,
    this.avatarUrl,
  });

  final String nama;
  final String tempatTglLahir;
  final String jenisKelamin;
  final String alamat;
  final String pekerjaan;

  /// 签发日期 `DD-MM-YYYY` = 建卡日（手机本地日期，T-4）。
  final String issueDate;

  /// 已格式化编号 `TTDDMM-YYYY-SPXXXX`；`card_no` 为空 → null，编号位留空（T-2，绝不回落 serial）。
  final String? cardNo;
  final String? avatarUrl;
}

/// 纯函数：快照 → SIM 字段。签发日 = createdAt（null = 建卡页预览 → 今天）。
SimFields buildSimFields(IdCardData data) {
  final dob = data.birthday == null ? NewCardDefaults.tanggalLahir : newCardDmy(data.birthday!);
  final cardNo = data.cardNo;
  return SimFields(
    nama: newCardTrimOrNull(data.name) ?? NewCardDefaults.nama,
    tempatTglLahir: '${newCardTrimOrNull(data.birthCity) ?? NewCardDefaults.tempatKota}, $dob',
    jenisKelamin: NewCardDefaults.jenisKelamin(data.gender),
    alamat: newCardTrimOrNull(data.address) ?? NewCardDefaults.alamat,
    pekerjaan: newCardTrimOrNull(data.occupation) ?? NewCardDefaults.pekerjaan,
    issueDate: newCardDmy((data.createdAt ?? DateTime.now()).toLocal()),
    cardNo: (cardNo == null || cardNo.isEmpty) ? null : formatSimCardNo(cardNo, birthday: data.birthday),
    avatarUrl: data.avatarUrl,
  );
}

/// UI 稿 F4 换算布局（画布 1988×1200；文字为 CSS 文字盒左上角，`line-height 1.1`；cqw × 19.88 = px）。
/// ⚠️ 起点值，L2 须与 UI 稿 F4 叠图逐像素校准。
abstract final class _SimLayout {
  static const Rect logo = Rect.fromLTWH(83.5, 69.6, 234.6, 234.6); // 4.2% · 5.8% · 宽 11.8%
  static const Offset title = Offset(363.8, 42); // 18.3% · 3.5%
  static const double titleSize = 123.3; // 6.2cqw
  static const Offset subtitle = Offset(1282.3, 93.6); // 64.5% · 7.8%
  static const double subtitleSize = 51.7; // 2.6cqw
  static const Offset header = Offset(363.8, 222); // 18.3% · 18.5%
  static const double headerSize = 53.7; // 2.7cqw
  static const Offset classMark = Offset(1779.3, 204); // 89.5% · 17%
  static const double classMarkSize = 131.2; // 6.6cqw
  static const Rect photo = Rect.fromLTWH(79.5, 378, 449.3, 546); // 4% · 31.5% · 22.6% × 45.5%
  static const double photoRadius = 31.8; // 1.6cqw

  static const double fieldX = 596.4; // 30%
  static const double fieldTop = 414; // 34.5%
  static const double fieldPitch = 64.2; // 5.35%
  static const double fieldSize = 44.7; // 2.25cqw
  static const double valueWidth = 874; // 到签章左缘留 30（值单行，超宽缩字）

  static const Offset number = Offset(1401.5, 414); // 70.5% · 34.5%
  static const double numberSize = 45.7; // 2.3cqw
  static const double numberWidth = 506; // 到卡右缘留 80

  static const Rect sign = Rect.fromLTWH(1500.9, 732, 310.1, 336.8); // 75.5% · 61% · 宽 15.6%（128×139）
  static const Offset issuerLabel = Offset(103.4, 1020); // 5.2% · 85%
  static const Offset issuerValue = Offset(103.4, 1083.6); // 5.2% · 90.3%
  static const Offset issueDate = Offset(1510.9, 1083.6); // 76% · 90.3%
}

const Color _kInk = Color(0xFF1E1B1B);
const List<FontVariation> _kRegular = [FontVariation('wght', 400)];
const List<FontVariation> _kMedium = [FontVariation('wght', 500)];
const List<FontVariation> _kBold = [FontVariation('wght', 700)];
const List<FontVariation> _kExtraBold = [FontVariation('wght', 800)];

/// 宠物 SIM 驾照卡面。底图（蓝色抬头带 + 底纹）来自 `Driving.png`，代码叠 Logo / 标题 / 字段 / 编号 / 照片 / 签章。
/// 不出现 `serial_id` 及其派生值（FR-142E.9）。**娱乐仿制**。
class SimCardFront extends StatelessWidget {
  const SimCardFront({super.key, required this.fields});

  final SimFields fields;

  @override
  Widget build(BuildContext context) {
    final rows = <(String, String)>[
      ('Nama', fields.nama),
      ('Tempat, Tanggal Lahir', fields.tempatTglLahir),
      ('Jenis Kelamin', fields.jenisKelamin),
      ('Alamat', fields.alamat),
      ('Pekerjaan', fields.pekerjaan),
    ];
    return SizedBox(
      width: kSimCardCanvas.width,
      height: kSimCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(kSimCardCanvasRadius),
        child: Stack(
          children: [
            const Positioned.fill(
              child: Image(image: AssetImage('assets/id_card/sim/background.png'), fit: BoxFit.fill),
            ),
            _rect(_SimLayout.logo,
                const Image(image: AssetImage('assets/id_card/sim/logo.png'), fit: BoxFit.contain)),
            newCardText('TAILTOPIA', _SimLayout.title,
                newCardTextStyle(_SimLayout.titleSize, _kBold, Colors.white, italic: true, tracking: 0.02)),
            newCardText('PET DRIVING LICENSE', _SimLayout.subtitle,
                newCardTextStyle(_SimLayout.subtitleSize, _kRegular, Colors.white, italic: true)),
            newCardText('SURAT IZIN MENGEMUDI HEWAN', _SimLayout.header,
                newCardTextStyle(_SimLayout.headerSize, _kMedium, _kInk)),
            // PRD：去掉车型代码，改为字母 T。
            newCardText('T', _SimLayout.classMark,
                newCardTextStyle(_SimLayout.classMarkSize, _kExtraBold, const Color(0xFF111111))),
            _photo(),
            for (var i = 0; i < rows.length; i++) ...[
              newCardText(rows[i].$1,
                  Offset(_SimLayout.fieldX, _SimLayout.fieldTop + 2 * i * _SimLayout.fieldPitch),
                  newCardTextStyle(_SimLayout.fieldSize, _kRegular, _kInk)),
              newCardText(rows[i].$2,
                  Offset(_SimLayout.fieldX, _SimLayout.fieldTop + (2 * i + 1) * _SimLayout.fieldPitch),
                  newCardTextStyle(_SimLayout.fieldSize, _kBold, _kInk),
                  width: _SimLayout.valueWidth),
            ],
            if (fields.cardNo != null)
              newCardText(fields.cardNo!, _SimLayout.number,
                  newCardTextStyle(_SimLayout.numberSize, _kBold, _kInk, tabular: true, tracking: 0.01),
                  width: _SimLayout.numberWidth, key: const ValueKey('simCardNumber')),
            // 爪印签章：与 KTP 共用同一素材（driving/Sign.png 与 ktp_paw_stamp.png 逐字节相同）。
            _rect(_SimLayout.sign,
                const Image(image: AssetImage('assets/ktp/ktp_paw_stamp.png'), fit: BoxFit.contain)),
            newCardText('Diterbitkan Oleh', _SimLayout.issuerLabel,
                newCardTextStyle(_SimLayout.fieldSize, _kRegular, _kInk)),
            newCardText('Tailtopia', _SimLayout.issuerValue, newCardTextStyle(_SimLayout.fieldSize, _kBold, _kInk)),
            newCardText(fields.issueDate, _SimLayout.issueDate,
                newCardTextStyle(_SimLayout.fieldSize, _kBold, _kInk, tabular: true)),
          ],
        ),
      ),
    );
  }

  Widget _photo() {
    final placeholder = Container(color: const Color(0xFFDCE8F7));
    return _rect(
      _SimLayout.photo,
      ClipRRect(
        borderRadius: BorderRadius.circular(_SimLayout.photoRadius),
        // 无图或加载失败用浅色占位，不空白。
        child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
            ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 500,
                errorBuilder: (_, _, _) => placeholder)
            : placeholder,
      ),
    );
  }

  static Widget _rect(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);
}
