import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';
import '../../domain/id_card.dart';
import 'id_card_number_format.dart';
import 'new_card_text.dart';

/// 居住证（Kartu Izin Tinggal，V1.3.4 ktp Story 1.3，UI 稿 F3，AD-7）逻辑画布：底图 994×600 的 2×，与 KTP 同画布。
/// 底图原图 `permit/Background.png` 1008×604，入库前按 alpha 外接框（x3 y4）裁到卡片本体 994×600。
const Size kKitasCardCanvas = Size(1988, 1200);

/// 居住证卡面圆角（画布坐标）：底图圆角实测设计 ≈41px（×2），与 KTP / Klasik 取同值。供水印层裁剪对齐。
const double kKitasCardCanvasRadius = 80;

/// 居住证展示字段（全部由快照派生）。大小写按 UI 稿（T-3）。
@immutable
class KitasFields {
  const KitasFields({
    required this.nama,
    required this.spesies,
    required this.ras,
    required this.tanggalLahir,
    required this.jenisKelamin,
    this.cardNo,
    this.avatarUrl,
  });

  final String nama;
  final String spesies;
  final String ras;
  final String tanggalLahir;
  final String jenisKelamin;

  /// 已格式化编号 `TT-DDMMYYSPXXXX`；`card_no` 为空 → null，编号位留空（T-2，绝不回落 serial）。
  final String? cardNo;
  final String? avatarUrl;
}

/// 纯函数：快照 → 居住证字段。
KitasFields buildKitasFields(IdCardData data) {
  final cardNo = data.cardNo;
  return KitasFields(
    nama: newCardTrimOrNull(data.name) ?? NewCardDefaults.nama,
    spesies: NewCardDefaults.spesies(data.petType),
    // 品种空 → `-`（同 KTP）。
    ras: newCardTrimOrNull(data.breed) ?? '-',
    tanggalLahir: data.birthday == null ? NewCardDefaults.tanggalLahir : newCardDmy(data.birthday!),
    jenisKelamin: NewCardDefaults.jenisKelamin(data.gender),
    cardNo: (cardNo == null || cardNo.isEmpty) ? null : formatKitasCardNo(cardNo),
    avatarUrl: data.avatarUrl,
  );
}

/// UI 稿 F3 换算布局（画布 1988×1200；文字为 CSS 文字盒顶，`line-height 1.1`；cqw × 19.88 = px）。
/// ⚠️ 起点值，L2 须与 UI 稿 F3 叠图逐像素校准。
abstract final class _KitasLayout {
  static const Rect label = Rect.fromLTWH(0, 50.4, 1988, 292); // 0 · 4.2% · 宽 100%（994×146）
  static const double headerTop = 114; // 9.5%，水平居中
  static const double brandTop = 216; // 18%，水平居中
  static const double headerSize = 57.7; // 2.9cqw
  static const Rect logo = Rect.fromLTWH(1699.7, 117.6, 159, 157); // 85.5% · 9.8% · 宽 8%（81×80）
  static const Rect photo = Rect.fromLTWH(79.5, 450, 449.3, 540); // 4% · 37.5% · 22.6% × 45%
  static const double photoRadius = 31.8; // 1.6cqw

  static const double fieldX = 596.4; // 30%
  static const double fieldTop = 402; // 33.5%
  static const double fieldPitch = 64.2; // 5.35%
  static const double fieldSize = 44.7; // 2.25cqw
  static const double valueWidth = 904; // 到签章左缘留 30（值单行，超宽缩字）

  static const double numberRight = 1988 - 1898.5; // 右对齐到 95.5%
  static const double numberTop = 402; // 33.5%
  static const double numberSize = 45.7; // 2.3cqw
  static const double numberWidth = 800; // 编号栏（右缘 1898.5 往左），不压字段列

  static const Rect sign = Rect.fromLTWH(1530.8, 588, 298.2, 274.6); // 77% · 49% · 宽 15%（177×163）
  static const double footerTop = 1118.4; // 93.2%，水平居中
  static const double footerSize = 41.7; // 2.1cqw
}

const Color _kLabel = Color(0xFF2E2A45);
const Color _kValue = Color(0xFF4B33A8);
const List<FontVariation> _kRegular = [FontVariation('wght', 400)];
const List<FontVariation> _kMedium = [FontVariation('wght', 500)];
const List<FontVariation> _kBold = [FontVariation('wght', 700)];

/// 宠物居住证卡面。底图 + 抬头带 `Label.png` + 右上 Logo（D-18：Permit 素材包自带）+ 爪印签章由素材提供，
/// 代码叠抬头文字 / 字段 / 编号 / 照片 / 底部副标题。不出现 `serial_id`（FR-142E.9）；签章下不画签名横线。**娱乐仿制**。
class KitasCardFront extends StatelessWidget {
  const KitasCardFront({super.key, required this.fields});

  final KitasFields fields;

  @override
  Widget build(BuildContext context) {
    final rows = <(String, String)>[
      ('Nama', fields.nama),
      ('Spesies', fields.spesies),
      ('Ras', fields.ras),
      ('Tanggal Lahir', fields.tanggalLahir),
      ('Jenis Kelamin', fields.jenisKelamin),
    ];
    return SizedBox(
      width: kKitasCardCanvas.width,
      height: kKitasCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(kKitasCardCanvasRadius),
        child: Stack(
          children: [
            const Positioned.fill(
              child: Image(image: AssetImage('assets/id_card/kitas/background.png'), fit: BoxFit.fill),
            ),
            _rect(_KitasLayout.label,
                const Image(image: AssetImage('assets/id_card/kitas/label.png'), fit: BoxFit.fill)),
            _centered('Pengurus Administrasi Hewan', _KitasLayout.headerTop,
                newCardTextStyle(_KitasLayout.headerSize, _kMedium, Colors.white)),
            _centered('TAILTOPIA', _KitasLayout.brandTop,
                newCardTextStyle(_KitasLayout.headerSize, _kBold, _kValue)),
            _rect(_KitasLayout.logo,
                const Image(image: AssetImage('assets/id_card/kitas/logo.png'), fit: BoxFit.contain)),
            _photo(),
            for (var i = 0; i < rows.length; i++) ...[
              newCardText(rows[i].$1,
                  Offset(_KitasLayout.fieldX, _KitasLayout.fieldTop + 2 * i * _KitasLayout.fieldPitch),
                  newCardTextStyle(_KitasLayout.fieldSize, _kRegular, _kLabel)),
              newCardText(rows[i].$2,
                  Offset(_KitasLayout.fieldX, _KitasLayout.fieldTop + (2 * i + 1) * _KitasLayout.fieldPitch),
                  newCardTextStyle(_KitasLayout.fieldSize, _kBold, _kValue),
                  width: _KitasLayout.valueWidth),
            ],
            if (fields.cardNo != null)
              Positioned(
                right: _KitasLayout.numberRight,
                top: _KitasLayout.numberTop,
                width: _KitasLayout.numberWidth,
                // 右对齐；异常长值（不应出现）等比缩字，不向左压到字段列。
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerRight,
                  child: Text(fields.cardNo!,
                      key: const ValueKey('kitasCardNumber'),
                      maxLines: 1,
                      softWrap: false,
                      style: newCardTextStyle(_KitasLayout.numberSize, _kBold, _kLabel, tabular: true, tracking: 0.01)),
                ),
              ),
            _rect(_KitasLayout.sign,
                const Image(image: AssetImage('assets/id_card/kitas/sign.png'), fit: BoxFit.contain)),
            _centered('Kartu Izin Tinggal Hewan', _KitasLayout.footerTop,
                newCardTextStyle(_KitasLayout.footerSize, _kRegular, _kValue, italic: true)),
          ],
        ),
      ),
    );
  }

  Widget _photo() {
    final placeholder = Container(color: const Color(0xFFE7E2FA));
    return _rect(
      _KitasLayout.photo,
      ClipRRect(
        borderRadius: BorderRadius.circular(_KitasLayout.photoRadius),
        // 无图或加载失败用浅色占位，不空白。
        child: fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty
            ? AppImage.widget(fields.avatarUrl!, fit: BoxFit.cover, thumbWidth: 500,
                errorBuilder: (_, _, _) => placeholder)
            : placeholder,
      ),
    );
  }

  static Widget _centered(String text, double top, TextStyle style) => Positioned(
        left: 0,
        right: 0,
        top: top,
        child: Text(text, textAlign: TextAlign.center, maxLines: 1, softWrap: false, style: style),
      );

  static Widget _rect(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);
}
