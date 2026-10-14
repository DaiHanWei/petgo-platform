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

/// F3 布局（画布 1988×1200；文字为文字盒顶，`line-height 1.1`）。起点取 UI 稿 F3 CSS（cqw × 19.88 = px），
/// V1.3.4 复评后按概念图 `ktp-assets/17-style-izin-tinggal.png`（994×600 = 画布 ½）像素差分反解校准：
/// 同示例数据渲染 → 缩到 994×600 → 逐元素比对墨迹外接框（字号取同文本墨迹宽度比，位置取墨迹顶/左/右缘差 ×2），
/// 图片素材取模板匹配位置并按原生尺寸 ×2。校准后各元素墨迹框与概念图偏差 ≤1 设计 px（照片位除外，概念图为红色占位）。
abstract final class _KitasLayout {
  static const Rect label = Rect.fromLTWH(0, 52, 1988, 292); // 原 top 50.4；按概念图紫带上下沿差分 +0.75 设计 px
  static const double headerTop = 116; // 原 114；差分 +1 设计 px，水平居中
  static const double brandTop = 214; // 原 216；差分 −1 设计 px，水平居中
  static const double headerSize = 62.7; // 原 57.7（2.9cqw）；按概念图 17 差分：墨迹宽 452/416、161/148 ⇒ ×1.087
  static const Rect logo = Rect.fromLTWH(1698, 118, 162, 160); // 模板匹配 (849,59)，素材原生 81×80 ×2
  static const Rect photo = Rect.fromLTWH(79.5, 450, 449.3, 540); // 4% · 37.5% · 22.6% × 45%（与概念图红框 40,224,224×270 偏差 <1px，未动）
  static const double photoRadius = 31.8; // 1.6cqw

  static const double fieldX = 593.4; // 原 596.4；差分 −1.5 设计 px
  static const double fieldTop = 393; // 原 402；差分 −4.5 设计 px
  static const double fieldPitch = 63.9; // 原 64.2；概念图 10 行墨迹顶跨度 8 行距 = 31.94 设计 px
  static const double fieldSize = 50.0; // 原 44.7（2.25cqw）；差分：标签墨迹宽比 1.08~1.13、值 1.12~1.13 ⇒ ×1.12
  static const double valueWidth = 894.6; // 到签章左缘（1518）留 30（值单行，超宽缩字）

  static const double numberRight = 1988 - 1902.5; // 原 1898.5；概念图编号墨迹右缘 950 设计 px
  static const double numberTop = 392; // 原 402；差分 −5 设计 px
  static const double numberSize = 49.0; // 原 45.7（2.3cqw）；差分：墨迹宽 221/206 ⇒ ×1.073
  static const double numberWidth = 800; // 编号栏（右缘往左），不压字段列

  /// 爪印签章：原 (1530.8, 588, 298.2, 274.6) 按 UI 稿宽 15% 缩成 0.84 倍（即复评「小约 20%」）；
  /// 模板匹配概念图得素材原生尺寸 177×163 位于 (759, 296) 设计 px ⇒ ×2。
  static const Rect sign = Rect.fromLTWH(1518, 592, 354, 326);
  static const double footerTop = 1114.4; // 原 1118.4；差分 −2 设计 px
  static const double footerDx = -15; // 概念图副标题墨迹中心 491 设计 px（卡面中线 497 偏左 7.5）
  static const double footerSize = 46.6; // 原 41.7（2.1cqw）；差分：墨迹宽 268/240 ⇒ ×1.117
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
                newCardTextStyle(_KitasLayout.footerSize, _kRegular, _kValue, italic: true),
                dx: _KitasLayout.footerDx),
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

  static Widget _centered(String text, double top, TextStyle style, {double dx = 0}) => Positioned(
        left: dx,
        right: -dx,
        top: top,
        child: Text(text, textAlign: TextAlign.center, maxLines: 1, softWrap: false, style: style),
      );

  static Widget _rect(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);
}
