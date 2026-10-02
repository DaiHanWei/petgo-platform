import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_frame.dart';
import '../../../../shared/card_render/card_watermark.dart';
import '../../domain/content/ts_roles.dart';
import '../../domain/tailsonality_result.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';

/// Tailsonality 结果卡画布：3:4（1080×1440）。定义在 feature 内，**不改** `shared/card_render/card_canvas.dart`。
const CardCanvas kTsCardCanvas = CardCanvas(size: Size(1080, 1440), radius: 48);

/// 角色插画是否已把代号 / 角色名 / slogan 烤在图里（决策日志「设计素材状态」：现有 16 张占位图如此）。
///
/// `true` ⇒ 卡面不叠文字层（否则会出现两遍）。纯插画无字版到货（发版检查单 RC-4）时改为 `false`，不改其它代码。
const bool kTsRoleArtHasBakedText = true;

/// 角色插画路径（按四字母取，16 张共用、不分物种）。**素材未入库**（D-21），缺失时画代码占位。
String tsRoleArtAsset(String letters) => 'assets/tailsonality/role_$letters.webp';

/// 结果卡（V1.3.2 Story 2.4 · AC2）：`CardFrame` 按画布坐标排版，Epic 4 的大图 / 分享出图从同一个
/// `RepaintBoundary` 截图。本 epic 恒带水印（`!result.unlocked`，FR-117.6）。
class TsResultCard extends StatefulWidget {
  const TsResultCard({
    super.key,
    required this.result,
    required this.watermarked,
    this.showTextOverlay,
    this.boundaryKey,
    this.watermarkedBoundaryKey,
  });

  final TailsonalityResult result;
  final bool watermarked;

  /// 是否叠文字层；缺省取 [kTsRoleArtHasBakedText] 的反面。
  final bool? showTextOverlay;

  /// 截图边界 key。由调用方持有（Story 4.1 要在外层再包一层带水印的边界），不传则组件内自建。
  final GlobalKey? boundaryKey;

  /// 「含水印」的外层截图边界 key（V1.3.2 Story 4.1，照 KTP 的 `idCardWatermarkedBoundaryKey`）。
  ///
  /// `CardFrame` 把水印挂在内层 boundary **外面**，截内层永远是干净图；未解锁的大图 /
  /// 分享图要带水印，就得截把水印一并框进来的这一层。仅 [watermarked] 时挂上。
  final GlobalKey? watermarkedBoundaryKey;

  @override
  State<TsResultCard> createState() => _TsResultCardState();
}

class _TsResultCardState extends State<TsResultCard> {
  final GlobalKey _ownKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    final r = widget.result;
    final role = kTsRoles[r.letters];
    final frame = CardFrame(
      boundaryKey: widget.boundaryKey ?? _ownKey,
      canvas: kTsCardCanvas,
      watermark: widget.watermarked ? const CardWatermark(canvas: kTsCardCanvas, opacity: kKeepsakeWatermarkOpacity) : null,
      child: TsResultCardFace(result: r, showTextOverlay: widget.showTextOverlay),
    );
    final outerKey = widget.watermarkedBoundaryKey;
    return Semantics(
      container: true,
      label: '${r.typeCode} · ${role?.name ?? ''} · ${role?.slogan ?? ''}',
      excludeSemantics: true,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: widget.watermarked && outerKey != null ? RepaintBoundary(key: outerKey, child: frame) : frame,
      ),
    );
  }
}

/// 结果卡**卡面**（插画 + 代号 + 角色名 + slogan），按 [kTsCardCanvas] 坐标排版（1080×1440）。
///
/// V1.3.2 Story 4.1 从 [TsResultCard] 抽出：结果分享卡（9:16）的主体段直接复用它（cover 铺满），
/// 不另画一套 —— 纯插画版素材到货后只改这里，分享卡自动跟随。
class TsResultCardFace extends StatelessWidget {
  const TsResultCardFace({super.key, required this.result, this.showTextOverlay});

  final TailsonalityResult result;

  /// 是否叠文字层；缺省取 [kTsRoleArtHasBakedText] 的反面。
  final bool? showTextOverlay;

  @override
  Widget build(BuildContext context) {
    final r = result;
    final role = kTsRoles[r.letters];
    final overlay = showTextOverlay ?? !kTsRoleArtHasBakedText;
    final spaced = r.typeCode.split('').join(' ');
    return Stack(
      fit: StackFit.expand,
      children: [
        Image.asset(
          tsRoleArtAsset(r.letters),
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => _Placeholder(typeCode: r.typeCode, name: role?.name ?? ''),
        ),
        if (overlay)
          Positioned(
            key: const ValueKey('tsResultCardText'),
            left: 72,
            right: 72,
            bottom: 96,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(spaced,
                    style: const TextStyle(
                        fontSize: 56, fontWeight: FontWeight.w800, color: AppColors.onAccent, letterSpacing: 6)),
                const SizedBox(height: 12),
                Text(role?.name ?? '',
                    style: const TextStyle(fontSize: 92, fontWeight: FontWeight.w900, color: AppColors.onAccent)),
                const SizedBox(height: 16),
                Text(role?.slogan ?? '',
                    style: const TextStyle(fontSize: 44, fontWeight: FontWeight.w600, color: AppColors.onAccent)),
              ],
            ),
          ),
      ],
    );
  }
}

/// 素材缺失时的代码绘制占位：浅紫底 + 代号大字（+ 角色名），尺寸比例与最终素材一致（3:4）。
class _Placeholder extends StatelessWidget {
  const _Placeholder({required this.typeCode, required this.name});

  final String typeCode;
  final String name;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('tsResultCardPlaceholder'),
      decoration: BoxDecoration(
        color: AppColors.violet100,
        border: Border.all(color: AppColors.lineViolet, width: 6),
      ),
      alignment: Alignment.center,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.pets, size: 160, color: AppColors.mint),
          const SizedBox(height: 40),
          Text(typeCode, style: const TextStyle(fontSize: 150, fontWeight: FontWeight.w900, color: AppColors.mint)),
          const SizedBox(height: 16),
          Text(name, style: const TextStyle(fontSize: 64, fontWeight: FontWeight.w700, color: AppColors.ink2)),
        ],
      ),
    );
  }
}
