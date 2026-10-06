import 'package:flutter/material.dart';

import 'card_canvas.dart';

/// 品牌水印层（做法照抄 `id_card/id_card_watermark.dart`，AD-15 Rule 1b）。
///
/// 🔴 **必须挂在截图区的兄弟节点上**，不能放进 `RepaintBoundary` 里 ——
/// [CardFrame] 已经这么摆好了，用它就不会摆错。
///
/// 这个位置带来的效果是**有意的取舍**（AC5）：
/// - 预览、以及用户**手动截屏** → 带水印
/// - 走管线的**高清导出** → 不带水印（`toImage` 只截 boundary 子树）
///
/// ⚠️ 别"顺手统一"成两边都带或都不带。前者会污染正式导出图，
/// 后者等于用户截个屏就白拿干净卡片。
///
/// ⚠️ 素材沿用身份证那张品牌平铺图（路径里的 `ktp` 是历史目录名，
/// 图本身是 TailTopia 通用水印，不是证件专用）。
class CardWatermark extends StatelessWidget {
  const CardWatermark({super.key, required this.canvas, this.opacity = defaultOpacity, this.tileVertically = false});

  final CardCanvas canvas;

  /// 产品要求「不要太明显」。0.25 是身份证那边模拟器实截对比后定下的值，沿用。
  static const double defaultOpacity = 0.25;

  final double opacity;

  /// 竖长画布用（2026-10-06：宠物足迹护照本 828×1587）：平铺图自带上下空白边，`BoxFit.cover` 铺满竖长画布后
  /// 空白边正好落在顶 / 底一条，底部会露出没水印的一截。为 true 时改用**裁掉空白边**的同一张图，
  /// 按画布宽度等比、自上而下竖向连续平铺，整块画布都有水印。缺省 false，其余卡面行为不变。
  final bool tileVertically;

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: IgnorePointer(
        child: LayoutBuilder(
          builder: (context, constraints) => ClipRRect(
            // 圆角按实际渲染宽度等比缩放，与缩放后的卡面圆角贴合。
            borderRadius:
                BorderRadius.circular(canvas.radius * constraints.maxWidth / canvas.width),
            child: Opacity(
              opacity: opacity,
              child: tileVertically
                  ? Image.asset(
                      'assets/passport_book/watermark_tile_v.png',
                      fit: BoxFit.fitWidth,
                      repeat: ImageRepeat.repeatY,
                      alignment: Alignment.topCenter,
                      filterQuality: FilterQuality.medium,
                    )
                  : Image.asset(
                      'assets/ktp/watermark_tile.png',
                      fit: BoxFit.cover,
                      filterQuality: FilterQuality.medium,
                    ),
            ),
          ),
        ),
      ),
    );
  }
}
