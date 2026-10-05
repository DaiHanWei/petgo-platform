import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_frame.dart';
import '../../data/ts_remote_art.dart';
import 'ts_remote_art_image.dart';

/// 配型卡画布：**1:1**（1080×1080）。2026-10-05 到货的 5 张配型卡是正方形（档位名、文案、刻度条都画在图里），
/// 原先的 3:4 会把两侧裁掉。页内卡 / 发帖截图 / 分享卡上半段都按它。
const CardCanvas kTsMatchCanvas = CardCanvas(size: Size(1080, 1080), radius: 48);

/// 配型卡（V1.3.2 Story 2.5 · AC6.1）：双人插画，**纯插画、卡上不叠文字**，**永不带水印**
/// （免费传播卡，同年龄卡；与结果卡规则相反且是有意的，PRD §3.2）。[CardFrame] 方便 4.2 / 4.4 出图。
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
        canvas: kTsMatchCanvas,
        watermark: null,
        child: TsMatchCardFace(tier: widget.tier),
      ),
    );
  }
}

/// 配型卡**卡面**：按档位取双人插画（按需下载，不打包，见 [TsRemoteArt]），下载中 / 取不到画代码占位。
///
/// V1.3.2 Story 4.2 从 [TsMatchCard] 抽出：配型分享卡的上半段复用它，取图映射只此一处（[TsRemoteArt.match]）。
class TsMatchCardFace extends StatelessWidget {
  const TsMatchCardFace({super.key, required this.tier});

  final int tier;

  @override
  Widget build(BuildContext context) {
    return TsRemoteArtImage(
      name: TsRemoteArt.match(tier),
      placeholder: (_) => Container(
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
    );
  }
}
