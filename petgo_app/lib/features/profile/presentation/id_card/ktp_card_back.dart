import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import 'ktp_card.dart';

/// KTP 证件卡背面（Story 6.2 · AC2/AC4 合规必做）。2026-07-16 照产品背面参考图重绘：
/// 与正面同浅蓝底 + 斜向品牌水印；左上 logo + 顶部标题「KARTU TANDA PENDUDUK TAILTOPIA」+
/// 中央白卡承载**娱乐仿制免责声明**（非官方 · 无法律效力 · 仅供娱乐，合规必做）+ 底部爪印签名线 + 展示编号。
/// 免责/标题文案由页面传入（i18n，en+id）。
class KtpCardBack extends StatelessWidget {
  const KtpCardBack({
    super.key,
    required this.serialLine,
    required this.disclaimerTitle,
    required this.disclaimerBody,
  });

  final String serialLine;
  final String disclaimerTitle;
  final String disclaimerBody;

  static const Color _bg1 = Color(0xFFDDEEF8);
  static const Color _bg2 = Color(0xFFC6E1F1);
  static const Color _ink = Color(0xFF14202E);
  static const Color _sub = Color(0xFF3A4B5A);
  static const Color _sigLine = Color(0xFFAFC2CF); // 签名线（浅灰蓝）
  static const Color _paw = Color(0xFF8AA3B4); // 底部爪印（灰蓝）

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kIdCardCanvas.width,
      height: kIdCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(36),
        child: Stack(
          children: [
            // 底：浅蓝渐变（同正面）
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
            // 斜向品牌水印（与正面共用 painter）
            Positioned.fill(child: CustomPaint(painter: KtpWatermarkPainter())),
            Padding(
              padding: const EdgeInsets.fromLTRB(58, 30, 54, 44),
              child: Column(
                children: [
                  // 顶部：左上 logo + 标题（logo 占左，标题在剩余空间居中，呼应参考图）
                  Row(
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
                    ],
                  ),
                  const Spacer(),
                  // 中央白卡：承载娱乐仿制免责声明（合规必做，AC2/AC4）。
                  Container(
                    width: 760,
                    padding: const EdgeInsets.symmetric(horizontal: 52, vertical: 44),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.92),
                      borderRadius: BorderRadius.circular(28),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          disclaimerTitle,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            color: _ink,
                            fontWeight: FontWeight.w800,
                            fontSize: 40,
                            letterSpacing: 0.5,
                          ),
                        ),
                        const SizedBox(height: 20),
                        Text(
                          disclaimerBody,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            color: _sub,
                            fontWeight: FontWeight.w500,
                            fontSize: 26,
                            height: 1.4,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const Spacer(),
                  // 底部签名线 + 爪印（替代真证件签名/指纹 → 娱乐仿制标记）。
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: const [
                      Expanded(child: Divider(color: _sigLine, thickness: 3)),
                      Padding(
                        padding: EdgeInsets.symmetric(horizontal: 26),
                        child: Icon(Icons.pets, color: _paw, size: 54),
                      ),
                      Expanded(child: Divider(color: _sigLine, thickness: 3)),
                    ],
                  ),
                  const SizedBox(height: 14),
                  // 展示编号。
                  Text(
                    serialLine,
                    style: const TextStyle(
                      color: _ink,
                      fontWeight: FontWeight.w700,
                      fontSize: 28,
                      letterSpacing: 2,
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

  /// 左上 logo（彩虹渐变边框圆角 + 白底爪印，与正面一致）。
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
}
