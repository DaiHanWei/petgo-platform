import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// KTP 证件卡逻辑画布尺寸（导出恒 1600×900，FR-49B）。所有内部布局按此绝对尺寸编排，
/// 页面用 [FittedBox] 缩放到可用宽度；导出用同尺寸 RepaintBoundary 保证像素精度。
const Size kIdCardCanvas = Size(1600, 900);

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

/// KTP 风格证件卡（正面，Story 6.2 · FR-49B）。1:1 还原用户给定 KTP 参考图（`KTP Tailtopia.png`）：
/// 浅蓝底 + 斜向品牌水印 + TailTopia logo + `KARTU TANDA PENDUDUK TAILTOPIA` 标题 + NIK + 字段列
/// + 照片框 + 爪印戳。**娱乐仿制**：品牌化 + 水印 + 爪印（替代指纹）+ 宠物字段 → 明确区分真实政府证件。
class KtpCardFront extends StatelessWidget {
  const KtpCardFront({super.key, required this.fields});

  final KtpFields fields;

  // —— KTP 专属配色（保真页局部常量，照 pet_card_page 范式；非全局主题色）——
  static const Color _bg1 = Color(0xFFDDEEF8);
  static const Color _bg2 = Color(0xFFC6E1F1);
  static const Color _wm = Color(0x3388B8D8); // 斜向水印文字
  static const Color _ink = Color(0xFF14202E); // 标题/值 近黑
  static const Color _label = Color(0xFF23303F); // 字段 label
  static const Color _batch = Color(0xFF6A7A88); // 右上趣味批次标
  static const Color _photo = Color(0xFFEAE4F5); // 照片占位淡紫（无头像时；原为刺眼纯红，语义像报错）
  static const Color _stamp = Color(0xFF171717); // 爪印戳

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kIdCardCanvas.width,
      height: kIdCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(36),
        child: Stack(
          children: [
            // 底：浅蓝渐变
            const Positioned.fill(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [_bg1, _bg2],
                  ),
                ),
              ),
            ),
            // 斜向品牌水印（三级水印之一/二：品牌 + 安全底纹）
            Positioned.fill(
              child: CustomPaint(painter: _KtpWatermarkPainter()),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(58, 30, 54, 30),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _header(),
                  const SizedBox(height: 16),
                  _nikRow(),
                  const SizedBox(height: 18),
                  Expanded(
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(child: _fields()),
                        const SizedBox(width: 24),
                        _rightColumn(),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _header() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _logo(),
        const SizedBox(width: 20),
        const Expanded(
          child: Text(
            'KARTU TANDA\nPENDUDUK TAILTOPIA',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: _ink,
              fontWeight: FontWeight.w800,
              fontSize: 46,
              height: 1.05,
              letterSpacing: 0.5,
            ),
          ),
        ),
        const SizedBox(width: 12),
        const Padding(
          padding: EdgeInsets.only(top: 8),
          child: Text(
            '*pet profile batch',
            style: TextStyle(
              color: _batch,
              fontStyle: FontStyle.italic,
              fontSize: 24,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }

  Widget _logo() {
    return Container(
      width: 108,
      height: 108,
      padding: const EdgeInsets.all(5),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(26),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint500, AppColors.mint, AppColors.gold],
        ),
      ),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(22),
        ),
        alignment: Alignment.center,
        child: const Icon(Icons.pets, color: AppColors.mint, size: 62),
      ),
    );
  }

  Widget _nikRow() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        const SizedBox(
          width: 200,
          child: Text('NIK',
              style: TextStyle(color: _ink, fontWeight: FontWeight.w700, fontSize: 44)),
        ),
        const Text(':  ',
            style: TextStyle(color: _ink, fontWeight: FontWeight.w700, fontSize: 44)),
        Text(fields.nik,
            style: const TextStyle(
                color: _ink, fontWeight: FontWeight.w800, fontSize: 44, letterSpacing: 1.5)),
      ],
    );
  }

  Widget _fields() {
    final rows = <List<String>>[
      ['Nama', fields.nama],
      ['Tempat/Tgl Lahir', fields.tempatTglLahir],
      ['Spesies', fields.spesies],
      ['Ras', fields.ras],
      ['Jenis Kelamin', fields.jenisKelamin],
      ['Alamat', fields.alamat],
      ['Status Perkawinan', fields.statusPerkawinan],
      ['Pekerjaan', fields.pekerjaan],
      ['Kewarganegaraan', fields.kewarganegaraan],
      ['Berlaku Hingga', fields.berlakuHingga],
    ];
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < rows.length; i++) ...[
          if (i > 0) const SizedBox(height: 12),
          _fieldRow(rows[i][0], rows[i][1]),
        ],
      ],
    );
  }

  Widget _fieldRow(String label, String value) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        SizedBox(
          width: 288,
          child: Text(label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: _label, fontWeight: FontWeight.w600, fontSize: 26)),
        ),
        const Text(':  ',
            style: TextStyle(color: _label, fontWeight: FontWeight.w600, fontSize: 26)),
        Expanded(
          child: Text(
            value.toUpperCase(),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: _ink, fontWeight: FontWeight.w700, fontSize: 26),
          ),
        ),
      ],
    );
  }

  Widget _rightColumn() {
    return SizedBox(
      width: 320,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Container(
            width: 300,
            height: 320,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: _photo,
              borderRadius: BorderRadius.circular(16),
              image: (fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty)
                  ? DecorationImage(
                      image: NetworkImage(fields.avatarUrl!), fit: BoxFit.cover)
                  : null,
            ),
            // 无头像时给占位框放宠物图标（避免裸露纯色空框）。
            child: (fields.avatarUrl != null && fields.avatarUrl!.isNotEmpty)
                ? null
                : const Icon(Icons.pets, size: 140, color: Color(0x80845EC9)),
          ),
          const SizedBox(height: 10),
          Text(fields.placeLine.toUpperCase(),
              style: const TextStyle(color: _ink, fontWeight: FontWeight.w600, fontSize: 24)),
          Text(fields.dateLine,
              style: const TextStyle(color: _ink, fontWeight: FontWeight.w600, fontSize: 24)),
          const SizedBox(height: 6),
          // 爪印戳（替代真证件指纹 → 娱乐仿制标记，三级水印/戳记之三）
          const Icon(Icons.pets, color: _stamp, size: 96),
        ],
      ),
    );
  }
}

/// 斜向重复品牌水印 painter（`KARTU TANDA PENDUDUK TAILTOPIA` 铺满）。
class _KtpWatermarkPainter extends CustomPainter {
  static const String _text = 'KARTU TANDA PENDUDUK TAILTOPIA    ';

  @override
  void paint(Canvas canvas, Size size) {
    final tp = TextPainter(
      text: const TextSpan(
        text: _text,
        style: TextStyle(
          color: KtpCardFront._wm,
          fontSize: 44,
          fontWeight: FontWeight.w800,
          letterSpacing: 3,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();

    canvas.save();
    canvas.clipRect(Offset.zero & size);
    canvas.rotate(-0.35); // ≈ -20°
    const double lineHeight = 110;
    for (double y = -size.height; y < size.height * 1.6; y += lineHeight) {
      for (double x = -size.width * 0.6; x < size.width * 1.6; x += tp.width) {
        tp.paint(canvas, Offset(x, y));
      }
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
