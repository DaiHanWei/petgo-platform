import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../shared/card_render/card_canvas.dart';
import '../../../shared/card_render/card_watermark.dart';
import '../../keepsake/presentation/keepsake_card_style.dart';

/// 页眉 + 内页块 + 页脚的统一版心（B1 / B2 / B2b / 骨架同尺寸）。
class PassportFrame extends StatelessWidget {
  const PassportFrame({super.key, required this.header, required this.child, this.footer});

  final Widget header;
  final Widget child;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            header,
            const SizedBox(height: 16),
            child,
            const SizedBox(height: 12),
            if (footer != null) Center(child: footer!),
          ],
        ),
      ),
    );
  }
}

/// 护照内页块（纸面，3:4）。
///
/// [watermarked]（V1.3.2 Story 3.4）：当前版本未买 → 叠品牌水印（`CardWatermark`，挂在内容的兄弟节点上）；
/// 已买 / 回看已买版本 → 不叠。
class PassportPageBlock extends StatelessWidget {
  const PassportPageBlock({super.key, required this.child, this.watermarked = false});

  final Widget child;
  final bool watermarked;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 3 / 4,
      child: Stack(
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppColors.cream2,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: AppColors.lineViolet),
              ),
              child: child,
            ),
          ),
          if (watermarked) const CardWatermark(
              key: ValueKey('passportWatermark'), canvas: kPetPassportPageCanvas, opacity: kKeepsakeWatermarkOpacity),
        ],
      ),
    );
  }
}

/// 护照内页的水印画布：3:4、圆角与纸面一致（水印层按它裁剪）。
const CardCanvas kPetPassportPageCanvas = CardCanvas(size: Size(300, 400), radius: 16);

/// 页眉：宠物名 + 完整 12 位护照号（单独一行、等宽数字、不截断）。
class PassportHeader extends StatelessWidget {
  const PassportHeader({super.key, required this.petName, required this.passportNo});

  final String petName;
  final String passportNo;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(petName,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
        const SizedBox(height: 2),
        Text(passportNo,
            key: const ValueKey('passportNo'),
            softWrap: false,
            overflow: TextOverflow.visible,
            style: const TextStyle(
                fontSize: 14,
                letterSpacing: 1.2,
                color: AppColors.ink2,
                fontFeatures: [FontFeature.tabularFigures()])),
      ],
    );
  }
}

