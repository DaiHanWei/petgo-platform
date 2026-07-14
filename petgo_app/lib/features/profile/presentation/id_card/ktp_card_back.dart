import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import 'ktp_card.dart';

/// KTP 证件卡背面（Story 6.2 · AC2/AC4 合规必做）。品牌化背面 + **娱乐仿制免责声明**
/// （非官方 · 无法律效力 · 仅供娱乐）+ 展示编号 + logo。与正面同浅蓝底 + 斜向水印，
/// 强化「趣味仿制、非真实政府证件」。免责/标题文案由页面传入（i18n，en+id）。
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

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kIdCardCanvas.width,
      height: kIdCardCanvas.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(36),
        child: Stack(
          children: [
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
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 140),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Container(
                      width: 150,
                      height: 150,
                      padding: const EdgeInsets.all(6),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(32),
                        gradient: const LinearGradient(
                          colors: [AppColors.mint500, AppColors.mint, AppColors.gold],
                        ),
                      ),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(26),
                        ),
                        alignment: Alignment.center,
                        child: const Icon(Icons.pets, color: AppColors.mint, size: 84),
                      ),
                    ),
                    const SizedBox(height: 30),
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
                    const SizedBox(height: 18),
                    Text(
                      disclaimerBody,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: _sub,
                        fontWeight: FontWeight.w500,
                        fontSize: 28,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 34),
                    Text(
                      serialLine,
                      style: const TextStyle(
                        color: _ink,
                        fontWeight: FontWeight.w700,
                        fontSize: 30,
                        letterSpacing: 2,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
