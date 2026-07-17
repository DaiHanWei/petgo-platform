import 'package:flutter/material.dart';

import '../../../../shared/widgets/app_image.dart';

/// KTP 证件卡逻辑画布尺寸。设计稿 `KTP Tailtopia-01.png` 为 994×600，本画布取其 2×，
/// 故所有设计坐标 ×2 即本文件常量（[_KtpLayout]）。页面用 [FittedBox] 缩放到可用宽度；
/// 导出用同尺寸 RepaintBoundary 保证像素精度。
const Size kIdCardCanvas = Size(1988, 1200);

/// KTP 证件卡展示字段（会话级：来自 6-1 档案数据 + 预览可编辑覆盖 + 趣味默认，**不写档案**）。
@immutable
class KtpFields {
  const KtpFields({
    required this.nik,
    required this.nama,
    required this.tempatTglLahir,
    required this.spesies,
    required this.ras,
    required this.jenisKelamin,
    required this.alamat,
    required this.statusPerkawinan,
    required this.pekerjaan,
    required this.kewarganegaraan,
    required this.berlakuHingga,
    required this.placeLine,
    required this.dateLine,
    this.avatarUrl,
  });

  final String nik;
  final String nama;
  final String tempatTglLahir;
  final String spesies;
  final String ras;
  final String jenisKelamin;
  final String alamat;
  final String statusPerkawinan;
  final String pekerjaan;
  final String kewarganegaraan;
  final String berlakuHingga;
  final String placeLine;
  final String dateLine;
  final String? avatarUrl;
}

/// 设计稿反解出的绝对布局（画布 1988×1200 坐标系 = 设计稿 994×600 的 2×）。
///
/// 取值方法：成品图与 `Asset/Background.png` 做像素差分得各叠加元素 bbox；字号由 cap height 定、
/// 字距由实测字宽反推（渲染端套同一 AA 阈值校准）。改动前请回设计稿复测，勿凭手感调。
abstract final class _KtpLayout {
  /// 卡片圆角（背景图 alpha 已自带圆角；此半径供裁剪与页面显示对齐）。
  static const double radius = 80;

  // —— 左上 logo ——
  static const Rect logo = Rect.fromLTWH(48, 46, 220, 220);

  // —— 标题（两行居中；中心 x=1005 略偏卡片中线右，照设计稿）——
  static const double titleCenterX = 1005;
  static const double titleBaseline = 154; // 第一行基线
  static const double titleSize = 88.7;
  static const double titleLineHeight = 91 / titleSize;

  // —— NIK 行（X 为文字盒左缘：已扣掉字形左边距，使墨迹落在设计稿实测位）——
  static const double nikBaseline = 392;
  static const double nikLabelX = 113;
  static const double nikColonX = 424;
  static const double nikValueX = 494;
  static const double nikSize = 88.2;
  static const double nikTracking = -2.0;

  // —— 字段列（10 行等距）——
  static const double fieldLabelX = 90;
  static const double fieldColonX = 596;
  static const double fieldValueX = 639;
  static const double fieldFirstBaseline = 508;
  static const double fieldPitch = 64;
  static const double fieldSize = 50.8;
  static const double fieldValueTracking = -0.5;

  /// 字段值最大宽度：右侧须给照片框留空（照片左缘 1526）。超出以省略号截断。
  static const double fieldValueMaxWidth = 860;

  // —— 右侧照片框（白描边 + 内圆角照片）——
  static const Rect photo = Rect.fromLTWH(1526, 340, 402, 480);
  static const double photoBorder = 9;
  static const double photoOuterRadius = 38;
  static const double photoInnerRadius = 29;

  // —— 照片下方地点/日期（居中于照片中轴 x=1726）——
  static const double stampCenterX = 1726;
  static const double placeBaseline = 866;
  static const double dateBaseline = 906;
  static const double placeSize = 39.2;
  static const double placeTracking = -2.0;

  // —— 爪印戳（替代真证件指纹 → 娱乐仿制标记）——
  // 尺寸含素材自带的透明边（墨迹只占 128×139 画布的 (5,3)-(124,133)），故框比墨迹略大。
  static const Rect paw = Rect.fromLTWH(1601, 912, 256, 279);
}

/// 全卡统一墨色（设计稿取样：整卡文字仅此一色）。
const Color _kInk = Color(0xFF1A1A1A);

const String _kFontFamily = 'Rubik';
const List<FontVariation> _kMedium = [FontVariation('wght', 500)];
const List<FontVariation> _kSemiBold = [FontVariation('wght', 600)];

/// KTP 风格证件卡（正面，Story 6.2 · FR-49B）。2026-07-17 按设计师交付的 `Front/` 资产 1:1 还原：
/// 底纹（浅蓝底 + 地球仪 + 猫剪影 + 斜向水印）整张来自 `Background.png`，代码只叠 logo / 标题 /
/// NIK / 字段列 / 照片 / 地点日期 / 爪印戳。**娱乐仿制**：品牌化 + 水印 + 爪印（替代指纹）+ 宠物字段
/// → 明确区分真实政府证件；免责声明改在页面 UI 呈现（不入卡面，故不进导出图）。
///
/// 设计稿上的 `*customable` / `*pet profile batch` 是给开发看的批注，**不印在卡上**。
class KtpCardFront extends StatelessWidget {
  const KtpCardFront({super.key, required this.fields});

  final KtpFields fields;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kIdCardCanvas.width,
      height: kIdCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(_KtpLayout.radius),
        child: Stack(
          children: [
            // 底纹整张来自设计资产（水印/地球仪/猫剪影已烘焙其中，代码不再手绘）。
            const Positioned.fill(
              child: Image(
                image: AssetImage('assets/ktp/ktp_front_bg.png'),
                fit: BoxFit.fill,
              ),
            ),
            // fit 必填：Image 不给 fit 时按图片原始像素居中绘制、不缩放（logo 素材仅 110×110，
            // 落进 220 的框会缩水一半）。
            _positioned(
              _KtpLayout.logo,
              const Image(image: AssetImage('assets/ktp/ktp_logo.png'), fit: BoxFit.fill),
            ),
            _title(),
            ..._nikRow(),
            ..._fieldRows(),
            _photo(),
            _centeredLine(fields.placeLine.toUpperCase(), _KtpLayout.placeBaseline),
            _centeredLine(fields.dateLine, _KtpLayout.dateBaseline),
            _positioned(
              _KtpLayout.paw,
              const Image(image: AssetImage('assets/ktp/ktp_paw_stamp.png'), fit: BoxFit.fill),
            ),
          ],
        ),
      ),
    );
  }

  static Widget _positioned(Rect r, Widget child) =>
      Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: child);

  /// 按基线绝对定位一段文字：[Baseline] 让子文字基线落在距容器顶 [baseline] 处，容器顶固定在
  /// 画布 y=0 → 基线即画布绝对 y。这样定位不受字体 ascent / 行高实现差异影响（设计稿量的正是基线）。
  ///
  /// [width] 必须由内层 [SizedBox] 施加，**不能**写在 [Positioned] 上：[Baseline] 会 loosen 约束再按
  /// 子件固有尺寸定尺寸，Positioned 的宽度传不到 Text → 居中容器塌成文字自身宽度、`textAlign` 失效。
  static Widget _atBaseline({
    required double left,
    required double baseline,
    double? width,
    required Widget child,
  }) {
    return Positioned(
      left: left,
      top: 0,
      child: Baseline(
        baseline: baseline,
        baselineType: TextBaseline.alphabetic,
        child: width == null ? child : SizedBox(width: width, child: child),
      ),
    );
  }

  static TextStyle _style({
    required double size,
    required List<FontVariation> weight,
    double? tracking,
    double? height,
  }) {
    return TextStyle(
      fontFamily: _kFontFamily,
      fontVariations: weight,
      // fontWeight 与可变轴同步：轴负责实际字重，此值供度量与 fallback。
      fontWeight: identical(weight, _kSemiBold) ? FontWeight.w600 : FontWeight.w500,
      color: _kInk,
      fontSize: size,
      letterSpacing: tracking,
      height: height,
    );
  }

  Widget _title() {
    const half = 700.0; // 居中容器半宽（够容下最长一行 944）
    return _atBaseline(
      left: _KtpLayout.titleCenterX - half,
      baseline: _KtpLayout.titleBaseline,
      width: half * 2,
      child: Text(
        'KARTU TANDA\nPENDUDUK TAILTOPIA',
        textAlign: TextAlign.center,
        style: _style(
          size: _KtpLayout.titleSize,
          weight: _kSemiBold,
          height: _KtpLayout.titleLineHeight,
        ),
      ),
    );
  }

  List<Widget> _nikRow() {
    final style = _style(size: _KtpLayout.nikSize, weight: _kSemiBold);
    return [
      _atBaseline(
        left: _KtpLayout.nikLabelX,
        baseline: _KtpLayout.nikBaseline,
        child: Text('NIK', style: style),
      ),
      _atBaseline(
        left: _KtpLayout.nikColonX,
        baseline: _KtpLayout.nikBaseline,
        child: Text(':', style: style),
      ),
      _atBaseline(
        left: _KtpLayout.nikValueX,
        baseline: _KtpLayout.nikBaseline,
        child: Text(
          fields.nik,
          maxLines: 1,
          style: _style(
            size: _KtpLayout.nikSize,
            weight: _kSemiBold,
            tracking: _KtpLayout.nikTracking,
          ),
        ),
      ),
    ];
  }

  List<Widget> _fieldRows() {
    final rows = <(String, String)>[
      ('Nama', fields.nama),
      ('Tempat/Tgl Lahir', fields.tempatTglLahir),
      ('Spesies', fields.spesies),
      ('Ras', fields.ras),
      ('Jenis Kelamin', fields.jenisKelamin),
      ('Alamat', fields.alamat),
      ('Status Perkawinan', fields.statusPerkawinan),
      ('Pekerjaan', fields.pekerjaan),
      ('Kewarganegaraan', fields.kewarganegaraan),
      ('Berlaku Hingga', fields.berlakuHingga),
    ];
    final labelStyle = _style(size: _KtpLayout.fieldSize, weight: _kMedium);
    final valueStyle = _style(
      size: _KtpLayout.fieldSize,
      weight: _kSemiBold,
      tracking: _KtpLayout.fieldValueTracking,
    );

    final out = <Widget>[];
    for (var i = 0; i < rows.length; i++) {
      final baseline = _KtpLayout.fieldFirstBaseline + _KtpLayout.fieldPitch * i;
      out.add(_atBaseline(
        left: _KtpLayout.fieldLabelX,
        baseline: baseline,
        child: Text(rows[i].$1, maxLines: 1, style: labelStyle),
      ));
      out.add(_atBaseline(
        left: _KtpLayout.fieldColonX,
        baseline: baseline,
        child: Text(':', style: labelStyle),
      ));
      out.add(_atBaseline(
        left: _KtpLayout.fieldValueX,
        baseline: baseline,
        width: _KtpLayout.fieldValueMaxWidth,
        child: Text(
          rows[i].$2.toUpperCase(),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: valueStyle,
        ),
      ));
    }
    return out;
  }

  /// 照片框：白描边 + 内圆角。设计稿里的纯红块是「此处由用户上传照片」的占位标记，不是配色；
  /// 无照片时退回品牌浅底 + 宠物图标，绝不渲染成红块（会像报错）。
  Widget _photo() {
    final url = fields.avatarUrl;
    final hasPhoto = url != null && url.isNotEmpty;
    return _positioned(
      _KtpLayout.photo,
      Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(_KtpLayout.photoOuterRadius),
        ),
        padding: const EdgeInsets.all(_KtpLayout.photoBorder),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(_KtpLayout.photoInnerRadius),
          child: hasPhoto
              ? AppImage.widget(url, fit: BoxFit.cover)
              : const ColoredBox(
                  color: Color(0xFFEAE4F5),
                  child: Center(child: Icon(Icons.pets, size: 160, color: Color(0x80845EC9))),
                ),
        ),
      ),
    );
  }

  Widget _centeredLine(String text, double baseline) {
    const half = 300.0;
    return _atBaseline(
      left: _KtpLayout.stampCenterX - half,
      baseline: baseline,
      width: half * 2,
      child: Text(
        text,
        textAlign: TextAlign.center,
        maxLines: 1,
        style: _style(
          size: _KtpLayout.placeSize,
          weight: _kSemiBold,
          tracking: _KtpLayout.placeTracking,
        ),
      ),
    );
  }
}
