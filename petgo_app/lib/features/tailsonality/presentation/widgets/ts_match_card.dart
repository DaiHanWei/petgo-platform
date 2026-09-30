import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_frame.dart';
import 'ts_result_card.dart';

/// 配型插画路径（1 = 4/4 … 5 = 0/4）。**素材未入库**（D-21），缺失时画代码占位。
String tsMatchArtAsset(int tier) => 'assets/tailsonality/match_tier$tier.webp';

/// 配型卡（V1.3.2 Story 2.5 · AC6.1）：3:4 双人插画，**纯插画、卡上不叠文字**，**永不带水印**
/// （免费传播卡，同年龄卡；与结果卡规则相反且是有意的，PRD §3.2）。复用 [kTsCardCanvas] + [CardFrame]，方便 4.2 出图。
class TsMatchCard extends StatefulWidget {
  const TsMatchCard({super.key, required this.tier, this.boundaryKey});

  final int tier;
  final GlobalKey? boundaryKey;

  @override
  State<TsMatchCard> createState() => _TsMatchCardState();
}

class _TsMatchCardState extends State<TsMatchCard> {
  final GlobalKey _ownKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: CardFrame(
        boundaryKey: widget.boundaryKey ?? _ownKey,
        canvas: kTsCardCanvas,
        watermark: null,
        child: Image.asset(
          tsMatchArtAsset(widget.tier),
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => Container(
            key: const ValueKey('tsMatchCardPlaceholder'),
            decoration: BoxDecoration(
              color: AppColors.violet100,
              border: Border.all(color: AppColors.lineViolet, width: 6),
            ),
            alignment: Alignment.center,
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.person_outline, size: 220, color: AppColors.mint),
                SizedBox(width: 40),
                Icon(Icons.pets, size: 200, color: AppColors.mint),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
